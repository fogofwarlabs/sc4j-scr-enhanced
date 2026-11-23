package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptManifest;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptPackage;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs a script on a game without game data, a game of no player, with an inspector on a free port whose log output the runtime writes to;
 * the runtime has started and run its first frame, and the inspector has attached to it. The pump runs the requests that wait, as the frame
 * start of a game does.
 */
final class InspectedRuntimeScenario implements AutoCloseable {
    static final @NotNull String MAIN_NAME = "main.js";

    private static final @NotNull String MANIFEST = "{\"name\": \"Test\", \"main\": \"" + MAIN_NAME + "\", \"api\": \"1\"}";
    private static final int MAP_TILE_SIZE = 4;
    private static final long SEED = 0x5C4E5L;

    /**
     * Starts a script of a main script and other scripts with the default instruction budget.
     *
     * @param namesAndSources the main script's text, then other script names and texts, alternating
     * @return the started scenario
     */
    static @NotNull InspectedRuntimeScenario start(@NotNull String @NotNull ... namesAndSources) throws IOException {
        return start(ScriptRuntime.INSTRUCTION_BUDGET, namesAndSources);
    }

    /**
     * Starts a script of a main script and other scripts.
     *
     * @param instructionBudget the instruction budget of one call
     * @param namesAndSources   the main script's text, then other script names and texts, alternating
     * @return the started scenario
     */
    static @NotNull InspectedRuntimeScenario start(int instructionBudget, @NotNull String @NotNull ... namesAndSources) throws IOException {
        Map<String, byte[]> scripts = new LinkedHashMap<>();

        scripts.put(MAIN_NAME, namesAndSources[0].getBytes(StandardCharsets.UTF_8));

        for (int index = 1; index < namesAndSources.length; index += 2) {
            scripts.put(namesAndSources[index], namesAndSources[index + 1].getBytes(StandardCharsets.UTF_8));
        }

        ScriptPackage scriptPackage = ScriptPackage.create(ScriptManifest.parse(MANIFEST.getBytes(StandardCharsets.UTF_8)), scripts, Map.of());

        return new InspectedRuntimeScenario(scriptPackage, instructionBudget);
    }


    private final @NotNull Game game;
    private final @NotNull ScriptInspector inspector;
    private final @NotNull ScriptRuntime runtime;
    private final int port;

    private InspectedRuntimeScenario(@NotNull ScriptPackage scriptPackage, int instructionBudget) throws IOException {
        this.game      = Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, 0, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]), new LcgRandom(0));
        this.inspector = new ScriptInspector();
        this.runtime   = ScriptRuntime.create(scriptPackage, this.game, ScriptResources.EMPTY, SEED, instructionBudget,
                                              this.inspector.createLogOutput(this.game));
        this.port      = this.inspector.start(0);

        this.runtime.start();
        this.runtime.runFrame(0);
        this.pump();
    }

    @NotNull Game getGame() {
        return this.game;
    }

    @NotNull ScriptInspector getInspector() {
        return this.inspector;
    }

    @NotNull ScriptRuntime getRuntime() {
        return this.runtime;
    }

    int getPort() {
        return this.port;
    }

    /**
     * Runs the game thread's part of a frame start: attaches the inspector and runs the requests that wait.
     */
    void pump() {
        this.inspector.onFrameStarted(this.game, this.runtime);
    }

    /**
     * Connects a DevTools client whose waits run the pump.
     *
     * @return the connected client
     */
    @NotNull DevToolsClientScenario connect() throws IOException, InterruptedException {
        return DevToolsClientScenario.connect(this.port, this::pump);
    }

    @Override
    public void close() {
        this.inspector.stop();
        this.runtime.dispose();
    }
}
