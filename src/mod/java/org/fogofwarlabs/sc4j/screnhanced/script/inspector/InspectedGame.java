package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptCompiledMapUtils;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptConsole;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Represents the game that the inspector is attached to: its script runtime, the console on it and the execution contexts that DevTools
 * evaluates in. A single-player game has a shared context, the default one, and a local context; any other game has the local context only.
 */
final class InspectedGame {
    private static final @NotNull String SHARED_NAME = "Shared code";
    private static final @NotNull String LOCAL_NAME = "Local code";
    private static final @NotNull String SCRIPT_ID_SEPARATOR = "/";

    /**
     * An execution context of DevTools: console code runs in it as shared or as local code.
     */
    static final class ExecutionContext {
        private final int id;
        private final @NotNull String uniqueId;
        private final boolean local;
        private final boolean defaultContext;

        private ExecutionContext(int id, @NotNull String uniqueId, boolean local, boolean defaultContext) {
            this.id             = id;
            this.uniqueId       = uniqueId;
            this.local          = local;
            this.defaultContext = defaultContext;
        }

        /**
         * @return the id of the context
         */
        int getId() {
            return this.id;
        }

        /**
         * @return the id of the context that is unique among the inspector's games
         */
        @NotNull String getUniqueId() {
            return this.uniqueId;
        }

        /**
         * @return {@code true} if console code runs as local code in the context, {@code false} if it runs as shared code
         */
        boolean isLocal() {
            return this.local;
        }

        /**
         * Creates the {@code ExecutionContextDescription} of the Chrome DevTools Protocol.
         *
         * @return the description
         */
        @NotNull Map<String, Object> createDescription() {
            Map<String, Object> description = new LinkedHashMap<>();
            Map<String, Object> auxiliaryData = new LinkedHashMap<>();

            auxiliaryData.put("isDefault", this.defaultContext);
            auxiliaryData.put("type", "default");

            description.put("id", this.id);
            description.put("origin", "");
            description.put("name", this.local ? LOCAL_NAME : SHARED_NAME);
            description.put("uniqueId", this.uniqueId);
            description.put("auxData", auxiliaryData);

            return description;
        }
    }


    private final @NotNull Game game;
    private final @NotNull ScriptRuntime runtime;
    private final @NotNull ScriptConsole console;
    private final @NotNull List<ExecutionContext> contexts;

    private boolean announced;

    /**
     * Creates an {@link InspectedGame} instance with its execution contexts.
     *
     * @param game           the game
     * @param runtime        the script runtime of the game
     * @param console        the console on the runtime
     * @param shared         whether the game is a single-player game, whose console runs shared code by default
     * @param firstContextId the id of the first execution context, the next ones following it
     * @param targetId       the id of the inspector's target, which the unique ids of the contexts start with
     */
    InspectedGame(@NotNull Game game, @NotNull ScriptRuntime runtime, @NotNull ScriptConsole console, boolean shared, int firstContextId,
                  @NotNull String targetId) {
        this.game     = game;
        this.runtime  = runtime;
        this.console  = console;
        this.contexts = shared ? List.of(new ExecutionContext(firstContextId, targetId + "." + firstContextId, false, true),
                                         new ExecutionContext(firstContextId + 1, targetId + "." + (firstContextId + 1), true, false))
                               : List.of(new ExecutionContext(firstContextId, targetId + "." + firstContextId, true, true));

        this.announced = false;
    }

    /**
     * @return the game
     */
    @NotNull Game getGame() {
        return this.game;
    }

    /**
     * @return the script runtime of the game
     */
    @NotNull ScriptRuntime getRuntime() {
        return this.runtime;
    }

    /**
     * @return the console on the runtime
     */
    @NotNull ScriptConsole getConsole() {
        return this.console;
    }

    /**
     * @return the execution contexts, the default one first
     */
    @NotNull List<ExecutionContext> getContexts() {
        return this.contexts;
    }

    /**
     * @return {@code true} if the game has a context of shared code, {@code false} otherwise
     */
    boolean isShared() {
        return !this.contexts.getFirst().isLocal();
    }

    /**
     * @return {@code true} if the game's message area showed the console's address, {@code false} otherwise
     */
    boolean isAnnounced() {
        return this.announced;
    }

    /**
     * Records that the game's message area showed the console's address.
     */
    void setAnnounced() {
        this.announced = true;
    }

    /**
     * Creates the parameters of the {@code Debugger.scriptParsed} event of each script of the script package, in name order. A script's id is
     * the unique id of the default context and the script name; its URL is the script name, as in the stack traces. A script that holds a
     * source map ({@link ScriptCompiledMapUtils}) has its URL as the {@code sourceMapURL}, so that DevTools shows the sources.
     *
     * @return the parameters
     */
    @NotNull List<Map<String, Object>> createScriptDescriptions() {
        List<Map<String, Object>> descriptions = new ArrayList<>();
        ExecutionContext defaultContext = this.contexts.getFirst();

        for (Map.Entry<String, byte[]> script : new TreeMap<>(this.runtime.getScripts()).entrySet()) {
            String source = new String(script.getValue(), StandardCharsets.UTF_8);
            String sourceMapUrl = ScriptCompiledMapUtils.findReference(source);
            Map<String, Object> description = new LinkedHashMap<>();

            description.put("scriptId", this.getScriptId(script.getKey()));
            description.put("url", script.getKey());
            description.put("startLine", 0);
            description.put("startColumn", 0);
            description.put("endLine", (int) source.chars().filter(character -> character == '\n').count());
            description.put("endColumn", source.length() - (source.lastIndexOf('\n') + 1));
            description.put("executionContextId", defaultContext.id);
            description.put("hash", Integer.toHexString(source.hashCode()));
            description.put("isModule", false);
            description.put("length", source.length());
            description.put("scriptLanguage", "JavaScript");

            if (sourceMapUrl != null) {
                description.put("sourceMapURL", sourceMapUrl);
            }

            descriptions.add(description);
        }

        return descriptions;
    }

    /**
     * Creates the id of a script in the {@code Debugger.scriptParsed} event: the unique id of the default context and the script name.
     *
     * @param scriptName the script name
     * @return the id
     */
    @NotNull String getScriptId(@NotNull String scriptName) {
        return this.contexts.getFirst().uniqueId + SCRIPT_ID_SEPARATOR + scriptName;
    }

    /**
     * Finds the source of a script by the id of its {@code Debugger.scriptParsed} event.
     *
     * @param scriptId the script id
     * @return the source, or {@code null} if the id names no script of the game
     */
    @Nullable String findScriptSource(@NotNull String scriptId) {
        String prefix = this.contexts.getFirst().uniqueId + SCRIPT_ID_SEPARATOR;

        if (!scriptId.startsWith(prefix)) {
            return null;
        }

        byte[] source = this.runtime.getScripts().get(scriptId.substring(prefix.length()));

        return source == null ? null : new String(source, StandardCharsets.UTF_8);
    }

    /**
     * Finds an execution context by its id or unique id.
     *
     * @param contextId the id, or {@code -1} for none
     * @param uniqueId  the unique id, or {@code null} for none
     * @return the context with the unique id, else with the id, else the default context when neither is given; {@code null} if the given id
     * names no context of the game
     */
    @Nullable ExecutionContext findContext(int contextId, @Nullable String uniqueId) {
        if (uniqueId == null && contextId < 0) {
            return this.contexts.getFirst();
        }

        for (ExecutionContext context : this.contexts) {
            if (uniqueId != null ? context.uniqueId.equals(uniqueId) : context.id == contextId) {
                return context;
            }
        }

        return null;
    }
}
