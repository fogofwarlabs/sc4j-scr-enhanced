package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.LiveMapGameScenario;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Packs a main script into an installed two-player map, starts a live melee game of a human Terran and a computer Zerg on the packed map and
 * advances it frame by frame; the game's {@link ScriptSimulationPlugin} runs the script. After each frame the scenario collects the texts
 * the script printed that start with {@value #PRINT_PREFIX}, once each.
 */
final class ScriptMapGameScenario {
    /**
     * The prefix of the printed texts the scenario collects.
     */
    static final @NotNull String PRINT_PREFIX = "ES ";

    /**
     * The installed two-player map, relative to the installation.
     */
    private static final @NotNull String MAP_PATH = "Maps/(2)Bottleneck.scm";
    private static final @NotNull String PACKED_MAP_NAME = "script-bottleneck.scm";
    private static final @NotNull String MAIN_NAME = "main.js";
    private static final @NotNull String MANIFEST = "{\"name\": \"Test\", \"main\": \"" + MAIN_NAME + "\", \"api\": \"1\"}";
    private static final @NotNull String PLAYER_NAME = "tester";
    private static final @NotNull String HUMAN_RACE = "terran";
    private static final @NotNull String COMPUTER_RACE = "zerg";
    private static final int HUMAN_SLOT = 0;
    private static final int GAME_SEED = 0x2F6A11;

    /**
     * Starts a melee game on the installed map with a packed main script, or on the installed map itself.
     *
     * @param gameData   the installed game data
     * @param folder     the folder of the packed map
     * @param mainSource the text of the main script, or {@code null} for the installed map without a script
     * @return the started scenario
     */
    static @NotNull ScriptMapGameScenario startMelee(@NotNull GameData gameData, @NotNull Path folder, @Nullable String mainSource) throws IOException {
        return startMelee(gameData, folder, mainSource, Map.of());
    }

    /**
     * Starts a melee game on the installed map with a packed main script and resources, or on the installed map itself.
     *
     * @param gameData   the installed game data
     * @param folder     the folder of the packed map
     * @param mainSource the text of the main script, or {@code null} for the installed map without a script
     * @param resources  the resource contents by name
     * @return the started scenario
     */
    static @NotNull ScriptMapGameScenario startMelee(@NotNull GameData gameData, @NotNull Path folder, @Nullable String mainSource,
                                                     @NotNull Map<String, byte[]> resources) throws IOException {
        return startMelee(gameData, folder, mainSource, resources, false);
    }

    /**
     * Starts a melee game on the installed map with a packed main script and resources, protected or not, or on the installed map itself.
     *
     * @param gameData     the installed game data
     * @param folder       the folder of the packed map, created when missing
     * @param mainSource   the text of the main script, or {@code null} for the installed map without a script
     * @param resources    the resource contents by name
     * @param protectedMap whether the packed map is protected, with the main script in protected form
     * @return the started scenario
     */
    static @NotNull ScriptMapGameScenario startMelee(@NotNull GameData gameData, @NotNull Path folder, @Nullable String mainSource,
                                                     @NotNull Map<String, byte[]> resources, boolean protectedMap) throws IOException {
        Path map = mainSource == null ? ExternalInput.INSTALLATION.getPath().resolve(MAP_PATH) : packMap(folder, mainSource, resources, protectedMap);

        return new ScriptMapGameScenario(gameData, map);
    }

    /**
     * Packs a main script and resources into the installed two-player map.
     *
     * @param folder       the folder of the packed map, created when missing
     * @param mainSource   the text of the main script
     * @param resources    the resource contents by name
     * @param protectedMap whether the packed map is protected, with the main script in protected form
     * @return the packed map
     */
    static @NotNull Path packMap(@NotNull Path folder, @NotNull String mainSource, @NotNull Map<String, byte[]> resources,
                                 boolean protectedMap) throws IOException {
        Path map = ExternalInput.INSTALLATION.getPath().resolve(MAP_PATH);
        ScriptPackage scriptPackage = ScriptPackage.create(ScriptManifest.parse(MANIFEST.getBytes(StandardCharsets.UTF_8)),
                                                           Map.of(MAIN_NAME, mainSource.getBytes(StandardCharsets.UTF_8)), resources);
        ScriptPackage packedPackage = protectedMap ? ScriptProtector.protectPackage(scriptPackage, new ScriptSourceMap()) : scriptPackage;
        byte[] packed = ScriptStorageUtils.writePackage(Files.readAllBytes(map), MAP_PATH, packedPackage, protectedMap);
        Path packedMap = folder.resolve(PACKED_MAP_NAME);

        Files.createDirectories(folder);
        Files.write(packedMap, packed);

        return packedMap;
    }

    /**
     * Finds the script plugin of a game.
     *
     * @param game the game
     * @return the game's script plugin, or {@code null} if the game has none
     */
    static @Nullable ScriptSimulationPlugin findPlugin(@NotNull Game game) {
        ScrSimulationPlugins plugins = game.getSimulationPlugins();

        if (plugins == null) {
            return null;
        }

        for (ScrSimulationPlugin plugin : plugins.getPlugins()) {
            if (plugin instanceof ScriptSimulationPlugin scriptPlugin) {
                return scriptPlugin;
            }
        }

        return null;
    }

    /**
     * Collects the shown texts of a game that start with {@value #PRINT_PREFIX}, without the prefix, once each.
     *
     * @param game   the game
     * @param prints the collected texts, to which the new ones are added
     */
    static void collectPrints(@NotNull Game game, @NotNull Set<String> prints) {
        MessageLines lines = game.getInterfaceLink().getMessageLines();

        for (int line = 0; line < MessageLines.LINE_COUNT; line++) {
            String text = new String(lines.getLine(line), StandardCharsets.UTF_8);
            int start = text.indexOf(PRINT_PREFIX);

            if (start >= 0) {
                prints.add(text.substring(start + PRINT_PREFIX.length()));
            }
        }
    }


    private final @NotNull LiveGame live;
    private final @NotNull ReplayRuntime gameRuntime;
    private final @NotNull Set<String> prints;

    /**
     * Creates a {@link ScriptMapGameScenario} instance: starts the game on the map.
     */
    private ScriptMapGameScenario(@NotNull GameData gameData, @NotNull Path map) throws IOException {
        MeleeGameSetup setup = MeleeGameSetup.load(map, PLAYER_NAME, HUMAN_RACE, COMPUTER_RACE, HUMAN_SLOT, GameSpeed.FASTEST);

        this.live        = new LiveGame(setup, GAME_SEED);
        this.gameRuntime = LiveMapGameScenario.startLive(gameData, this.live, map);
        this.prints      = new LinkedHashSet<>();
    }

    @NotNull Game getGame() {
        return this.gameRuntime.getGame();
    }


    /**
     * Queues a command of the human player into the outgoing turn, which runs on a later frame.
     *
     * @param typeId  the command type byte
     * @param payload the command bytes after the type byte
     * @return {@code true} if queued, {@code false} otherwise
     */
    boolean submit(int typeId, byte @NotNull [] payload) {
        return this.live.submit(typeId, payload);
    }

    /**
     * Writes the replay of the frames played so far.
     *
     * @return the replay file bytes
     */
    byte @NotNull [] writeReplay() {
        return this.live.writeReplay(this.getGame().getCurrentFrame(), 0);
    }

    /**
     * @return the collected printed texts without the prefix, in the order they were first seen
     */
    @NotNull List<@NotNull String> getPrints() {
        return new ArrayList<>(this.prints);
    }

    /**
     * Runs game frames.
     *
     * @param count the number of frames
     */
    void stepFrames(int count) {
        for (int frame = 0; frame < count; frame++) {
            this.stepFrame();
        }
    }

    /**
     * Runs game frames until a collected text starts with a beginning, at most a number of frames.
     *
     * @param beginning     the beginning of the text, after the prefix
     * @param maximumFrames the largest number of frames
     */
    void stepUntilPrinted(@NotNull String beginning, int maximumFrames) {
        for (int frame = 0; frame < maximumFrames && this.prints.stream().noneMatch(text -> text.startsWith(beginning)); frame++) {
            this.stepFrame();
        }
    }

    /**
     * Runs one game frame and collects the printed texts.
     */
    private void stepFrame() {
        LiveMapGameScenario.stepFrame(this.gameRuntime, this.live);
        collectPrints(this.getGame(), this.prints);
    }
}
