package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageAnimation;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImagePool;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySink;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStorageRegion;
import org.fogofwarlabs.sc4j.screnhanced.memory.ClassicGrpTableStructure;
import org.fogofwarlabs.sc4j.screnhanced.memory.ClassicHeap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Checks the classic image graphics rule with a GRP that EUD writes place in memory: a new image takes the GRP table entry written before it, an
 * image entry's pointer applies to the image that held the entry at the write, the GRP decodes from its absolute frame addresses frame by frame,
 * written bytes decode the frames they hold again, and a written frame applies to images whose GRP can be read. Anims in memory: a pointer to an
 * anim or a GRP that links anims gives the HD and HD2 sheets with their fallback, a write parses the anim again, a malformed anim gives none, and a
 * copied installed table pointer draws that entry's image.
 */
final class ClassicImageGraphicsTest {
    /**
     * The address the GRP bytes are written to and the size of the memory there.
     */
    private static final int GRP_ADDRESS = 0x58A364;
    private static final int GRP_MEMORY_SIZE = 0x100;
    /**
     * The address of the memory a GRP with more frames than a WORD's high bit is written to.
     */
    private static final int LARGE_GRP_ADDRESS = 0x0F000000;
    /**
     * The frame count of the large GRP.
     */
    private static final int LARGE_FRAME_COUNT = 40000;
    /**
     * The image type whose table entry the tests write.
     */
    private static final int IMAGE_TYPE = 3;
    private static final int POOL_CAPACITY = 5000;
    /**
     * The offset in the GRP of the first pixel of frame {@code 0}, which {@link #a_write_decodes_the_frames_whose_bytes_it_changes} rewrites.
     */
    private static final int FIRST_PIXEL_OFFSET = 27;
    /**
     * The frame index the frame tests write, and a render function without a known draw function.
     */
    private static final int WRITTEN_FRAME = 5;
    private static final int UNKNOWN_RENDER_FUNCTION = 0x401000;
    /**
     * The frame a script sets, other than {@link #WRITTEN_FRAME}.
     */
    private static final int SCRIPT_FRAME = 3;

    /**
     * The memory the anim tests write to, the place of their GRP in it and of their anims after it.
     */
    private static final int ANIM_MEMORY = 0x0F000000;
    private static final int ANIM_MEMORY_SIZE = 0x1000;
    private static final int LINKED_GRP_ADDRESS = ANIM_MEMORY + 0x10;
    private static final int HD_ANIM_OFFSET = 0x100;
    private static final int HD2_ANIM_OFFSET = 0x400;
    /**
     * The image type whose installed table pointer the copy test copies.
     */
    private static final int COPIED_IMAGE_TYPE = 7;
    /**
     * The offsets of the frame data addresses of frame {@code 0} and frame {@code 1} in the GRP of {@link ImageFileScenario#createGrp(int)}.
     */
    private static final int FIRST_FRAME_DATA_OFFSET = 10;
    private static final int SECOND_FRAME_DATA_OFFSET = 18;
    /**
     * The offsets of the HD and the HD2 anim offset in the link before a GRP.
     */
    private static final int LINK_HD_OFFSET = 4;
    private static final int LINK_HD2_OFFSET = 8;
    /**
     * The time a test waits for a parse that gives no sheet.
     */
    private static final long FAILED_PARSE_WAIT_MILLISECONDS = 200;
    /**
     * The time a test waits for a new parse of a written anim.
     */
    private static final long PARSE_TIMEOUT_NANOSECONDS = 10_000_000_000L;

