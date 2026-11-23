package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.image.ImageFileWriterUtils;
import org.jetbrains.annotations.NotNull;

/**
 * The frame layout of a picture read as a sheet ({@link PictureSheet}): frames of one size cut row by row from the top left, frame {@code n} the
 * {@code n}-th cell. A sheet of turning frame sets holds {@value #TURNING_DIRECTIONS} frames per frame set, north through south clockwise.
 * <p>
 * The canvas of a frame in map pixels is the frame size over the scale. A GRP centers its canvas on the image position, so the anchor, the pixel
 * of a frame placed on the image position, becomes the canvas center: an anchor other than the frame center widens the canvas on the opposite
 * side by the padding before the frame. A sheet without an anchor keeps the frame size as its canvas.
 */
public final class SheetLayout {
    /**
     * The frames of a frame set of an image that turns.
     */
    public static final int TURNING_DIRECTIONS = 17;
    /**
     * The largest side of a picture, in picture pixels.
     */
    public static final int MAXIMUM_PICTURE_SIZE = 4096;
    /**
     * The anchor value of a layout without an anchor.
     */
    public static final int NO_ANCHOR = -1;
    /**
     * The frame count value of a layout with every whole frame of the picture.
     */
    public static final int ALL_FRAMES = -1;

    /**
     * The scales a sheet may have: picture pixels per map pixel.
     */
    private static final int @NotNull [] SCALES = {1, 2, 4};

    /**
     * Creates a {@link SheetLayout} instance after checking it against a picture.
     *
     * @param pictureWidth  the picture width in picture pixels
     * @param pictureHeight the picture height in picture pixels
     * @param frameWidth    the frame width in picture pixels
     * @param frameHeight   the frame height in picture pixels
     * @param frameCount    the frames, or {@link #ALL_FRAMES} for every whole frame of the picture
     * @param directions    the frames of a frame set: {@code 1} or {@value #TURNING_DIRECTIONS}
     * @param scale         the picture pixels per map pixel: {@code 1}, {@code 2} or {@code 4}
     * @param anchorX       the anchor's column in a frame, in picture pixels, or {@link #NO_ANCHOR} for the frame center
     * @param anchorY       the anchor's row in a frame, in picture pixels, or {@link #NO_ANCHOR} for the frame center
     * @return the created {@link SheetLayout} instance
     * @throws IllegalArgumentException if a value is outside its range, the frames do not fit the picture, the scale does not divide the frame
     *                                  size or the anchor, the frame count is no multiple of the directions or the canvas exceeds {@code 255}
     *                                  map pixels
     */
    public static @NotNull SheetLayout create(int pictureWidth, int pictureHeight, int frameWidth, int frameHeight, int frameCount, int directions,
                                              int scale, int anchorX, int anchorY) {
        if (pictureWidth < 1 || pictureHeight < 1 || pictureWidth > MAXIMUM_PICTURE_SIZE || pictureHeight > MAXIMUM_PICTURE_SIZE) {
            throw Exceptions.newIllegalArgumentException("The picture is {0} by {1} pixels; a sheet is at most {2} by {2}.", pictureWidth,
                                                         pictureHeight, MAXIMUM_PICTURE_SIZE);
        }

        if (frameWidth < 1 || frameHeight < 1 || frameWidth > pictureWidth || frameHeight > pictureHeight) {
            throw Exceptions.newIllegalArgumentException("The frame size {0} by {1} does not fit the picture of {2} by {3}.", frameWidth, frameHeight,
                                                         pictureWidth, pictureHeight);
        }

        if (!isScale(scale)) {
            throw Exceptions.newIllegalArgumentException("The scale {0} is not 1, 2 or 4.", scale);
        }

        if (frameWidth % scale != 0 || frameHeight % scale != 0) {
            throw Exceptions.newIllegalArgumentException("The scale {0} does not divide the frame size {1} by {2}.", scale, frameWidth, frameHeight);
        }

        if (directions != 1 && directions != TURNING_DIRECTIONS) {
            throw Exceptions.newIllegalArgumentException("The directions {0} are not 1 or {1}.", directions, TURNING_DIRECTIONS);
        }

        int columns = pictureWidth / frameWidth;
        int cells = columns * (pictureHeight / frameHeight);
        int count = frameCount == ALL_FRAMES ? cells - cells % directions : frameCount;

        if (count < 1 || count > cells) {
            throw Exceptions.newIllegalArgumentException("The frame count {0} is not from 1 to the {1} frames of the picture.", count, cells);
        }

        if (count % directions != 0) {
            throw Exceptions.newIllegalArgumentException("The frame count {0} is no multiple of the directions {1}.", count, directions);
        }

        int frameMapWidth = frameWidth / scale;
        int frameMapHeight = frameHeight / scale;
        int paddingLeft = 0;
        int paddingTop = 0;
        int canvasWidth = frameMapWidth;
        int canvasHeight = frameMapHeight;

        if (anchorX != NO_ANCHOR || anchorY != NO_ANCHOR) {
            int pixelX = anchorX == NO_ANCHOR ? frameWidth / 2 : anchorX;
            int pixelY = anchorY == NO_ANCHOR ? frameHeight / 2 : anchorY;

            if (pixelX < 0 || pixelX >= frameWidth || pixelY < 0 || pixelY >= frameHeight) {
                throw Exceptions.newIllegalArgumentException("The anchor ({0}, {1}) lies outside the frame of {2} by {3}.", pixelX, pixelY, frameWidth,
                                                             frameHeight);
            }

            int mapX = pixelX / scale;
            int mapY = pixelY / scale;
            int halfWidth = Math.max(mapX, frameMapWidth - mapX);
            int halfHeight = Math.max(mapY, frameMapHeight - mapY);

            paddingLeft  = halfWidth - mapX;
            paddingTop   = halfHeight - mapY;
            canvasWidth  = halfWidth * 2;
            canvasHeight = halfHeight * 2;
        }

        if (canvasWidth > ImageFileWriterUtils.MAXIMUM_GRP_SIZE || canvasHeight > ImageFileWriterUtils.MAXIMUM_GRP_SIZE) {
            throw Exceptions.newIllegalArgumentException("The canvas is {0} by {1} map pixels; a sheet's canvas is at most {2} by {2}.", canvasWidth,
                                                         canvasHeight, ImageFileWriterUtils.MAXIMUM_GRP_SIZE);
        }

        return new SheetLayout(frameWidth, frameHeight, count, directions, scale, columns, canvasWidth, canvasHeight, paddingLeft, paddingTop);
    }

