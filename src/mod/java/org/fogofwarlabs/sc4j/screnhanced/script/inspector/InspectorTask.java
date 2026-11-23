package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a request of a DevTools session that runs on the game thread between frames. The game thread and the session's timeout each claim
 * the task before they answer it, so that exactly one of them does.
 */
final class InspectorTask {
    private final @NotNull InspectorSession session;
    private final long requestId;
    private final @NotNull String method;
    private final @NotNull Map<String, Object> parameters;
    private final long createdNanoseconds;

    private final @NotNull AtomicBoolean claimed;

    /**
     * Creates an {@link InspectorTask} instance that is not claimed.
     *
     * @param session            the session of the request
     * @param requestId          the id of the request
     * @param method             the method of the request
     * @param parameters         the parameters of the request
     * @param createdNanoseconds the time the request arrived, from {@link System#nanoTime()}
     */
    InspectorTask(@NotNull InspectorSession session, long requestId, @NotNull String method, @NotNull Map<String, Object> parameters,
                  long createdNanoseconds) {
        this.session            = session;
        this.requestId          = requestId;
        this.method             = method;
        this.parameters         = parameters;
        this.createdNanoseconds = createdNanoseconds;

        this.claimed = new AtomicBoolean(false);
    }

    /**
     * @return the session of the request
     */
    @NotNull InspectorSession getSession() {
        return this.session;
    }

    /**
     * @return the id of the request
     */
    long getRequestId() {
        return this.requestId;
    }

    /**
     * @return the method of the request
     */
    @NotNull String getMethod() {
        return this.method;
    }

    /**
     * @return the parameters of the request
     */
    @NotNull Map<String, Object> getParameters() {
        return this.parameters;
    }

    /**
     * @return the time the request arrived, from {@link System#nanoTime()}
     */
    long getCreatedNanoseconds() {
        return this.createdNanoseconds;
    }

    /**
     * @return {@code true} if the task was claimed, {@code false} otherwise
     */
    boolean isClaimed() {
        return this.claimed.get();
    }


    /**
     * Claims the task for its answer.
     *
     * @return {@code true} if the caller claimed it, {@code false} if it was claimed before
     */
    boolean claim() {
        return this.claimed.compareAndSet(false, true);
    }
}