    /**
     * Creates a GRP of {@link #LARGE_FRAME_COUNT} one-pixel frames on a 1x1 canvas at {@link #LARGE_GRP_ADDRESS}: every frame draws palette index
     * {@code 4} but the last, which draws {@code 9}.
     */
    private static byte @NotNull [] createLargeGrp() {
        int tableEnd = 6 + LARGE_FRAME_COUNT * 8;
        int firstData = tableEnd + 2;
        int lastData = firstData + 4;
        ByteBuffer grp = ByteBuffer.allocate(lastData + 4).order(ByteOrder.LITTLE_ENDIAN);

        grp.putShort((short) LARGE_FRAME_COUNT).putShort((short) 1).putShort((short) 1);

        for (int frame = 0; frame < LARGE_FRAME_COUNT; frame++) {
            int data = frame == LARGE_FRAME_COUNT - 1 ? lastData : firstData;

            grp.put((byte) 0).put((byte) 0).put((byte) 1).put((byte) 1).putInt(LARGE_GRP_ADDRESS + data);
        }

        // Each frame: one line offset and a literal run of one.
        grp.position(firstData);
        grp.putShort((short) 2).put((byte) 1).put((byte) 4);
        grp.putShort((short) 2).put((byte) 1).put((byte) 9);

        return grp.array();
    }

    private static @NotNull ClassicAddressSpace createAnimSpace() {
        ClassicAddressSpace space = createSpace();

        space.add(new ClassicStorageRegion(ANIM_MEMORY, ANIM_MEMORY_SIZE));

        return space;
    }

    private static void writeBytes(@NotNull ClassicAddressSpace space, int address, byte @NotNull [] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        for (int offset = 0; offset < bytes.length; offset += Integer.BYTES) {
            space.writeDword(address + offset, buffer.getInt(offset));
        }
    }

    private static @NotNull ClassicAddressSpace createSpace() {
        ClassicAddressSpace space = new ClassicAddressSpace();

        space.add(ClassicStorageRegion.createImageTableRegion());
        space.addAll(ClassicStorageRegion.createEmulatorStorageRegions());
        space.add(new ClassicStorageRegion(GRP_ADDRESS, GRP_MEMORY_SIZE));

        return space;
    }

    private static @NotNull SpriteImage createImage(@NotNull SpriteImagePool pool) {
        SpriteImage image = pool.allocate();

        Assertions.assertNotNull(image);

        image.initializeNativeStorage(IMAGE_TYPE, 0, 0, 0, 0);

        return image;
    }

    private static void writeSlotPointer(@NotNull ClassicAddressSpace space, @NotNull SpriteImage image, int pointer) {
        space.writeDword(ClassicImageGraphics.IMAGE_ARRAY + image.getIndex() * ClassicImageGraphics.IMAGE_ENTRY_SIZE +
                         ClassicImageGraphics.IMAGE_GRP_OFFSET, pointer);
    }

    private static byte readPixel(@NotNull ScrImageSheet sheet, int frameIndex, int pixel) throws IOException {
        SpriteSheet frameSheet = sheet.getFrame(frameIndex);
        SpriteFrame frame = frameSheet.getFrame(0);
        byte[] pixels = new byte[frame.getWidth() * frame.getHeight()];

        frameSheet.getColor().copyBytes(frame.getPixelOffset(), pixels, 0, pixels.length);

        return pixels[pixel];
    }


    @Test
    void a_new_image_takes_the_table_entry_written_before_it() throws IOException {
        ClassicAddressSpace space = createSpace();
        SpriteImagePool pool = new SpriteImagePool(POOL_CAPACITY);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        writeBytes(space, GRP_ADDRESS, ImageFileScenario.createGrp(GRP_ADDRESS));

        SpriteImage before = createImage(pool);

        space.writeDword(ClassicImageGraphics.GRP_TABLE + IMAGE_TYPE * Integer.BYTES, GRP_ADDRESS);

        SpriteImage after = createImage(pool);
        ScrImageSheet sheet = graphics.findImageSheet(after);

        Assertions.assertEquals(0, space.readDword(ClassicImageGraphics.GRP_TABLE + IMAGE_TYPE * Integer.BYTES));
        Assertions.assertNull(graphics.findImageSheet(before));
        Assertions.assertNotNull(sheet);
        Assertions.assertEquals(2, sheet.getFrameCount());
        Assertions.assertEquals(4, sheet.getFrame(0).getWidth());
        Assertions.assertEquals(3, sheet.getFrame(1).getHeight());
        Assertions.assertEquals(1, sheet.getFrame(1).getFrameCount());
        Assertions.assertEquals(5, readPixel(sheet, 0, 0));
        Assertions.assertEquals(7, readPixel(sheet, 0, 3));
        Assertions.assertEquals(0, readPixel(sheet, 1, 0));
        Assertions.assertEquals(9, readPixel(sheet, 1, 2));
        Assertions.assertEquals(1, sheet.getFrame(1).getFrame(0).getX());
        Assertions.assertThrows(IOException.class, () -> sheet.getFrame(2));

        space.writeDword(ClassicImageGraphics.GRP_TABLE + IMAGE_TYPE * Integer.BYTES, 0);

        Assertions.assertNotNull(graphics.findImageSheet(after));
        Assertions.assertNull(graphics.findImageSheet(createImage(pool)));
    }

