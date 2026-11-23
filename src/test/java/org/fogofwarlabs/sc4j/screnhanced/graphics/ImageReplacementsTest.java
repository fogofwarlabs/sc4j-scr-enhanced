package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImagePool;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * Tests the image graphics a script replaces: GRPs and anims read from byte ranges and checked, the HD and HD2 rule of the graphics, the
 * graphics of an image type and of one unit's images, the unit's subunit, the end of a unit's graphics when its slot is freed, the frame sets an
 * image plays and an image type loops, and the shadow of a main image that draws a sheet.
 */
final class ImageReplacementsTest {
    private static final int UNIT_POOL_CAPACITY = 8;
    private static final int IMAGE_POOL_CAPACITY = 8;
    private static final int UNIT_INDEX = 3;
    private static final int OTHER_UNIT_INDEX = 4;
    private static final int SUBUNIT_INDEX = 5;
    private static final int GENERATION = 7;
    private static final int IMAGE_TYPE = 239;
    private static final int OTHER_IMAGE_TYPE = 240;
    /**
     * The bytes before the GRP in the byte range test.
     */
    private static final int PREFIX_SIZE = 5;
    /**
     * The offset of the frame data address of frame {@code 1} in {@link ImageFileScenario#createGrp(int)}.
     */
    private static final int SECOND_FRAME_DATA_OFFSET = 18;
    private static final int OUTSIDE_ADDRESS = 1000;
    private static final int IMAGE_TYPE_COUNT = 1000;
    private static final int PLAY_START = 10;
    private static final int FRAME_TIME = 3;
    private static final int DIRECTION = 3;
    private static final int TURNING_DIRECTIONS = 17;
    private static final int PALETTE_SIZE = 1024;

    private static @NotNull Unit allocateUnit(@NotNull UnitPool pool, int index, int generation) {
        pool.markLoaded(index, generation, 0, 0, 0, 0);

        Unit unit = pool.getUnit(index);

        unit.setSprite(new Sprite(index));

        return unit;
    }

    private static @NotNull SpriteImage addImage(@NotNull SpriteImagePool images, @NotNull Unit unit, int imageType) {
        SpriteImage image = images.allocate();
        Sprite sprite = unit.getSpriteOrNull();

        Assertions.assertNotNull(image);
        Assertions.assertNotNull(sprite);

        image.initializeNativeStorage(imageType, 0, 0, 0, 0);
        sprite.insertHeadImage(image);

        return image;
    }

    private static @NotNull ImageGraphics createGraphics(@NotNull ImageReplacements replacements) throws IOException {
        byte[] grp = ImageFileScenario.createGrp(0);

        return ImageGraphics.of(replacements.readGrp(grp, 0, grp.length, "unit.grp"), null, null);
    }

    /**
     * Creates the graphics of a one-frame sheet of a red square with a shadow picture.
     */
    private static @NotNull ImageGraphics createSheetGraphics(@NotNull ImageReplacements replacements) throws IOException {
        int[] colors = new int[4 * 4];

        Arrays.fill(colors, 0xFFFF0000);

        Picture picture = new Picture(4, 4, PixelLayer.ofRgba(colors));
        PictureSheet sheet = PictureSheet.create("square.png", picture, SheetLayout.create(4, 4, 4, 4, 1, 1, 1, SheetLayout.NO_ANCHOR,
                                                                                           SheetLayout.NO_ANCHOR), null, picture, null);
        PictureSheetConverter converter = new PictureSheetConverter(new byte[PALETTE_SIZE]);
        int[] frames = {0};

        return replacements.createSheetGraphics(sheet, frames, converter.createGrp(sheet, frames), converter.createShadowGrp(sheet, frames));
    }

