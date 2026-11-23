package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;
import org.mozilla.javascript.debug.DebuggableScript;

import java.io.Serial;
import java.util.List;

/**
 * Runs console code on a script runtime: code that a developer console sends to the running game, to read the script and the game and, as
 * shared code, to change them.
 * <p>
 * The console has its own top-level scope, whose prototype is the top-level scope of the main script: the main script's top-level names,
 * {@code require}, {@code log}, {@code defineHooks} and the globals resolve in it, and the names that console code declares stay in it. The
 * scope also has {@value #LAST_RESULT_NAME}, the last result ({@link #setLastResult}), {@value #MODULES_NAME}, the top-level scopes of the
 * loaded modules by script name, and {@value #GLOBAL_THIS_NAME}, the console's scope itself, whose prototype chain holds every name that
 * console code reaches. An assignment to a name that the main script's scope or the global scope has changes that name in shared code
 * and throws a {@code TypeError} in local code.
 * <p>
 * {@link #run} runs console code within one instruction budget, as shared code or as local code under the rules of the local hooks: the game
 * read-only and local, no random number, no new module and the shared-only functions refused. Every method runs on the caller's thread, the
 * thread of the runtime's other calls.
 */
public final class ScriptConsole {
    /**
     * The source name of console code, in the locations of its errors.
     */
    public static final @NotNull String SOURCE_NAME = "console";
    /**
     * The name of the last result in the console's scope.
     */
    public static final @NotNull String LAST_RESULT_NAME = "$_";
    /**
     * The name of the object of the loaded modules' top-level scopes in the console's scope.
     */
    public static final @NotNull String MODULES_NAME = "modules";
    /**
     * The name of the global object, which in console code names the console's scope.
     */
    public static final @NotNull String GLOBAL_THIS_NAME = "globalThis";

    private static final @NotNull String FUNCTION_PREFIX = "(";
    private static final @NotNull String FUNCTION_SUFFIX = "\n)";

    /**
     * A call of console code.
     *
     * @param <T> the type of the call's result
     */
    @FunctionalInterface
    public interface Call<T> {
        /**
         * Runs the call.
         *
         * @param context the current context
         * @return the result
         */
        T run(@NotNull Context context);
    }

    /**
     * The console's top-level scope: an assignment to a name that only the scopes of its prototype chain have, the main script's and the global
     * one, goes to the scope that has it in shared code and throws in local code, instead of adding the name to the console's scope.
     */
    private static final class ConsoleScope extends TopLevel {
        @Serial
        private static final long serialVersionUID = 1L;

        private boolean local;

        private ConsoleScope() {
            this.local = false;
        }

        @Override
        public void put(@NotNull String name, @NotNull Scriptable start, @Nullable Object value) {
            if (start == this && !super.has(name, this)) {
                for (Scriptable owner = this.getPrototype(); owner instanceof TopLevel; owner = owner.getPrototype()) {
                    if (!owner.has(name, owner)) {
                        continue;
                    }

                    if (this.local) {
                        throw org.mozilla.javascript.ScriptRuntime.typeError(Exceptions.format("Local code cannot change the variable {0} of the script.",
                                                                                               name));
                    }

                    owner.put(name, owner, value);

                    return;
                }
            }

            super.put(name, start, value);
        }

        /**
         * Defines a name of the console's own, not enumerable, whatever the prototypes have.
         */
        private void define(@NotNull String name, @Nullable Object value) {
            super.put(name, this, value);
            this.setAttributes(name, ScriptableObject.DONTENUM);
        }
    }


    /**
     * Tells whether a value is a function of the prelude, the upper layer of the game API.
     *
     * @param value the value
     * @return {@code true} if the value is a function that a file of the prelude defines, {@code false} otherwise
     */
    public static boolean isPreludeFunction(@Nullable Object value) {
        if (!(value instanceof NativeFunction function)) {
            return false;
        }

        DebuggableScript script = function.getDebuggableView();
        String sourceName = script == null ? null : script.getSourceName();

        return sourceName != null && ScriptPrelude.isPreludeSource(sourceName);
    }


