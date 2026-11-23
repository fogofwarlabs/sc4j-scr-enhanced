package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tests the HTTP side of {@link InspectorServer}: the discovery endpoints, the refusal of other hosts and origins, the WebSocket handshake, and
 * the loopback address it listens on.
 */
final class InspectorServerTest {
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 2_000;
    private static final @NotNull String CLIENT_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    /**
     * Sends a raw HTTP request and reads the response up to the end of its header, or to the end of the connection.
     */
    private static @NotNull String request(int port, @NotNull String request, boolean headerOnly) throws IOException {
        try (Socket socket = new Socket(ScriptInspector.HOST, port)) {
            socket.setSoTimeout(CONNECT_TIMEOUT_MILLISECONDS * 5);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            InputStream input = socket.getInputStream();
            ByteArrayOutputStream response = new ByteArrayOutputStream();

            for (int value = input.read(); value >= 0; value = input.read()) {
                response.write(value);

                if (headerOnly && response.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                    break;
                }
            }

            return response.toString(StandardCharsets.UTF_8);
        }
    }

    private static @NotNull String get(int port, @NotNull String path, @NotNull String host, @Nullable String origin) throws IOException {
        return request(port, "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n" + (origin == null ? "" : "Origin: " + origin + "\r\n") + "\r\n",
                       false);
    }

    private static @NotNull String readBody(@NotNull String response) {
        return response.substring(response.indexOf("\r\n\r\n") + 4);
    }

    @Test
    void is_loopback_host_should_accept_only_loopback_names_with_or_without_a_port() {
        for (String host : List.of("localhost", "LOCALHOST:9339", "127.0.0.1", "127.0.0.1:9339", "[::1]", "[::1]:9339")) {
            Assertions.assertTrue(InspectorServer.isLoopbackHost(host), host);
        }

        for (String host : List.of("evil.example", "evil.example:9339", "127.0.0.1.evil.example", "192.168.0.10:9339", "localhost:port", "[::2]",
                                   "")) {
            Assertions.assertFalse(InspectorServer.isLoopbackHost(host), host);
        }

        Assertions.assertFalse(InspectorServer.isLoopbackHost(null));
    }

    @Test
    void is_allowed_origin_should_accept_no_origin_and_devtools_only() {
        Assertions.assertTrue(InspectorServer.isAllowedOrigin(null));
        Assertions.assertTrue(InspectorServer.isAllowedOrigin("devtools://devtools"));

        for (String origin : List.of("https://evil.example", "http://127.0.0.1:9339", "null", "chrome-extension://abc")) {
            Assertions.assertFalse(InspectorServer.isAllowedOrigin(origin), origin);
        }
    }

    @Test
    void discovery_answers_the_version_and_the_target_list() throws IOException {
        ScriptInspector inspector = new ScriptInspector();
        int port = inspector.start(0);

        try {
            String version = get(port, "/json/version", "127.0.0.1:" + port, null);
            Map<?, ?> versionBody = (Map<?, ?>) JsonReader.parse(readBody(version).getBytes(StandardCharsets.UTF_8));

            Assertions.assertTrue(version.startsWith("HTTP/1.1 200 OK"), version);
            Assertions.assertEquals("1.3", versionBody.get("Protocol-Version"));

            for (String path : List.of("/json", "/json/list")) {
                String list = get(port, path, "localhost:" + port, null);
                Map<?, ?> target = (Map<?, ?>) ((List<?>) JsonReader.parse(readBody(list).getBytes(StandardCharsets.UTF_8))).getFirst();
                String id = (String) target.get("id");

                Assertions.assertEquals("node", target.get("type"));
                Assertions.assertEquals("ws://127.0.0.1:" + port + "/" + id, target.get("webSocketDebuggerUrl"));
                Assertions.assertEquals("devtools://devtools/bundled/js_app.html?experiments=true&v8only=true&ws=127.0.0.1:" + port + "/" + id,
                                        target.get("devtoolsFrontendUrl"));
                Assertions.assertEquals(target.get("devtoolsFrontendUrl"), inspector.getFrontendUrl());
            }
        } finally {
            inspector.stop();
        }

        Assertions.assertFalse(inspector.isRunning());
    }

    @Test
    void requests_of_other_hosts_and_origins_are_refused() throws IOException, InterruptedException {
        ScriptInspector inspector = new ScriptInspector();
        int port = inspector.start(0);

        try {
            String id = (String) DevToolsClientScenario.readTargets(port).getFirst().get("id");
            String upgrade = "GET /" + id + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                             "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " + CLIENT_KEY + "\r\n";

            Assertions.assertTrue(get(port, "/json/list", "evil.example:" + port, null).startsWith("HTTP/1.1 403"));
            Assertions.assertTrue(get(port, "/json/list", "127.0.0.1:" + port, "https://evil.example").startsWith("HTTP/1.1 403"));
            Assertions.assertTrue(request(port, upgrade + "Origin: https://evil.example\r\n\r\n", false).startsWith("HTTP/1.1 403"));
            Assertions.assertTrue(request(port, upgrade.replace("Host: 127.0.0.1", "Host: rebound.example") + "\r\n", false)
                                          .startsWith("HTTP/1.1 403"));
            Assertions.assertTrue(get(port, "/unknown", "127.0.0.1:" + port, null).startsWith("HTTP/1.1 404"));
            Assertions.assertTrue(request(port, "POST /json/list HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", false).startsWith("HTTP/1.1 405"));
        } finally {
            inspector.stop();
        }
    }

    @Test
    void the_web_socket_handshake_needs_the_target_id_and_a_key() throws IOException, InterruptedException {
        ScriptInspector inspector = new ScriptInspector();
        int port = inspector.start(0);

        try {
            String id = (String) DevToolsClientScenario.readTargets(port).getFirst().get("id");
            String headers = " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n" +
                             "Sec-WebSocket-Version: 13\r\n";

            Assertions.assertTrue(request(port, "GET /other" + headers + "Sec-WebSocket-Key: " + CLIENT_KEY + "\r\n\r\n", false)
                                          .startsWith("HTTP/1.1 404"));
            Assertions.assertTrue(request(port, "GET /" + id + headers + "\r\n", false).startsWith("HTTP/1.1 400"));

            String accepted = request(port, "GET /" + id + headers + "Sec-WebSocket-Key: " + CLIENT_KEY + "\r\nOrigin: devtools://devtools\r\n\r\n",
                                      true);

            Assertions.assertTrue(accepted.startsWith("HTTP/1.1 101 Switching Protocols"), accepted);
            Assertions.assertTrue(accepted.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"), accepted);
        } finally {
            inspector.stop();
        }
    }

    @Test
    void the_server_listens_on_the_loopback_address_only() throws IOException {
        ScriptInspector inspector = new ScriptInspector();
        int port = inspector.start(0);

        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address.isLoopbackAddress() || !(address instanceof Inet4Address)) {
                        continue;
                    }

                    try (Socket socket = new Socket()) {
                        Assertions.assertThrows(IOException.class, () -> socket.connect(new InetSocketAddress(address, port),
                                                                                        CONNECT_TIMEOUT_MILLISECONDS), address::toString);
                    }
                }
            }

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ScriptInspector.HOST, port), CONNECT_TIMEOUT_MILLISECONDS);
            }
        } finally {
            inspector.stop();
        }
    }
}
