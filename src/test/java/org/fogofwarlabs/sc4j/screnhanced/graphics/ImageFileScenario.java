package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageAnimation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Creates the small GRP, anim, PNG and DDS files of the graphics tests and waits for the parse of an anim.
 */
public final class ImageFileScenario {
    /**
     * The asset scales of HD and HD2 anims.
     */
    public static final int HD_SCALE = EmbeddedAnim.HD_SCALE;
    public static final int HD2_SCALE = EmbeddedAnim.HD2_SCALE;
    /**
     * The two pixels of {@link #createPng()} and the one colour of {@link #createDds()}.
     */
    public static final int PNG_FIRST_COLOR = 0xFFFF0000;
    public static final int PNG_SECOND_COLOR = 0x800000FF;
    public static final int DDS_COLOR = 0xFFFF0000;
    public static final int DDS_SIDE = 4;
    /**
     * The frame count and the canvas of {@link #createGrp(int)}.
     */
    public static final int GRP_FRAME_COUNT = 2;
    public static final int GRP_WIDTH = 4;
    public static final int GRP_HEIGHT = 3;
    /**
     * The anim layout of {@link #createAnim(int)}: the header, the entry with one layer record, one frame record and a 4x4 DXT1 texture.
     */
    static final int ANIM_HEADER_SIZE = 332;
    static final int ANIM_FRAME_TABLE = ANIM_HEADER_SIZE + 24;
    static final int ANIM_TEXTURE = ANIM_FRAME_TABLE + 16;
    static final int DDS_SIZE = 136;
    static final int ANIM_CANVAS = 16;
    static final int ANIM_FRAME_SIZE = 4;
    /**
     * The name of the anim layer {@link #createAnim(int)} has.
     */
    static final @NotNull String DIFFUSE_LAYER_NAME = "diffuse";

    private static final int GRP_SIZE = 44;
    /**
     * The time {@link #awaitSheet} waits for an anim parse.
     */
    private static final long PARSE_TIMEOUT_NANOSECONDS = 10_000_000_000L;

    /**
     * Creates a two-frame GRP on a 4x3 canvas whose frame data addresses count from a base: frame {@code 0} is a 2x2 rectangle (palette indices
     * {@code 5, 6} over two of {@code 7}), frame {@code 1} a 3x1 rectangle at {@code (1, 1)} (one transparent pixel, then {@code 8, 9}).
     *
     * @param base the address of the GRP's first byte, {@code 0} for a GRP file
     * @return the GRP bytes
     */
    public static byte @NotNull [] createGrp(int base) {
        ByteBuffer grp = ByteBuffer.allocate(GRP_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        grp.putShort((short) GRP_FRAME_COUNT).putShort((short) GRP_WIDTH).putShort((short) GRP_HEIGHT);
        grp.put((byte) 0).put((byte) 0).put((byte) 2).put((byte) 2).putInt(base + 22);
        grp.put((byte) 1).put((byte) 1).put((byte) 3).put((byte) 1).putInt(base + 31);
        // Frame 0: two line offsets, a literal run of two and a repeat run of two.
        grp.putShort((short) 4).putShort((short) 7);
        grp.put((byte) 2).put((byte) 5).put((byte) 6);
        grp.put((byte) 0x42).put((byte) 7);
        // Frame 1: one line offset, a transparent run of one and a literal run of two.
        grp.putShort((short) 2);
        grp.put((byte) 0x81).put((byte) 2).put((byte) 8).put((byte) 9);

        return grp.array();
    }

    /**
     * Creates a single-entry anim of a scale: a {@code diffuse} layer of one 4x4 DXT1 block and one canvas-placed frame on a 16x16 canvas.
     *
     * @param scale the asset scale, {@link #HD_SCALE} or {@link #HD2_SCALE}
     * @return the anim bytes
     */
    public static byte @NotNull [] createAnim(int scale) {
        ByteBuffer anim = ByteBuffer.allocate(ANIM_TEXTURE + DDS_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        anim.putInt(EmbeddedAnim.MAGIC).put((byte) scale).put((byte) 2).putShort((short) 0).putShort((short) 1).putShort((short) 1);
        anim.put(DIFFUSE_LAYER_NAME.getBytes(StandardCharsets.US_ASCII));
        anim.position(ANIM_HEADER_SIZE);
        anim.putShort((short) 1).putShort((short) 0).putShort((short) ANIM_CANVAS).putShort((short) ANIM_CANVAS).putInt(ANIM_FRAME_TABLE);
        anim.putInt(ANIM_TEXTURE).putInt(DDS_SIZE).putShort((short) ANIM_FRAME_SIZE).putShort((short) ANIM_FRAME_SIZE);
        anim.putShort((short) 0).putShort((short) 0).putShort((short) 6).putShort((short) 6);
        anim.putShort((short) ANIM_FRAME_SIZE).putShort((short) ANIM_FRAME_SIZE).putInt(1);
        anim.putInt(0x20534444).putInt(124).putInt(0).putInt(ANIM_FRAME_SIZE).putInt(ANIM_FRAME_SIZE);
        anim.position(ANIM_TEXTURE + 28);
        anim.putInt(1);
        anim.position(ANIM_TEXTURE + 80);
        anim.putInt(4).putInt(0x31545844);
        anim.position(ANIM_TEXTURE + 128);
        anim.putShort((short) 0xF800).putShort((short) 0x001F).putInt(0);

        return anim.array();
    }

    /**
     * Asks anims for the sheet of a scale until a parse gives one.
     *
     * @param animation the anims
     * @param scale     the asset scale drawn
     * @return the sheet
     */
    public static @NotNull TextureSheet awaitSheet(@NotNull ScrImageAnimation animation, int scale) {
        long deadline = System.nanoTime() + PARSE_TIMEOUT_NANOSECONDS;
        TextureSheet sheet = animation.findSheet(scale);

        while (sheet == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            sheet = animation.findSheet(scale);
        }

        Assertions.assertNotNull(sheet, "the anim is parsed");

        return sheet;
    }

    /**
     * Creates a PNG of two pixels in a row: {@link #PNG_FIRST_COLOR}, then {@link #PNG_SECOND_COLOR}.
     *
     * @return the PNG bytes
     */
    public static byte @NotNull [] createPng() {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        image.setRGB(0, 0, PNG_FIRST_COLOR);
        image.setRGB(1, 0, PNG_SECOND_COLOR);

        try {
            ImageIO.write(image, "png", output);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        return output.toByteArray();
    }

    /**
     * Creates a {@link #DDS_SIDE} by {@link #DDS_SIDE} DXT1 DDS of one block whose pixels are all {@link #DDS_COLOR}.
     *
     * @return the DDS bytes
     */
    public static byte @NotNull [] createDds() {
        ByteBuffer dds = ByteBuffer.allocate(DDS_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        dds.putInt(0x20534444).putInt(124).putInt(0).putInt(DDS_SIDE).putInt(DDS_SIDE);
        dds.position(28);
        dds.putInt(1);
        dds.position(80);
        dds.putInt(4).putInt(0x31545844);
        dds.position(128);
        dds.putShort((short) 0xF800).putShort((short) 0x001F).putInt(0);

        return dds.array();
    }


    private ImageFileScenario() {
    }
}