    @Test
    void play_image_should_draw_frame_sets_by_game_frame_until_they_end() throws IOException {
        UnitPool pool = new UnitPool(UNIT_POOL_CAPACITY);
        SpriteImagePool images = new SpriteImagePool(IMAGE_POOL_CAPACITY);
        int[] frame = {PLAY_START};
        ImageReplacements replacements = new ImageReplacements(() -> pool, () -> frame[0], IMAGE_TYPE_COUNT, imageType -> imageType == IMAGE_TYPE);
        Unit unit = allocateUnit(pool, UNIT_INDEX, GENERATION);
        SpriteImage turning = addImage(images, unit, IMAGE_TYPE);
        SpriteImage still = addImage(images, unit, OTHER_IMAGE_TYPE);
        ImageGraphics graphics = createGraphics(replacements);

        turning.setFrameIndexOffset(DIRECTION);
        replacements.playImage(turning, new int[]{2, 5}, FRAME_TIME, false, null);
        replacements.playImage(still, new int[]{2, 5}, FRAME_TIME, true, graphics);

        Assertions.assertEquals(2 * TURNING_DIRECTIONS + DIRECTION, replacements.findImageFrame(turning), "a turning image adds its direction");
        Assertions.assertEquals(2, replacements.findImageFrame(still));
        Assertions.assertSame(graphics, replacements.findGraphics(still), "the play's graphics are drawn meanwhile");

        frame[0] = PLAY_START + FRAME_TIME;

        Assertions.assertEquals(5 * TURNING_DIRECTIONS + DIRECTION, replacements.findImageFrame(turning));

        frame[0] = PLAY_START + FRAME_TIME * 2;

        Assertions.assertEquals(-1, replacements.findImageFrame(turning), "an ended play draws the image's own frame");
        Assertions.assertEquals(2, replacements.findImageFrame(still), "a looping play starts over");

        replacements.stopPlayingImage(still);
        replacements.removeFreedUnits();

        Assertions.assertEquals(-1, replacements.findImageFrame(still));
        Assertions.assertNull(replacements.findGraphics(still), "a stopped play draws the image's own graphics");
        Assertions.assertThrows(IllegalArgumentException.class, () -> replacements.playImage(still, new int[0], 1, false, null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> replacements.playImage(still, new int[]{1}, 0, false, null));
    }

    @Test
    void set_image_frame_should_end_a_play_and_a_play_should_end_a_held_frame() {
        UnitPool pool = new UnitPool(UNIT_POOL_CAPACITY);
        SpriteImagePool images = new SpriteImagePool(IMAGE_POOL_CAPACITY);
        ImageReplacements replacements = new ImageReplacements(() -> pool, () -> PLAY_START, IMAGE_TYPE_COUNT, imageType -> false);
        SpriteImage image = addImage(images, allocateUnit(pool, UNIT_INDEX, GENERATION), IMAGE_TYPE);

        replacements.playImage(image, new int[]{4}, FRAME_TIME, true, null);
        replacements.setImageFrame(image, 7);

        Assertions.assertEquals(7, replacements.findImageFrame(image));

        replacements.holdImageFrame(image, 8);
        replacements.playImage(image, new int[]{4}, FRAME_TIME, true, null);

        Assertions.assertEquals(4, replacements.findImageFrame(image));

        replacements.stopPlayingImage(image);

        Assertions.assertEquals(-1, replacements.findImageFrame(image), "the play ended the held frame");
    }

    @Test
    void set_type_animation_should_loop_frame_sets_for_every_image_of_the_type() {
        UnitPool pool = new UnitPool(UNIT_POOL_CAPACITY);
        SpriteImagePool images = new SpriteImagePool(IMAGE_POOL_CAPACITY);
        int[] frame = {0};
        ImageReplacements replacements = new ImageReplacements(() -> pool, () -> frame[0], IMAGE_TYPE_COUNT, imageType -> false);
        SpriteImage image = addImage(images, allocateUnit(pool, UNIT_INDEX, GENERATION), IMAGE_TYPE);

        replacements.setTypeAnimation(IMAGE_TYPE, new int[]{1, 2}, 4);

        Assertions.assertEquals(1, replacements.findImageFrame(image));

        frame[0] = 4;

        Assertions.assertEquals(2, replacements.findImageFrame(image));

        frame[0] = 8;

        Assertions.assertEquals(1, replacements.findImageFrame(image));

        replacements.setImageFrame(image, 6);

        Assertions.assertEquals(6, replacements.findImageFrame(image), "an image's own frame override wins over the loop of its type");
        Assertions.assertThrows(IllegalArgumentException.class, () -> replacements.setTypeAnimation(IMAGE_TYPE_COUNT, new int[]{1}, 1));
    }

    @Test
    void find_graphics_should_draw_the_sheet_shadow_for_a_shadow_image_of_its_main_image() throws IOException {
        UnitPool pool = new UnitPool(UNIT_POOL_CAPACITY);
        SpriteImagePool images = new SpriteImagePool(IMAGE_POOL_CAPACITY);
        ImageReplacements replacements = new ImageReplacements(() -> pool, () -> 0, IMAGE_TYPE_COUNT, imageType -> false);
        Unit unit = allocateUnit(pool, UNIT_INDEX, GENERATION);
        Sprite sprite = unit.getSpriteOrNull();
        SpriteImage shadow = addImage(images, unit, OTHER_IMAGE_TYPE);
        SpriteImage main = addImage(images, unit, IMAGE_TYPE);
        ImageGraphics sheet = createSheetGraphics(replacements);

        Assertions.assertNotNull(sprite);
        Assertions.assertTrue(sheet.isSheet());
        Assertions.assertNotNull(sheet.getShadow());

        shadow.setModifier(SpriteImage.MODIFIER_SHADOW);
        sprite.setMainImage(main);

        Assertions.assertNull(replacements.findGraphics(shadow), "the shadow of installed graphics stays installed");

        replacements.replaceImage(IMAGE_TYPE, sheet);
        replacements.setImageFrame(main, 0);

        Assertions.assertSame(sheet.getShadow(), replacements.findGraphics(shadow));
        Assertions.assertEquals(0, replacements.findImageFrame(shadow), "the shadow draws the main image's frame");
        Assertions.assertSame(sheet, replacements.findGraphics(main));
    }

    @Test
    void read_grp_should_read_a_grp_file_inside_a_byte_range() throws IOException {
        byte[] grp = ImageFileScenario.createGrp(0);
        byte[] data = new byte[PREFIX_SIZE + grp.length + PREFIX_SIZE];

        System.arraycopy(grp, 0, data, PREFIX_SIZE, grp.length);

        ImageGrp read = new ImageReplacements(() -> null).readGrp(data, PREFIX_SIZE, grp.length, "range.grp");
        SpriteSheet frame = read.getFrame(1);

        Assertions.assertEquals("range.grp", read.getName());
        Assertions.assertEquals(ImageFileScenario.GRP_FRAME_COUNT, read.getFrameCount());
        Assertions.assertEquals(1, frame.getFrameCount());
        Assertions.assertSame(frame, read.getFrame(1), "a decoded frame is kept");
    }

    @Test
    void read_grp_should_reject_bytes_that_are_no_grp_or_whose_frames_cannot_be_read() {
        ImageReplacements replacements = new ImageReplacements(() -> null);
        byte[] zeros = new byte[ImageFileScenario.createGrp(0).length];
        byte[] outside = ImageFileScenario.createGrp(0);

        outside[SECOND_FRAME_DATA_OFFSET]     = (byte) OUTSIDE_ADDRESS;
        outside[SECOND_FRAME_DATA_OFFSET + 1] = (byte) (OUTSIDE_ADDRESS >>> Byte.SIZE);

        Assertions.assertThrows(IOException.class, () -> replacements.readGrp(zeros, 0, zeros.length, "zeros.grp"));
        Assertions.assertThrows(IOException.class, () -> replacements.readGrp(outside, 0, outside.length, "outside.grp"));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> replacements.readGrp(zeros, 1, zeros.length, "range.grp"));
    }

