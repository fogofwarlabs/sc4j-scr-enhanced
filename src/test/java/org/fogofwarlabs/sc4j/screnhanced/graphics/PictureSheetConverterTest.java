package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.scr.data.format.image.AnimFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Tests the conversion of a sheet's frames: the GRP's frame count and canvas, its transparent, color and team color indices, the anim's scale,
 * layers and frames, and the frame order a script gives.
 */
final class PictureSheetConverterTest {
    private static final int FRAME_SIZE = 4;
    private static final int OPAQUE_RED = 0xFFFF0000;
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;
    private static final int TEAM_STRENGTH = 0xFF000000;
    private static final int PALETTE_ENTRY_SIZE = 4;
    private static final int PALETTE_ENTRIES = 256;

    /**
     * Creates a palette whose entry {@code n} is red {@code n} and black otherwise.
     */
    private static byte @NotNull [] createPalette() {
        byte[] palette = new byte[PALETTE_ENTRIES * PALETTE_ENTRY_SIZE];

        for (int index = 0; index < PALETTE_ENTRIES; index++) {
            palette[index * PALETTE_ENTRY_SIZE] = (byte) index;
        }

        return palette;
    }

    /**
     * Creates a sheet of two frames side by side: frame {@code 0} red in its left half and transparent in its right half, frame {@code 1} white
     * with full team color strength.
     */
    private static @NotNull PictureSheet createSheet(int scale) {
        int side = FRAME_SIZE * scale;
        int[] colors = new int[side * 2 * side];
        int[] team = new int[colors.length];

        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side * 2; x++) {
                int offset = y * side * 2 + x;

                if (x < side / 2) {
                    colors[offset] = OPAQUE_RED;
                } else if (x >= side) {
                    colors[offset] = OPAQUE_WHITE;
                    team[offset]   = TEAM_STRENGTH;
                }
            }
        }

        Picture picture = new Picture(side * 2, side, PixelLayer.ofRgba(colors));
        Picture teamColor = new Picture(side * 2, side, PixelLayer.ofRgba(team));
        SheetLayout layout = SheetLayout.create(side * 2, side, side, side, SheetLayout.ALL_FRAMES, 1, scale, SheetLayout.NO_ANCHOR,
                                                SheetLayout.NO_ANCHOR);

        return PictureSheet.create("sheet.png", picture, layout, teamColor, null, null);
    }

    @Test
    void create_grp_should_quantize_each_frame_onto_the_canvas() throws IOException {
        PictureSheetConverter converter = new PictureSheetConverter(createPalette());
        GrpFile grp = GrpFile.parse(converter.createGrp(createSheet(2), new int[]{0, 1, 1}));
        byte[] first = grp.decodeFrame(0).getPixels();
        byte[] second = grp.decodeFrame(1).getPixels();

        Assertions.assertEquals(3, grp.getFrameCount());
        Assertions.assertEquals(FRAME_SIZE, grp.getWidth());
        Assertions.assertEquals(FRAME_SIZE, grp.getHeight());
        Assertions.assertEquals(PictureSheetConverter.LAST_COLOR_INDEX, first[0] & 0xFF, "red takes the nearest entry outside the reserved ones");
        Assertions.assertEquals(0, first[FRAME_SIZE - 1], "a transparent pixel is index 0");
        Assertions.assertEquals(PictureSheetConverter.FIRST_TEAM_INDEX, second[0], "a bright team color pixel takes the first team index");
        Assertions.assertEquals(FRAME_SIZE / 2, grp.getFrameWidth(0), "the frame rectangle covers the opaque pixels");
    }

    @Test
    void create_anim_should_hold_one_frame_per_given_frame_with_the_team_color_layer() throws IOException {
        AnimFile hd = AnimFile.parse(PictureSheetConverter.createAnim(createSheet(4), new int[]{1, 0, 1, 1}, false));
        AnimFile hd2 = AnimFile.parse(PictureSheetConverter.createAnim(createSheet(1), new int[]{0, 1}, false));

        Assertions.assertEquals(EmbeddedAnim.HD_SCALE, hd.getScale());
        Assertions.assertEquals(4, hd.getEntry(0).getFrameCount());
        Assertions.assertEquals(hd.getEntry(0).getFrameX(0), hd.getEntry(0).getFrameX(2), "equal frames share their atlas cell");
        Assertions.assertTrue(hd.getEntry(0).hasLayer(hd.findLayer("teamcolor")));
        Assertions.assertEquals(EmbeddedAnim.HD2_SCALE, hd2.getScale(), "a scale 1 sheet makes an HD2 anim");
        Assertions.assertEquals(FRAME_SIZE * 4, hd2.getEntry(0).getWidth(), "the canvas is in anim units");
        Assertions.assertDoesNotThrow(() -> EmbeddedAnim.check(PictureSheetConverter.createAnim(createSheet(2), new int[]{0}, false)));
    }

    @Test
    void count_anim_bytes_should_count_one_byte_per_texel_and_layer() {
        SheetLayout layout = SheetLayout.create(64, 64, 32, 32, SheetLayout.ALL_FRAMES, 1, 2, SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR);

        Assertions.assertEquals(4L * 32 * 32, PictureSheetConverter.countAnimBytes(layout, false, false));
        Assertions.assertEquals(4L * 32 * 32 * 3, PictureSheetConverter.countAnimBytes(layout, true, true));
    }

    @Test
    void create_grp_should_refuse_a_frame_outside_the_sheet() {
        PictureSheetConverter converter = new PictureSheetConverter(createPalette());

        Assertions.assertThrows(IllegalArgumentException.class, () -> converter.createGrp(createSheet(1), new int[]{2}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new PictureSheetConverter(new byte[3]));
    }
}
