package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SfxdataDat;
import org.fogofwarlabs.sc4j.scr.game.presentation.SoundEvents;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptMapScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests {@code core.sound} and {@code display.sound} on a game without game data: a sound of shared code joins the game's sound events at once,
 * a sound of local code at the next frame start, a sound file of the resources as a sound id past the sound table that the runtime answers with
 * the file, and the refusals.
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
    private static final int LOCAL_X = 1;
    private static final int LOCAL_Y = 2;
    /**
     * The sound resource: a RIFF WAVE of 8-bit mono PCM at 11025 Hz, and a resource that is no sound.
     */
    private static final @NotNull String SOUND_NAME = "sounds/hit.wav";
    private static final @NotNull String TEXT_NAME = "notes.txt";
    private static final int SAMPLE_RATE = 11025;
    private static final int SAMPLE_COUNT = 16;
    private static final int SILENT_SAMPLE = 0x80;
    private static final int WAVE_HEADER_SIZE = 44;
    private static final int FORMAT_CHUNK_SIZE = 16;
    private static final short PCM_FORMAT = 1;
    private static final short MONO = 1;
    private static final short SAMPLE_BITS = 8;
    /**
     * The bytes of the RIFF size field and of the RIFF chunk header before the WAVE form.
     */
    private static final int RIFF_HEADER_SIZE = 8;

    /**
     * Creates a RIFF WAVE of silent 8-bit mono PCM samples.
     */
    private static byte @NotNull [] createWave() {
        ByteBuffer wave = ByteBuffer.allocate(WAVE_HEADER_SIZE + SAMPLE_COUNT).order(ByteOrder.LITTLE_ENDIAN);

        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(WAVE_HEADER_SIZE - RIFF_HEADER_SIZE + SAMPLE_COUNT)
            .put("WAVE".getBytes(StandardCharsets.US_ASCII));
        wave.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(FORMAT_CHUNK_SIZE).putShort(PCM_FORMAT).putShort(MONO).putInt(SAMPLE_RATE)
            .putInt(SAMPLE_RATE * MONO * SAMPLE_BITS / Byte.SIZE).putShort((short) (MONO * SAMPLE_BITS / Byte.SIZE)).putShort(SAMPLE_BITS);
        wave.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(SAMPLE_COUNT);

        while (wave.hasRemaining()) {
            wave.put((byte) SILENT_SAMPLE);
        }

        return wave.array();
    }

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
        Assertions.assertNull(scenario.getRuntime().findSoundFile(SHARED_SOUND), "a sound of the table has no sound file");

        events.clear();
        scenario.getRuntime().callLocalFrame(1);

        Assertions.assertEquals(0, events.getCount(), "a local sound waits for the next frame start");

        scenario.getRuntime().playLocalSounds();
        scenario.getRuntime().playLocalSounds();

        Assertions.assertEquals(1, events.getCount());
        Assertions.assertEquals(LOCAL_SOUND, events.getSoundId(0));
    }

    @Test
    void play_at_should_queue_a_sound_file_of_the_resources() throws IOException {
        byte[] wave = createWave();
        Map<String, byte[]> files = new LinkedHashMap<>();

        files.put(SOUND_NAME, wave);
        files.put(TEXT_NAME, "no sound".getBytes(StandardCharsets.UTF_8));

        Game game = Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, 0, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]), new LcgRandom(0));
        ScriptGameScenario scenario = new ScriptGameScenario(game, """
                                                                   var sound = game.map.resources.getSound('sounds/hit.wav');
                                                                   display.sound.playAt(sound, Position.of(40, 72));
                                                                   log.info(sound.getName(), sound === game.map.resources.getSound('sounds/hit.wav'), sound.getId() === core.resources.getSound('sounds/hit.wav'));
                                                                   [function () { game.map.resources.getSound('notes.txt'); }, function () { game.map.resources.getSound('none.wav'); },
                                                                    function () { core.sound.playResourceAt(-1, 0, 0); }, function () { core.sound.playResourceAt(sound.getId() + 1, 0, 0); },
                                                                    function () { display.sound.playAt(sound, null); }].forEach(function (call) {
                                                                       try {
                                                                           call();
                                                                       } catch (error) {
                                                                           log.info(error.name);
                                                                       }
                                                                   });
                                                                   function onLocalFrame(frame) {
                                                                       display.sound.playAt(sound, Position.of(1, 2));
                                                                   }
                                                                   """, ScriptMapScenario.createResources(files));
        SoundEvents events = game.getInterfaceLink().getSoundEvents();

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("sounds/hit.wav true true", "Error", "Error", "RangeError", "RangeError", "TypeError"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertEquals(1, events.getCount());
        Assertions.assertEquals(SfxdataDat.SOUND_COUNT, events.getSoundId(0), "the first sound file takes the first id past the sound table");
        Assertions.assertArrayEquals(wave, scenario.getRuntime().findSoundFile(SfxdataDat.SOUND_COUNT));
        Assertions.assertNull(scenario.getRuntime().findSoundFile(SfxdataDat.SOUND_COUNT + 1));
        Assertions.assertEquals(SOUND_X, events.getPositionX(0));
        Assertions.assertEquals(SOUND_Y, events.getPositionY(0));
        Assertions.assertEquals(ScrUnitIds.NONE, events.getSourceUnitType(0));

        events.clear();
        scenario.getRuntime().callLocalFrame(1);

        Assertions.assertEquals(0, events.getCount(), "a local sound waits for the next frame start");

        scenario.getRuntime().playLocalSounds();

        Assertions.assertEquals(1, events.getCount());
        Assertions.assertEquals(SfxdataDat.SOUND_COUNT, events.getSoundId(0), "a sound file keeps its sound id");
        Assertions.assertEquals(LOCAL_X, events.getPositionX(0));
        Assertions.assertEquals(LOCAL_Y, events.getPositionY(0));
    }
}
