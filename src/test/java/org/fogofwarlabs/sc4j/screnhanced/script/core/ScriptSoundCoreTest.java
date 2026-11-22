package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.presentation.SoundEvents;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

/**
 * Tests {@code core.sound} and {@code display.sound} on a game without game data: a sound of shared code joins the game's sound events at once,
 * a sound of local code at the next frame start, and the refusals.
 */
final class ScriptSoundCoreTest {
    private static final int MAP_TILE_SIZE = 4;
    /**
     * The sounds and the positions the test script plays.
     */
    private static final int SHARED_SOUND = 7;
    private static final int LOCAL_SOUND = 1143;
    private static final int SOUND_X = 40;
    private static final int SOUND_Y = 72;

    @Test
    void play_at_should_queue_shared_sounds_at_once_and_local_sounds_at_the_next_frame_start() throws IOException {
        Game game = Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, 0, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]), new LcgRandom(0));
        ScriptGameScenario scenario = new ScriptGameScenario(game, """
                                                                   display.sound.playAt(7, Position.of(40, 72));
                                                                   [function () { core.sound.playAt(1144, 0, 0); }, function () { core.sound.playAt(-1, 0, 0); },
                                                                    function () { display.sound.playAt(7, null); }].forEach(function (call) {
                                                                       try {
                                                                           call();
                                                                       } catch (error) {
                                                                           log.info(error.name);
                                                                       }
                                                                   });
                                                                   function onLocalFrame(frame) {
                                                                       core.sound.playAt(1143, 1, 2);
                                                                   }
                                                                   """);
        SoundEvents events = game.getInterfaceLink().getSoundEvents();

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("RangeError", "RangeError", "TypeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertEquals(1, events.getCount());
        Assertions.assertEquals(SHARED_SOUND, events.getSoundId(0));
        Assertions.assertEquals(SOUND_X, events.getPositionX(0));
        Assertions.assertEquals(SOUND_Y, events.getPositionY(0));
        Assertions.assertEquals(ScrUnitIds.NONE, events.getSourceUnitType(0));

        events.clear();
        scenario.getRuntime().callLocalFrame(1);

        Assertions.assertEquals(0, events.getCount(), "a local sound waits for the next frame start");

        scenario.getRuntime().playLocalSounds();
        scenario.getRuntime().playLocalSounds();

        Assertions.assertEquals(1, events.getCount());
        Assertions.assertEquals(LOCAL_SOUND, events.getSoundId(0));
    }
}
