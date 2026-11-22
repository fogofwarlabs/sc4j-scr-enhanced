package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptManifest;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptPackage;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates script runtimes of the runtime tests from script texts, on a game without game data, and records their log output as lines
 * {@code LEVEL module: message}.
 */
final class ScriptRuntimeScenario implements ScriptLogOutput {
    static final @NotNull String MAIN_NAME = "main.js";
    static final long SEED = 0x5C4E5L;

    private static final @NotNull String MANIFEST = "{\"name\": \"Test\", \"main\": \"" + MAIN_NAME + "\", \"api\": \"1\"}";
    private static final int MAP_TILE_SIZE = 4;

    /**
     * Creates a game without game data.
     */
    static @NotNull Game createGame() {
        return Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, 0, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]), new LcgRandom(0));
    }

    /**
     * Creates a script package.
     *
     * @param namesAndSources the script names and texts, alternating; the main script is {@value #MAIN_NAME}
     */
    static @NotNull ScriptPackage createPackage(@NotNull String @NotNull ... namesAndSources) throws IOException {
        Map<String, byte[]> scripts = new LinkedHashMap<>();

        for (int index = 0; index < namesAndSources.length; index += 2) {
            scripts.put(namesAndSources[index], namesAndSources[index + 1].getBytes(StandardCharsets.UTF_8));
        }

        return ScriptPackage.create(ScriptManifest.parse(MANIFEST.getBytes(StandardCharsets.UTF_8)), scripts, Map.of());
    }

    /**
     * Creates a scenario whose main script is the given text, with {@link #SEED} and the default budget, and starts it.
     */
    static @NotNull ScriptRuntimeScenario start(@NotNull String mainSource) throws IOException {
        ScriptRuntimeScenario scenario = new ScriptRuntimeScenario(createPackage(MAIN_NAME, mainSource), SEED, ScriptRuntime.INSTRUCTION_BUDGET);

        scenario.getRuntime().start();

        return scenario;
    }

    /**
     * Runs a package with a seed for a number of frames and returns the log lines.
     */
    static @NotNull List<@NotNull String> run(@NotNull ScriptPackage scriptPackage, long seed, int frameCount) {
        ScriptRuntimeScenario scenario = new ScriptRuntimeScenario(scriptPackage, seed, ScriptRuntime.INSTRUCTION_BUDGET);

        scenario.getRuntime().start();

        for (int frame = 0; frame < frameCount; frame++) {
            scenario.getRuntime().runFrame(frame);
        }

        scenario.getRuntime().dispose();

        return scenario.getLines();
    }


    private final @NotNull List<String> lines;
    private final @NotNull ScriptRuntime runtime;

    /**
     * Creates a {@link ScriptRuntimeScenario} instance on a new game without game data, with a runtime that is not started.
     */
    ScriptRuntimeScenario(@NotNull ScriptPackage scriptPackage, long seed, int instructionBudget) {
        this(scriptPackage, createGame(), seed, instructionBudget);
    }

    /**
     * Creates a {@link ScriptRuntimeScenario} instance on a game, with a runtime that is not started.
     */
    ScriptRuntimeScenario(@NotNull ScriptPackage scriptPackage, @NotNull Game game, long seed, int instructionBudget) {
        this.lines   = new ArrayList<>();
        this.runtime = ScriptRuntime.create(scriptPackage, game, ScriptResources.EMPTY, seed, instructionBudget, this);
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
