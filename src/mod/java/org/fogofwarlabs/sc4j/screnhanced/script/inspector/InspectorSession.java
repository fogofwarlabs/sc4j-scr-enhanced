package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptConsole;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;

import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Serves one DevTools connection: reads its Chrome DevTools Protocol requests, answers them, and sends it the events of the inspector.
 * <p>
 * The session answers the {@code Runtime} domain: {@code enable} and {@code disable}, {@code evaluate}, {@code callFunctionOn},
 * {@code getProperties}, {@code releaseObject} and {@code releaseObjectGroup}, which run on the game thread at the next frame start
 * ({@link #runTask}), and {@code compileScript}, {@code globalLexicalScopeNames}, {@code getIsolateId} and {@code getHeapUsage}, which it answers
 * at once. Of the {@code Debugger} domain it answers {@code enable}, which sends a {@code Debugger.scriptParsed} event for each script of the
 * script package, {@code getScriptSource}, the breakpoints by URL with their conditions, the pause on exceptions and the pause request at
 * once, and {@code resume}, the steps and {@code evaluateOnCallFrame} on the game thread ({@link ScriptDebugger}). Any other method, of any
 * domain, gets an empty result, so that DevTools works without a profiler or pages.
 * <p>
 * An evaluation with {@code throwOnSideEffect}, which DevTools sends while the console's text is typed, runs only a name or a chain of property
 * reads, as local code; any other text is refused. The {@code getCompletions} function of DevTools runs as Java code, since Rhino keeps the
 * first value of a {@code const} declared in a loop body; for the same reason the {@code const} declarations of any other function of
 * {@code callFunctionOn} run as {@code let}. A request that waits longer than the timeout, while the game runs no frame, is
 * answered with an error.
 * <p>
 * One thread reads the connection and one writes it; the game thread runs the tasks and owns the session's objects.
 */
final class InspectorSession {
    /**
     * The largest length of a request in bytes.
     */
    static final int MAXIMUM_MESSAGE_LENGTH = 16 * 1024 * 1024;

    private static final int OUTGOING_LIMIT = 10_000;
    private static final long WRITER_POLL_MILLISECONDS = 250;
    private static final int SERVER_ERROR = -32000;
    private static final @NotNull String WRITER_THREAD_NAME = "sc4j-devtools-writer";
    private static final @NotNull String SIDE_EFFECT_DESCRIPTION = "EvalError: Possible side-effect in debug-evaluate";
    private static final @NotNull String INCOMPLETE_DESCRIPTION = "SyntaxError: Unexpected end of input";
    private static final @NotNull String NO_GAME_MESSAGE = "No game with a map script runs.";
    private static final @NotNull String NO_FRAME_MESSAGE = "The game ran no frame: it is paused or has ended.";
    private static final @NotNull String NO_OBJECT_MESSAGE = "Could not find object with given id";
    private static final @NotNull String NO_CONTEXT_MESSAGE = "Cannot find context with specified id";
    private static final @NotNull String NO_SCRIPT_MESSAGE = "No script for id: {0}";
    private static final @NotNull String NO_BREAKPOINTS_MESSAGE = "Only breakpoints by URL are supported.";
    private static final @NotNull String NO_CALL_FRAME_MESSAGE = "Could not find call frame with given id";
    private static final @NotNull String BACKTRACE_GROUP = "backtrace";
    private static final @NotNull String NO_EXCEPTIONS_STATE = "none";
    /**
     * A name or a chain of property reads with names, numbers and string literals: the text that an evaluation with
     * {@code throwOnSideEffect} runs.
     */
    private static final @NotNull Pattern PROPERTY_READS = Pattern.compile(
            "\\s*(this|[A-Za-z_$][\\w$]*)(\\s*(\\.\\s*[A-Za-z_$][\\w$]*|\\[\\s*(\\d+|'[^'\\\\]*'|\"[^\"\\\\]*\")\\s*]))*\\s*"
    );
    /**
     * The function of DevTools that lists the property names for completion.
     */
    private static final @NotNull Pattern COMPLETIONS_FUNCTION = Pattern.compile("\\s*function\\s+getCompletions\\s*\\(");
    /**
     * The {@code const} declarations of the functions of DevTools, which run as {@code let}: Rhino has no {@code for (const ...)} and keeps the
     * first value of a {@code const} declared in a loop body.
     */
    private static final @NotNull Pattern CONST_DECLARATION = Pattern.compile("\\bconst(?=\\s)");
    private static final @NotNull ContextFactory COMPILE_CONTEXT_FACTORY = new ContextFactory();
    /**
     * The reviver of {@code JSON.parse} that keeps every value.
     */
    private static final @NotNull Callable KEEPING_REVIVER = (context, scope, thisObject, arguments) -> arguments[1];

    private static @NotNull Map<String, Object> createResponse(long requestId, @NotNull String key, @NotNull Map<String, Object> value) {
        Map<String, Object> response = new LinkedHashMap<>();

        response.put("id", requestId);
        response.put(key, value);

        return response;
    }

    private static @NotNull Map<String, Object> createError(@NotNull String message) {
        Map<String, Object> error = new LinkedHashMap<>();

        error.put("code", SERVER_ERROR);
        error.put("message", message);

        return error;
    }

    /**
     * Creates the result of an evaluation that threw: the thrown value and the exception details.
     */
    private static @NotNull Map<String, Object> createExceptionResult(@NotNull Map<String, Object> details) {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("result", details.get("exception"));
        result.put("exceptionDetails", details);

        return result;
    }

    /**
     * Creates the result of an evaluation that did not run, with an error of a description.
     */
    private static @NotNull Map<String, Object> createRefusalResult(@NotNull String description) {
        Map<String, Object> thrown = new LinkedHashMap<>();
        Map<String, Object> details = new LinkedHashMap<>();

        thrown.put("type", "object");
        thrown.put("subtype", "error");
        thrown.put("className", description.substring(0, description.indexOf(':')));
        thrown.put("description", description);

        details.put("exceptionId", 0);
        details.put("text", "Uncaught");
        details.put("lineNumber", 0);
        details.put("columnNumber", 0);
        details.put("exception", thrown);

        return createExceptionResult(details);
    }

    private static @Nullable String findString(@NotNull Map<String, Object> parameters, @NotNull String key) {
        return parameters.get(key) instanceof String value ? value : null;
    }

    private static @NotNull String requireString(@NotNull Map<String, Object> parameters, @NotNull String key) {
        if (!(parameters.get(key) instanceof String value)) {
            throw Exceptions.newIllegalArgumentException("Invalid parameters: {0} is missing.", key);
        }

        return value;
    }

    private static boolean isSet(@NotNull Map<String, Object> parameters, @NotNull String key) {
        return Boolean.TRUE.equals(parameters.get(key));
    }

    @SuppressWarnings("unchecked")
    private static @NotNull Map<String, Object> findObject(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }


    private final @NotNull ScriptInspector inspector;
    private final @NotNull Socket socket;
    private final @NotNull WebSocketConnection connection;
    private final @NotNull String targetId;

    private final @NotNull BlockingQueue<String> outgoing;
    private final @NotNull Queue<InspectorTask> pending;
    private final @NotNull InspectorObjects objects;

    private volatile boolean runtimeEnabled;
    private volatile boolean debuggerEnabled;
    private volatile boolean closed;

    /**
     * Creates an {@link InspectorSession} instance over a connection whose WebSocket handshake is done.
     *
     * @param inspector  the inspector
     * @param socket     the socket of the connection
     * @param connection the WebSocket connection over the socket
     * @param targetId   the id of the inspector's target
     */
    InspectorSession(@NotNull ScriptInspector inspector, @NotNull Socket socket, @NotNull WebSocketConnection connection, @NotNull String targetId) {
        this.inspector  = inspector;
        this.socket     = socket;
        this.connection = connection;
        this.targetId   = targetId;

        this.outgoing = new LinkedBlockingQueue<>(OUTGOING_LIMIT);
        this.pending  = new ConcurrentLinkedQueue<>();
        this.objects  = new InspectorObjects();

        this.runtimeEnabled  = false;
        this.debuggerEnabled = false;
        this.closed          = false;
    }

    /**
     * @return {@code true} if the session receives the {@code Runtime} events, {@code false} otherwise
     */
    boolean isRuntimeEnabled() {
        return this.runtimeEnabled;
    }

    /**
     * Sets whether the session receives the {@code Runtime} events.
     *
     * @param runtimeEnabled {@code true} after {@code Runtime.enable}
     */
    void setRuntimeEnabled(boolean runtimeEnabled) {
        this.runtimeEnabled = runtimeEnabled;
    }

    /**
     * Sets whether the session receives the {@code Debugger} events.
     *
     * @param debuggerEnabled {@code true} after {@code Debugger.enable}
     */
    void setDebuggerEnabled(boolean debuggerEnabled) {
        this.debuggerEnabled = debuggerEnabled;
    }

    /**
     * @return {@code true} if the session receives the {@code Debugger} events, {@code false} otherwise
     */
    boolean isDebuggerEnabled() {
        return this.debuggerEnabled && !this.closed;
    }


    /**
     * Reads and answers the requests on the calling thread until the connection ends, with a writer thread of its own; then closes the session.
     */
    void run() {
        Thread.ofPlatform().daemon().name(WRITER_THREAD_NAME).start(this::writeMessages);

        try {
            for (String message = this.connection.readMessage(); message != null && !this.closed; message = this.connection.readMessage()) {
                this.handleMessage(message);
            }
        } catch (IOException exception) {
            // The connection ended.
        } finally {
            this.close();
        }
    }

    private void writeMessages() {
        try {
            while (!this.closed) {
                String message = this.outgoing.poll(WRITER_POLL_MILLISECONDS, TimeUnit.MILLISECONDS);

                if (message != null) {
                    this.connection.writeText(message);
                }

                this.expireTasks();
            }
        } catch (IOException exception) {
            this.close();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.close();
        }
    }

    /**
     * Answers with an error the tasks that waited longer than the timeout for a frame.
     */
    private void expireTasks() {
        long now = System.nanoTime();

        for (Iterator<InspectorTask> iterator = this.pending.iterator(); iterator.hasNext(); ) {
            InspectorTask task = iterator.next();

            if (task.isClaimed()) {
                iterator.remove();
            } else if (now - task.getCreatedNanoseconds() > ScriptInspector.TASK_TIMEOUT_NANOSECONDS && task.claim()) {
                iterator.remove();
                this.answerUnavailable(task.getRequestId(), task.getMethod(), NO_FRAME_MESSAGE);
            }
        }
    }

    private void handleMessage(@NotNull String text) {
        Map<String, Object> message;

        try {
            message = findObject(JsonReader.parse(text.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            return;
        }

        if (!(message.get("id") instanceof Number id) || !(message.get("method") instanceof String method)) {
            return;
        }

        long requestId = id.longValue();
        Map<String, Object> parameters = findObject(message.get("params"));

        try {
            this.dispatch(requestId, method, parameters);
        } catch (RuntimeException exception) {
            this.send(createResponse(requestId, "error", createError(String.valueOf(exception.getMessage()))));
        }
    }

    private void dispatch(long requestId, @NotNull String method, @NotNull Map<String, Object> parameters) {
        switch (method) {
            case "Runtime.enable" -> {
                this.inspector.enableRuntime(this);
                this.reply(requestId, Map.of());
            }
            case "Runtime.disable" -> {
                this.runtimeEnabled = false;
                this.reply(requestId, Map.of());
            }
            case "Runtime.evaluate",
                 "Runtime.callFunctionOn",
                 "Runtime.getProperties",
                 "Runtime.releaseObject",
                 "Runtime.releaseObjectGroup" -> this.queueTask(requestId, method, parameters);
            case "Runtime.compileScript" -> this.reply(requestId, this.compileScript(parameters));
            case "Runtime.globalLexicalScopeNames" -> this.reply(requestId, Map.of("names", List.of()));
            case "Runtime.getIsolateId" -> this.reply(requestId, Map.of("id", this.targetId));
            case "Runtime.getHeapUsage" -> this.reply(requestId, Map.of("usedSize", 0, "totalSize", 0));
            case "Debugger.enable" -> {
                this.reply(requestId, Map.of("debuggerId", this.targetId));
                this.inspector.enableDebugger(this);
            }
            case "Debugger.disable" -> {
                this.debuggerEnabled = false;
                this.reply(requestId, Map.of());
            }
            case "Debugger.getScriptSource" -> this.reply(requestId, Map.of("scriptSource", this.findScriptSource(requireString(parameters, "scriptId"))));
            case "Debugger.getPossibleBreakpoints" -> this.reply(requestId, Map.of("locations", List.of()));
            case "Debugger.setBreakpointByUrl" -> this.reply(requestId, this.setBreakpointByUrl(parameters));
            case "Debugger.removeBreakpoint" -> {
                this.inspector.getDebugger().removeBreakpoint(requireString(parameters, "breakpointId"));
                this.reply(requestId, Map.of());
            }
            case "Debugger.setBreakpointsActive" -> {
                this.inspector.getDebugger().setActive(isSet(parameters, "active"));
                this.reply(requestId, Map.of());
            }
            case "Debugger.setPauseOnExceptions" -> {
                this.inspector.getDebugger().setPauseOnExceptions(!NO_EXCEPTIONS_STATE.equals(findString(parameters, "state")));
                this.reply(requestId, Map.of());
            }
            case "Debugger.pause" -> {
                this.inspector.getDebugger().requestPause();
                this.reply(requestId, Map.of());
            }
            case "Debugger.resume",
                 "Debugger.stepOver",
                 "Debugger.stepInto",
                 "Debugger.stepOut",
                 "Debugger.evaluateOnCallFrame" -> this.queueTask(requestId, method, parameters);
            case "Debugger.setBreakpoint",
                 "Debugger.setBreakpointOnFunctionCall" -> this.send(createResponse(requestId, "error", createError(NO_BREAKPOINTS_MESSAGE)));
            default -> this.reply(requestId, Map.of());
        }
    }

    private void queueTask(long requestId, @NotNull String method, @NotNull Map<String, Object> parameters) {
        if (this.inspector.getInspected() == null) {
            this.answerUnavailable(requestId, method, NO_GAME_MESSAGE);

            return;
        }

        InspectorTask task = new InspectorTask(this, requestId, method, parameters, System.nanoTime());

        this.pending.add(task);
        this.inspector.queue(task);
    }

    /**
     * Answers a request that cannot run: an evaluation with an error result, a release with an empty result, anything else with an error.
     */
    private void answerUnavailable(long requestId, @NotNull String method, @NotNull String message) {
        switch (method) {
            case "Runtime.evaluate",
                 "Runtime.callFunctionOn",
                 "Debugger.evaluateOnCallFrame" -> this.reply(requestId, createRefusalResult("Error: " + message));
            case "Runtime.releaseObject", "Runtime.releaseObjectGroup" -> this.reply(requestId, Map.of());
            default -> this.send(createResponse(requestId, "error", createError(message)));
        }
    }

    /**
     * Checks whether a text compiles as console code, as DevTools asks before it evaluates a line: an incomplete text gives the error that
     * makes DevTools wait for more lines.
     */
    private @NotNull Map<String, Object> compileScript(@NotNull Map<String, Object> parameters) {
        String expression = requireString(parameters, "expression");

        try (Context context = COMPILE_CONTEXT_FACTORY.enterContext()) {
            context.setInterpretedMode(true);
            context.setLanguageVersion(Context.VERSION_ECMASCRIPT);

            if (!context.stringIsCompilableUnit(expression)) {
                return createRefusalResult(INCOMPLETE_DESCRIPTION);
            }

            context.compileString(expression, ScriptConsole.SOURCE_NAME, 1, null);

            return Map.of();
        } catch (RhinoException exception) {
            Map<String, Object> details = new InspectorObjects().createExceptionDetails(exception);
            Map<String, Object> result = new LinkedHashMap<>();

            result.put("exceptionDetails", details);

            return result;
        }
    }

    /**
     * Adds a breakpoint by {@code url} or {@code urlRegex} at {@code lineNumber}, with an optional {@code condition}; its location is the
     * script of the attached game with the URL as its name.
     */
    private @NotNull Map<String, Object> setBreakpointByUrl(@NotNull Map<String, Object> parameters) {
        String url = findString(parameters, "url");
        String urlRegex = findString(parameters, "urlRegex");

        if (url == null && urlRegex == null || !(parameters.get("lineNumber") instanceof Number lineNumber)) {
            throw Exceptions.newIllegalArgumentException("Invalid parameters: url or urlRegex and lineNumber are missing.");
        }

        int line = lineNumber.intValue();
        String id = this.inspector.getDebugger().addBreakpoint(this, url, url == null ? Pattern.compile(urlRegex) : null, line + 1,
                                                               findString(parameters, "condition"));
        InspectedGame game = this.inspector.getInspected();
        List<Object> locations = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();

        if (game != null && url != null && game.findScriptSource(game.getScriptId(url)) != null) {
            locations.add(Map.of("scriptId", game.getScriptId(url), "lineNumber", line, "columnNumber", 0));
        }

        result.put("breakpointId", id);
        result.put("locations", locations);

        return result;
    }

    /**
     * Finds the source of a script of the attached game.
     */
    private @NotNull String findScriptSource(@NotNull String scriptId) {
        InspectedGame game = this.inspector.getInspected();
        String source = game == null ? null : game.findScriptSource(scriptId);

        if (source == null) {
            throw Exceptions.newIllegalArgumentException(NO_SCRIPT_MESSAGE, scriptId);
        }

        return source;
    }

    /**
     * Called on the game thread when the inspector attached to another game: forgets the objects of the previous game and, with the
     * {@code Runtime} events enabled, replaces the execution contexts; with the {@code Debugger} events enabled, sends the scripts.
     *
     * @param game the attached game
     */
    void onGameAttached(@NotNull InspectedGame game) {
        this.objects.clear();

        if (this.runtimeEnabled) {
            this.send(Map.of("method", "Runtime.executionContextsCleared", "params", Map.of()));
            this.sendContexts(game);
        }

        if (this.debuggerEnabled) {
            this.sendScripts(game);
        }
    }

    /**
     * Sends the {@code Debugger.paused} event, on the game thread, with the frames of the held call, their scopes and {@code this} as remote
     * objects of the group {@value #BACKTRACE_GROUP}.
     *
     * @param context the current context
     * @param game    the attached game
     * @param frames  the frames, the innermost first
     * @param reason  the reason
     * @param data    the thrown value, or {@code null} for none
     * @param hits    the ids of the breakpoints that hold
     */
    void sendPaused(@NotNull Context context, @NotNull InspectedGame game, @NotNull List<ScriptDebugger.Frame> frames, @NotNull String reason,
                    @Nullable Object data, @NotNull List<String> hits) {
        if (!this.isDebuggerEnabled()) {
            return;
        }

        InspectedGame.ExecutionContext executionContext = game.getContexts().getFirst();
        InspectorObjects.Origin origin = new InspectorObjects.Origin(BACKTRACE_GROUP, executionContext.getId(), executionContext.isLocal());
        List<Object> callFrames = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>();

        for (int index = 0; index < frames.size(); index++) {
            ScriptDebugger.Frame frame = frames.get(index);
            Map<String, Object> callFrame = new LinkedHashMap<>();

            callFrame.put("callFrameId", this.inspector.getDebugger().createFrameId(index));
            callFrame.put("functionName", frame.getFunctionName());
            callFrame.put("location", Map.of("scriptId", game.getScriptId(frame.getScriptName()), "lineNumber", Math.max(frame.getLine() - 1, 0),
                                             "columnNumber", 0));
            callFrame.put("url", frame.getScriptName());
            callFrame.put("scopeChain", this.createScopeChain(context, frame.getActivation(), origin));
            callFrame.put("this", this.objects.createRemoteObject(context, frame.getThis(), origin, false));

            callFrames.add(callFrame);
        }

        parameters.put("callFrames", callFrames);
        parameters.put("reason", reason);
        parameters.put("hitBreakpoints", hits);

        if (data != null) {
            parameters.put("data", this.objects.createRemoteObject(context, data, origin, true));
        }

        this.send(Map.of("method", "Debugger.paused", "params", parameters));
    }

    /**
     * Creates the scope chain of a frame: its variables ({@code local}), the variables of the functions around it ({@code closure}), the top
     * level of its module ({@code module}) and the global scope ({@code global}).
     */
    private @NotNull List<Object> createScopeChain(@NotNull Context context, @Nullable Scriptable activation, @NotNull InspectorObjects.Origin origin) {
        List<Object> scopes = new ArrayList<>();

        for (Scriptable scope = activation; scope != null; scope = scope.getParentScope()) {
            String type = scope.getParentScope() == null ? "global"
                                                         : scope instanceof NativeCall ? (scope == activation ? "local" : "closure")
                                                                                       : "module";

            scopes.add(Map.of("type", type, "object", this.objects.createRemoteObject(context, scope, origin, false)));
        }

        return scopes;
    }

    /**
     * Sends the {@code Debugger.resumed} event and releases the remote objects of the held call, on the game thread.
     */
    void sendResumed() {
        this.objects.releaseGroup(BACKTRACE_GROUP);

        if (this.isDebuggerEnabled()) {
            this.send(Map.of("method", "Debugger.resumed", "params", Map.of()));
        }
    }

    /**
     * Runs {@code Debugger.evaluateOnCallFrame}: the expression in the scope of a frame of the held call, with the rules of the default
     * context.
     */
    private @NotNull Map<String, Object> evaluateOnCallFrame(@NotNull Map<String, Object> parameters, @NotNull InspectedGame game) {
        ScriptDebugger debugger = this.inspector.getDebugger();
        ScriptDebugger.Frame frame = debugger.findHeldFrame(requireString(parameters, "callFrameId"));
        String expression = requireString(parameters, "expression");

        if (frame == null || frame.getActivation() == null) {
            throw Exceptions.newIllegalArgumentException(NO_CALL_FRAME_MESSAGE);
        }

        if (isSet(parameters, "throwOnSideEffect") && !PROPERTY_READS.matcher(expression).matches()) {
            return createRefusalResult(SIDE_EFFECT_DESCRIPTION);
        }

        InspectedGame.ExecutionContext context = game.getContexts().getFirst();
        InspectorObjects.Origin origin = new InspectorObjects.Origin(Objects.requireNonNullElse(findString(parameters, "objectGroup"), ""),
                                                                     context.getId(), context.isLocal());
        ScriptConsole console = game.getConsole();

        return this.runConsole(console, origin, scriptContext -> this.createResult(scriptContext, console,
                                                                                   debugger.evaluate(scriptContext, frame, expression), origin,
                                                                                   parameters));
    }

    /**
     * Sends the {@code Debugger.scriptParsed} event of each script of a game.
     *
     * @param game the game
     */
    void sendScripts(@NotNull InspectedGame game) {
        for (Map<String, Object> script : game.createScriptDescriptions()) {
            this.send(Map.of("method", "Debugger.scriptParsed", "params", script));
        }
    }

    /**
     * Sends the {@code Runtime.executionContextCreated} event of each execution context of a game.
     *
     * @param game the game
     */
    void sendContexts(@NotNull InspectedGame game) {
        for (InspectedGame.ExecutionContext context : game.getContexts()) {
            this.send(Map.of("method", "Runtime.executionContextCreated", "params", Map.of("context", context.createDescription())));
        }
    }

    /**
     * Runs a task on the game thread, between frames, and answers it.
     *
     * @param task the task, claimed by the caller
     * @param game the attached game
     */
    void runTask(@NotNull InspectorTask task, @NotNull InspectedGame game) {
        Map<String, Object> parameters = task.getParameters();

        this.pending.remove(task);

        try {
            Map<String, Object> result = switch (task.getMethod()) {
                case "Runtime.evaluate" -> this.evaluate(parameters, game);
                case "Runtime.callFunctionOn" -> this.callFunctionOn(parameters, game);
                case "Runtime.getProperties" -> this.getProperties(parameters, game);
                case "Debugger.evaluateOnCallFrame" -> this.evaluateOnCallFrame(parameters, game);
                case "Debugger.resume" -> this.resume(ScriptDebugger.Step.NONE);
                case "Debugger.stepOver" -> this.resume(ScriptDebugger.Step.OVER);
                case "Debugger.stepInto" -> this.resume(ScriptDebugger.Step.INTO);
                case "Debugger.stepOut" -> this.resume(ScriptDebugger.Step.OUT);
                case "Runtime.releaseObject" -> {
                    this.objects.release(requireString(parameters, "objectId"));

                    yield Map.of();
                }
                case "Runtime.releaseObjectGroup" -> {
                    this.objects.releaseGroup(requireString(parameters, "objectGroup"));

                    yield Map.of();
                }
                default -> Map.of();
            };

            this.reply(task.getRequestId(), result);
        } catch (RuntimeException exception) {
            this.send(createResponse(task.getRequestId(), "error", createError(String.valueOf(exception.getMessage()))));
        }
    }

    private @NotNull Map<String, Object> resume(@NotNull ScriptDebugger.Step step) {
        this.inspector.getDebugger().resume(step);

        return Map.of();
    }

    /**
     * Runs {@code Runtime.evaluate}: the expression in the console's scope, in the requested execution context.
     */
    private @NotNull Map<String, Object> evaluate(@NotNull Map<String, Object> parameters, @NotNull InspectedGame game) {
        String expression = requireString(parameters, "expression");
        InspectedGame.ExecutionContext context = this.findContext(parameters, game, "contextId");
        boolean throwOnSideEffect = isSet(parameters, "throwOnSideEffect");

        if (throwOnSideEffect && !PROPERTY_READS.matcher(expression).matches()) {
            return createRefusalResult(SIDE_EFFECT_DESCRIPTION);
        }

        String group = Objects.requireNonNullElse(findString(parameters, "objectGroup"), "");
        boolean local = context.isLocal() || throwOnSideEffect;
        InspectorObjects.Origin origin = new InspectorObjects.Origin(group, context.getId(), local);
        ScriptConsole console = game.getConsole();

        return this.runConsole(console, origin, scriptContext -> {
            Object value = console.evaluate(scriptContext, expression);

            if (isSet(parameters, "includeCommandLineAPI") && !throwOnSideEffect) {
                console.setLastResult(value);
            }

            return this.createResult(scriptContext, console, value, origin, parameters);
        });
    }

    /**
     * Runs {@code Runtime.callFunctionOn}: a function declaration called on an object by id, or on the console's scope.
     */
    private @NotNull Map<String, Object> callFunctionOn(@NotNull Map<String, Object> parameters, @NotNull InspectedGame game) {
        String declaration = CONST_DECLARATION.matcher(requireString(parameters, "functionDeclaration")).replaceAll("let");
        String objectId = findString(parameters, "objectId");
        InspectorObjects.Entry target = objectId == null ? null : this.requireEntry(objectId);
        InspectorObjects.Origin origin;

        if (target != null) {
            InspectorObjects.Origin targetOrigin = target.getOrigin();

            origin = new InspectorObjects.Origin(Objects.requireNonNullElse(findString(parameters, "objectGroup"), ""), targetOrigin.getContextId(),
                                                 targetOrigin.isLocal());
        } else {
            String idKey = parameters.containsKey("executionContextId") ? "executionContextId" : "contextId";
            InspectedGame.ExecutionContext context = this.findContext(parameters, game, idKey);

            origin = new InspectorObjects.Origin(Objects.requireNonNullElse(findString(parameters, "objectGroup"), ""), context.getId(),
                                                 context.isLocal());
        }

        if (isSet(parameters, "throwOnSideEffect")) {
            return createRefusalResult(SIDE_EFFECT_DESCRIPTION);
        }

        ScriptConsole console = game.getConsole();
        List<?> arguments = parameters.get("arguments") instanceof List<?> list ? list : List.of();
        Object thisObject = target == null ? console.getScope() : target.getValue();

        if (COMPLETIONS_FUNCTION.matcher(declaration).lookingAt()) {
            String type = arguments.isEmpty() ? "" : String.valueOf(findObject(arguments.getFirst()).get("value"));

            return console.run(true, scriptContext -> Map.of("result", Map.of("type", "object", "value",
                                                                              this.objects.findCompletions(console.getScope(), thisObject, type))));
        }

        return this.runConsole(console, origin, scriptContext -> {
            Function function = console.compileFunction(scriptContext, declaration);
            Object[] values = new Object[arguments.size()];

            for (int index = 0; index < values.length; index++) {
                values[index] = this.toScriptValue(scriptContext, console.getScope(), findObject(arguments.get(index)));
            }

            Scriptable callThis = thisObject instanceof Scriptable scriptable ? scriptable : console.getScope();
            Object value = function.call(scriptContext, console.getScope(), callThis, values);

            return this.createResult(scriptContext, console, value, origin, parameters);
        });
    }

    /**
     * Runs {@code Runtime.getProperties}: the properties of an object by id, read with the rules of the object's execution context.
     */
    private @NotNull Map<String, Object> getProperties(@NotNull Map<String, Object> parameters, @NotNull InspectedGame game) {
        InspectorObjects.Entry entry = this.requireEntry(requireString(parameters, "objectId"));
        boolean ownProperties = isSet(parameters, "ownProperties");
        boolean accessorPropertiesOnly = isSet(parameters, "accessorPropertiesOnly");
        boolean nonIndexedOnly = isSet(parameters, "nonIndexedPropertiesOnly");
        boolean preview = isSet(parameters, "generatePreview");

        try {
            return game.getConsole().run(entry.getOrigin().isLocal(), scriptContext -> {
                try {
                    return this.objects.getProperties(scriptContext, entry, ownProperties, accessorPropertiesOnly, nonIndexedOnly, preview);
                } catch (RhinoException exception) {
                    Map<String, Object> result = new LinkedHashMap<>();

                    result.put("result", List.of());
                    result.put("exceptionDetails", this.objects.createExceptionDetails(scriptContext, exception, entry.getOrigin()));

                    return result;
                }
            });
        } catch (RhinoException exception) {
            Map<String, Object> result = new LinkedHashMap<>();

            result.put("result", List.of());
            result.put("exceptionDetails", this.objects.createExceptionDetails(exception));

            return result;
        }
    }

    /**
     * Runs a call of console code in the origin's execution context; a script error becomes the result's exception details.
     */
    private @NotNull Map<String, Object> runConsole(@NotNull ScriptConsole console, @NotNull InspectorObjects.Origin origin,
                                                    ScriptConsole.@NotNull Call<Map<String, Object>> call) {
        try {
            return console.run(origin.isLocal(), scriptContext -> {
                try {
                    return call.run(scriptContext);
                } catch (RhinoException exception) {
                    return createExceptionResult(this.objects.createExceptionDetails(scriptContext, exception, origin));
                }
            });
        } catch (RhinoException exception) {
            return createExceptionResult(this.objects.createExceptionDetails(exception));
        }
    }

    /**
     * Creates the result of a call: the value by reference, or as JSON when the request asks for it.
     */
    private @NotNull Map<String, Object> createResult(@NotNull Context context, @NotNull ScriptConsole console, @Nullable Object value,
                                                      @NotNull InspectorObjects.Origin origin, @NotNull Map<String, Object> parameters) {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("result", isSet(parameters, "returnByValue") ? this.objects.createValueObject(context, console.getScope(), value)
                                                                : this.objects.createRemoteObject(context, value, origin, isSet(parameters, "generatePreview")));

        return result;
    }

    /**
     * Converts a {@code CallArgument} of the Chrome DevTools Protocol to a script value.
     */
    private @Nullable Object toScriptValue(@NotNull Context context, @NotNull Scriptable scope, @NotNull Map<String, Object> argument) {
        if (argument.get("objectId") instanceof String objectId) {
            return this.requireEntry(objectId).getValue();
        }

        if (argument.get("unserializableValue") instanceof String text) {
            return switch (text) {
                case "NaN" -> Double.NaN;
                case "Infinity" -> Double.POSITIVE_INFINITY;
                case "-Infinity" -> Double.NEGATIVE_INFINITY;
                case "-0" -> -0.0;
                default -> {
                    if (!text.endsWith("n")) {
                        throw Exceptions.newIllegalArgumentException("Invalid unserializable value: {0}", text);
                    }

                    yield new BigInteger(text.substring(0, text.length() - 1));
                }
            };
        }

        if (!argument.containsKey("value")) {
            return Undefined.instance;
        }

        Object value = argument.get("value");

        return switch (value) {
            case null -> null;
            case Number number -> number.doubleValue();
            case String text -> text;
            case Boolean flag -> flag;
            default -> NativeJSON.parse(context, scope, JsonWriterUtils.write(value), KEEPING_REVIVER);
        };
    }

    private @NotNull InspectorObjects.Entry requireEntry(@NotNull String objectId) {
        InspectorObjects.Entry entry = this.objects.findEntry(objectId);

        if (entry == null) {
            throw Exceptions.newIllegalArgumentException(NO_OBJECT_MESSAGE);
        }

        return entry;
    }

    private @NotNull InspectedGame.ExecutionContext findContext(@NotNull Map<String, Object> parameters, @NotNull InspectedGame game,
                                                                @NotNull String idKey) {
        int contextId = parameters.get(idKey) instanceof Number number ? number.intValue() : -1;
        InspectedGame.ExecutionContext context = game.findContext(contextId, findString(parameters, "uniqueContextId"));

        if (context == null) {
            throw Exceptions.newIllegalArgumentException(NO_CONTEXT_MESSAGE);
        }

        return context;
    }

    private void reply(long requestId, @NotNull Map<String, Object> result) {
        this.send(createResponse(requestId, "result", result));
    }

    /**
     * Sends a message, as JSON, through the writer thread; a session whose writer falls too far behind is closed.
     *
     * @param message the message
     */
    void send(@NotNull Map<String, Object> message) {
        this.sendText(JsonWriterUtils.write(message));
    }

    /**
     * Sends a message, already JSON, through the writer thread; a session whose writer falls too far behind is closed without a close frame,
     * so that the caller never waits for the connection.
     *
     * @param message the message
     */
    void sendText(@NotNull String message) {
        if (!this.closed && !this.outgoing.offer(message)) {
            this.closed = true;

            this.closeSocket();
            this.inspector.removeSession(this);
        }
    }

    /**
     * Closes the connection and leaves the inspector's sessions; the tasks that wait are answered by no one.
     */
    void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        this.connection.close(WebSocketConnection.CLOSE_GOING_AWAY);
        this.closeSocket();
        this.inspector.removeSession(this);
    }

    private void closeSocket() {
        try {
            this.socket.close();
        } catch (IOException exception) {
            // The socket is closed either way.
        }
    }
}
