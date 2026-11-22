package org.fogofwarlabs.sc4j.screnhanced;

import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageAnimation;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImagePool;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.LiveMapGameScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Runs every raw map ({@code .chk}) in the folder that the system property {@value #MAPS_PROPERTY} names (a local check; the maps stay outside
 * the repository) twice with the classic memory of SC:R Enhanced: once with its plugins asked for every image's graphics every frame as a renderer
 * asks, once with the plugins replaced by plugins that give nothing. The state hash of every frame is the same, and an image of each map draws an
 * anim or another image's installed graphics.
 */
@RequiresInstallation
@EnabledIfSystemProperty(named = ClassicImageMapCheckTest.MAPS_PROPERTY, matches = ".+")
final class ClassicImageMapCheckTest {
    /**
     * The system property that names the folder of the maps.
     */
    static final @NotNull String MAPS_PROPERTY = "sc4j.screnhanced.imageMaps";

    private static final @NotNull String MAP_EXTENSION = ".chk";
    private static final @NotNull String PLAYER_NAME = "tester";
    private static final int SEED = 0x1234;
    private static final int FRAMES = 240;
    private static final int HD_SCALE = 4;
    private static final int HD2_SCALE = 2;
    /**
     * The time the check waits for the anims to be parsed after the run.
     */
    private static final long PARSE_TIMEOUT_NANOSECONDS = 30_000_000_000L;

    /**
     * One run of a map: the state hash after every frame, the plugins and the images.
     */
    private static final class MapRun {
        private final @NotNull List<Long> hashes;
        private final @NotNull ScrGamePlugin @NotNull [] plugins;
        private final @NotNull SpriteImagePool pool;

        private MapRun(@NotNull List<Long> hashes, @NotNull ScrGamePlugin @NotNull [] plugins, @NotNull SpriteImagePool pool) {
            this.hashes  = hashes;
            this.plugins = plugins;
            this.pool    = pool;
        }
    }

    /**
     * Runs a map and records the state hash after every frame.
     *
     * @param gameData the installed game data
     * @param mapPath  the raw map
     * @param asked    {@code true} to ask the plugins for every image's graphics every frame, {@code false} to replace them by plugins that give nothing
     * @return the run
     * @throws IOException if the map cannot be read
     */
    private static @NotNull MapRun run(@NotNull GameData gameData, @NotNull Path mapPath, boolean asked) throws IOException {
        byte[] mapData = Files.readAllBytes(mapPath);
        MeleeGameSetup setup = MeleeGameSetup.createUseMapSettings(mapData, PLAYER_NAME, ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST);
        LiveGame live = new LiveGame(setup, SEED);
        ReplayRuntime runtime = LiveMapGameScenario.startLive(gameData, live, mapPath);
        ScrGamePlugin[] plugins = runtime.getGame().getPlugins();
        List<Long> hashes = new ArrayList<>();

        if (!asked) {
            Arrays.fill(plugins, new ScrGamePlugin() {
            });
        }

        while (runtime.getGame().getCurrentFrame() < FRAMES) {
            LiveMapGameScenario.stepFrame(runtime, live);

            Assertions.assertNull(runtime.getFailure());

            if (asked) {
                askGraphics(runtime.getGame().getSpriteSystem().getImagePool(), plugins);
            }

            hashes.add(runtime.getGame().computeStateHash());
        }

        return new MapRun(hashes, plugins, runtime.getGame().getSpriteSystem().getImagePool());
    }

    private static void askGraphics(@NotNull SpriteImagePool pool, @NotNull ScrGamePlugin @NotNull [] plugins) {
        for (int slot = 0; slot < pool.getCapacity(); slot++) {
            if (!pool.isAllocated(slot)) {
                continue;
            }

            SpriteImage image = pool.getImage(slot);

            for (ScrGamePlugin plugin : plugins) {
                plugin.findImageSheet(image);
                plugin.findImageTypeId(image);
                plugin.findImageFrame(image);
                plugin.findImageDrawFunction(image);

                ScrImageAnimation animation = plugin.findImageAnimation(image);

                if (animation != null) {
                    animation.findSheet(HD_SCALE);
                    animation.findSheet(HD2_SCALE);
                }
            }
        }
    }

    /**
     * Describes what the images of a run draw: the image types whose installed graphics they draw and the frame counts and texel sizes of their
     * anims, once parsed.
     *
     * @param run the run
     * @return the sorted descriptions joined by {@code ; }
     */
    private static @NotNull String describe(@NotNull MapRun run) {
        SpriteImagePool pool = run.pool;
        TreeSet<String> found = new TreeSet<>();
        long deadline = System.nanoTime() + PARSE_TIMEOUT_NANOSECONDS;

        for (int slot = 0; slot < pool.getCapacity(); slot++) {
            if (!pool.isAllocated(slot)) {
                continue;
            }

            SpriteImage image = pool.getImage(slot);

            for (ScrGamePlugin plugin : run.plugins) {
                int type = plugin.findImageTypeId(image);
                ScrImageAnimation animation = plugin.findImageAnimation(image);

                if (type >= 0) {
                    found.add("image " + image.getImageTypeId() + " draws image " + type);
                }

                if (animation != null) {
                    for (int scale : new int[]{HD_SCALE, HD2_SCALE}) {
                        TextureSheet sheet = animation.findSheet(scale);

                        while (sheet == null && System.nanoTime() < deadline) {
                            Thread.onSpinWait();
                            sheet = animation.findSheet(scale);
                        }

                        found.add("image " + image.getImageTypeId() + " scale " + scale + ": " +
                                  (sheet == null ? "no anim" : sheet.getFrameCount() + " frames, " + sheet.getUnitsPerTexel() + " units per texel") +
                                  (plugin.findImageSheet(image) == null ? "" : ", with a GRP"));
                    }
                }
            }
        }

        return String.join("; ", found);
    }

    @Test
    void every_map_keeps_its_state_hash_while_its_images_draw_graphics_from_game_memory() throws IOException {
        List<Path> maps;

        try (Stream<Path> files = Files.list(Path.of(System.getProperty(MAPS_PROPERTY)))) {
            maps = files.filter(file -> file.getFileName().toString().endsWith(MAP_EXTENSION)).sorted().toList();
        }

        Assertions.assertFalse(maps.isEmpty(), "the folder holds no raw map");

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            for (Path map : maps) {
                MapRun idle = run(gameData, map, false);
                MapRun asked = run(gameData, map, true);
                String description = describe(asked);

                System.out.println(map.getFileName() + ": " + description);

                Assertions.assertEquals(FRAMES, idle.hashes.size());
                Assertions.assertEquals(idle.hashes, asked.hashes, () -> map.getFileName() + " changes the state hash");
                Assertions.assertFalse(description.isEmpty(), () -> map.getFileName() + " draws nothing from game memory");
            }
        }
    }
}
