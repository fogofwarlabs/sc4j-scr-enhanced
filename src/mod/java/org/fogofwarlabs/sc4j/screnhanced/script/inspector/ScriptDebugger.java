package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptConsole;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.debug.DebugFrame;
import org.mozilla.javascript.debug.DebuggableScript;
import org.mozilla.javascript.debug.Debugger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Debugs the scripts of the attached game: Rhino reports to it each call and line of the scripts of the script package.
 * <p>
 * It holds the game thread at a breakpoint, at a {@code debugger} statement, at a thrown error while DevTools pauses on exceptions, after a
 * step and at a pause request. While it holds, it runs the requests of the sessions until one resumes; the game, its window and, in a
 * multiplayer game, the other players wait meanwhile.
 * <p>
 * Rhino calls it on the game thread. Breakpoints, the pause on exceptions and the pause request are set from the sessions' threads.
 */
final class ScriptDebugger implements Debugger {
    private static final long HOLD_POLL_MILLISECONDS = 10;
    private static final @NotNull String CONDITION_SOURCE_NAME = "breakpoint condition";
    private static final @NotNull String PAUSE_REASON = "other";
    private static final @NotNull String EXCEPTION_REASON = "exception";
    private static final @NotNull String FRAME_ID_SEPARATOR = ":";

    /**
     * How the debugger holds again after it resumes.
     */
    enum Step {
        /**
         * At the next breakpoint only.
         */
        NONE,
        /**
         * At the next line.
         */
        INTO,
        /**
         * At the next line of the held function or of a function that called it.
         */
        OVER,
        /**
         * At the next line of a function that called the held function.
         */
        OUT
    }

    /**
     * A breakpoint at a line of the scripts whose name is a URL or matches a pattern.
     */
    private static final class Breakpoint {
        private final @NotNull InspectorSession session;
        private final @Nullable String url;
        private final @Nullable Pattern urlPattern;
        private final int line;
        private final @Nullable String condition;

        private Breakpoint(@NotNull InspectorSession session, @Nullable String url, @Nullable Pattern urlPattern, int line, @Nullable String condition) {
            this.session    = session;
            this.url        = url;
            this.urlPattern = urlPattern;
            this.line       = line;
            this.condition  = condition == null || condition.isBlank() ? null : condition;
        }

        private boolean matches(@NotNull String scriptName, int line) {
            return this.line == line && (this.url != null ? this.url.equals(scriptName) : this.urlPattern != null && this.urlPattern.matcher(scriptName).find());
        }
    }

    /**
     * A running call of a function or of the top level of a script.
     */
    final class Frame implements DebugFrame {
        private final @NotNull DebuggableScript script;

        private @Nullable Scriptable activation;
        private @Nullable Scriptable thisObject;
        private int line;

        private Frame(@NotNull DebuggableScript script) {
            this.script = script;

            this.activation = null;
            this.thisObject = null;
            this.line       = 0;
        }

        /**
         * @return the name of the script
         */
        @NotNull String getScriptName() {
            return this.script.getSourceName();
        }

        /**
         * @return the name of the function, empty for the top level of a script or an anonymous function
         */
        @NotNull String getFunctionName() {
            return this.script.isFunction() ? Objects.requireNonNullElse(this.script.getFunctionName(), "") : "";
        }

        /**
         * @return the current line, from 1
         */
        int getLine() {
            return this.line;
        }

        /**
         * @return the scope of the call's variables, or {@code null} before the call entered
         */
        @Nullable Scriptable getActivation() {
            return this.activation;
        }

        /**
         * @return the {@code this} of the call, or {@code null} before the call entered
         */
        @Nullable Scriptable getThis() {
            return this.thisObject;
        }

        @Override
        public void onEnter(@NotNull Context context, @NotNull Scriptable activation, @NotNull Scriptable thisObject, Object @NotNull [] arguments) {
            this.activation = activation;
            this.thisObject = thisObject;

            ScriptDebugger.this.frames.add(this);
        }

        @Override
        public void onLineChange(@NotNull Context context, int lineNumber) {
            this.line = lineNumber;

            ScriptDebugger.this.onLine(this);
        }

        @Override
        public void onExceptionThrown(@NotNull Context context, @NotNull Throwable exception) {
            ScriptDebugger.this.onException(exception);
        }

        @Override
        public void onExit(@NotNull Context context, boolean byThrow, @Nullable Object resultOrException) {
            List<Frame> frames = ScriptDebugger.this.frames;
            int index = frames.lastIndexOf(this);

            if (index >= 0) {
                frames.remove(index);
            }
        }

