package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.data.format.image.ImageFileWriterUtils;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;

/**
 * Converts the frames of a {@link PictureSheet} into the formats the renderers and the simulation read: an SD GRP quantized to a tileset palette
 * and an HD or HD2 anim with a block-compressed {@code diffuse} layer and, for a sheet with a team color picture, a {@code teamcolor} layer.
 * <p>
 * Each output frame is one frame of the sheet ({@code frames}), so a sheet with fewer frame sets can follow the frame numbering of a script. A map
 * pixel of a GRP frame is the block of {@code scale} by {@code scale} picture pixels it covers: it is opaque when the block's mean alpha is at least
 * {@value #OPAQUE_ALPHA}, takes a team color index ({@code 8} to {@code 15} by brightness) when the block's mean team color strength is at least
 * {@value #TEAM_STRENGTH}, and else the nearest palette entry from {@value #FIRST_COLOR_INDEX} to {@value #LAST_COLOR_INDEX}, outside the team
 * colors and the color cycling ranges. The GRP depends on the pixels, the layout and the palette only.
 * <p>
 * The anim holds each sheet frame once in an atlas of cells padded to multiples of {@code 4} texels; a scale {@code 4} sheet makes an HD anim, a
 * scale {@code 2} sheet an HD2 anim and a scale {@code 1} sheet an HD2 anim with each pixel doubled. Every frame is placed by its canvas offset.
 */
public final class PictureSheetConverter {
    /**
     * The mean alpha from which a map pixel of a GRP frame is opaque.
     */
    public static final int OPAQUE_ALPHA = 128;
    /**
     * The mean team color strength from which a map pixel of a GRP frame takes a team color index.
     */
    public static final int TEAM_STRENGTH = 128;
    /**
     * The first team color index of a GRP.
     */
    public static final int FIRST_TEAM_INDEX = 8;
    /**
     * The number of team color indices of a GRP.
     */
    public static final int TEAM_INDEX_COUNT = 8;
    /**
     * The first palette entry a plain color takes.
     */
    public static final int FIRST_COLOR_INDEX = 16;
    /**
     * The last palette entry a plain color takes, below the color cycling range at the end of the palette.
     */
    public static final int LAST_COLOR_INDEX = 247;

    /**
     * The bytes of one entry of a terrain palette: red, green, blue and one unused byte.
     */
    private static final int PALETTE_ENTRY_SIZE = 4;
    private static final int PALETTE_SIZE = 256 * PALETTE_ENTRY_SIZE;
    /**
     * The bits per channel of the color lookup table.
     */
    private static final int LOOKUP_BITS = 5;
    private static final int LOOKUP_SHIFT = Byte.SIZE - LOOKUP_BITS;
    private static final int LOOKUP_MASK = (1 << LOOKUP_BITS) - 1;
    private static final int BYTE_MASK = 0xFF;
    private static final int ALPHA_SHIFT = 24;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    /**
     * The luma weights of red, green and blue, in thousandths.
     */
    private static final int RED_LUMA = 299;
    private static final int GREEN_LUMA = 587;
    private static final int BLUE_LUMA = 114;
    private static final int LUMA_SCALE = 1000;
    private static final int CHANNEL_COUNT = 256;
    /**
     * The scale of the HD anims and of the HD2 anims.
     */
    private static final int HD_SCALE = 4;
    private static final int HD2_SCALE = 2;
    /**
     * The texels of a DXT5 block side.
     */
    private static final int BLOCK_SIZE = 4;
    /**
     * The widest atlas an anim gets, in texels.
     */
    private static final int MAXIMUM_ATLAS_WIDTH = 8192;
    private static final @NotNull String DIFFUSE_LAYER = "diffuse";
    private static final @NotNull String TEAM_COLOR_LAYER = "teamcolor";

