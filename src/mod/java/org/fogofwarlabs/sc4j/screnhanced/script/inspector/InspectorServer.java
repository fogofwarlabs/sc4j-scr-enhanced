package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Listens on a port of {@value ScriptInspector#HOST} for DevTools: answers the discovery requests {@code GET /json/version} and
 * {@code GET /json/list} (also {@code /json}) that {@code chrome://inspect} sends, and opens a WebSocket session ({@link InspectorSession}) for
 * {@code GET /<target id>} with an upgrade.
 * <p>
 * The server refuses, with status 403, a request whose {@code Host} header names no loopback host ({@code localhost}, {@code 127.0.0.1} or
 * {@code [::1]}), so that a web page cannot reach it through a DNS name bound to this machine, and a request with an {@code Origin} header other
 * than that of DevTools ({@code devtools://}), so that a web page cannot open a session. The target id is random, so that a client must read it
 * from the discovery first.
 * <p>
 * One thread accepts connections; each connection runs on a thread of its own.
 */
final class InspectorServer implements Closeable {
    private static final @NotNull String ACCEPT_THREAD_NAME = "sc4j-devtools-server";
    private static final @NotNull String CONNECTION_THREAD_NAME = "sc4j-devtools-connection";
    private static final int BACKLOG = 16;
    private static final int REQUEST_TIMEOUT_MILLISECONDS = 10_000;
    private static final int MAXIMUM_HEADER_LENGTH = 16 * 1024;
    private static final int WEB_SOCKET_KEY_LENGTH = 16;
    private static final @NotNull String LINE_END = "\r\n";
    private static final @NotNull String HEADER_END = "\r\n\r\n";
    private static final @NotNull String GET_METHOD = "GET";
    private static final @NotNull String HTTP_VERSION_PREFIX = "HTTP/1.";
    private static final @NotNull String VERSION_PATH = "/json/version";
    private static final @NotNull List<@NotNull String> LIST_PATHS = List.of("/json", "/json/list");
    private static final @NotNull List<@NotNull String> LOOPBACK_HOSTS = List.of("localhost", ScriptInspector.HOST, "[::1]");
    private static final @NotNull String DEVTOOLS_ORIGIN_PREFIX = "devtools://";
    private static final @NotNull String WEB_SOCKET_UPGRADE = "websocket";
    private static final @NotNull String UPGRADE_TOKEN = "upgrade";
    private static final @NotNull String BROWSER_NAME = "SC:R Enhanced";
    private static final @NotNull String PROTOCOL_VERSION = "1.3";
    private static final @NotNull String TARGET_TITLE = "SC:R Enhanced script";
    private static final @NotNull String TARGET_DESCRIPTION = "The console of the map script of SC:R Enhanced";
    private static final @NotNull String COMPATIBLE_FRONTEND_URL = "devtools://devtools/bundled/inspector.html?experiments=true&v8only=true&ws=";
    private static final @NotNull String JSON_TYPE = "application/json; charset=UTF-8";
    private static final @NotNull String TEXT_TYPE = "text/plain; charset=UTF-8";

    /**
     * An HTTP request line and its headers.
     */
    private static final class HttpRequest {
        private final @NotNull String method;
        private final @NotNull String path;
        private final @NotNull Map<String, String> headers;

        private HttpRequest(@NotNull String method, @NotNull String path, @NotNull Map<String, String> headers) {
            this.method  = method;
            this.path    = path;
            this.headers = headers;
        }

        /**
         * Finds a header by name, in any case.
         *
         * @return the value, the values of a repeated header joined with commas, or {@code null} if the request has no such header
         */
        private @Nullable String findHeader(@NotNull String name) {
            return this.headers.get(name.toLowerCase(Locale.ROOT));
        }
    }


    /**
     * Starts a server on a port of {@value ScriptInspector#HOST}.
     *
     * @param inspector the inspector of the sessions
     * @param port      the port, or {@code 0} for a free one
     * @param targetId  the id of the target, the path of its WebSocket
     * @return the started {@link InspectorServer} instance
     * @throws IOException if the server cannot listen on the port
     */
    static @NotNull InspectorServer start(@NotNull ScriptInspector inspector, int port, @NotNull String targetId) throws IOException {
        ServerSocket serverSocket = new ServerSocket();

        try {
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(ScriptInspector.HOST), port), BACKLOG);
        } catch (IOException exception) {
            serverSocket.close();

            throw exception;
        }

        InspectorServer server = new InspectorServer(inspector, serverSocket, targetId);

        Thread.ofPlatform().daemon().name(ACCEPT_THREAD_NAME).start(server::acceptConnections);

        return server;
    }

    /**
     * Reads the request line and headers of an HTTP request.
     *
     * @return the request, or {@code null} if it is not an HTTP/1.x request or its header is too long
     */
    private static @Nullable HttpRequest readRequest(@NotNull InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;

        while (matched < HEADER_END.length()) {
            int value = input.read();

            if (value < 0 || header.size() >= MAXIMUM_HEADER_LENGTH) {
                return null;
            }

            header.write(value);
            matched = value == HEADER_END.charAt(matched) ? matched + 1 : value == HEADER_END.charAt(0) ? 1 : 0;
        }

        String[] lines = header.toString(StandardCharsets.ISO_8859_1).split(LINE_END);
        String[] requestLine = lines[0].split(" ");

        if (requestLine.length != 3 || !requestLine[2].startsWith(HTTP_VERSION_PREFIX)) {
            return null;
        }

        Map<String, String> headers = new HashMap<>();

        for (int index = 1; index < lines.length; index++) {
            int separator = lines[index].indexOf(':');

            if (separator <= 0) {
                continue;
            }

            String name = lines[index].substring(0, separator).strip().toLowerCase(Locale.ROOT);
            String value = lines[index].substring(separator + 1).strip();

            headers.merge(name, value, (first, second) -> first + ", " + second);
        }

        int query = requestLine[1].indexOf('?');

        return new HttpRequest(requestLine[0], query < 0 ? requestLine[1] : requestLine[1].substring(0, query), headers);
    }

    /**
     * Tells whether a {@code Host} header names a loopback host, with or without a port.
     *
     * @param host the value of the header, or {@code null} if the request has none
     * @return {@code true} if the host is {@code localhost}, {@code 127.0.0.1} or {@code [::1]}, {@code false} otherwise
     */
    static boolean isLoopbackHost(@Nullable String host) {
        if (host == null) {
            return false;
        }

        String name = host.strip().toLowerCase(Locale.ROOT);
        int portSeparator = name.lastIndexOf(':');

        if (portSeparator > name.lastIndexOf(']')) {
            String port = name.substring(portSeparator + 1);

            if (port.isEmpty() || !port.chars().allMatch(Character::isDigit)) {
                return false;
            }

            name = name.substring(0, portSeparator);
        }

        return LOOPBACK_HOSTS.contains(name);
    }

    /**
     * Tells whether an {@code Origin} header lets a request in: none, as from {@code chrome://inspect} and other clients that are no web page,
     * or that of DevTools.
     *
     * @param origin the value of the header, or {@code null} if the request has none
     * @return {@code true} if the request may reach the server, {@code false} otherwise
     */
    static boolean isAllowedOrigin(@Nullable String origin) {
        return origin == null || origin.startsWith(DEVTOOLS_ORIGIN_PREFIX);
    }

    private static void writeResponse(@NotNull OutputStream output, @NotNull String status, @NotNull String type, @NotNull String body) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + LINE_END +
                        "Content-Type: " + type + LINE_END +
                        "Content-Length: " + content.length + LINE_END +
                        "Cache-Control: no-cache" + LINE_END +
                        "Connection: close" + LINE_END + LINE_END;

        output.write(header.getBytes(StandardCharsets.ISO_8859_1));
        output.write(content);
        output.flush();
    }

    private static boolean isWebSocketKey(@Nullable String key) {
        try {
            return key != null && Base64.getDecoder().decode(key.strip()).length == WEB_SOCKET_KEY_LENGTH;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }


    private final @NotNull ScriptInspector inspector;
    private final @NotNull ServerSocket serverSocket;
    private final @NotNull String targetId;

    private InspectorServer(@NotNull ScriptInspector inspector, @NotNull ServerSocket serverSocket, @NotNull String targetId) {
        this.inspector    = inspector;
        this.serverSocket = serverSocket;
        this.targetId     = targetId;
    }

    /**
     * @return the port the server listens on
     */
    int getPort() {
        return this.serverSocket.getLocalPort();
    }

    /**
     * @return the id of the target, the path of its WebSocket
     */
    @NotNull String getTargetId() {
        return this.targetId;
    }


    private void acceptConnections() {
        while (!this.serverSocket.isClosed()) {
            try {
                Socket socket = this.serverSocket.accept();

                Thread.ofPlatform().daemon().name(CONNECTION_THREAD_NAME).start(() -> this.serveConnection(socket));
            } catch (IOException exception) {
                // The server closed, or the connection failed before it was accepted.
            }
        }
    }

    private void serveConnection(@NotNull Socket socket) {
        try (socket) {
            socket.setSoTimeout(REQUEST_TIMEOUT_MILLISECONDS);

            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            HttpRequest request = readRequest(input);

            if (request == null) {
                writeResponse(output, "400 Bad Request", TEXT_TYPE, "Bad request.");

                return;
            }

            if (!isLoopbackHost(request.findHeader("Host")) || !isAllowedOrigin(request.findHeader("Origin"))) {
                writeResponse(output, "403 Forbidden", TEXT_TYPE, "Only DevTools on this machine may connect.");

                return;
            }

            if (!request.method.equals(GET_METHOD)) {
                writeResponse(output, "405 Method Not Allowed", TEXT_TYPE, "Only GET requests are served.");

                return;
            }

            if (request.path.equals(VERSION_PATH)) {
                writeResponse(output, "200 OK", JSON_TYPE, JsonWriterUtils.write(Map.of("Browser", BROWSER_NAME, "Protocol-Version", PROTOCOL_VERSION)));
            } else if (LIST_PATHS.contains(request.path)) {
                writeResponse(output, "200 OK", JSON_TYPE, JsonWriterUtils.write(List.of(this.createTarget())));
            } else if (request.path.equals("/" + this.targetId) && WEB_SOCKET_UPGRADE.equalsIgnoreCase(request.findHeader("Upgrade"))) {
                this.openSession(socket, input, output, request);
            } else {
                writeResponse(output, "404 Not Found", TEXT_TYPE, "Not found.");
            }
        } catch (IOException exception) {
            // The connection failed.
        }
    }

    /**
     * Completes the WebSocket opening handshake of a request and serves the session on the current thread until it ends.
     */
    private void openSession(@NotNull Socket socket, @NotNull InputStream input, @NotNull OutputStream output,
                             @NotNull HttpRequest request) throws IOException {
        String connectionHeader = request.findHeader("Connection");
        String key = request.findHeader("Sec-WebSocket-Key");

        if (connectionHeader == null || !connectionHeader.toLowerCase(Locale.ROOT).contains(UPGRADE_TOKEN) ||
            !WebSocketConnection.VERSION.equals(request.findHeader("Sec-WebSocket-Version")) || !isWebSocketKey(key)) {
            writeResponse(output, "400 Bad Request", TEXT_TYPE, "Bad WebSocket handshake.");

            return;
        }

        String response = "HTTP/1.1 101 Switching Protocols" + LINE_END +
                          "Upgrade: websocket" + LINE_END +
                          "Connection: Upgrade" + LINE_END +
                          "Sec-WebSocket-Accept: " + WebSocketConnection.createAcceptKey(key) + LINE_END + LINE_END;

        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        socket.setSoTimeout(0);

        InspectorSession session = new InspectorSession(this.inspector, socket, new WebSocketConnection(input, output,
                                                                                                        InspectorSession.MAXIMUM_MESSAGE_LENGTH),
                                                        this.targetId);

        this.inspector.addSession(session);
        session.run();
    }

    /**
     * Creates the target of the discovery list, in the form of the Node.js inspector.
     */
    private @NotNull Map<String, Object> createTarget() {
        String address = ScriptInspector.HOST + ":" + this.getPort() + "/" + this.targetId;
        Map<String, Object> target = new LinkedHashMap<>();
        InspectedGame inspected = this.inspector.getInspected();

        target.put("description", TARGET_DESCRIPTION);
        target.put("devtoolsFrontendUrl", ScriptInspector.FRONTEND_URL + address);
        target.put("devtoolsFrontendUrlCompat", COMPATIBLE_FRONTEND_URL + address);
        target.put("faviconUrl", "");
        target.put("id", this.targetId);
        target.put("title", inspected == null ? TARGET_TITLE : TARGET_TITLE + (inspected.isShared() ? " (shared code)" : " (local code)"));
        target.put("type", "node");
        target.put("url", "");
        target.put("webSocketDebuggerUrl", "ws://" + address);

        return target;
    }

    /**
     * Stops listening; the sessions stay open until the inspector closes them.
     */
    @Override
    public void close() {
        try {
            this.serverSocket.close();
        } catch (IOException exception) {
            // The server socket is closed either way.
        }
    }
}