        @Override
        public void onDebuggerStatement(@NotNull Context context) {
            ScriptDebugger.this.hold(PAUSE_REASON, null, List.of());
        }
    }


    private final @NotNull ScriptInspector inspector;
    private final @NotNull Map<String, Breakpoint> breakpoints;
    private final @NotNull AtomicInteger nextBreakpointId;

    private volatile boolean active;
    private volatile boolean pauseOnExceptions;
    private volatile boolean pauseRequested;

    private final @NotNull List<Frame> frames;
    private @Nullable InspectedGame game;
    private @NotNull Set<String> scriptNames;
    private @NotNull Step step;
    private int stepDepth;
    private @Nullable List<Frame> heldFrames;
    private int holdCount;
    private boolean resumed;
    private boolean evaluating;
    private @Nullable Throwable lastException;

    /**
     * Creates a {@link ScriptDebugger} instance without breakpoints, attached to no game.
     *
     * @param inspector the inspector whose sessions the debugger serves
     */
    ScriptDebugger(@NotNull ScriptInspector inspector) {
        this.inspector        = inspector;
        this.breakpoints      = new ConcurrentHashMap<>();
        this.nextBreakpointId = new AtomicInteger(1);

        this.active            = true;
        this.pauseOnExceptions = false;
        this.pauseRequested    = false;

        this.frames      = new ArrayList<>();
        this.game        = null;
        this.scriptNames = Set.of();
        this.step        = Step.NONE;
        this.stepDepth   = 0;
        this.heldFrames  = null;
        this.holdCount   = 0;
        this.resumed     = false;
        this.evaluating  = false;
    }

    /**
     * Attaches the debugger to a game, on the game thread: it debugs the scripts of the game's script package from the next call on.
     *
     * @param game the game
     */
    void attach(@NotNull InspectedGame game) {
        this.game        = game;
        this.scriptNames = Set.copyOf(game.getRuntime().getScripts().keySet());
        this.step        = Step.NONE;
        this.frames.clear();

        game.getRuntime().setDebugger(this);
    }

    /**
     * Adds a breakpoint.
     *
     * @param session    the session that sets it
     * @param url        the script name, or {@code null} to match the pattern
     * @param urlPattern the pattern of the script names, or {@code null} with a script name
     * @param line       the line, from 1
     * @param condition  the condition, or {@code null} for none
     * @return the id of the breakpoint
     */
    @NotNull String addBreakpoint(@NotNull InspectorSession session, @Nullable String url, @Nullable Pattern urlPattern, int line,
                                  @Nullable String condition) {
        String id = Integer.toString(this.nextBreakpointId.getAndIncrement());

        this.breakpoints.put(id, new Breakpoint(session, url, urlPattern, line, condition));

        return id;
    }

    /**
     * Removes a breakpoint.
     *
     * @param id the id of the breakpoint
     */
    void removeBreakpoint(@NotNull String id) {
        this.breakpoints.remove(id);
    }

    /**
     * Removes the breakpoints of a session.
     *
     * @param session the session
     */
    void removeBreakpoints(@NotNull InspectorSession session) {
        this.breakpoints.values().removeIf(breakpoint -> breakpoint.session == session);
    }

    /**
     * Sets whether the breakpoints hold.
     *
     * @param active {@code true} if they hold
     */
    void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Sets whether a thrown error holds, caught or not.
     *
     * @param pauseOnExceptions {@code true} if it holds
     */
    void setPauseOnExceptions(boolean pauseOnExceptions) {
        this.pauseOnExceptions = pauseOnExceptions;
    }

    /**
     * Makes the debugger hold at the next line of a script.
     */
    void requestPause() {
        this.pauseRequested = true;
    }

    /**
     * Resumes the held call, on the game thread.
     *
     * @param step how the debugger holds again
     */
    void resume(@NotNull Step step) {
        List<Frame> held = this.heldFrames;

        if (held == null) {
            return;
        }

        this.step      = step;
        this.stepDepth = held.size();
        this.resumed   = true;
    }