    @Test
    void read_anim_should_read_the_scale_and_reject_malformed_anims() throws IOException {
        ImageReplacements replacements = new ImageReplacements(() -> null);
        byte[] hd = ImageFileScenario.createAnim(ImageFileScenario.HD_SCALE);
        byte[] hd2 = ImageFileScenario.createAnim(ImageFileScenario.HD2_SCALE);
        byte[] broken = ImageFileScenario.createAnim(ImageFileScenario.HD_SCALE);

        // Breaks the DDS magic of the diffuse layer.
        broken[ImageFileScenario.ANIM_TEXTURE] = 0;

        Assertions.assertTrue(replacements.readAnim(hd, 0, hd.length, "unit.anim").isHd());
        Assertions.assertEquals(ImageFileScenario.HD2_SCALE, replacements.readAnim(hd2, 0, hd2.length, "unit2.anim").getScale());
        Assertions.assertThrows(IOException.class, () -> replacements.readAnim(broken, 0, broken.length, "broken.anim"));
        Assertions.assertThrows(IOException.class, () -> replacements.readAnim(new byte[hd.length], 0, hd.length, "zeros.anim"));
        Assertions.assertThrows(IOException.class, () -> replacements.readAnim(hd, 0, hd.length / 2, "short.anim"));
    }

