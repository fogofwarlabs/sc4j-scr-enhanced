package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrScreenOverlay;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStorageRegion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the classic game screen: pixels written to the game screen bitmap are shown from then on, a game without the bitmap shows none, and a
 * map script's pixels go to the same screen.
 */
final class ClassicScreenGraphicsTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    /**
     * The size of the game screen bitmap header.
     */
    private static final int HEADER_SIZE = 8;
    /**
     * The address of the pixels in the tests.
     */
    private static final int PIXELS = 0x0F04B000;
    /**
     * The first pixel of the first console row the tests write.
     */
    private static final int CONSOLE_ROW_PIXEL = WIDTH * 401;
    /**
     * The four palette indices {@code 1} to {@code 4} of one written DWORD.
     */
    private static final int WRITTEN_PIXELS = 0x04030201;

    private static @NotNull ClassicAddressSpace createSpace(boolean bitmap) {
        ClassicAddressSpace space = new ClassicAddressSpace();

        space.add(new ClassicStorageRegion(ClassicScreenGraphics.GAME_SCREEN, HEADER_SIZE));
        space.add(new ClassicStorageRegion(PIXELS, WIDTH * HEIGHT));

        if (bitmap) {
            space.writeDword(ClassicScreenGraphics.GAME_SCREEN, HEIGHT << Short.SIZE | WIDTH);
            space.writeDword(ClassicScreenGraphics.GAME_SCREEN + Integer.BYTES, PIXELS);
        }

        return space;
    }

    @Test
    void written_screen_pixels_are_shown() {
        ClassicAddressSpace space = createSpace(true);
        ClassicScreenGraphics graphics = new ClassicScreenGraphics();

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));

        Assertions.assertNull(graphics.findScreenOverlay());

        space.writeDword(PIXELS + CONSOLE_ROW_PIXEL, WRITTEN_PIXELS);

        ScrScreenOverlay overlay = graphics.findScreenOverlay();

        Assertions.assertNotNull(overlay);
        Assertions.assertEquals(WIDTH, overlay.getWidth());
        Assertions.assertEquals(HEIGHT, overlay.getHeight());
        Assertions.assertEquals(4, overlay.getShownCount());
        Assertions.assertEquals(1, overlay.getPixel(CONSOLE_ROW_PIXEL));
        Assertions.assertEquals(4, overlay.getPixel(CONSOLE_ROW_PIXEL + 3));
        Assertions.assertTrue(overlay.isShown(CONSOLE_ROW_PIXEL + 3));
        Assertions.assertFalse(overlay.isShown(CONSOLE_ROW_PIXEL + 4));

        long changes = overlay.getChangeCount();

        space.writeDword(PIXELS + CONSOLE_ROW_PIXEL, WRITTEN_PIXELS);

        Assertions.assertEquals(changes, overlay.getChangeCount());

        space.writeDword(PIXELS + CONSOLE_ROW_PIXEL, 0);

        Assertions.assertEquals(0, overlay.getPixel(CONSOLE_ROW_PIXEL));
        Assertions.assertTrue(overlay.isShown(CONSOLE_ROW_PIXEL));
        Assertions.assertTrue(overlay.getChangeCount() > changes);
    }

    @Test
    void a_game_without_a_screen_bitmap_shows_no_screen_pixel() {
        ClassicAddressSpace space = createSpace(false);
        ClassicScreenGraphics graphics = new ClassicScreenGraphics();

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        space.writeDword(PIXELS, WRITTEN_PIXELS);

        Assertions.assertNull(graphics.findScreenOverlay());
    }

    @Test
    void fill_pixels_should_show_script_pixels_in_the_same_screen_as_eud_writes() {
        ClassicAddressSpace space = createSpace(true);
        ClassicScreenGraphics graphics = new ClassicScreenGraphics();

        graphics.fillPixels(-1, 0, 3, 2, 7);
        graphics.fillPixels(WIDTH - 1, HEIGHT - 1, 5, 5, 9);

        ScrScreenOverlay overlay = graphics.findScreenOverlay();

        Assertions.assertNotNull(overlay);
        Assertions.assertEquals(WIDTH, overlay.getWidth());
        Assertions.assertEquals(5, overlay.getShownCount(), "the part outside the screen is ignored");
        Assertions.assertEquals(7, overlay.getPixel(WIDTH + 1));
        Assertions.assertEquals(9, overlay.getPixel(WIDTH * HEIGHT - 1));

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        space.writeDword(PIXELS + CONSOLE_ROW_PIXEL, WRITTEN_PIXELS);

        Assertions.assertSame(overlay, graphics.findScreenOverlay());
        Assertions.assertEquals(9, overlay.getShownCount());
        Assertions.assertEquals(7, overlay.getPixel(0));
    }
}