    /**
     * Finds a frame of the held call by the id that the {@code Debugger.paused} event gave it.
     *
     * @param callFrameId the id
     * @return the frame, or {@code null} if the debugger does not hold or the id names no frame of the held call
     */
    @Nullable Frame findHeldFrame(@NotNull String callFrameId) {
        List<Frame> held = this.heldFrames;
        String prefix = this.holdCount + FRAME_ID_SEPARATOR;

        if (held == null || !callFrameId.startsWith(prefix)) {
            return null;
        }

        try {
            int index = Integer.parseInt(callFrameId.substring(prefix.length()));

            return index >= 0 && index < held.size() ? held.get(index) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Evaluates code in the scope of a frame of the held call.
     *
     * @param context    the current context
     * @param frame      the frame
     * @param expression the code
     * @return the value of the code's last statement
     * @throws RhinoException if the code does not compile or throws
     */
    @Nullable Object evaluate(@NotNull Context context, @NotNull Frame frame, @NotNull String expression) {
        Scriptable scope = Objects.requireNonNull(frame.getActivation(), "The frame has not entered.");
        boolean wasEvaluating = this.evaluating;

        this.evaluating = true;

        try {
            return context.compileString(expression, ScriptConsole.SOURCE_NAME, 1, null).exec(context, scope);
        } finally {
            this.evaluating = wasEvaluating;
        }
    }

    /**
     * Creates the id that a frame of the held call has in the {@code Debugger.paused} event.
     *
     * @param index the index of the frame, the innermost first
     * @return the id
     */
    @NotNull String createFrameId(int index) {
        return this.holdCount + FRAME_ID_SEPARATOR + index;
    }

    @Override
    public void handleCompilationDone(@NotNull Context context, @NotNull DebuggableScript script, @NotNull String source) {
    }

    @Override
    public @Nullable DebugFrame getFrame(@NotNull Context context, @NotNull DebuggableScript script) {
        return this.scriptNames.contains(script.getSourceName()) ? new Frame(script) : null;
    }

    private void onLine(@NotNull Frame frame) {
        if (this.heldFrames != null || this.evaluating) {
            return;
        }

        int depth = this.frames.size();
        boolean stepped = switch (this.step) {
            case NONE -> false;
            case INTO -> true;
            case OVER -> depth <= this.stepDepth;
            case OUT -> depth < this.stepDepth;
        };

        if (this.pauseRequested || stepped) {
            this.pauseRequested = false;
            this.hold(PAUSE_REASON, null, List.of());

            return;
        }

        if (!this.active || this.breakpoints.isEmpty()) {
            return;
        }

        List<String> hits = new ArrayList<>();

        for (Map.Entry<String, Breakpoint> entry : this.breakpoints.entrySet()) {
            Breakpoint breakpoint = entry.getValue();

            if (breakpoint.matches(frame.getScriptName(), frame.getLine()) && this.isConditionMet(frame, breakpoint.condition)) {
                hits.add(entry.getKey());
            }
        }

        if (!hits.isEmpty()) {
            this.hold(PAUSE_REASON, null, hits);
        }
    }

    private boolean isConditionMet(@NotNull Frame frame, @Nullable String condition) {
        Scriptable scope = frame.getActivation();
        Context context = Context.getCurrentContext();

        if (condition == null) {
            return true;
        }

        if (scope == null || context == null) {
            return false;
        }

        this.evaluating = true;

        try {
            return Context.toBoolean(context.evaluateString(scope, condition, CONDITION_SOURCE_NAME, 1, null));
        } catch (RhinoException exception) {
            return false;
        } finally {
            this.evaluating = false;
        }
    }

    private void onException(@NotNull Throwable exception) {
        if (!this.pauseOnExceptions || this.heldFrames != null || this.evaluating || exception == this.lastException) {
            return;
        }

        this.lastException = exception;

        Object value = exception instanceof JavaScriptException thrown ? thrown.getValue()
                                                                       : exception instanceof RhinoException error ? error.details()
                                                                                                                   : exception.toString();

        this.hold(EXCEPTION_REASON, value, List.of());
    }

    /**
     * Holds the game thread: tells the sessions where, then runs their requests until one resumes or no session debugs any more.
     */
    private void hold(@NotNull String reason, @Nullable Object data, @NotNull List<String> hits) {
        InspectedGame current = this.game;
        Context context = Context.getCurrentContext();

        if (current == null || context == null || this.heldFrames != null || !this.inspector.hasDebuggerSessions()) {
            return;
        }

        List<Frame> held = new ArrayList<>(this.frames);
        ScriptRuntime runtime = current.getRuntime();

        Collections.reverse(held);

        this.step       = Step.NONE;
        this.resumed    = false;
        this.heldFrames = held;
        this.holdCount++;

        runtime.setHeld(true);

        try {
            this.inspector.sendPaused(context, current, held, reason, data, hits);

            while (!this.resumed && this.inspector.hasDebuggerSessions()) {
                if (!this.inspector.runTasks(current)) {
                    Thread.sleep(HOLD_POLL_MILLISECONDS);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            runtime.setHeld(false);

            this.heldFrames = null;

            this.inspector.sendResumed();
        }
    }
}
