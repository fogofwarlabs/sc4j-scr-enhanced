package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageFileScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tests which games {@link ScriptSimulationExtension} gives a script plugin and the resources it gets: a live game reads them from its map file
 * and stores its script with resource references in its replay, and the replay played back reads the resources from the map file of the install's
 * map folders, or fails without it; the replay of a protected map holds the protected scripts and the protected mark.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptSimulationExtensionTest {
    /**
     * The script: at its start it replaces the image {@value #REPLACED_IMAGE_TYPE} with a GRP of the resources and prints whether it has the
     * GRP.
     */
    private static final @NotNull String SCRIPT = """
                                                  function onStart() {
                                                      display.images.replace(ImageType.get(239), {sd: game.map.resources.getGrp('units/marine.grp')});
                                                      game.print('ES started ' + game.map.resources.has('units/marine.grp'));
                                                  }
                                                  """;
    private static final @NotNull String GRP_NAME = "units/marine.grp";
    private static final int REPLACED_IMAGE_TYPE = 239;
    private static final int PLAYED_FRAMES = 8;

    /**
     * Creates a replay runtime with an SC4J install named for the map lookup.
     */
    private static @NotNull ReplayRuntime createPlayback(@NotNull ReplayFile replay, @NotNull InstallationTestResources resources,
                                                         @NotNull Path install) throws IOException {
        String previous = System.getProperty(ScriptRunner.INSTALL_PROPERTY);

        System.setProperty(ScriptRunner.INSTALL_PROPERTY, install.toString());

        try {
            return RunningGameScenario.createRuntime(replay, resources.getGameData());
        } finally {
            if (previous == null) {
                System.clearProperty(ScriptRunner.INSTALL_PROPERTY);
            } else {
                System.setProperty(ScriptRunner.INSTALL_PROPERTY, previous);
            }
        }
    }

    @Test
    void a_replay_of_a_live_script_game_reads_the_resources_from_the_map_file(@NotNull InstallationTestResources resources,
                                                                              @TempDir @NotNull Path folder) throws IOException {
        Path install = folder.resolve("install");
        Path mapFolder = ScriptRunner.getMapFolders(install).getFirst();

        Files.createDirectories(mapFolder);

        Map<String, byte[]> files = ScriptGraphicsTest.createResources();
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), mapFolder, SCRIPT, files);

        scenario.stepFrames(PLAYED_FRAMES);

        ScriptSimulationPlugin livePlugin = ScriptMapGameScenario.findPlugin(scenario.getGame());
        byte[] section = scenario.getGame().getReplaySections().get(ScriptStorageUtils.REPLAY_SECTION);

        Assertions.assertEquals(List.of("started true"), scenario.getPrints());
        Assertions.assertNotNull(livePlugin);
        Assertions.assertNotNull(livePlugin.getImageReplacements().findImageGraphics(REPLACED_IMAGE_TYPE));
        Assertions.assertNotNull(section, "the live game stores its script");

        ReplayFile replay = ReplayFile.parse(scenario.writeReplay());
        ScriptTrailer recorded = ScriptStorageUtils.readReplaySection(section);
        ScriptResources.Entry grp = recorded.getResources().findEntry(GRP_NAME);

        Assertions.assertArrayEquals(section, replay.readModSection(ScriptStorageUtils.REPLAY_SECTION));
        Assertions.assertTrue(recorded.getScripts().getScripts().containsKey("main.js"));
        Assertions.assertNotNull(grp);
        Assertions.assertEquals(0, grp.getStoredSize(), "the replay holds the resource as a reference");
        Assertions.assertEquals(ImageFileScenario.createGrp(0).length, grp.getOriginalSize());

        ReplayRuntime playback = createPlayback(replay, resources, install);
        Game game = playback.getGame();
        ScriptSimulationPlugin plugin = ScriptMapGameScenario.findPlugin(game);

        Assertions.assertNotNull(plugin, "the replay holds the script");

        playback.start(0, 0);

        while (game.getCurrentFrame() < PLAYED_FRAMES) {
            Assertions.assertTrue(playback.step(0, game.getCurrentFrame() * playback.getFrameDuration()));
        }

        Assertions.assertFalse(plugin.getRuntime().isStopped());
        Assertions.assertNotNull(plugin.getImageReplacements().findImageGraphics(REPLACED_IMAGE_TYPE), "the replay's script reads the GRP");

        IllegalStateException refusal = Assertions.assertThrows(IllegalStateException.class,
                                                                () -> createPlayback(replay, resources, folder.resolve("other-install")));

        Assertions.assertTrue(refusal.getMessage().contains("no map in"), refusal.getMessage());
    }

    @Test
    void a_replay_of_a_protected_map_carries_the_protected_scripts(@NotNull InstallationTestResources resources,
                                                                   @TempDir @NotNull Path folder) throws IOException {
        Path install = folder.resolve("install");
        Path mapFolder = ScriptRunner.getMapFolders(install).getFirst();
        Map<String, byte[]> files = ScriptGraphicsTest.createResources();
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), mapFolder, SCRIPT, files, true);

        scenario.stepFrames(PLAYED_FRAMES);

        byte[] section = scenario.getGame().getReplaySections().get(ScriptStorageUtils.REPLAY_SECTION);

        Assertions.assertEquals(List.of("started true"), scenario.getPrints());
        Assertions.assertNotNull(section);

        ScriptTrailer recorded = ScriptStorageUtils.readReplaySection(section);
        ReplayRuntime playback = createPlayback(ReplayFile.parse(scenario.writeReplay()), resources, install);
        Game game = playback.getGame();
        ScriptSimulationPlugin plugin = ScriptMapGameScenario.findPlugin(game);

        Assertions.assertTrue(recorded.isProtected());
        Assertions.assertEquals(ScriptProtector.protect("main.js", SCRIPT).getText(),
                                new String(recorded.getScripts().getScripts().get("main.js"), StandardCharsets.UTF_8));
        Assertions.assertNotNull(plugin, "the replay holds the protected script");

        playback.start(0, 0);

        while (game.getCurrentFrame() < PLAYED_FRAMES) {
            Assertions.assertTrue(playback.step(0, game.getCurrentFrame() * playback.getFrameDuration()));
        }

        Assertions.assertFalse(plugin.getRuntime().isStopped());
        Assertions.assertNotNull(plugin.getImageReplacements().findImageGraphics(REPLACED_IMAGE_TYPE), "the replay's script reads the GRP");
    }
}
