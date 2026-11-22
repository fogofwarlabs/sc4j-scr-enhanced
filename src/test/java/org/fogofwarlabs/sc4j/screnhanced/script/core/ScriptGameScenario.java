package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptManifest;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptPackage;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptLogOutput;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates a script runtime with the game API of a game from the text of its main script and records its log output as lines
 * {@code LEVEL module: message}.
 */
final class ScriptGameScenario implements ScriptLogOutput {
    static final long SEED = 0x5C4E5L;

    private static final @NotNull String MAIN_NAME = "main.js";
    private static final @NotNull String MANIFEST = "{\"name\": \"Test\", \"main\": \"" + MAIN_NAME + "\", \"api\": \"1\"}";

    private final @NotNull List<String> lines;
    private final @NotNull ScriptRuntime runtime;

    /**
     * Creates a {@link ScriptGameScenario} instance without resources, with a runtime that is not started.
     */
    ScriptGameScenario(@NotNull Game game, @NotNull String mainSource) throws IOException {
        this(game, mainSource, ScriptResources.EMPTY);
    }

    /**
     * Creates a {@link ScriptGameScenario} instance with the runtime's instruction budget, with a runtime that is not started.
     */
    ScriptGameScenario(@NotNull Game game, @NotNull String mainSource, @NotNull ScriptResources resources) throws IOException {
        this(game, mainSource, resources, ScriptRuntime.INSTRUCTION_BUDGET);
    }

    /**
     * Creates a {@link ScriptGameScenario} instance with a runtime that is not started.
     */
    ScriptGameScenario(@NotNull Game game, @NotNull String mainSource, @NotNull ScriptResources resources, int instructionBudget) throws IOException {
        ScriptPackage scriptPackage = ScriptPackage.create(ScriptManifest.parse(MANIFEST.getBytes(StandardCharsets.UTF_8)),
                                                           Map.of(MAIN_NAME, mainSource.getBytes(StandardCharsets.UTF_8)), Map.of());

        this.lines   = new ArrayList<>();
        this.runtime = ScriptRuntime.create(scriptPackage, game, resources, SEED, instructionBudget, this);
    }

    @NotNull ScriptRuntime getRuntime() {
        return this.runtime;
    }

    /**
     * @return the log lines so far
     */
    @NotNull List<@NotNull String> getLines() {
        return List.copyOf(this.lines);
    }

    /**
     * @return the messages of the info lines so far, without level and module
     */
    @NotNull List<@NotNull String> getInfoMessages() {
        List<String> messages = new ArrayList<>();
        String prefix = LogLevel.INFO.getTitle() + ' ';

        for (String line : this.lines) {
            if (line.startsWith(prefix)) {
                messages.add(line.substring(line.indexOf(": ") + 2));
            }
        }

        return messages;
    }

    @Override
    public void write(@NotNull LogLevel level, @NotNull String moduleName, @NotNull String message) {
        this.lines.add(level.getTitle() + ' ' + moduleName + ": " + message);
    }
}