    private static boolean isScale(int scale) {
        for (int value : SCALES) {
            if (value == scale) {
                return true;
            }
        }

        return false;
    }


    private final int frameWidth;
    private final int frameHeight;
    private final int frameCount;
    private final int directions;
    private final int scale;
    private final int columns;

    private final int canvasWidth;
    private final int canvasHeight;
    private final int paddingLeft;
    private final int paddingTop;

    private SheetLayout(int frameWidth, int frameHeight, int frameCount, int directions, int scale, int columns,
                        int canvasWidth, int canvasHeight, int paddingLeft, int paddingTop) {
        this.frameWidth  = frameWidth;
        this.frameHeight = frameHeight;
        this.frameCount  = frameCount;
        this.directions  = directions;
        this.scale       = scale;
        this.columns     = columns;

        this.canvasWidth  = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.paddingLeft  = paddingLeft;
        this.paddingTop   = paddingTop;
    }

    /**
     * @return the frame width in picture pixels
     */
    public int getFrameWidth() {
        return this.frameWidth;
    }

    /**
     * @return the frame height in picture pixels
     */
    public int getFrameHeight() {
        return this.frameHeight;
    }

    /**
     * @return the number of frames
     */
    public int getFrameCount() {
        return this.frameCount;
    }

    /**
     * @return the frames of a frame set: {@code 1} or {@value #TURNING_DIRECTIONS}
     */
    public int getDirections() {
        return this.directions;
    }

    /**
     * @return the picture pixels per map pixel
     */
    public int getScale() {
        return this.scale;
    }

    /**
     * @return the canvas width in map pixels
     */
    public int getCanvasWidth() {
        return this.canvasWidth;
    }

    /**
     * @return the canvas height in map pixels
     */
    public int getCanvasHeight() {
        return this.canvasHeight;
    }

    /**
     * @return the map pixels of the canvas left of a frame
     */
    public int getPaddingLeft() {
        return this.paddingLeft;
    }

    /**
     * @return the map pixels of the canvas above a frame
     */
    public int getPaddingTop() {
        return this.paddingTop;
    }

    /**
     * @param frame a frame, {@code 0} up to {@link #getFrameCount()} minus one
     * @return the picture column of the frame's left edge
     */
    public int getFrameX(int frame) {
        return frame % this.columns * this.frameWidth;
    }

    /**
     * @param frame a frame, {@code 0} up to {@link #getFrameCount()} minus one
     * @return the picture row of the frame's top edge
     */
    public int getFrameY(int frame) {
        return frame / this.columns * this.frameHeight;
    }
}
