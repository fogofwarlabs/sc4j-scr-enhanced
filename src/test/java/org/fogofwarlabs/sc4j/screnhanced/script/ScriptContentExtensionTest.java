package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SpritesDat;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptFile;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptHeader;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptInstruction;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptOpcodes;
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
 * Tests the entries {@link ScriptContentExtension} reserves: their ids, values and scripts, that a game runs to the same state hashes with them
 * as without in a melee game against a computer player, and the frame sets of the {@code Unit} scripts.
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
    private static final int INIT_ANIMATION = 0;
    private static final int DEATH_ANIMATION = 1;
    private static final int GROUND_ATTACK_INIT_ANIMATION = 2;
    private static final int WALKING_ANIMATION = 11;
    private static final @NotNull List<Integer> SOUND_OPCODES = List.of(IscriptOpcodes.PLAYSND, IscriptOpcodes.PLAYSNDRAND, IscriptOpcodes.PLAYSNDBTWN);

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

    private static @NotNull List<Integer> findFrames(@NotNull IscriptHeader header, int animationType) {
        List<Integer> frames = new ArrayList<>();

        for (IscriptHeader.Animation animation : header.getAnimations()) {
            if (animation.getTypeIndex() != animationType) {
                continue;
            }

            for (IscriptInstruction instruction : animation.getInstructions()) {
                if (instruction.getOpcode() == IscriptOpcodes.PLAYFRAM) {
                    frames.add(instruction.getOperands()[0]);
                }
            }
        }

        return frames;
    }

    private static @NotNull List<Integer> findOpcodes(@NotNull IscriptHeader header, int animationType) {
        List<Integer> opcodes = new ArrayList<>();

        for (IscriptHeader.Animation animation : header.getAnimations()) {
            if (animation.getTypeIndex() == animationType) {
                animation.getInstructions().forEach(instruction -> opcodes.add(instruction.getOpcode()));
            }
        }

        return opcodes;
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

    @Test
    void unit_scripts_walk_attack_and_die_through_their_frame_sets_without_sounds() throws IOException {
        try (GameData extended = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            new ScriptContentExtension().extendInstalledData(extended);

            ScriptContentExtension.Entries entries = ScriptContentExtension.findEntries(extended.getAddedGraphics());

            Assertions.assertNotNull(entries);

            IscriptFile iscript = extended.getAddedGraphics().createIscript(extended.getIscript());

            for (int script : new int[]{entries.getUnitScript(), entries.getUnitShadowScript()}) {
                IscriptHeader header = iscript.findHeader(script);

                Assertions.assertNotNull(header);

                for (IscriptHeader.Animation animation : header.getAnimations()) {
                    for (IscriptInstruction instruction : animation.getInstructions()) {
                        Assertions.assertFalse(SOUND_OPCODES.contains(instruction.getOpcode()), "no sound");
                    }
                }

                Assertions.assertEquals(List.of(17, 34, 51, 68, 85, 102, 119, 136), findFrames(header, WALKING_ANIMATION));
                Assertions.assertEquals(List.of(153, 170, 153), findFrames(header, GROUND_ATTACK_INIT_ANIMATION), "aim, fire, aim");
                Assertions.assertEquals(List.of(187, 204, 221, 238), findFrames(header, DEATH_ANIMATION));
                Assertions.assertEquals(script == entries.getUnitShadowScript(), findOpcodes(header, INIT_ANIMATION).contains(IscriptOpcodes.IMGUL),
                                        "the shadow script creates the shadow image");
            }
        }
    }
}
