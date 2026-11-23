package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The terrain pictures a game's simulation replaces, per megatile: a picture that the HD and HD2 graphics draw over the installed tile with its
 * alpha, and its classic pixels quantized to the tileset palette with their coverage, which the classic graphics draw over the installed or
 * written pixels of the megatile ({@link ClassicTerrainGraphics}). The replacements change only what is drawn, never the simulation; a later
 * replacement of a megatile replaces the earlier one.
 */
public final class TerrainReplacements {
    /**
     * The pixels of a classic megatile picture.
     */
    private static final int TILE_PIXELS = TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE;

    /**
     * The replacement of one megatile.
     */
    private static final class Replacement {
        private final @NotNull Picture picture;
        private final byte @NotNull [] pixels;
        private final byte @NotNull [] coverage;

        private Replacement(@NotNull Picture picture, byte @NotNull [] pixels, byte @NotNull [] coverage) {
            this.picture  = picture;
            this.pixels   = pixels;
            this.coverage = coverage;
        }
    }

    private final @NotNull Map<Integer, Replacement> replacements;

    /**
     * Creates a {@link TerrainReplacements} instance without replacements.
     */
    public TerrainReplacements() {
        this.replacements = new HashMap<>();
    }

    /**
     * @return {@code true} if no megatile is replaced, {@code false} otherwise
     */
    public boolean isEmpty() {
        return this.replacements.isEmpty();
    }

    /**
     * Replaces the pictures of a megatile.
     *
     * @param megatile the megatile index
     * @param picture  the picture the HD and HD2 graphics draw
     * @param pixels   the {@code 32x32} palette indices the classic graphics draw, rows from the top, kept and not changed afterwards
     * @param coverage the {@code 32x32} coverage of the pixels, nonzero where a pixel is drawn, kept and not changed afterwards
     * @throws IllegalArgumentException if the pixels or the coverage are not {@code 32x32}
     */
    public void replaceTile(int megatile, @NotNull Picture picture, byte @NotNull [] pixels, byte @NotNull [] coverage) {
        if (pixels.length != TILE_PIXELS || coverage.length != TILE_PIXELS) {
            throw Exceptions.newIllegalArgumentException("A megatile picture has {0} pixels, not {1} and {2}.", TILE_PIXELS, pixels.length,
                                                         coverage.length);
        }

        this.replacements.put(megatile, new Replacement(picture, pixels, coverage));
    }

    /**
     * @param megatile the megatile index
     * @return the picture the HD and HD2 graphics draw over the megatile, or {@code null} if the megatile is not replaced
     */
    public @Nullable Picture findPicture(int megatile) {
        Replacement replacement = this.replacements.get(megatile);

        return replacement == null ? null : replacement.picture;
    }

    /**
     * @param megatile the megatile index
     * @return the {@code 32x32} palette indices the classic graphics draw over the megatile, or {@code null} if the megatile is not replaced
     */
    public byte @Nullable [] findPixels(int megatile) {
        Replacement replacement = this.replacements.get(megatile);

        return replacement == null ? null : replacement.pixels;
    }

    /**
     * @param megatile the megatile index
     * @return the {@code 32x32} coverage of the classic pixels, nonzero where a pixel is drawn, or {@code null} if the megatile is not replaced
     */
    public byte @Nullable [] findCoverage(int megatile) {
        Replacement replacement = this.replacements.get(megatile);

        return replacement == null ? null : replacement.coverage;
    }
}
