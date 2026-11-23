package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests the frame layout of a sheet: frame counts and positions, turning frame sets, the scale, the canvas an anchor widens, the limits, and the
 * extra pictures a sheet checks against its picture.
 */
final class SheetLayoutTest {
    private static @NotNull SheetLayout create(int pictureWidth, int pictureHeight, int frameWidth, int frameHeight, int frameCount, int directions,
                                               int scale, int anchorX, int anchorY) {
        return SheetLayout.create(pictureWidth, pictureHeight, frameWidth, frameHeight, frameCount, directions, scale, anchorX, anchorY);
    }

    private static @NotNull Picture createPicture(int width, int height) {
        return new Picture(width, height, PixelLayer.ofRgba(new int[width * height]));
    }

    @Test
    void create_should_cut_every_whole_frame_row_by_row() {
        SheetLayout layout = create(70, 40, 16, 16, SheetLayout.ALL_FRAMES, 1, 1, SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR);

        Assertions.assertEquals(8, layout.getFrameCount());
        Assertions.assertEquals(16, layout.getCanvasWidth());
        Assertions.assertEquals(16, layout.getCanvasHeight());
        Assertions.assertEquals(16, layout.getFrameX(5));
        Assertions.assertEquals(16, layout.getFrameY(5));
        Assertions.assertEquals(3, create(70, 40, 16, 16, 3, 1, 1, SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR).getFrameCount());
    }

    @Test
    void create_should_keep_whole_frame_sets_of_a_turning_sheet() {
        SheetLayout layout = create(17 * 8, 24, 8, 8, SheetLayout.ALL_FRAMES, SheetLayout.TURNING_DIRECTIONS, 1, SheetLayout.NO_ANCHOR,
                                    SheetLayout.NO_ANCHOR);

        Assertions.assertEquals(3 * SheetLayout.TURNING_DIRECTIONS, layout.getFrameCount());
        Assertions.assertEquals(SheetLayout.TURNING_DIRECTIONS, layout.getDirections());
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(17 * 8, 24, 8, 8, 20, SheetLayout.TURNING_DIRECTIONS, 1,
                                                                             SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR));
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(64, 64, 8, 8, 1, 4, 1, SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR));
    }

    @Test
    void create_should_divide_the_frame_size_by_the_scale() {
        SheetLayout layout = create(128, 64, 64, 32, SheetLayout.ALL_FRAMES, 1, 4, SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR);

        Assertions.assertEquals(16, layout.getCanvasWidth());
        Assertions.assertEquals(8, layout.getCanvasHeight());
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(128, 64, 30, 32, 1, 1, 4, SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR));
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(128, 64, 32, 32, 1, 1, 3, SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR));
    }

    @Test
    void create_should_widen_the_canvas_to_center_the_anchor() {
        SheetLayout foot = create(40, 80, 20, 40, SheetLayout.ALL_FRAMES, 1, 1, 10, 36);
        SheetLayout head = create(40, 80, 20, 40, SheetLayout.ALL_FRAMES, 1, 1, 6, 4);

        Assertions.assertEquals(20, foot.getCanvasWidth());
        Assertions.assertEquals(72, foot.getCanvasHeight());
        Assertions.assertEquals(0, foot.getPaddingTop());
        Assertions.assertEquals(28, head.getCanvasWidth());
        Assertions.assertEquals(8, head.getPaddingLeft());
        Assertions.assertEquals(72, head.getCanvasHeight());
        Assertions.assertEquals(32, head.getPaddingTop());
        Assertions.assertEquals(foot.getCanvasHeight() / 2, foot.getPaddingTop() + 36);
    }

    @Test
    void create_should_refuse_a_canvas_past_255_and_a_frame_outside_the_picture() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(512, 256, 256, 256, 1, 1, 1, SheetLayout.NO_ANCHOR,
                                                                             SheetLayout.NO_ANCHOR));
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(200, 200, 200, 200, 1, 1, 1, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(32, 32, 64, 16, 1, 1, 1, SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR));
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(32, 32, 16, 16, 5, 1, 1, SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR));
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(32, 32, 16, 16, 1, 1, 1, 16, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(SheetLayout.MAXIMUM_PICTURE_SIZE + 1, 16, 16, 16, 1, 1, 1,
                                                                             SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR));
    }

    @Test
    void picture_sheet_should_refuse_extra_pictures_of_another_size() {
        Picture picture = createPicture(32, 16);
        SheetLayout layout = create(32, 16, 16, 16, SheetLayout.ALL_FRAMES, 1, 1, SheetLayout.NO_ANCHOR, SheetLayout.NO_ANCHOR);

        Assertions.assertThrows(IllegalArgumentException.class, () -> PictureSheet.create("tree", picture, layout, createPicture(16, 16), null, null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> PictureSheet.create("tree", picture, layout, null, createPicture(32, 8), null));
        Assertions.assertTrue(PictureSheet.create("tree", picture, layout, createPicture(32, 16), createPicture(32, 16), null).hasShadow());
    }
}
