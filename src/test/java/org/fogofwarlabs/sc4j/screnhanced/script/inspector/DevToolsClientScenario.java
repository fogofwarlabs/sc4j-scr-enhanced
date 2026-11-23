package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Plays DevTools against an inspector: finds the target through {@code /json/list}, opens its WebSocket with the JDK's client, sends Chrome
 * DevTools Protocol requests and waits for their answers and for events. While it waits it runs the game thread's part, a pump that the test
 * gives, so that the requests that wait for a frame start run.
 */
final class DevToolsClientScenario implements AutoCloseable {
    private static final long TIMEOUT_MILLISECONDS = 20_000;
    private static final long POLL_MILLISECONDS = 5;

    /**
     * Reads the discovery list of an inspector.
     *
     * @param port the port of the inspector
     * @return the targets
     */
    static @NotNull List<Map<String, Object>> readTargets(int port) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/list"))
                                                                                   .build(),
                                                                        HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(200, response.statusCode(), response.body());

        List<Map<String, Object>> targets = new ArrayList<>();

        for (Object target : (List<?>) Objects.requireNonNull(JsonReader.parse(response.body().getBytes(StandardCharsets.UTF_8)))) {
            targets.add(asObject(target));
        }

        return targets;
    }

    /**
     * Connects to the target of an inspector.
     *
     * @param port the port of the inspector
     * @param pump the game thread's part, run while the client waits
     * @return the connected scenario
     */
    static @NotNull DevToolsClientScenario connect(int port, @NotNull Runnable pump) throws IOException, InterruptedException {
        String url = (String) readTargets(port).getFirst().get("webSocketDebuggerUrl");

        return new DevToolsClientScenario(url, pump);
    }

    @SuppressWarnings("unchecked")
    static @NotNull Map<String, Object> asObject(@Nullable Object value) {
        Assertions.assertInstanceOf(Map.class, value);

        return (Map<String, Object>) value;
    }

    static @NotNull List<Object> asList(@Nullable Object value) {
        Assertions.assertInstanceOf(List.class, value);

        return new ArrayList<>((List<?>) value);
    }


    private final @NotNull BlockingQueue<Map<String, Object>> received;
    private final @NotNull List<Map<String, Object>> events;
    private final @NotNull Runnable pump;
    private final @NotNull WebSocket webSocket;
    private long nextId;

    private DevToolsClientScenario(@NotNull String url, @NotNull Runnable pump) {
        this.received = new LinkedBlockingQueue<>();
        this.events   = new ArrayList<>();
        this.pump     = pump;
        this.nextId   = 1;

        StringBuilder message = new StringBuilder();

        this.webSocket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(url), new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                message.append(data);

                if (last) {
                    try {
                        DevToolsClientScenario.this.received.add(asObject(JsonReader.parse(message.toString().getBytes(StandardCharsets.UTF_8))));
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }

                    message.setLength(0);
                }

                webSocket.request(1);

                return null;
            }
        }).join();
    }

    /**
     * Sends a request and waits for its answer.
     *
     * @param method     the method
     * @param parameters the parameters
     * @return the answer: {@code result} or {@code error}
     */
    @NotNull Map<String, Object> send(@NotNull String method, @NotNull Map<String, Object> parameters) {
        long id = this.nextId++;
        Map<String, Object> request = new LinkedHashMap<>();

        request.put("id", id);
        request.put("method", method);
        request.put("params", parameters);
        this.webSocket.sendText(JsonWriterUtils.write(request), true).join();

        return this.await(message -> message.get("id") instanceof Number number && number.longValue() == id, method);
    }

    /**
     * Sends a request and returns its result.
     *
     * @param method     the method
     * @param parameters the parameters
     * @return the result
     */
    @NotNull Map<String, Object> call(@NotNull String method, @NotNull Map<String, Object> parameters) {
        Map<String, Object> answer = this.send(method, parameters);

        Assertions.assertNull(answer.get("error"), () -> method + " failed: " + answer);

        return asObject(answer.get("result"));
    }

    /**
     * Evaluates an expression in the default context with a preview.
     *
     * @param expression the expression
     * @return the result of {@code Runtime.evaluate}
     */
    @NotNull Map<String, Object> evaluate(@NotNull String expression) {
        return this.call("Runtime.evaluate", Map.of("expression", expression, "objectGroup", "console", "includeCommandLineAPI", true,
                                                    "generatePreview", true));
    }

    /**
     * Evaluates an expression in the default context and returns the remote object of its value, failing on an exception.
     *
     * @param expression the expression
     * @return the remote object
     */
    @NotNull Map<String, Object> evaluateValue(@NotNull String expression) {
        Map<String, Object> result = this.evaluate(expression);

        Assertions.assertNull(result.get("exceptionDetails"), () -> expression + " threw: " + result);

        return asObject(result.get("result"));
    }

    /**
     * Waits for an event; events that came before count too, each once.
     *
     * @param method the method of the event
     * @return the parameters of the event
     */
    @NotNull Map<String, Object> awaitEvent(@NotNull String method) {
        for (Iterator<Map<String, Object>> iterator = this.events.iterator(); iterator.hasNext(); ) {
            Map<String, Object> event = iterator.next();

            if (method.equals(event.get("method"))) {
                iterator.remove();

                return asObject(event.get("params"));
            }
        }

        return asObject(this.await(message -> method.equals(message.get("method")), method).get("params"));
    }

    /**
     * Waits for a message that matches, keeping the events before it.
     */
    private @NotNull Map<String, Object> await(@NotNull Predicate<Map<String, Object>> matching, @NotNull String description) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLISECONDS;

        while (System.currentTimeMillis() < deadline) {
            this.pump.run();

            Map<String, Object> message;

            try {
                message = this.received.poll(POLL_MILLISECONDS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();

                return Assertions.fail(exception);
            }

            if (message == null) {
                continue;
            }

            if (matching.test(message)) {
                return message;
            }

            if (message.containsKey("method")) {
                this.events.add(message);
            }
        }

        return Assertions.fail("No answer to " + description);
    }

    @Override
    public void close() {
        this.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
    }
}
