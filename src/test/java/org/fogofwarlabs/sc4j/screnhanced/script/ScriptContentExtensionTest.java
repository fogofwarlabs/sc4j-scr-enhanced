package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SpritesDat;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.LiveMapGameScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the entries {@link ScriptContentExtension} reserves: their ids, values and scripts, and that a game runs to the same state hashes
 * with them as without in a melee game against a computer player.
 */
@RequiresInstallation
final class ScriptContentExtensionTest {
    /**
     * The frames the game runs.
     */
    private static final int LAST_FRAME = 3000;
    private static final @NotNull String MAP_PATH = "Maps/(2)Bottleneck.scm";
    private static final @NotNull String PLAYER_NAME = "tester";
    private static final int GAME_SEED = 0x2F6A11;

    private static @NotNull List<Long> playGame(@NotNull GameData gameData) throws IOException {
        Path map = ExternalInput.INSTALLATION.getPath().resolve(MAP_PATH);
        LiveGame live = new LiveGame(MeleeGameSetup.load(map, PLAYER_NAME, "terran", "zerg", 0, GameSpeed.FASTEST), GAME_SEED);
        ReplayRuntime runtime = LiveMapGameScenario.startLive(gameData, live, map);
        List<Long> hashes = new ArrayList<>();

        while (runtime.getGame().getCurrentFrame() < LAST_FRAME) {
            LiveMapGameScenario.stepFrame(runtime, live);

            hashes.add(runtime.getGame().computeStateHash());
        }

        return hashes;
    }

    @Test
    void reserved_entries_follow_the_native_entries_and_leave_games_unchanged() throws IOException {
        List<Long> nativeHashes;

        try (GameData plain = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            nativeHashes = playGame(plain);
        }

        try (GameData extended = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ScriptContentExtension extension = new ScriptContentExtension();

            extension.extendInstalledData(extended);
            extension.extendInstalledData(extended);

            ScrAddedGraphics graphics = extended.getAddedGraphics();
            ScriptContentExtension.Entries entries = ScriptContentExtension.findEntries(graphics);

            Assertions.assertNotNull(entries);
            Assertions.assertEquals(ScriptContentExtension.IMAGE_COUNT + 1, graphics.getImages().size());
            Assertions.assertEquals(ScriptContentExtension.SPRITE_COUNT, graphics.getSprites().size());
            Assertions.assertEquals(ScriptContentExtension.FLINGY_COUNT, graphics.getFlingies().size());
            Assertions.assertEquals(ScrAddedGraphics.FIRST_IMAGE_ID, entries.getFirstImage());
            Assertions.assertEquals(entries.getFirstImage() + ScriptContentExtension.IMAGE_COUNT, entries.getShadowImage());
            Assertions.assertEquals(ScrAddedGraphics.FIRST_SPRITE_ID, entries.getFirstSprite());
            Assertions.assertEquals(ScrAddedGraphics.FIRST_FLINGY_ID, entries.getFirstFlingy());
            Assertions.assertEquals(entries.getStillScript(), graphics.getImage(entries.getFirstImage()).getValue(ImagesDat.ISCRIPT));
            Assertions.assertEquals(entries.getFirstImage() + 1, graphics.getSprites().get(1).getValue(SpritesDat.IMAGE));
            Assertions.assertNotEquals(entries.getFramesPath(entries.getFirstImage()), entries.getFramesPath(entries.getFirstImage() + 1));
            Assertions.assertEquals(entries.getFramesPath(entries.getFirstImage()), extended.getFramesPath(entries.getFirstImage()));

            List<Long> extendedHashes = playGame(extended);

            Assertions.assertNotNull(extended.getIscript().findHeader(entries.getProjectileScript()));
            Assertions.assertEquals(nativeHashes, extendedHashes);
        }
    }
}
