package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.LiveMapGameScenario;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptManifest;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptPackage;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runs a script on a live single-player melee game of an installed map, a human Terran against a computer Zerg, or on the replay of such a
 * game, with an inspector on a free port that has attached to it. The script starts after the game's first frames, and the pump runs the
 * requests that wait inside a command dispatch of the game, as the frame start of a game does.
 */
final class InspectedGameScenario implements AutoCloseable {
    private static final @NotNull String MAP_PATH = "Maps/(2)Bottleneck.scm";
    private static final @NotNull String MANIFEST = "{\"name\": \"Test\", \"main\": \"main.js\", \"api\": \"1\"}";
    private static final @NotNull String PLAYER_NAME = "tester";
    private static final @NotNull String HUMAN_RACE = "terran";
    private static final @NotNull String COMPUTER_RACE = "zerg";
    private static final int HUMAN_SLOT = 0;
    private static final int GAME_SEED = 0x2F6A11;
    private static final long SCRIPT_SEED = 0x5C4E5L;
    private static final int START_FRAMES = 8;

    /**
     * Starts a live melee game and the script on it.
     *
     * @param gameData   the installed game data
     * @param mainSource the text of the main script
     * @return the started scenario
     */
    static @NotNull InspectedGameScenario startLive(@NotNull GameData gameData, @NotNull String mainSource) throws IOException {
        Path map = ExternalInput.INSTALLATION.getPath().resolve(MAP_PATH);
        MeleeGameSetup setup = MeleeGameSetup.load(map, PLAYER_NAME, HUMAN_RACE, COMPUTER_RACE, HUMAN_SLOT, GameSpeed.FASTEST);
        LiveGame live = new LiveGame(setup, GAME_SEED);
        ReplayRuntime gameRuntime = LiveMapGameScenario.startLive(gameData, live, map);

        for (int frame = 0; frame < START_FRAMES; frame++) {
            LiveMapGameScenario.stepFrame(gameRuntime, live);
        }

        return new InspectedGameScenario(gameRuntime, live, mainSource);
    }

    /**
     * Plays the replay of the frames that this scenario's live game played so far, and starts the script on it.
     *
     * @param gameData   the installed game data
     * @param mainSource the text of the main script
     * @return the started scenario of the replay
     */
    @NotNull InspectedGameScenario startReplay(@NotNull GameData gameData, @NotNull String mainSource) throws IOException {
        Assertions.assertNotNull(this.live);

        ReplayRuntime playback = RunningGameScenario.createRuntime(ReplayFile.parse(this.live.writeReplay(this.getGame().getCurrentFrame(), 0)),
                                                                   gameData);
        Game game = playback.getGame();

        playback.start(0, 0);

        while (game.getCurrentFrame() < START_FRAMES) {
            Assertions.assertTrue(playback.step(0, game.getCurrentFrame() * playback.getFrameDuration()));
        }

        return new InspectedGameScenario(playback, null, mainSource);
    }


    private final @NotNull ReplayRuntime gameRuntime;
    private final @Nullable LiveGame live;
    private final @NotNull ScriptInspector inspector;
    private final @NotNull ScriptRuntime runtime;
    private final int port;

    private InspectedGameScenario(@NotNull ReplayRuntime gameRuntime, @Nullable LiveGame live, @NotNull String mainSource) throws IOException {
        ScriptPackage scriptPackage = ScriptPackage.create(ScriptManifest.parse(MANIFEST.getBytes(StandardCharsets.UTF_8)),
                                                           Map.of("main.js", mainSource.getBytes(StandardCharsets.UTF_8)), Map.of());

        this.gameRuntime = gameRuntime;
        this.live        = live;
        this.inspector   = new ScriptInspector();
        this.runtime     = ScriptRuntime.create(scriptPackage, this.getGame(), ScriptResources.EMPTY, SCRIPT_SEED, ScriptRuntime.INSTRUCTION_BUDGET,
                                                this.inspector.createLogOutput(this.getGame()));
        this.port        = this.inspector.start(0);

        int frame = this.getGame().getCurrentFrame();

        RunningGameScenario.runInCommandDispatch(this.gameRuntime, () -> {
            this.runtime.start();
            this.runtime.runFrame(frame);
        });
        this.pump();
    }

    @NotNull Game getGame() {
        return this.gameRuntime.getGame();
    }

    /**
     * Runs the game thread's part of a frame start inside a command dispatch: attaches the inspector and runs the requests that wait.
     */
    void pump() {
        RunningGameScenario.runInCommandDispatch(this.gameRuntime, () -> this.inspector.onFrameStarted(this.getGame(), this.runtime));
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