    /**
     * Counts the bytes of the block-compressed layers of the anims of frames of a sheet ({@link #createAnim}): one byte per texel and layer of
     * the atlas cell of each distinct sheet frame, the shadow anim included.
     *
     * @param layout    the frame layout
     * @param frames    the sheet frame of each anim frame
     * @param teamColor whether the sheet has a team color picture
     * @param shadow    whether the sheet has a shadow picture
     * @return the bytes
     */
    public static long countAnimBytes(@NotNull SheetLayout layout, int @NotNull [] frames, boolean teamColor, boolean shadow) {
        int factor = getTexelFactor(layout.getScale());
        long cell = (long) roundToBlock(layout.getFrameWidth() * factor) * roundToBlock(layout.getFrameHeight() * factor);
        int layers = 1 + (teamColor ? 1 : 0) + (shadow ? 1 : 0);
        long distinct = Arrays.stream(frames).distinct().count();

        return cell * distinct * layers;
    }

    /**
     * Creates the anim of frames of a sheet.
     *
     * @param sheet  the sheet
     * @param frames the sheet frame of each anim frame
     * @param shadow {@code true} for the anim of the shadow picture, {@code false} for that of the picture
     * @return the anim bytes
     * @throws IllegalArgumentException if a frame is outside the sheet
     */
    public static byte @NotNull [] createAnim(@NotNull PictureSheet sheet, int @NotNull [] frames, boolean shadow) {
        SheetLayout layout = sheet.getLayout();
        int scale = layout.getScale();
        int animScale = scale == HD_SCALE ? HD_SCALE : HD2_SCALE;
        int factor = getTexelFactor(scale);
        int cellWidth = roundToBlock(layout.getFrameWidth() * factor);
        int cellHeight = roundToBlock(layout.getFrameHeight() * factor);
        Map<Integer, Integer> cells = new LinkedHashMap<>();

        for (int frame : frames) {
            checkFrame(layout, frame);
            cells.putIfAbsent(frame, cells.size());
        }

        int columns = Math.clamp((int) Math.ceil(Math.sqrt(cells.size())), 1, Math.max(1, MAXIMUM_ATLAS_WIDTH / cellWidth));
        int rows = (cells.size() + columns - 1) / columns;
        int atlasWidth = columns * cellWidth;
        int atlasHeight = rows * cellHeight;
        boolean team = !shadow && sheet.hasTeamColor();
        byte[] diffuse = new byte[atlasWidth * atlasHeight * ImageFileWriterUtils.RGBA_BYTES];
        byte[] teamLayer = team ? new byte[diffuse.length] : null;

        for (Map.Entry<Integer, Integer> cell : cells.entrySet()) {
            int frame = cell.getKey();
            int cellX = cell.getValue() % columns * cellWidth;
            int cellY = cell.getValue() / columns * cellHeight;

            for (int y = 0; y < layout.getFrameHeight() * factor; y++) {
                for (int x = 0; x < layout.getFrameWidth() * factor; x++) {
                    int pictureX = x / factor;
                    int pictureY = y / factor;
                    int offset = ((cellY + y) * atlasWidth + cellX + x) * ImageFileWriterUtils.RGBA_BYTES;

                    if (shadow) {
                        diffuse[offset + 3] = (byte) sheet.getShadowCoverage(frame, pictureX, pictureY);
                    } else {
                        int color = sheet.getColor(frame, pictureX, pictureY);

                        diffuse[offset]     = (byte) (color >>> RED_SHIFT);
                        diffuse[offset + 1] = (byte) (color >>> GREEN_SHIFT);
                        diffuse[offset + 2] = (byte) color;
                        diffuse[offset + 3] = (byte) (color >>> ALPHA_SHIFT);
                    }

                    if (teamLayer != null) {
                        byte strength = (byte) sheet.getTeamStrength(frame, pictureX, pictureY);

                        teamLayer[offset]     = strength;
                        teamLayer[offset + 1] = strength;
                        teamLayer[offset + 2] = strength;
                        teamLayer[offset + 3] = strength;
                    }
                }
            }
        }

        int unitsPerTexel = ImageFileWriterUtils.ANIM_UNIT_SCALE / animScale;
        int frameWidthUnits = layout.getFrameWidth() / scale * ImageFileWriterUtils.ANIM_UNIT_SCALE;
        int frameHeightUnits = layout.getFrameHeight() / scale * ImageFileWriterUtils.ANIM_UNIT_SCALE;
        List<int[]> values = new ArrayList<>();

        for (int frame : frames) {
            int cell = cells.get(frame);

            values.add(new int[]{cell % columns * cellWidth * unitsPerTexel, cell / columns * cellHeight * unitsPerTexel,
                                 layout.getPaddingLeft() * ImageFileWriterUtils.ANIM_UNIT_SCALE,
                                 layout.getPaddingTop() * ImageFileWriterUtils.ANIM_UNIT_SCALE, frameWidthUnits, frameHeightUnits,
                                 ImageFileWriterUtils.ANIM_FRAME_CANVAS_PLACED});
        }

        Map<String, byte[]> layers = new LinkedHashMap<>();

        layers.put(DIFFUSE_LAYER, ImageFileWriterUtils.encodeDxt5(atlasWidth, atlasHeight, diffuse, true));

        if (teamLayer != null) {
            layers.put(TEAM_COLOR_LAYER, ImageFileWriterUtils.encodeDxt5(atlasWidth, atlasHeight, teamLayer));
        }

        return ImageFileWriterUtils.encodeAnim(animScale, layout.getCanvasWidth() * ImageFileWriterUtils.ANIM_UNIT_SCALE,
                                               layout.getCanvasHeight() * ImageFileWriterUtils.ANIM_UNIT_SCALE, values, layers);
    }

