package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStorageRegion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Checks the classic terrain pictures: a write to minitile pixels changes every megatile that shows the minitile, mirrored where its reference says
 * so, a write to a megatile's WORD references changes that megatile, other megatiles keep the installed picture, an unchanged picture keeps its
 * array, and a replaced megatile draws its covered pixels over the written or installed picture.
 */
final class ClassicTerrainGraphicsTest {
    /**
     * The addresses the tileset pointers hold in the tests.
     */
    private static final int MINITILES = 0xB31080;
    private static final int MEGATILES = 0xE31080;
    /**
     * The installed fill of minitile {@code 0}, and the first pixel value of minitile {@code 1}'s top row ({@code 10..17}).
     */
    private static final int FILL = 7;
    private static final int FIRST_ROW_START = 10;
    private static final int WRITTEN_PIXELS = 0x04030201;
    /**
     * The reference of minitile {@code 1} mirrored, and unmirrored.
     */
    private static final int MIRRORED_MINITILE_1 = 3;
    private static final int MINITILE_1 = 2;
    /**
     * The palette index a replacement covers the first pixel of megatile {@code 0} with.
     */
    private static final byte REPLACED_PIXEL = 9;

    /**
     * Creates the installed files: megatile {@code 0} shows minitile {@code 0} everywhere, megatile {@code 1} shows minitile {@code 1} mirrored in
     * its top-left minitile and minitile {@code 0} elsewhere, megatile {@code 2} shows minitile {@code 0}; minitile {@code 0} is {@link #FILL},
     * minitile {@code 1} has the top row {@code 10..17} and {@code 0} below.
     */
    private static byte @NotNull [] readInstalled(@NotNull String extension) throws IOException {
        if (extension.equals(ClassicTerrainGraphics.MINITILE_EXTENSION)) {
            byte[] minitiles = new byte[2 * ClassicTerrainGraphics.MINITILE_SIZE];

            Arrays.fill(minitiles, 0, ClassicTerrainGraphics.MINITILE_SIZE, (byte) FILL);

            for (int column = 0; column < TerrainTileset.MINI_TILE_SIZE; column++) {
                minitiles[ClassicTerrainGraphics.MINITILE_SIZE + column] = (byte) (FIRST_ROW_START + column);
            }

            return minitiles;
        }

        if (extension.equals(ClassicTerrainGraphics.MEGATILE_EXTENSION)) {
            ByteBuffer megatiles = ByteBuffer.allocate(3 * ClassicTerrainGraphics.MEGATILE_MINITILES * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);

            megatiles.putInt(ClassicTerrainGraphics.MEGATILE_MINITILES * Integer.BYTES, MIRRORED_MINITILE_1);

            return megatiles.array();
        }

        throw Exceptions.newIOException("No installed file {0}.", extension);
    }

    private static @NotNull ClassicAddressSpace createSpace() {
        ClassicAddressSpace space = new ClassicAddressSpace();

        space.add(new ClassicStorageRegion(ClassicTerrainGraphics.MINITILE_POINTER, Integer.BYTES));
        space.add(new ClassicStorageRegion(ClassicTerrainGraphics.MEGATILE_POINTER, Integer.BYTES));
        space.add(new ClassicStorageRegion(MINITILES, 4 * ClassicTerrainGraphics.MINITILE_SIZE));
        space.add(new ClassicStorageRegion(MEGATILES, 4 * ClassicTerrainGraphics.MEGATILE_ENTRY_SIZE));
        space.writeDword(ClassicTerrainGraphics.MINITILE_POINTER, MINITILES);
        space.writeDword(ClassicTerrainGraphics.MEGATILE_POINTER, MEGATILES);

        return space;
    }

    private static int @NotNull [] readTopRow(byte @NotNull [] picture, int first) {
        int[] row = new int[TerrainTileset.MINI_TILE_SIZE];

        for (int column = 0; column < row.length; column++) {
            row[column] = picture[first + column];
        }

        return row;
    }


