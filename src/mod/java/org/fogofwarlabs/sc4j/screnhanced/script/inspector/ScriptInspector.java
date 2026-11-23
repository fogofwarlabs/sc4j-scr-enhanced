package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptLogOutput;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Gives Chrome and Edge DevTools a console on the script of the running game: a server on {@code 127.0.0.1} that speaks the {@code Runtime}
 * domain of the Chrome DevTools Protocol ({@link InspectorServer}, {@link InspectorSession}).
 * <p>
 * The inspector attaches to the game whose script runtime calls {@link #onFrameStarted} while the server runs, and leaves the previous one.
 * Console code of DevTools waits in a queue until the game thread calls {@link #onFrameStarted}, at the frame start, after the script's frame;
 * it runs there, between the frames of the simulation, and its answer goes back on the session's thread. A single-player game
 * ({@link #isSharedGame}) has a context of shared code, the default one, and a context of local code; a multiplayer game and a replay have
 * only the local context, so that the console cannot make the game differ between the players or from its replay. The log output of a game
 * ({@link #createLogOutput}) sends the script's {@code log} messages and errors of the attached game to the sessions. The debugger
 * ({@link ScriptDebugger}) holds the attached game's script calls at breakpoints and runs the requests there.
 * <p>
 * The game thread calls {@link #onFrameStarted}, {@link #onFramePlayed} and the log outputs; any thread calls {@link #start} and
 * {@link #stop}.
 */
public final class ScriptInspector {
    /**
     * The default port of the server, next to the ports of the Node.js inspector (9229) and of Chrome's remote debugging (9222).
     */
    public static final int DEFAULT_PORT = 9339;
    /**
     * The address the server listens on.
     */
    public static final @NotNull String HOST = "127.0.0.1";
    /**
     * The system property whose port starts the server of {@link #getShared()}.
     */
    public static final @NotNull String PORT_PROPERTY = "sc4j.scr-enhanced.inspect";

    /**
     * The time a request waits for a frame before it is answered with an error, while the game is paused or has ended.
     */
    static final long TASK_TIMEOUT_NANOSECONDS = 5_000_000_000L;
    /**
     * The address of the DevTools frontend for Node.js, without the server's address, which follows it.
     */
    static final @NotNull String FRONTEND_URL = "devtools://devtools/bundled/js_app.html?experiments=true&v8only=true&ws=";

    private static final @NotNull Logger LOGGER = Logger.of(ScriptInspector.class);
    private static final @NotNull ScriptInspector SHARED = createShared();
    /**
     * The time the message area shows the console's address.
     */
    private static final int ANNOUNCE_MILLISECONDS = 20_000;

    /**
     * @return the inspector of the client, which the games share; its server runs when {@value #PORT_PROPERTY} names a port
     */
    public static @NotNull ScriptInspector getShared() {
        return SHARED;
    }

    private static @NotNull ScriptInspector createShared() {
        ScriptInspector inspector = new ScriptInspector();
        String port = System.getProperty(PORT_PROPERTY);

        if (port != null) {
            try {
                inspector.start(Integer.parseInt(port));
            } catch (IOException | NumberFormatException exception) {
                LOGGER.warning("The DevTools console cannot use port {0}: {1}", port, exception.getMessage());
            }
        }

        return inspector;
    }

    /**
     * Tells whether the console of a game runs shared code by default: in a single-player game, which has no peer whose game could differ.
     * Multiplayer games and replays run local code only.
     *
     * @param game the game
     * @return {@code true} if the game is a live single-player game, {@code false} otherwise
     */
    public static boolean isSharedGame(@NotNull Game game) {
        TriggerContext triggers = game.getTriggerContext();

        return !game.isReplayPlayback() && triggers != null && triggers.getMultiplayerSession() == 0;
    }


    private final @NotNull Object lock;
    private final @NotNull List<InspectorSession> sessions;
    private final @NotNull Queue<InspectorTask> tasks;
    /**
     * The objects of the log messages, which give no object an id.
     */
    private final @NotNull InspectorObjects logObjects;
    private final @NotNull ScriptDebugger debugger;

    private volatile @Nullable InspectorServer server;
    private volatile @Nullable InspectedGame inspected;
    private int nextContextId;

    /**
     * Creates a {@link ScriptInspector} instance whose server does not run.
     */
    public ScriptInspector() {
        this.lock       = new Object();
        this.sessions   = new CopyOnWriteArrayList<>();
        this.tasks      = new ConcurrentLinkedQueue<>();
        this.logObjects = new InspectorObjects();
        this.debugger   = new ScriptDebugger(this);

        this.server        = null;
        this.inspected     = null;
        this.nextContextId = 1;
    }

    /**
     * @return {@code true} if the server runs, {@code false} otherwise
     */
    public boolean isRunning() {
        return this.server != null;
    }

    /**
     * @return the port the server listens on, or {@code -1} if it does not run
     */
    public int getPort() {
        InspectorServer current = this.server;

        return current == null ? -1 : current.getPort();
    }

    /**
     * @return the address of the DevTools frontend on the server, or {@code null} if it does not run
     */
    public @Nullable String getFrontendUrl() {
        InspectorServer current = this.server;

        return current == null ? null : FRONTEND_URL + HOST + ":" + current.getPort() + "/" + current.getTargetId();
    }

    /**
     * @return the attached game, or {@code null} if the inspector is attached to none
     */
    @Nullable InspectedGame getInspected() {
        return this.inspected;
    }


    /**
     * Starts the server on a port of {@value #HOST}, after stopping a server that runs on another port.
     *
     * @param port the port, or {@code 0} for a free one
     * @return the port the server listens on
     * @throws IOException if the server cannot listen on the port
     */
    public int start(int port) throws IOException {
        synchronized (this.lock) {
            InspectorServer current = this.server;

            if (current != null && (port == 0 || current.getPort() == port)) {
                return current.getPort();
            }

            this.stop();

            InspectorServer started = InspectorServer.start(this, port, UUID.randomUUID().toString());

            this.server = started;

            LOGGER.info("DEVTOOLS CONSOLE: {0}", this.getFrontendUrl());

            return started.getPort();
        }
    }

    /**
     * Stops the server and closes its sessions; the inspector leaves the attached game.
     */
    public void stop() {
        synchronized (this.lock) {
            InspectorServer current = this.server;

            if (current == null) {
                return;
            }

            this.server    = null;
            this.inspected = null;

            current.close();

            for (InspectorSession session : this.sessions) {
                session.close();
            }

            this.sessions.clear();
            this.tasks.clear();
        }
    }

    /**
     * Called at each frame start of a game with a script, after the script's frame, on the game thread: attaches the inspector to the game when
     * it is attached to another one, then runs the requests that wait.
     *
     * @param game    the game
     * @param runtime the script runtime of the game, started
     */
    public void onFrameStarted(@NotNull Game game, @NotNull ScriptRuntime runtime) {
        if (this.server == null) {
            return;
        }

        InspectedGame current = this.inspected;

        if (current == null || current.getRuntime() != runtime) {
            current = this.attach(game, runtime);

            if (current == null) {
                return;
            }
        }

        this.runTasks(current);
    }

    /**
     * Runs the requests that wait, on the game thread.
     *
     * @param game the attached game
     * @return {@code true} if a request ran, {@code false} otherwise
     */
    boolean runTasks(@NotNull InspectedGame game) {
        boolean ran = false;

        for (InspectorTask task = this.tasks.poll(); task != null; task = this.tasks.poll()) {
            if (task.claim()) {
                task.getSession().runTask(task, game);

                ran = true;
            }
        }

        return ran;
    }

    /**
     * @return the debugger of the attached game
     */
    @NotNull ScriptDebugger getDebugger() {
        return this.debugger;
    }

    /**
     * @return {@code true} if a session receives the {@code Debugger} events, {@code false} otherwise
     */
    boolean hasDebuggerSessions() {
        for (InspectorSession session : this.sessions) {
            if (session.isDebuggerEnabled()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Sends the {@code Debugger.paused} event to the sessions, on the game thread.
     *
     * @param context the current context
     * @param game    the attached game
     * @param frames  the frames of the held call, the innermost first
     * @param reason  the reason
     * @param data    the thrown value, or {@code null} for none
     * @param hits    the ids of the breakpoints that hold
     */
    void sendPaused(@NotNull Context context, @NotNull InspectedGame game, @NotNull List<ScriptDebugger.Frame> frames, @NotNull String reason,
                    @Nullable Object data, @NotNull List<String> hits) {
        for (InspectorSession session : this.sessions) {
            session.sendPaused(context, game, frames, reason, data, hits);
        }
    }

    /**
     * Sends the {@code Debugger.resumed} event to the sessions, on the game thread.
     */
    void sendResumed() {
        for (InspectorSession session : this.sessions) {
            session.sendResumed();
        }
    }

    /**
     * Called after each frame the client plays, on the game thread: the message area of the attached game shows the console's address once.
     *
     * @param runtime the script runtime of the game
     */
    public void onFramePlayed(@NotNull ScriptRuntime runtime) {
        InspectedGame current = this.inspected;

        if (current == null || current.getRuntime() != runtime || current.isAnnounced()) {
            return;
        }

        current.setAnnounced();
        current.getGame().getInterfaceLink().getMessageLines().writeGameMessage(this.createAnnouncement(current).getBytes(StandardCharsets.UTF_8),
                                                                                ANNOUNCE_MILLISECONDS);
    }

    private @NotNull String createAnnouncement(@NotNull InspectedGame game) {
        String code = game.isShared() ? "shared code" : "local code";

        return Exceptions.format("DevTools console: {0}:{1}, {2}", HOST, Integer.toString(this.getPort()), code);
    }

    /**
     * Attaches the inspector to a game: creates the console and the execution contexts, and tells the sessions that the contexts changed.
     *
     * @return the attached game, or {@code null} if the runtime has no console
     */
    private @Nullable InspectedGame attach(@NotNull Game game, @NotNull ScriptRuntime runtime) {
        synchronized (this.lock) {
            InspectorServer current = this.server;

            if (current == null) {
                return null;
            }

            InspectedGame attached;

            try {
                attached = new InspectedGame(game, runtime, runtime.createConsole(), isSharedGame(game), this.nextContextId, current.getTargetId());
            } catch (IllegalStateException exception) {
                return null;
            }

            this.nextContextId += attached.getContexts().size();
            this.inspected = attached;

            this.debugger.attach(attached);

            for (InspectorSession session : this.sessions) {
                session.onGameAttached(attached);
            }

            LOGGER.info("DEVTOOLS CONSOLE: {0} ({1})", this.getFrontendUrl(), attached.isShared() ? "shared and local code" : "local code");

            return attached;
        }
    }

    /**
     * Adds a session of the server.
     *
     * @param session the session
     */
    void addSession(@NotNull InspectorSession session) {
        this.sessions.add(session);
    }

    /**
     * Removes a closed session.
     *
     * @param session the session
     */
    void removeSession(@NotNull InspectorSession session) {
        this.sessions.remove(session);
        this.debugger.removeBreakpoints(session);
    }

    /**
     * Enables the {@code Runtime} events of a session and sends it the execution contexts of the attached game, together, so that no change of
     * the game comes between them.
     *
     * @param session the session
     */
    void enableRuntime(@NotNull InspectorSession session) {
        synchronized (this.lock) {
            session.setRuntimeEnabled(true);

            InspectedGame current = this.inspected;

            if (current != null) {
                session.sendContexts(current);
            }
        }
    }

    /**
     * Enables the {@code Debugger} events of a session and sends it the scripts of the attached game, together, so that no change of the game
     * comes between them.
     *
     * @param session the session
     */
    void enableDebugger(@NotNull InspectorSession session) {
        synchronized (this.lock) {
            session.setDebuggerEnabled(true);

            InspectedGame current = this.inspected;

            if (current != null) {
                session.sendScripts(current);
            }
        }
    }

    /**
     * Adds a request that runs on the game thread at the next frame start.
     *
     * @param task the request
     */
    void queue(@NotNull InspectorTask task) {
        this.tasks.add(task);
    }

    /**
     * Creates the log output of a game's script runtime: it writes to the SC:R Enhanced log and, while the inspector is attached to the game,
     * sends each message to the sessions as a console message and each uncaught script error as an exception.
     *
     * @param game the game
     * @return the created {@link ScriptLogOutput} instance
     */
    public @NotNull ScriptLogOutput createLogOutput(@NotNull Game game) {
        return new ScriptLogOutput() {
            @Override
            public void write(@NotNull LogLevel level, @NotNull String moduleName, @NotNull String message) {
                ScriptRuntime.LOGGER_OUTPUT.write(level, moduleName, message);
                ScriptInspector.this.sendLogMessage(game, level, moduleName, message);
            }

            @Override
            public void writeScriptError(@NotNull String moduleName, @NotNull String message, @NotNull RhinoException error) {
                ScriptRuntime.LOGGER_OUTPUT.writeScriptError(moduleName, message, error);
                ScriptInspector.this.sendScriptError(game, error);
            }
        };
    }

    private void sendLogMessage(@NotNull Game game, @NotNull LogLevel level, @NotNull String moduleName, @NotNull String message) {
        InspectedGame current = this.inspected;

        if (current == null || current.getGame() != game) {
            return;
        }

        Map<String, Object> text = new LinkedHashMap<>();
        Map<String, Object> parameters = new LinkedHashMap<>();

        text.put("type", "string");
        text.put("value", moduleName + ": " + message);

        parameters.put("type", switch (level) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case DEBUG, TRACE -> "debug";
            default -> "log";
        });
        parameters.put("args", List.of(text));
        parameters.put("executionContextId", current.getContexts().getFirst().getId());
        parameters.put("timestamp", (double) System.currentTimeMillis());

        this.sendEvent("Runtime.consoleAPICalled", parameters);
    }

    private void sendScriptError(@NotNull Game game, @NotNull RhinoException error) {
        InspectedGame current = this.inspected;

        if (current == null || current.getGame() != game) {
            return;
        }

        Map<String, Object> details = this.logObjects.createExceptionDetails(error);
        Map<String, Object> parameters = new LinkedHashMap<>();

        details.put("executionContextId", current.getContexts().getFirst().getId());
        parameters.put("timestamp", (double) System.currentTimeMillis());
        parameters.put("exceptionDetails", details);

        this.sendEvent("Runtime.exceptionThrown", parameters);
    }

    private void sendEvent(@NotNull String method, @NotNull Map<String, Object> parameters) {
        Map<String, Object> event = new LinkedHashMap<>();

        event.put("method", method);
        event.put("params", parameters);

        String text = JsonWriterUtils.write(event);

        for (InspectorSession session : this.sessions) {
            if (session.isRuntimeEnabled()) {
                session.sendText(text);
            }
        }
    }
}