    /**
     * @return the anim texels per picture pixel of a sheet scale: {@code 2} for scale {@code 1}, else {@code 1}
     */
    private static int getTexelFactor(int scale) {
        return scale == 1 ? HD2_SCALE : 1;
    }

    private static int roundToBlock(int value) {
        return (value + BLOCK_SIZE - 1) / BLOCK_SIZE * BLOCK_SIZE;
    }

    private static void checkFrame(@NotNull SheetLayout layout, int frame) {
        if (frame < 0 || frame >= layout.getFrameCount()) {
            throw Exceptions.newIllegalArgumentException("The frame {0} is not from 0 to {1}.", frame, layout.getFrameCount() - 1);
        }
    }


    private final byte @NotNull [] palette;
    /**
     * The palette entry of each color of {@value #LOOKUP_BITS} bits per channel plus one, {@code 0} until it is found.
     */
    private final short @NotNull [] lookup;

    /**
     * Creates a {@link PictureSheetConverter} instance over a tileset palette.
     *
     * @param palette the {@code 1024} bytes of a terrain palette: red, green, blue and an unused byte per entry
     * @throws IllegalArgumentException if the palette has another size
     */
    public PictureSheetConverter(byte @NotNull [] palette) {
        if (palette.length != PALETTE_SIZE) {
            throw Exceptions.newIllegalArgumentException("A terrain palette holds {0} bytes, not {1}.", PALETTE_SIZE, palette.length);
        }

        this.palette = palette.clone();
        this.lookup  = new short[1 << LOOKUP_BITS * 3];
    }

    /**
     * Creates the GRP of frames of a sheet: the SD frames of the map when the sheet has them, else the picture quantized to the palette.
     *
     * @param sheet  the sheet
     * @param frames the sheet frame of each GRP frame
     * @return the GRP bytes
     * @throws IOException              if a frame of the SD frames cannot be decoded
     * @throws IllegalArgumentException if a frame is outside the sheet or there is no frame
     */
    public byte @NotNull [] createGrp(@NotNull PictureSheet sheet, int @NotNull [] frames) throws IOException {
        SheetLayout layout = sheet.getLayout();
        List<byte[]> canvases = new ArrayList<>();

        for (int frame : frames) {
            checkFrame(layout, frame);
            canvases.add(sheet.getSd() != null ? sheet.decodeSdFrame(frame) : this.quantizeFrame(sheet, frame, false));
        }

        if (canvases.isEmpty()) {
            throw Exceptions.newIllegalArgumentException("A GRP needs a frame.");
        }

        return ImageFileWriterUtils.encodeGrp(layout.getCanvasWidth(), layout.getCanvasHeight(), canvases);
    }

