package org.fogofwarlabs.sc4j.screnhanced.graphics;

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

/**
 * Tests the image graphics a script replaces: GRPs and anims read from byte ranges and checked, the HD and HD2 rule of the graphics, the
 * graphics of an image type and of one unit's images, the unit's subunit, and the end of a unit's graphics when its slot is freed.
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