    @Test
    void a_minitile_write_changes_the_megatiles_that_show_it() {
        ClassicAddressSpace space = createSpace();
        ClassicTerrainGraphics graphics = new ClassicTerrainGraphics(ClassicTerrainGraphicsTest::readInstalled, () -> null);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));

        Assertions.assertNull(graphics.findTerrainTile(0));
        Assertions.assertNull(graphics.findTerrainTile(1));

        space.writeDword(MINITILES + ClassicTerrainGraphics.MINITILE_SIZE, WRITTEN_PIXELS);

        byte[] picture = graphics.findTerrainTile(1);

        Assertions.assertNull(graphics.findTerrainTile(0));
        Assertions.assertNotNull(picture);
        Assertions.assertArrayEquals(new int[]{17, 16, 15, 14, 4, 3, 2, 1}, readTopRow(picture, 0));
        Assertions.assertArrayEquals(new int[]{FILL, FILL, FILL, FILL, FILL, FILL, FILL, FILL}, readTopRow(picture, TerrainTileset.MINI_TILE_SIZE));
        Assertions.assertEquals(0, picture[TerrainTileset.TILE_SIZE]);
        Assertions.assertSame(picture, graphics.findTerrainTile(1));

        space.writeDword(MINITILES + ClassicTerrainGraphics.MINITILE_SIZE, WRITTEN_PIXELS);

        Assertions.assertSame(picture, graphics.findTerrainTile(1));
    }

    @Test
    void a_reference_write_changes_its_megatile() {
        ClassicAddressSpace space = createSpace();
        ClassicTerrainGraphics graphics = new ClassicTerrainGraphics(ClassicTerrainGraphicsTest::readInstalled, () -> null);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        space.writeDword(MEGATILES + 2 * ClassicTerrainGraphics.MEGATILE_ENTRY_SIZE, MINITILE_1);

        byte[] picture = graphics.findTerrainTile(2);

        Assertions.assertNotNull(picture);
        Assertions.assertNull(graphics.findTerrainTile(0));
        Assertions.assertNull(graphics.findTerrainTile(1));
        Assertions.assertArrayEquals(new int[]{10, 11, 12, 13, 14, 15, 16, 17}, readTopRow(picture, 0));
        Assertions.assertArrayEquals(new int[]{FILL, FILL, FILL, FILL, FILL, FILL, FILL, FILL}, readTopRow(picture, TerrainTileset.MINI_TILE_SIZE));
    }

    @Test
    void a_replaced_megatile_draws_its_covered_pixels_over_the_written_or_installed_picture() {
        ClassicAddressSpace space = createSpace();
        TerrainReplacements replacements = new TerrainReplacements();
        ClassicTerrainGraphics graphics = new ClassicTerrainGraphics(ClassicTerrainGraphicsTest::readInstalled, () -> replacements);
        Picture picture = new Picture(TerrainTileset.TILE_SIZE, TerrainTileset.TILE_SIZE,
                                      PixelLayer.ofRgba(new int[TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE]));
        byte[] pixels = new byte[TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE];
        byte[] coverage = new byte[pixels.length];

        pixels[0]   = REPLACED_PIXEL;
        coverage[0] = 1;

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        replacements.replaceTile(0, picture, pixels, coverage);

        byte[] installed = graphics.findTerrainTile(0);

        Assertions.assertNotNull(installed);
        Assertions.assertArrayEquals(new int[]{REPLACED_PIXEL, FILL, FILL, FILL, FILL, FILL, FILL, FILL}, readTopRow(installed, 0));
        Assertions.assertSame(installed, graphics.findTerrainTile(0));
        Assertions.assertSame(picture, graphics.findTerrainTilePicture(0));
        Assertions.assertNull(graphics.findTerrainTile(2));
        Assertions.assertNull(graphics.findTerrainTilePicture(2));

        space.writeDword(MINITILES, WRITTEN_PIXELS);

        byte[] written = graphics.findTerrainTile(0);

        Assertions.assertNotNull(written);
        Assertions.assertArrayEquals(new int[]{REPLACED_PIXEL, 2, 3, 4, FILL, FILL, FILL, FILL}, readTopRow(written, 0));
    }

    @Test
    void unreadable_installed_files_keep_the_installed_pictures() {
        ClassicAddressSpace space = createSpace();
        ClassicTerrainGraphics graphics = new ClassicTerrainGraphics(extension -> {
            throw Exceptions.newIOException("No installed file {0}.", extension);
        }, () -> null);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        space.writeDword(MEGATILES, MINITILE_1);

        Assertions.assertNull(graphics.findTerrainTile(0));
    }
}