    /**
     * Creates the GRP of the shadow picture of frames of a sheet: every covered map pixel takes the palette entry nearest to black.
     *
     * @param sheet  the sheet, with a shadow picture
     * @param frames the sheet frame of each GRP frame
     * @return the GRP bytes
     * @throws IllegalArgumentException if a frame is outside the sheet or there is no frame
     * @throws IllegalStateException    if the sheet has no shadow picture
     */
    public byte @NotNull [] createShadowGrp(@NotNull PictureSheet sheet, int @NotNull [] frames) {
        SheetLayout layout = sheet.getLayout();
        List<byte[]> canvases = new ArrayList<>();

        for (int frame : frames) {
            checkFrame(layout, frame);
            canvases.add(this.quantizeFrame(sheet, frame, true));
        }

        if (canvases.isEmpty()) {
            throw Exceptions.newIllegalArgumentException("A GRP needs a frame.");
        }

        return ImageFileWriterUtils.encodeGrp(layout.getCanvasWidth(), layout.getCanvasHeight(), canvases);
    }

    /**
     * Quantizes a terrain picture to the classic pixels of a megatile: each of the {@code 32x32} map pixels is the block of picture pixels it
     * covers ({@code width / 32} by {@code height / 32}); it is drawn when the block's mean alpha is at least {@value #OPAQUE_ALPHA}, in the
     * nearest palette entry from {@value #FIRST_COLOR_INDEX} to {@value #LAST_COLOR_INDEX}. The pixels depend on the picture and the palette only.
     *
     * @param picture  the picture, its width and height multiples of {@code 32}
     * @param coverage receives the {@code 32x32} coverage, rows from the top, {@code 1} where a pixel is drawn and {@code 0} elsewhere
     * @return the {@code 32x32} palette indices, rows from the top, {@code 0} where no pixel is drawn
     * @throws IllegalArgumentException if a side of the picture is not a multiple of {@code 32} or the coverage is not {@code 32x32}
     */
    public byte @NotNull [] quantizeTile(@NotNull Picture picture, byte @NotNull [] coverage) {
        int tileSize = TerrainTileset.TILE_SIZE;

        if (picture.getWidth() % tileSize != 0 || picture.getHeight() % tileSize != 0 || coverage.length != tileSize * tileSize) {
            throw Exceptions.newIllegalArgumentException("A tile picture is a multiple of {0} pixels wide and high, not {1}x{2}.", tileSize,
                                                         picture.getWidth(), picture.getHeight());
        }

        int blockWidth = picture.getWidth() / tileSize;
        int blockHeight = picture.getHeight() / tileSize;
        int blockPixels = blockWidth * blockHeight;
        byte[] pixels = new byte[tileSize * tileSize];

        for (int mapY = 0; mapY < tileSize; mapY++) {
            for (int mapX = 0; mapX < tileSize; mapX++) {
                long alpha = 0;
                long red = 0;
                long green = 0;
                long blue = 0;

                for (int y = mapY * blockHeight; y < (mapY + 1) * blockHeight; y++) {
                    for (int x = mapX * blockWidth; x < (mapX + 1) * blockWidth; x++) {
                        int color = picture.getColor().getValue(y * picture.getWidth() + x);
                        int pixelAlpha = color >>> ALPHA_SHIFT;

                        alpha += pixelAlpha;
                        red += (color >>> RED_SHIFT & BYTE_MASK) * pixelAlpha;
                        green += (color >>> GREEN_SHIFT & BYTE_MASK) * pixelAlpha;
                        blue += (color & BYTE_MASK) * pixelAlpha;
                    }
                }

                int offset = mapY * tileSize + mapX;

                if (alpha < (long) OPAQUE_ALPHA * blockPixels) {
                    coverage[offset] = 0;

                    continue;
                }

                pixels[offset]   = (byte) this.findNearest((int) (red / alpha), (int) (green / alpha), (int) (blue / alpha));
                coverage[offset] = 1;
            }
        }

        return pixels;
    }

