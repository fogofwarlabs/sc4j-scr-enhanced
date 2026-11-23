package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * A picture cut into frames by a {@link SheetLayout}, with an optional team color picture (its alpha the strength of the owner's color), an
 * optional shadow picture (its alpha the shadow coverage) and optional SD frames of the map ({@code sd}), which replace the frames
 * {@link PictureSheetConverter} quantizes in the SD graphics. The pictures are read once and never change.
 */
public final class PictureSheet {
    private static final int ALPHA_SHIFT = 24;
    private static final int BYTE_MASK = 0xFF;

    /**
     * Creates a {@link PictureSheet} instance after checking that the extra pictures and the SD frames match the picture and the layout.
     *
     * @param name      the name of the sheet for messages
     * @param picture   the picture
     * @param layout    the frame layout, checked against the picture
     * @param teamColor the team color picture of the same size, or {@code null} for none
     * @param shadow    the shadow picture of the same size, or {@code null} for none
     * @param sd        the SD frames of the map, or {@code null} to quantize the picture
     * @return the created {@link PictureSheet} instance
     * @throws IllegalArgumentException if an extra picture has another size, the SD frames have fewer frames than the layout or another canvas
     */
    public static @NotNull PictureSheet create(@NotNull String name, @NotNull Picture picture, @NotNull SheetLayout layout, @Nullable Picture teamColor,
                                               @Nullable Picture shadow, @Nullable GrpFile sd) {
        checkSize(picture, teamColor, "team color");
        checkSize(picture, shadow, "shadow");

        if (sd != null && (sd.getFrameCount() < layout.getFrameCount() || sd.getWidth() != layout.getCanvasWidth() ||
                           sd.getHeight() != layout.getCanvasHeight())) {
            throw Exceptions.newIllegalArgumentException("The SD frames hold {0} frames on {1} by {2}; the sheet needs {3} frames on {4} by {5}.",
                                                         sd.getFrameCount(), sd.getWidth(), sd.getHeight(), layout.getFrameCount(),
                                                         layout.getCanvasWidth(), layout.getCanvasHeight());
        }

        return new PictureSheet(name, layout, readColors(picture), picture.getWidth(), teamColor == null ? null : readColors(teamColor),
                                shadow == null ? null : readColors(shadow), sd);
    }

    private static void checkSize(@NotNull Picture picture, @Nullable Picture extra, @NotNull String kind) {
        if (extra != null && (extra.getWidth() != picture.getWidth() || extra.getHeight() != picture.getHeight())) {
            throw Exceptions.newIllegalArgumentException("The {0} picture is {1} by {2}, the picture {3} by {4}.", kind, extra.getWidth(),
                                                         extra.getHeight(), picture.getWidth(), picture.getHeight());
        }
    }

    private static int @NotNull [] readColors(@NotNull Picture picture) {
        int[] colors = new int[picture.getWidth() * picture.getHeight()];

        picture.getColor().copyValues(0, colors, 0, colors.length);

        return colors;
    }


    private final @NotNull String name;
    private final @NotNull SheetLayout layout;

    /**
     * The straight-alpha colors of the picture, {@code 0xAARRGGBB}, row-major.
     */
    private final int @NotNull [] colors;
    private final int pictureWidth;
    private final int @Nullable [] teamColors;
    private final int @Nullable [] shadowColors;
    private final @Nullable GrpFile sd;

    private PictureSheet(@NotNull String name, @NotNull SheetLayout layout, int @NotNull [] colors, int pictureWidth, int @Nullable [] teamColors,
                         int @Nullable [] shadowColors, @Nullable GrpFile sd) {
        this.name   = name;
        this.layout = layout;

        this.colors       = colors;
        this.pictureWidth = pictureWidth;
        this.teamColors   = teamColors;
        this.shadowColors = shadowColors;
        this.sd           = sd;
    }

    /**
     * @return the name of the sheet for messages
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the frame layout
     */
    public @NotNull SheetLayout getLayout() {
        return this.layout;
    }

    /**
     * @return {@code true} if the sheet has a team color picture, {@code false} otherwise
     */
    public boolean hasTeamColor() {
        return this.teamColors != null;
    }

    /**
     * @return {@code true} if the sheet has a shadow picture, {@code false} otherwise
     */
    public boolean hasShadow() {
        return this.shadowColors != null;
    }

    /**
     * @return the SD frames of the map, or {@code null} if the SD graphics quantize the picture
     */
    public @Nullable GrpFile getSd() {
        return this.sd;
    }

    /**
     * Reads a pixel of a frame.
     *
     * @param frame the frame
     * @param x     the column in the frame, in picture pixels
     * @param y     the row in the frame, in picture pixels
     * @return the straight-alpha color, {@code 0xAARRGGBB}
     */
    public int getColor(int frame, int x, int y) {
        return this.colors[this.getOffset(frame, x, y)];
    }

    /**
     * Reads the team color strength of a pixel of a frame.
     *
     * @param frame the frame
     * @param x     the column in the frame, in picture pixels
     * @param y     the row in the frame, in picture pixels
     * @return the strength from {@code 0} to {@code 255}, {@code 0} for a sheet without a team color picture
     */
    public int getTeamStrength(int frame, int x, int y) {
        return this.teamColors == null ? 0 : this.teamColors[this.getOffset(frame, x, y)] >>> ALPHA_SHIFT;
    }

    /**
     * Reads the shadow coverage of a pixel of a frame.
     *
     * @param frame the frame
     * @param x     the column in the frame, in picture pixels
     * @param y     the row in the frame, in picture pixels
     * @return the coverage from {@code 0} to {@code 255}
     * @throws IllegalStateException if the sheet has no shadow picture
     */
    public int getShadowCoverage(int frame, int x, int y) {
        if (this.shadowColors == null) {
            throw Exceptions.newIllegalStateException("The sheet {0} has no shadow picture.", this.name);
        }

        return this.shadowColors[this.getOffset(frame, x, y)] >>> ALPHA_SHIFT & BYTE_MASK;
    }

    /**
     * Decodes a frame of the SD frames of the map.
     *
     * @param frame the frame
     * @return the palette indices on the canvas, row-major, {@code 0} transparent
     * @throws IOException           if the frame cannot be decoded
     * @throws IllegalStateException if the sheet has no SD frames
     */
    byte @NotNull [] decodeSdFrame(int frame) throws IOException {
        if (this.sd == null) {
            throw Exceptions.newIllegalStateException("The sheet {0} has no SD frames.", this.name);
        }

        return this.sd.decodeFrame(frame).getPixels();
    }

    private int getOffset(int frame, int x, int y) {
        return (this.layout.getFrameY(frame) + y) * this.pictureWidth + this.layout.getFrameX(frame) + x;
    }
}
