package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.LiveMapGameScenario;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Starts a live game on an installed map, runs it a few frames and runs a script with the game API on it: the script starts and runs its frames
 * inside a command dispatch of the game, after each game frame, and the map tile changes it queued apply after each of its frames, as at the
 * end of a frame start. A melee game has a human Terran and a computer Zerg with their start units;
 * a Use Map Settings game has the human alone and the map's locations.
 */
final class LiveScriptGameScenario {
    /**
     * The installed two-player map, relative to the installation.
     */
    private static final @NotNull String MAP_PATH = "Maps/(2)Bottleneck.scm";
    private static final @NotNull String PLAYER_NAME = "tester";
    private static final @NotNull String HUMAN_RACE = "terran";
    private static final @NotNull String COMPUTER_RACE = "zerg";
    private static final int HUMAN_SLOT = 0;
    private static final int GAME_SEED = 0x2F6A11;
    /**
     * The frames the game runs before the script starts, so that the start units are in place.
     */
    private static final int START_FRAMES = 8;

    private final @NotNull ReplayRuntime gameRuntime;
    private final @NotNull LiveGame live;
    private final @NotNull ScriptGameScenario script;

    /**
     * Starts a melee game and the script on it.
     *
     * @param gameData   the installed game data
     * @param mainSource the text of the main script
     * @return the started scenario
     */
    static @NotNull LiveScriptGameScenario startMelee(@NotNull GameData gameData, @NotNull String mainSource) throws IOException {
        return startMelee(gameData, mainSource, ScriptResources.EMPTY);
    }

    /**
     * Starts a melee game and the script with resources on it.
     *
     * @param gameData   the installed game data
     * @param mainSource the text of the main script
     * @param resources  the resources of the script
     * @return the started scenario
     */
    static @NotNull LiveScriptGameScenario startMelee(@NotNull GameData gameData, @NotNull String mainSource,
                                                      @NotNull ScriptResources resources) throws IOException {
        Path map = getMap();
        MeleeGameSetup setup = MeleeGameSetup.load(map, PLAYER_NAME, HUMAN_RACE, COMPUTER_RACE, HUMAN_SLOT, GameSpeed.FASTEST);

        return new LiveScriptGameScenario(gameData, map, setup, mainSource, resources);
    }

    /**
     * Starts a Use Map Settings game and the script on it.
     *
     * @param gameData   the installed game data
     * @param mainSource the text of the main script
     * @return the started scenario
     */
    static @NotNull LiveScriptGameScenario startUseMapSettings(@NotNull GameData gameData, @NotNull String mainSource) throws IOException {
        Path map = getMap();
        MeleeGameSetup setup = MeleeGameSetup.loadUseMapSettings(map, PLAYER_NAME, HUMAN_RACE, HUMAN_SLOT, GameSpeed.FASTEST);

        return new LiveScriptGameScenario(gameData, map, setup, mainSource, ScriptResources.EMPTY);
    }

    private static @NotNull Path getMap() {
        return ExternalInput.INSTALLATION.getPath().resolve(MAP_PATH);
    }


    /**
     * Creates a {@link LiveScriptGameScenario} instance: starts the game, runs its first frames, starts the script and runs its first frame, as
     * the first frame start of a game does.
     */
    private LiveScriptGameScenario(@NotNull GameData gameData, @NotNull Path map, @NotNull MeleeGameSetup setup, @NotNull String mainSource,
                                   @NotNull ScriptResources resources) throws IOException {
        this.live        = new LiveGame(setup, GAME_SEED);
        this.gameRuntime = LiveMapGameScenario.startLive(gameData, this.live, map);

        for (int frame = 0; frame < START_FRAMES; frame++) {
            LiveMapGameScenario.stepFrame(this.gameRuntime, this.live);
        }

        this.script = new ScriptGameScenario(this.gameRuntime.getGame(), mainSource, resources);

        int frame = this.getGame().getCurrentFrame();

        RunningGameScenario.runInCommandDispatch(this.gameRuntime, () -> {
            this.script.getRuntime().start();
            this.script.getRuntime().runFrame(frame);
            this.script.getRuntime().applyTerrainChanges();
        });
    }

    @NotNull Game getGame() {
        return this.gameRuntime.getGame();
    }

    /**
     * Runs one game frame, then the script's frame and the map tile changes it queued.
     */
    void stepFrame() {
        LiveMapGameScenario.stepFrame(this.gameRuntime, this.live);

        int frame = this.getGame().getCurrentFrame();

        RunningGameScenario.runInCommandDispatch(this.gameRuntime, () -> {
            this.script.getRuntime().runFrame(frame);
            this.script.getRuntime().applyTerrainChanges();
        });
    }

    /**
     * @return the script runtime
     */
    @NotNull ScriptRuntime getRuntime() {
        return this.script.getRuntime();
    }

    /**
     * Runs a mod command of a player inside a command dispatch of the game, as the command stream of a frame does.
     *
     * @param slot the slot of the issuing player
     * @param data the command data
     */
    void runModCommand(int slot, byte @NotNull [] data) {
        RunningGameScenario.runInCommandDispatch(this.gameRuntime, () -> this.script.getRuntime().queueModCommand(slot, data));
    }

    /**
     * Runs the script's local frame of the current game frame, as the client does after a game frame.
     */
    void runLocalFrame() {
        this.script.getRuntime().callLocalFrame(this.getGame().getCurrentFrame());
    }

    /**
     * @return the log lines of the script so far
     */
    @NotNull List<@NotNull String> getLines() {
        return this.script.getLines();
    }

    /**
     * @return the messages of the script's info lines so far
     */
    @NotNull List<@NotNull String> getInfoMessages() {
        return this.script.getInfoMessages();
    }
}