    private final @NotNull ScriptRuntime runtime;
    private final @NotNull TopLevel global;
    private final @NotNull ScriptModuleLoader moduleLoader;

    private final @NotNull ConsoleScope scope;
    private int modulesLoadCount;

    /**
     * Creates a {@link ScriptConsole} instance with a new scope.
     *
     * @param context      the current context
     * @param runtime      the runtime the console code runs on
     * @param global       the global scope
     * @param moduleLoader the module loader of the runtime
     * @param mainScope    the top-level scope of the main script, or the global scope if the main script did not load
     */
    ScriptConsole(@NotNull Context context, @NotNull ScriptRuntime runtime, @NotNull TopLevel global, @NotNull ScriptModuleLoader moduleLoader,
                  @NotNull Scriptable mainScope) {
        this.runtime      = runtime;
        this.global       = global;
        this.moduleLoader = moduleLoader;

        this.scope            = new ConsoleScope();
        this.modulesLoadCount = -1;

        this.scope.setPrototype(mainScope);
        this.scope.setParentScope(null);
        this.scope.cacheBuiltins(global, false);
        this.scope.define(LAST_RESULT_NAME, Undefined.instance);
        this.scope.define(GLOBAL_THIS_NAME, this.scope);
        this.updateModules(context);
    }

    /**
     * @return the console's top-level scope, also the {@code this} of console code
     */
    public @NotNull Scriptable getScope() {
        return this.scope;
    }

    /**
     * Sets the last result, {@value #LAST_RESULT_NAME} in the console's scope.
     *
     * @param value the result
     */
    public void setLastResult(@Nullable Object value) {
        this.scope.define(LAST_RESULT_NAME, value);
    }

    /**
     * Runs console code within one instruction budget of the runtime, as shared or local code; the promise jobs it queued run after it, within
     * the same budget.
     *
     * @param local {@code true} to run local code, {@code false} to run shared code
     * @param call  the call
     * @param <T>   the type of the call's result
     * @return the result of the call
     * @throws RhinoException        if the console code threw a script error, ran more than the budget or overflowed the stack
     * @throws IllegalStateException if the runtime has not started, is disposed or runs another call
     */
    public <T> T run(boolean local, @NotNull Call<T> call) {
        return this.runtime.callConsole(local, context -> {
            this.scope.local = local;
            this.updateModules(context);

            return call.run(context);
        });
    }

    /**
     * Evaluates console code in the console's scope; call it inside {@link #run}.
     *
     * @param context the current context
     * @param source  the code
     * @return the value of the code's last statement
     * @throws RhinoException if the code does not compile or throws
     */
    public @Nullable Object evaluate(@NotNull Context context, @NotNull String source) {
        return context.compileString(source, SOURCE_NAME, 1, null).exec(context, this.scope);
    }

    /**
     * Compiles a function expression in the console's scope; call it inside {@link #run}.
     *
     * @param context     the current context
     * @param declaration the text of the function expression
     * @return the function
     * @throws RhinoException if the text does not compile, throws, or is no function
     */
    public @NotNull Function compileFunction(@NotNull Context context, @NotNull String declaration) {
        Object value = this.evaluate(context, FUNCTION_PREFIX + declaration + FUNCTION_SUFFIX);

        if (!(value instanceof Function function)) {
            throw org.mozilla.javascript.ScriptRuntime.typeError("The function declaration is no function.");
        }

        return function;
    }

    /**
     * Makes {@value #MODULES_NAME} of the console's scope list the loaded modules, when a module has loaded since the last time.
     */
    private void updateModules(@NotNull Context context) {
        if (this.modulesLoadCount == this.moduleLoader.getLoadCount()) {
            return;
        }

        Scriptable modules = context.newObject(this.global);
        List<ScriptModule> loaded = this.moduleLoader.getLoadedModules();

        for (ScriptModule module : loaded) {
            ScriptableObject.defineProperty(modules, module.getName(), module.getScope(), ScriptableObject.READONLY);
        }

        this.scope.define(MODULES_NAME, modules);
        this.modulesLoadCount = this.moduleLoader.getLoadCount();
    }
}
