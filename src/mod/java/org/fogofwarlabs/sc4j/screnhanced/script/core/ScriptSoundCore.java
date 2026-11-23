package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SfxdataDat;
import org.fogofwarlabs.sc4j.scr.game.presentation.SoundEvents;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines {@code core.sound}: the game sounds a map script plays. The functions run in shared and local code; shared code plays a sound on every
 * peer, local code only on the local machine. They change no game state.
 * <p>
 * {@code playAt(soundId, x, y)} plays a sound of {@code sfxdata.dat}, from {@code 0} to {@value SfxdataDat#SOUND_COUNT} minus one, at a map
 * position as an animation script plays its sounds ({@link SoundEvents#add}, without a source unit): the interface plays it by its distance from
 * the screen, louder the nearer it is, whether the players see the position or not. {@code playResourceAt(sound, x, y)} plays a sound file of
 * the map's resources, a handle of {@code core.resources.getSound} ({@link ScriptGraphicsCore}), at a map position the same way
 * ({@link SoundEvents#addFile}), on the channels of the map sound files that Play WAV plays on. The game plays the sounds of a game loop
 * iteration after it, so a sound of local code, which runs between the iterations, waits for the next frame start ({@link #playLocalSounds()}).
 */
final class ScriptSoundCore {
    /**
     * A sound that waits: a sound id or a sound file, and its map position.
     */
    private static final class WaitingSound {
        private final int soundId;
        private final byte @Nullable [] file;
        private final int positionX;
        private final int positionY;

        private WaitingSound(int soundId, byte @Nullable [] file, int positionX, int positionY) {
            this.soundId   = soundId;
            this.file      = file;
            this.positionX = positionX;
            this.positionY = positionY;
        }
    }


    /**
     * Adds a sound of the sound table, or a sound file when one is given, to the game's sound events.
     */
    private static void addSound(@NotNull SoundEvents events, int soundId, byte @Nullable [] file, int positionX, int positionY) {
        if (file != null) {
            events.addFile(file, positionX, positionY);
        } else {
            events.add(soundId, positionX, positionY, ScrUnitIds.NONE);
        }
    }


    private final @NotNull ScriptGame scriptGame;
    private final @NotNull List<WaitingSound> localSounds;

    /**
     * Creates a {@link ScriptSoundCore} instance without waiting sounds.
     *
     * @param scriptGame the game binding
     */
    ScriptSoundCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame  = scriptGame;
        this.localSounds = new ArrayList<>();
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

            this.play(soundId, null, ScriptCoreUtils.readInteger(arguments, 1), ScriptCoreUtils.readInteger(arguments, 2));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "playResourceAt", 3, (context, scope, thisObject, arguments) -> {
            byte[] file = graphicsCore.readSoundFile(arguments, 0);

            this.play(SoundEvents.FILE_SOUND_ID, file, ScriptCoreUtils.readInteger(arguments, 1), ScriptCoreUtils.readInteger(arguments, 2));

            return Context.getUndefinedValue();
        });
    }

    /**
     * Plays the sounds local code played since the previous call, in order, with the sounds of the current game loop iteration.
     */
    void playLocalSounds() {
        SoundEvents events = this.getSoundEvents();

        for (WaitingSound sound : this.localSounds) {
            addSound(events, sound.soundId, sound.file, sound.positionX, sound.positionY);
        }

        this.localSounds.clear();
    }

    /**
     * Plays a sound of the sound table or a sound file at a map position: at once in shared code, at the next frame start in local code.
     */
    private void play(int soundId, byte @Nullable [] file, int positionX, int positionY) {
        if (this.scriptGame.isLocal()) {
            this.localSounds.add(new WaitingSound(soundId, file, positionX, positionY));
        } else {
            addSound(this.getSoundEvents(), soundId, file, positionX, positionY);
        }
    }

    private @NotNull SoundEvents getSoundEvents() {
        return this.scriptGame.getGame().getInterfaceLink().getSoundEvents();
    }
}