    /**
     * Quantizes one frame onto its canvas.
     *
     * @param shadow {@code true} to quantize the shadow picture, {@code false} for the picture
     * @return the palette indices on the canvas, row-major, {@code 0} transparent
     */
    private byte @NotNull [] quantizeFrame(@NotNull PictureSheet sheet, int frame, boolean shadow) {
        SheetLayout layout = sheet.getLayout();
        int scale = layout.getScale();
        int blockPixels = scale * scale;
        byte[] canvas = new byte[layout.getCanvasWidth() * layout.getCanvasHeight()];
        int shadowIndex = shadow ? this.findNearest(0, 0, 0) : 0;

        for (int mapY = 0; mapY < layout.getFrameHeight() / scale; mapY++) {
            for (int mapX = 0; mapX < layout.getFrameWidth() / scale; mapX++) {
                int alpha = 0;
                int red = 0;
                int green = 0;
                int blue = 0;
                int strength = 0;

                for (int y = mapY * scale; y < (mapY + 1) * scale; y++) {
                    for (int x = mapX * scale; x < (mapX + 1) * scale; x++) {
                        if (shadow) {
                            alpha += sheet.getShadowCoverage(frame, x, y);

                            continue;
                        }

                        int color = sheet.getColor(frame, x, y);
                        int pixelAlpha = color >>> ALPHA_SHIFT;

                        alpha += pixelAlpha;
                        red += (color >>> RED_SHIFT & BYTE_MASK) * pixelAlpha;
                        green += (color >>> GREEN_SHIFT & BYTE_MASK) * pixelAlpha;
                        blue += (color & BYTE_MASK) * pixelAlpha;
                        strength += sheet.getTeamStrength(frame, x, y);
                    }
                }

                if (alpha < OPAQUE_ALPHA * blockPixels) {
                    continue;
                }

                int index;

                if (shadow) {
                    index = shadowIndex;
                } else {
                    int meanRed = red / alpha;
                    int meanGreen = green / alpha;
                    int meanBlue = blue / alpha;

                    if (strength >= TEAM_STRENGTH * blockPixels) {
                        int luma = (meanRed * RED_LUMA + meanGreen * GREEN_LUMA + meanBlue * BLUE_LUMA) / LUMA_SCALE;

                        index = FIRST_TEAM_INDEX + (CHANNEL_COUNT - 1 - luma) * TEAM_INDEX_COUNT / CHANNEL_COUNT;
                    } else {
                        index = this.findNearest(meanRed, meanGreen, meanBlue);
                    }
                }

                canvas[(layout.getPaddingTop() + mapY) * layout.getCanvasWidth() + layout.getPaddingLeft() + mapX] = (byte) index;
            }
        }

        return canvas;
    }

    /**
     * Finds the palette entry of a color: the entry from {@value #FIRST_COLOR_INDEX} to {@value #LAST_COLOR_INDEX} nearest by squared RGB
     * distance to the color with its channels cut to {@value #LOOKUP_BITS} bits, the lowest entry of equal distance.
     */
    private int findNearest(int red, int green, int blue) {
        int key = (red >>> LOOKUP_SHIFT) << LOOKUP_BITS * 2 | (green >>> LOOKUP_SHIFT) << LOOKUP_BITS | blue >>> LOOKUP_SHIFT;
        int found = this.lookup[key];

        if (found != 0) {
            return found - 1;
        }

        int keyRed = (key >>> LOOKUP_BITS * 2 & LOOKUP_MASK) << LOOKUP_SHIFT;
        int keyGreen = (key >>> LOOKUP_BITS & LOOKUP_MASK) << LOOKUP_SHIFT;
        int keyBlue = (key & LOOKUP_MASK) << LOOKUP_SHIFT;
        int best = FIRST_COLOR_INDEX;
        int bestDistance = Integer.MAX_VALUE;

        for (int index = FIRST_COLOR_INDEX; index <= LAST_COLOR_INDEX; index++) {
            int offset = index * PALETTE_ENTRY_SIZE;
            int redDistance = (this.palette[offset] & BYTE_MASK) - keyRed;
            int greenDistance = (this.palette[offset + 1] & BYTE_MASK) - keyGreen;
            int blueDistance = (this.palette[offset + 2] & BYTE_MASK) - keyBlue;
            int distance = redDistance * redDistance + greenDistance * greenDistance + blueDistance * blueDistance;

            if (distance < bestDistance) {
                best         = index;
                bestDistance = distance;
            }
        }

        this.lookup[key] = (short) (best + 1);

        return best;
    }
}