    @Test
    void image_graphics_draw_the_anim_of_the_drawn_scale_else_the_other_one() throws IOException {
        ImageReplacements replacements = new ImageReplacements(() -> null);
        byte[] hdBytes = ImageFileScenario.createAnim(ImageFileScenario.HD_SCALE);
        byte[] hd2Bytes = ImageFileScenario.createAnim(ImageFileScenario.HD2_SCALE);
        ImageAnim hd = replacements.readAnim(hdBytes, 0, hdBytes.length, "unit.anim");
        ImageAnim hd2 = replacements.readAnim(hd2Bytes, 0, hd2Bytes.length, "unit2.anim");
        ImageGraphics both = ImageGraphics.of(null, hd, hd2);
        ImageGraphics hd2Only = ImageGraphics.of(null, null, hd2);

        Assertions.assertEquals(1, ImageFileScenario.awaitSheet(both, ImageFileScenario.HD_SCALE).getUnitsPerTexel());
        Assertions.assertEquals(2, ImageFileScenario.awaitSheet(both, ImageFileScenario.HD2_SCALE).getUnitsPerTexel());
        Assertions.assertEquals(2, ImageFileScenario.awaitSheet(hd2Only, ImageFileScenario.HD_SCALE).getUnitsPerTexel(),
                                "HD draws the HD2 anim without an HD anim");
        Assertions.assertTrue(hd2Only.hasAnimation());
        Assertions.assertNull(hd2Only.getGrp());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ImageGraphics.of(null, null, null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ImageGraphics.of(null, hd2, null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ImageGraphics.of(null, null, hd));
    }

    @Test
    void find_graphics_should_prefer_the_graphics_of_the_unit_over_those_of_the_image_type() throws IOException {
        UnitPool pool = new UnitPool(UNIT_POOL_CAPACITY);
        SpriteImagePool images = new SpriteImagePool(IMAGE_POOL_CAPACITY);
        ImageReplacements replacements = new ImageReplacements(() -> pool);
        Unit unit = allocateUnit(pool, UNIT_INDEX, GENERATION);
        Unit other = allocateUnit(pool, OTHER_UNIT_INDEX, GENERATION);
        Unit subunit = allocateUnit(pool, SUBUNIT_INDEX, GENERATION);
        SpriteImage unitImage = addImage(images, unit, IMAGE_TYPE);
        SpriteImage unitOverlay = addImage(images, unit, OTHER_IMAGE_TYPE);
        SpriteImage otherImage = addImage(images, other, IMAGE_TYPE);
        SpriteImage subunitImage = addImage(images, subunit, IMAGE_TYPE);
        ImageGraphics typeGraphics = createGraphics(replacements);
        ImageGraphics unitGraphics = createGraphics(replacements);

        unit.setSubunit(subunit);
        replacements.replaceImage(IMAGE_TYPE, typeGraphics);
        replacements.replaceUnitImage(unit, IMAGE_TYPE, unitGraphics);

        Assertions.assertSame(unitGraphics, replacements.findGraphics(unitImage));
        Assertions.assertSame(unitGraphics, replacements.findGraphics(subunitImage), "the subunit's images belong to the unit");
        Assertions.assertSame(typeGraphics, replacements.findGraphics(otherImage));
        Assertions.assertNull(replacements.findGraphics(unitOverlay), "an image of another type keeps its graphics");
        Assertions.assertSame(unitGraphics, replacements.findUnitImageGraphics(unit, IMAGE_TYPE));
        Assertions.assertSame(typeGraphics, replacements.findImageGraphics(IMAGE_TYPE));

        SpriteImage recreated = addImage(images, unit, IMAGE_TYPE);

        Assertions.assertSame(unitGraphics, replacements.findGraphics(recreated), "a new image of the unit takes the unit's graphics");

        replacements.restoreUnitImage(unit, IMAGE_TYPE);

        Assertions.assertSame(typeGraphics, replacements.findGraphics(unitImage));

        replacements.restoreImage(IMAGE_TYPE);

        Assertions.assertNull(replacements.findGraphics(unitImage));
        Assertions.assertNull(replacements.findGraphics(otherImage));
        Assertions.assertThrows(IllegalArgumentException.class, () -> replacements.replaceImage(-1, typeGraphics));
    }

    @Test
    void unit_graphics_end_when_the_unit_slot_is_freed() throws IOException {
        UnitPool pool = new UnitPool(UNIT_POOL_CAPACITY);
        SpriteImagePool images = new SpriteImagePool(IMAGE_POOL_CAPACITY);
        ImageReplacements replacements = new ImageReplacements(() -> pool);
        Unit unit = allocateUnit(pool, UNIT_INDEX, GENERATION);
        SpriteImage image = addImage(images, unit, IMAGE_TYPE);
        ImageGraphics graphics = createGraphics(replacements);

        replacements.replaceUnitImage(unit, IMAGE_TYPE, graphics);
        replacements.removeFreedUnits();

        Assertions.assertEquals(1, replacements.getUnitReplacementCount(), "a unit in its slot keeps its graphics");
        Assertions.assertSame(graphics, replacements.findGraphics(image));

        pool.recycleFinalized(UNIT_INDEX);

        Assertions.assertNull(replacements.findGraphics(image), "a freed slot's graphics no longer apply");

        allocateUnit(pool, UNIT_INDEX, GENERATION + 1);

        SpriteImage newImage = addImage(images, unit, IMAGE_TYPE);

        Assertions.assertNull(replacements.findGraphics(newImage), "a new unit in the slot does not take the graphics");
        Assertions.assertNull(replacements.findUnitImageGraphics(unit, IMAGE_TYPE));

        replacements.removeFreedUnits();

        Assertions.assertEquals(0, replacements.getUnitReplacementCount());
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            pool.recycleFinalized(UNIT_INDEX);
            replacements.replaceUnitImage(unit, IMAGE_TYPE, graphics);
        });
    }
}
