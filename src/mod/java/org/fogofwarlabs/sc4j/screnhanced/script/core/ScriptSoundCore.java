package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SfxdataDat;
import org.fogofwarlabs.sc4j.scr.game.presentation.SoundEvents;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defines {@code core.sound}: the game sounds a map script plays. The functions run in shared and local code; shared code plays a sound on every
 * peer, local code only on the local machine. They change no game state.
 * <p>
 * {@code playAt(soundId, x, y)} plays a sound of {@code sfxdata.dat}, from {@code 0} to {@value SfxdataDat#SOUND_COUNT} minus one, at a map
 * position as an animation script plays its sounds ({@link SoundEvents#add}, without a source unit): the interface plays it by its distance from
 * the screen, louder the nearer it is, whether the players see the position or not. {@code playResourceAt(sound, x, y)} plays a sound file of
 * the map's resources, a handle of {@code core.resources.getSound} ({@link ScriptGraphicsCore}), at a map position the same way, on the
 * channels of the map sound files that Play WAV plays on: each sound file takes a sound id past the sound table on its first play, which the
 * game's plugin answers with the file ({@link #findSoundFile}). The game plays the sounds of a game loop iteration after it, so a sound of local
 * code, which runs between the iterations, waits for the next frame start ({@link #playLocalSounds()}).
 */
final class ScriptSoundCore {
    /**
     * A sound that waits: its sound id and map position.
     */
    private static final class WaitingSound {
        private final int soundId;
        private final int positionX;
        private final int positionY;

        private WaitingSound(int soundId, int positionX, int positionY) {
            this.soundId   = soundId;
            this.positionX = positionX;
            this.positionY = positionY;
        }
    }


    private final @NotNull ScriptGame scriptGame;
    private final @NotNull List<WaitingSound> localSounds;

    /**
     * The played sound files: the sound id of a file is {@value SfxdataDat#SOUND_COUNT} plus its index.
     */
    private final @NotNull List<byte[]> files;
    private final @NotNull Map<byte[], Integer> fileIndices;

    /**
     * Creates a {@link ScriptSoundCore} instance without waiting sounds and sound files.
     *
     * @param scriptGame the game binding
     */
    ScriptSoundCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame  = scriptGame;
        this.localSounds = new ArrayList<>();

        this.files       = new ArrayList<>();
        this.fileIndices = new IdentityHashMap<>();
    }

    /**
     * Defines the functions of {@code core.sound}.
     *
     * @param namespace    the namespace object
     * @param global       the global scope
     * @param graphicsCore the resource functions, which read sound handle arguments
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull ScriptGraphicsCore graphicsCore) {
        ScriptCoreUtils.defineFunction(global, namespace, "playAt", 3, (context, scope, thisObject, arguments) -> {
            int soundId = ScriptCoreUtils.requireId("sound", ScriptCoreUtils.readInteger(arguments, 0), SfxdataDat.SOUND_COUNT);

            this.play(soundId, ScriptCoreUtils.readInteger(arguments, 1), ScriptCoreUtils.readInteger(arguments, 2));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "playResourceAt", 3, (context, scope, thisObject, arguments) -> {
            int soundId = this.findSoundId(graphicsCore.readSoundFile(arguments, 0));

            this.play(soundId, ScriptCoreUtils.readInteger(arguments, 1), ScriptCoreUtils.readInteger(arguments, 2));

            return Context.getUndefinedValue();
        });
    }

    /**
     * Plays the sounds local code played since the previous call, in order, with the sounds of the current game loop iteration.
     */
    void playLocalSounds() {
        SoundEvents events = this.getSoundEvents();

        for (WaitingSound sound : this.localSounds) {
            events.add(sound.soundId, sound.positionX, sound.positionY, ScrUnitIds.NONE);
        }

        this.localSounds.clear();
    }

    /**
     * Finds the sound file of a sound id that a played sound file took.
     *
     * @param soundId the sound id of a played sound event
     * @return the sound file, or {@code null} for a sound id that no sound file took
     */
    byte @Nullable [] findSoundFile(int soundId) {
        int index = soundId - SfxdataDat.SOUND_COUNT;

        return index >= 0 && index < this.files.size() ? this.files.get(index) : null;
    }

    /**
     * Returns the sound id of a sound file, which takes the next free id past the sound table on its first play.
     */
    private int findSoundId(byte @NotNull [] file) {
        Integer index = this.fileIndices.get(file);

        if (index == null) {
            index = this.files.size();
            this.files.add(file);
            this.fileIndices.put(file, index);
        }

        return SfxdataDat.SOUND_COUNT + index;
    }

    /**
     * Plays a sound at a map position: at once in shared code, at the next frame start in local code.
     */
    private void play(int soundId, int positionX, int positionY) {
        if (this.scriptGame.isLocal()) {
            this.localSounds.add(new WaitingSound(soundId, positionX, positionY));
        } else {
            this.getSoundEvents().add(soundId, positionX, positionY, ScrUnitIds.NONE);
        }
    }

    private @NotNull SoundEvents getSoundEvents() {
        return this.scriptGame.getGame().getInterfaceLink().getSoundEvents();
    }
}