    @Test
    void an_image_entry_pointer_applies_to_the_image_that_held_the_entry() {
        ClassicAddressSpace space = createSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        writeBytes(space, GRP_ADDRESS, ImageFileScenario.createGrp(GRP_ADDRESS));

        SpriteImage image = createImage(pool);

        writeSlotPointer(space, image, GRP_ADDRESS);

        Assertions.assertNotNull(graphics.findImageSheet(image));

        pool.recycle(image);

        Assertions.assertSame(image, createImage(pool));
        Assertions.assertNull(graphics.findImageSheet(image));
    }

    @Test
    void a_write_decodes_the_frames_whose_bytes_it_changes() throws IOException {
        ClassicAddressSpace space = createSpace();
        SpriteImagePool pool = new SpriteImagePool(POOL_CAPACITY);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);
        byte[] grp = ImageFileScenario.createGrp(GRP_ADDRESS);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        space.writeDword(ClassicImageGraphics.GRP_TABLE + IMAGE_TYPE * Integer.BYTES, GRP_ADDRESS);

        SpriteImage image = createImage(pool);

        Assertions.assertNull(graphics.findImageSheet(image), "zero bytes are no GRP");

        writeBytes(space, GRP_ADDRESS, grp);

        ScrImageSheet sheet = graphics.findImageSheet(image);

        Assertions.assertNotNull(sheet);

        SpriteSheet first = sheet.getFrame(0);
        SpriteSheet second = sheet.getFrame(1);

        Assertions.assertSame(first, sheet.getFrame(0));

        grp[FIRST_PIXEL_OFFSET] = 4;

        int changed = FIRST_PIXEL_OFFSET & -Integer.BYTES;

        space.writeDword(GRP_ADDRESS + changed, ByteBuffer.wrap(grp).order(ByteOrder.LITTLE_ENDIAN).getInt(changed));

        SpriteSheet changedFirst = sheet.getFrame(0);

        Assertions.assertNotSame(first, changedFirst);
        Assertions.assertEquals(4, readPixel(sheet, 0, 0));
        Assertions.assertSame(second, sheet.getFrame(1), "a frame whose bytes are unchanged is kept");

        space.writeDword(GRP_ADDRESS, 1 | 4 << Short.SIZE);

        Assertions.assertEquals(1, sheet.getFrameCount(), "a header write reads the GRP again");
        Assertions.assertNotSame(changedFirst, sheet.getFrame(0));
    }

    @Test
    void a_grp_takes_a_word_of_frames_and_decodes_the_drawn_ones() throws IOException {
        ClassicAddressSpace space = createSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);
        byte[] grp = createLargeGrp();

        space.add(new ClassicStorageRegion(LARGE_GRP_ADDRESS, grp.length));
        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        writeBytes(space, LARGE_GRP_ADDRESS, grp);

        SpriteImage image = createImage(pool);

        writeSlotPointer(space, image, LARGE_GRP_ADDRESS);

        ScrImageSheet sheet = graphics.findImageSheet(image);

        Assertions.assertNotNull(sheet);
        Assertions.assertEquals(LARGE_FRAME_COUNT, sheet.getFrameCount());
        Assertions.assertEquals(9, readPixel(sheet, LARGE_FRAME_COUNT - 1, 0));
        Assertions.assertEquals(4, readPixel(sheet, LARGE_FRAME_COUNT - 2, 0));
    }

    @Test
    void a_written_frame_applies_until_the_image_changes_its_own_frame() {
        ClassicAddressSpace space = createSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);
        int frameField = ClassicImageGraphics.IMAGE_ARRAY + ClassicImageGraphics.IMAGE_FRAME_OFFSET;

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        space.writeDword(frameField, WRITTEN_FRAME << Short.SIZE);

        SpriteImage image = createImage(pool);

        Assertions.assertEquals(-1, graphics.findImageFrame(image), "a write before the image applies to an earlier image");

        space.writeDword(frameField + image.getIndex() * ClassicImageGraphics.IMAGE_ENTRY_SIZE, WRITTEN_FRAME << Short.SIZE);

        Assertions.assertEquals(WRITTEN_FRAME, graphics.findImageFrame(image));

        image.setFrameIndex(1);

        Assertions.assertEquals(-1, graphics.findImageFrame(image));
    }

    @Test
    void a_written_frame_waits_for_a_readable_grp_of_the_image() {
        ClassicAddressSpace space = createSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));

        SpriteImage image = createImage(pool);

        writeSlotPointer(space, image, GRP_ADDRESS);
        space.writeDword(ClassicImageGraphics.IMAGE_ARRAY + image.getIndex() * ClassicImageGraphics.IMAGE_ENTRY_SIZE +
                         ClassicImageGraphics.IMAGE_FRAME_OFFSET, WRITTEN_FRAME << Short.SIZE);

        Assertions.assertNull(graphics.findImageSheet(image));
        Assertions.assertEquals(-1, graphics.findImageFrame(image), "the installed graphics draw the image's own frame");

        writeBytes(space, GRP_ADDRESS, ImageFileScenario.createGrp(GRP_ADDRESS));

        Assertions.assertEquals(WRITTEN_FRAME, graphics.findImageFrame(image));
    }

    @Test
    void an_anim_pointer_draws_its_anim_in_hd_and_hd2_and_keeps_the_installed_classic_graphics() {
        ClassicAddressSpace space = createAnimSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        writeBytes(space, ANIM_MEMORY, ImageFileScenario.createAnim(EmbeddedAnim.HD_SCALE));

        SpriteImage image = createImage(pool);

        writeSlotPointer(space, image, ANIM_MEMORY);

        ScrImageAnimation animation = graphics.findImageAnimation(image);

        Assertions.assertNotNull(animation);
        Assertions.assertNull(graphics.findImageSheet(image), "the classic graphics stay installed");
        Assertions.assertEquals(-1, graphics.findImageTypeId(image));

        TextureSheet sheet = ImageFileScenario.awaitSheet(animation, EmbeddedAnim.HD_SCALE);

        Assertions.assertEquals(1, sheet.getFrameCount());
        Assertions.assertEquals(ImageFileScenario.ANIM_CANVAS, sheet.getWidth());
        Assertions.assertEquals(1, sheet.getUnitsPerTexel());
        Assertions.assertNotNull(sheet.findLayer(ImageFileScenario.DIFFUSE_LAYER_NAME));
        Assertions.assertSame(sheet, animation.findSheet(EmbeddedAnim.HD2_SCALE), "HD2 draws the HD anim without an HD2 anim");
        Assertions.assertSame(sheet, animation.findSheet(EmbeddedAnim.HD_SCALE), "the sheet is kept while its bytes are unchanged");
    }

    @Test
    void a_grp_links_its_hd_and_hd2_anims() {
        ClassicAddressSpace space = createAnimSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);
        byte[] grp = ImageFileScenario.createGrp(GRP_ADDRESS);

        // The test GRP's frame addresses are absolute at GRP_ADDRESS: relocate them to the linked GRP.
        ByteBuffer relocated = ByteBuffer.wrap(grp).order(ByteOrder.LITTLE_ENDIAN);

        relocated.putInt(FIRST_FRAME_DATA_OFFSET, relocated.getInt(FIRST_FRAME_DATA_OFFSET) - GRP_ADDRESS + LINKED_GRP_ADDRESS);
        relocated.putInt(SECOND_FRAME_DATA_OFFSET, relocated.getInt(SECOND_FRAME_DATA_OFFSET) - GRP_ADDRESS + LINKED_GRP_ADDRESS);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        writeBytes(space, LINKED_GRP_ADDRESS, grp);
        writeBytes(space, LINKED_GRP_ADDRESS + HD_ANIM_OFFSET, ImageFileScenario.createAnim(EmbeddedAnim.HD_SCALE));
        writeBytes(space, LINKED_GRP_ADDRESS + HD2_ANIM_OFFSET, ImageFileScenario.createAnim(EmbeddedAnim.HD2_SCALE));
        space.writeDword(LINKED_GRP_ADDRESS - EmbeddedGraphics.LINK_SIZE, EmbeddedGraphics.LINK_MAGIC);
        space.writeDword(LINKED_GRP_ADDRESS - EmbeddedGraphics.LINK_SIZE + LINK_HD_OFFSET, HD_ANIM_OFFSET);
        space.writeDword(LINKED_GRP_ADDRESS - EmbeddedGraphics.LINK_SIZE + LINK_HD2_OFFSET, HD2_ANIM_OFFSET);

        SpriteImage image = createImage(pool);

        writeSlotPointer(space, image, LINKED_GRP_ADDRESS);

        ScrImageSheet grpSheet = graphics.findImageSheet(image);
        ScrImageAnimation animation = graphics.findImageAnimation(image);

        Assertions.assertNotNull(grpSheet);
        Assertions.assertEquals(2, grpSheet.getFrameCount());
        Assertions.assertNotNull(animation);
        Assertions.assertEquals(1, ImageFileScenario.awaitSheet(animation, EmbeddedAnim.HD_SCALE).getUnitsPerTexel());
        Assertions.assertEquals(2, ImageFileScenario.awaitSheet(animation, EmbeddedAnim.HD2_SCALE).getUnitsPerTexel());

        space.writeDword(LINKED_GRP_ADDRESS - EmbeddedGraphics.LINK_SIZE + LINK_HD_OFFSET, 0);

        Assertions.assertEquals(2, ImageFileScenario.awaitSheet(animation, EmbeddedAnim.HD_SCALE).getUnitsPerTexel(),
                                "HD draws the HD2 anim without an HD anim");

        space.writeDword(LINKED_GRP_ADDRESS - EmbeddedGraphics.LINK_SIZE, 0);

        Assertions.assertNull(graphics.findImageAnimation(image), "a GRP without the link has no anims");
        Assertions.assertNotNull(graphics.findImageSheet(image));
    }

    @Test
    void a_write_to_an_anim_parses_it_again() {
        ClassicAddressSpace space = createAnimSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);
        byte[] anim = ImageFileScenario.createAnim(EmbeddedAnim.HD_SCALE);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        writeBytes(space, ANIM_MEMORY, anim);

        SpriteImage image = createImage(pool);

        writeSlotPointer(space, image, ANIM_MEMORY);

        ScrImageAnimation animation = graphics.findImageAnimation(image);

        Assertions.assertNotNull(animation);

        TextureSheet first = ImageFileScenario.awaitSheet(animation, EmbeddedAnim.HD_SCALE);

        // Moves the frame on the canvas.
        space.writeDword(ANIM_MEMORY + ImageFileScenario.ANIM_FRAME_TABLE + 4, 2 | 3 << Short.SIZE);

        Assertions.assertSame(first, animation.findSheet(EmbeddedAnim.HD_SCALE), "the sheet parsed last stays until the new one is ready");

        long deadline = System.nanoTime() + PARSE_TIMEOUT_NANOSECONDS;
        TextureSheet second = first;

        while (second == first && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            second = animation.findSheet(EmbeddedAnim.HD_SCALE);
        }

        Assertions.assertNotSame(first, second);
        Assertions.assertNotNull(second);
        Assertions.assertEquals(2, second.getFrame(0).getX());
        Assertions.assertEquals(3, second.getFrame(0).getY());
    }

    @Test
    void a_malformed_anim_gives_no_sheet() throws InterruptedException {
        ClassicAddressSpace space = createAnimSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);
        byte[] anim = ImageFileScenario.createAnim(EmbeddedAnim.HD_SCALE);

        // Breaks the DDS magic of the diffuse layer.
        anim[ImageFileScenario.ANIM_TEXTURE] = 0;

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        writeBytes(space, ANIM_MEMORY, anim);

        SpriteImage image = createImage(pool);

        writeSlotPointer(space, image, ANIM_MEMORY);

        ScrImageAnimation animation = graphics.findImageAnimation(image);

        Assertions.assertNotNull(animation);
        Assertions.assertNull(animation.findSheet(EmbeddedAnim.HD_SCALE));

        Thread.sleep(FAILED_PARSE_WAIT_MILLISECONDS);

        Assertions.assertNull(animation.findSheet(EmbeddedAnim.HD_SCALE));
        Assertions.assertNull(animation.findSheet(EmbeddedAnim.HD2_SCALE));
        Assertions.assertNull(graphics.findImageSheet(image));
    }

    @Test
    void a_copied_installed_table_pointer_draws_the_installed_graphics_of_its_image() {
        ClassicAddressSpace space = createSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);

        new ClassicGrpTableStructure().provide(new ClassicMemorySink(space), new ClassicHeap());
        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));

        int copied = space.readDword(ClassicImageGraphics.GRP_TABLE + COPIED_IMAGE_TYPE * Integer.BYTES);

        Assertions.assertNotEquals(0, copied);

        space.writeDword(ClassicImageGraphics.GRP_TABLE + IMAGE_TYPE * Integer.BYTES, copied);

        SpriteImage image = createImage(pool);

        Assertions.assertEquals(COPIED_IMAGE_TYPE, graphics.findImageTypeId(image));
        Assertions.assertNull(graphics.findImageSheet(image));
        Assertions.assertNull(graphics.findImageAnimation(image));

        space.writeDword(ClassicImageGraphics.IMAGE_ARRAY + image.getIndex() * ClassicImageGraphics.IMAGE_ENTRY_SIZE +
                         ClassicImageGraphics.IMAGE_FRAME_OFFSET, WRITTEN_FRAME << Short.SIZE);

        Assertions.assertEquals(WRITTEN_FRAME, graphics.findImageFrame(image), "the installed graphics take a written frame");
    }

    @Test
    void a_written_render_function_selects_its_draw_function_until_the_image_changes_its_own() {
        ClassicAddressSpace space = createSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> null);
        int renderField = ClassicImageGraphics.IMAGE_ARRAY + ClassicImageGraphics.IMAGE_RENDER_OFFSET;

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));

        SpriteImage image = createImage(pool);

        renderField += image.getIndex() * ClassicImageGraphics.IMAGE_ENTRY_SIZE;
        image.setModifier(SpriteImage.MODIFIER_SHADOW);
        space.writeDword(renderField, UNKNOWN_RENDER_FUNCTION);

        Assertions.assertEquals(-1, graphics.findImageDrawFunction(image));

        space.writeDword(renderField, ClassicImageGraphics.NORMAL_RENDER_FUNCTION);

        Assertions.assertEquals(SpriteImage.MODIFIER_NORMAL, graphics.findImageDrawFunction(image));

        image.setModifier(SpriteImage.MODIFIER_REMAPPING);

        Assertions.assertEquals(-1, graphics.findImageDrawFunction(image));
    }

    @Test
    void a_script_replacement_wins_over_the_eud_pointers_of_its_image() throws IOException {
        ClassicAddressSpace space = createSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ImageReplacements replacements = new ImageReplacements(() -> null);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> replacements);
        byte[] anim = ImageFileScenario.createAnim(EmbeddedAnim.HD_SCALE);
        ImageGraphics replaced = ImageGraphics.of(null, replacements.readAnim(anim, 0, anim.length, "replaced.anim"), null);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        writeBytes(space, GRP_ADDRESS, ImageFileScenario.createGrp(GRP_ADDRESS));
        space.writeDword(ClassicImageGraphics.GRP_TABLE + IMAGE_TYPE * Integer.BYTES, GRP_ADDRESS);

        SpriteImage image = createImage(pool);

        Assertions.assertNotNull(graphics.findImageSheet(image), "the EUD GRP applies without a replacement");

        replacements.replaceImage(IMAGE_TYPE, replaced);

        Assertions.assertNull(graphics.findImageSheet(image), "the replacement without a GRP keeps the installed GRP");
        Assertions.assertSame(replaced, graphics.findImageAnimation(image));
        Assertions.assertEquals(-1, graphics.findImageTypeId(image));

        replacements.restoreImage(IMAGE_TYPE);

        Assertions.assertNotNull(graphics.findImageSheet(image), "the EUD GRP applies again");
        Assertions.assertNull(graphics.findImageAnimation(image));
    }

    @Test
    void a_script_frame_draw_function_and_graphics_of_one_image_win_until_the_image_changes_or_its_slot_is_reused() throws IOException {
        ClassicAddressSpace space = createSpace();
        SpriteImagePool pool = new SpriteImagePool(1);
        ImageReplacements replacements = new ImageReplacements(() -> null);
        ClassicImageGraphics graphics = new ClassicImageGraphics(() -> pool, () -> replacements);
        byte[] anim = ImageFileScenario.createAnim(EmbeddedAnim.HD_SCALE);
        ImageGraphics replaced = ImageGraphics.of(null, replacements.readAnim(anim, 0, anim.length, "replaced.anim"), null);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));

        SpriteImage image = createImage(pool);
        int frameField = ClassicImageGraphics.IMAGE_ARRAY + image.getIndex() * ClassicImageGraphics.IMAGE_ENTRY_SIZE +
                         ClassicImageGraphics.IMAGE_FRAME_OFFSET;

        space.writeDword(frameField, WRITTEN_FRAME << Short.SIZE);
        replacements.setImageFrame(image, SCRIPT_FRAME);
        replacements.setImageDrawFunction(image, SpriteImage.MODIFIER_HALLUCINATION);
        replacements.replaceImageGraphics(image, replaced);

        Assertions.assertEquals(SCRIPT_FRAME, graphics.findImageFrame(image), "the script frame wins over the EUD write");
        Assertions.assertEquals(SpriteImage.MODIFIER_HALLUCINATION, graphics.findImageDrawFunction(image));
        Assertions.assertSame(replaced, graphics.findImageAnimation(image));

        replacements.setImageFrame(image, -1);

        Assertions.assertEquals(WRITTEN_FRAME, graphics.findImageFrame(image), "the EUD frame applies again");

        replacements.setImageFrame(image, SCRIPT_FRAME);
        image.setFrameIndex(1);
        image.setModifier(SpriteImage.MODIFIER_SHADOW);

        Assertions.assertEquals(-1, graphics.findImageFrame(image), "the image changed its own frame");
        Assertions.assertEquals(-1, graphics.findImageDrawFunction(image), "the image changed its own draw function");
        Assertions.assertSame(replaced, graphics.findImageAnimation(image), "the graphics last while the image lives");

        pool.recycle(image);

        SpriteImage reused = createImage(pool);

        Assertions.assertSame(image, reused);
        Assertions.assertNull(graphics.findImageAnimation(reused), "a new image in the slot has no script graphics");

        pool.recycle(reused);

        Assertions.assertThrows(IllegalArgumentException.class, () -> replacements.setImageFrame(reused, SCRIPT_FRAME),
                                "a free slot takes no frame");
    }

    @Test
    void a_held_frame_stays_through_the_frame_steps_of_the_image_until_it_is_released() {
        SpriteImagePool pool = new SpriteImagePool(1);
        ImageReplacements replacements = new ImageReplacements(() -> null);
        SpriteImage image = createImage(pool);

        replacements.holdImageFrame(image, SCRIPT_FRAME);
        image.setFrameIndex(1);
        image.setFrameIndex(2);

        Assertions.assertEquals(SCRIPT_FRAME, replacements.findImageFrame(image), "the held frame stays while the image steps its frame");

        replacements.setImageFrame(image, SCRIPT_FRAME);
        image.setFrameIndex(3);

        Assertions.assertEquals(-1, replacements.findImageFrame(image), "a set frame replaces the held one and ends at the next step");

        replacements.holdImageFrame(image, SCRIPT_FRAME);
        replacements.holdImageFrame(image, -1);

        Assertions.assertEquals(-1, replacements.findImageFrame(image), "a released frame draws the image's own frame");

        replacements.holdImageFrame(image, SCRIPT_FRAME);
        pool.recycle(image);
        replacements.removeFreedUnits();

        Assertions.assertEquals(-1, replacements.findImageFrame(createImage(pool)), "a new image in the slot holds no frame");
    }
}
