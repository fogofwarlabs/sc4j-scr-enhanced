package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsoleArt;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsolePlacement;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrScreenOverlay;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStorageRegion;
import org.fogofwarlabs.sc4j.screnhanced.memory.ClassicConsoleAreaStructure;
import org.fogofwarlabs.sc4j.screnhanced.memory.ClassicDialogStructure;
import org.fogofwarlabs.sc4j.screnhanced.memory.ClassicMemory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the classic console: a written dialog rectangle moves the dialog, the text box included, a written control rectangle moves the control in
 * its dialog, a cleared visible flag hides the control, a written picture size hides the dialog and shows its picture at its rectangle, and a
 * written console transparency shows the console and the pictures only at its shown pixels, a console area owner shows its written picture, and
 * written resource icons replace the installed ones; a map script's console settings, those of the controls included, win over EUD memory for
 * the items they set until the script restores them.
 */
final class ClassicConsoleGraphicsTest {
    private static final int WIDTH = ClassicMemory.SCREEN_WIDTH;
    /**
     * The first dialog and picture buffer addresses of the tests, and the distance between two dialogs.
     */
    private static final int DIALOGS = 0x0F100000;
    private static final int PICTURES = 0x0F200000;
    private static final int DIALOG_STRIDE = 0x2000;
    /**
     * The index of the menu button, the selection panel, the command card and the text box in {@link ClassicDialogStructure#DIALOGS}.
     */
    private static final int MENU = 5;
    private static final int SELECTION = 1;
    private static final int COMMAND_CARD = 0;
    private static final int TEXT_BOX = 6;
    private static final int MENU_LEFT = 550;
    private static final int MENU_TOP = 458;
    private static final int PICTURE_LEFT = 11;
    private static final int PICTURE_TOP = 412;
    private static final int PICTURE_WIDTH = 4;
    private static final int PICTURE_HEIGHT = 2;
    /**
     * The command card's first button: its identifier, its index and its place in the dialog after the move.
     */
    private static final int BUTTON = 1;
    private static final int BUTTON_INDEX = 0;
    private static final int BUTTON_LEFT = 30;
    private static final int BUTTON_TOP = -40;
    /**
     * The command card's second button, its index, and the command card's place.
     */
    private static final int SECOND_BUTTON = 2;
    private static final int SECOND_BUTTON_INDEX = 1;
    private static final int COMMAND_CARD_LEFT = 496;
    private static final int COMMAND_CARD_TOP = 354;
    private static final int TEXT_BOX_LEFT = 186;
    private static final int TEXT_BOX_TOP = 381;
    private static final int WORD_MASK = 0xFFFF;
    private static final long DWORD_MASK = 0xFFFFFFFFL;
    /**
     * The picture's first row: three palette indices and a transparent {@code 0}.
     */
    private static final int PICTURE_ROW = 0x00030201;
    /**
     * A transparency row that skips the whole screen row: pairs (255, 0), (255, 0), (130, 0) and the end pair.
     */
    private static final int SKIPPED_ROW_START = 0x00FF00FF;
    private static final int SKIPPED_ROW_END = 0x00000082;
    private static final int SKIPPED_ROW_SIZE = 2 * Integer.BYTES;
    /**
     * The resource icons buffer of the tests and a GRP of one frame: the frame count and width, the height and the frame's left and top, its
     * width, height and pixel offset, and its six pixels.
     */
    private static final int ICONS = 0x0F300000;
    private static final int @NotNull [] ICON_GRP = {0x00080001, 0x02010008, 0x00100203, 0, 0x04030201, 0x00000605};
    private static final int ICON_LEFT = 1;
    private static final int ICON_TOP = 2;
    private static final int ICON_WIDTH = 3;
    private static final int ICON_HEIGHT = 2;
    /**
     * The Terran console area table of the tests, the owner of its first area and the owner's picture buffer.
     */
    private static final int CONSOLE_AREAS = 0x515300;
    private static final int CONSOLE_AREAS_SIZE = 0x48;
    private static final int AREA_OWNER = 0x0F400000;
    private static final int AREA_PICTURE = 0x0F410000;
    /**
     * A place and a colour that a map script sets.
     */
    private static final int SCRIPT_LEFT = 20;
    private static final int SCRIPT_TOP = 30;
    private static final int SCRIPT_COLOR = 0xFF102030;

    private static @NotNull ClassicAddressSpace createSpace() {
        ClassicAddressSpace space = new ClassicAddressSpace();

        space.add(new ClassicStorageRegion(ClassicMemory.CONSOLE_TRANSPARENCY_DATA, ClassicMemory.TRANSPARENCY_DATA_SIZE));

        for (int index = 0; index < ClassicDialogStructure.DIALOGS.size(); index++) {
            ClassicDialogStructure structure = ClassicDialogStructure.DIALOGS.get(index);
            int dialog = getDialog(index);
            int picture = PICTURES + index * ClassicConsoleGraphics.PICTURE_BUFFER_SIZE;

            space.add(new ClassicStorageRegion(structure.getPointer(), Integer.BYTES));
            space.add(new ClassicStorageRegion(dialog, structure.createImage(dialog, 0, picture)));
            space.add(new ClassicStorageRegion(picture, ClassicConsoleGraphics.PICTURE_BUFFER_SIZE));
            space.writeDword(structure.getPointer(), dialog);
        }

        space.add(new ClassicStorageRegion(ClassicMemory.RESOURCE_ICONS, Integer.BYTES));
        space.add(new ClassicStorageRegion(ICONS, ClassicMemory.RESOURCE_ICONS_SIZE));
        space.writeDword(ClassicMemory.RESOURCE_ICONS, ICONS);

        space.add(new ClassicStorageRegion(CONSOLE_AREAS, CONSOLE_AREAS_SIZE));
        space.add(new ClassicStorageRegion(AREA_OWNER, ClassicDialogStructure.ENTRY_SIZE + Short.BYTES));
        space.add(new ClassicStorageRegion(AREA_PICTURE, ClassicConsoleGraphics.PICTURE_BUFFER_SIZE));
        space.writeDword(CONSOLE_AREAS + ClassicConsoleAreaStructure.OWNER_OFFSET, AREA_OWNER);
        space.writeDword(AREA_OWNER + ClassicDialogStructure.PICTURE_OFFSET, AREA_PICTURE);

        return space;
    }

    private static @NotNull ClassicConsoleGraphics observe(@NotNull ClassicAddressSpace space) {
        ClassicConsoleGraphics graphics = new ClassicConsoleGraphics(() -> null);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));

        return graphics;
    }

    private static int getDialog(int index) {
        return DIALOGS + index * DIALOG_STRIDE;
    }

    /**
     * Writes the DWORD at an address that need not be aligned, keeping the bytes around it.
     */
    private static void writeUnaligned(@NotNull ClassicAddressSpace space, int address, int value) {
        int aligned = address & -Integer.BYTES;
        int shift = (address - aligned) * Byte.SIZE;
        long both = space.readDword(aligned) & DWORD_MASK | (long) space.readDword(aligned + Integer.BYTES) << Integer.SIZE;
        long replaced = both & ~(DWORD_MASK << shift) | (value & DWORD_MASK) << shift;

        space.writeDword(aligned, (int) replaced);
        space.writeDword(aligned + Integer.BYTES, (int) (replaced >>> Integer.SIZE));
    }

    private static void writePicture(@NotNull ClassicAddressSpace space) {
        int dialog = getDialog(SELECTION);
        int picture = PICTURES + SELECTION * ClassicConsoleGraphics.PICTURE_BUFFER_SIZE;

        space.writeDword(dialog + ClassicDialogStructure.RECTANGLE_OFFSET, PICTURE_TOP << Short.SIZE | PICTURE_LEFT);
        space.writeDword(dialog + ClassicDialogStructure.PICTURE_SIZE_OFFSET, PICTURE_HEIGHT << Short.SIZE | PICTURE_WIDTH);
        space.writeDword(picture, PICTURE_ROW);
    }

    @Test
    void the_installed_console_stays_until_a_map_writes_it() {
        ClassicConsoleGraphics graphics = observe(createSpace());

        Assertions.assertNull(graphics.findConsolePlacement());
    }

    @Test
    void a_written_rectangle_moves_its_dialog() {
        ClassicAddressSpace space = createSpace();
        ClassicConsoleGraphics graphics = observe(space);

        space.writeDword(getDialog(MENU) + ClassicDialogStructure.RECTANGLE_OFFSET, MENU_TOP << Short.SIZE | MENU_LEFT);
        space.writeDword(getDialog(TEXT_BOX) + ClassicDialogStructure.RECTANGLE_OFFSET, TEXT_BOX_TOP << Short.SIZE | TEXT_BOX_LEFT);

        ScrConsolePlacement placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertTrue(placement.isDialogMoved(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(MENU_LEFT, placement.getDialogLeft(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(MENU_TOP, placement.getDialogTop(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(TEXT_BOX_LEFT, placement.getDialogLeft(ScrConsolePlacement.Dialog.TEXT_BOX));
        Assertions.assertEquals(TEXT_BOX_TOP, placement.getDialogTop(ScrConsolePlacement.Dialog.TEXT_BOX));
        Assertions.assertFalse(placement.isDialogMoved(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertEquals(0, placement.getPlacedControls(ScrConsolePlacement.Dialog.MENU).length);
        Assertions.assertFalse(placement.isMasked());
    }

    @Test
    void a_written_control_rectangle_moves_the_control_in_its_dialog() {
        ClassicAddressSpace space = createSpace();
        ClassicConsoleGraphics graphics = observe(space);
        int button = getDialog(COMMAND_CARD) + ClassicDialogStructure.getControlOffset(BUTTON_INDEX);

        writeUnaligned(space, button + ClassicDialogStructure.RECTANGLE_OFFSET, BUTTON_TOP << Short.SIZE | BUTTON_LEFT & WORD_MASK);

        ScrConsolePlacement placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertFalse(placement.isDialogMoved(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertTrue(placement.isControlMoved(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON));
        Assertions.assertEquals(COMMAND_CARD_LEFT + BUTTON_LEFT, placement.getControlLeft(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON));
        Assertions.assertEquals(COMMAND_CARD_TOP + BUTTON_TOP, placement.getControlTop(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON));
        Assertions.assertFalse(placement.isControlMoved(ScrConsolePlacement.Dialog.COMMAND_CARD, SECOND_BUTTON));

        space.writeDword(getDialog(COMMAND_CARD) + ClassicDialogStructure.RECTANGLE_OFFSET, MENU_TOP << Short.SIZE | MENU_LEFT);

        placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertEquals(MENU_LEFT + BUTTON_LEFT, placement.getControlLeft(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON),
                                "a moved control keeps its place in its moved dialog");
    }

    @Test
    void a_cleared_visible_flag_hides_the_control() {
        ClassicAddressSpace space = createSpace();
        ClassicConsoleGraphics graphics = observe(space);
        int button = getDialog(COMMAND_CARD) + ClassicDialogStructure.getControlOffset(SECOND_BUTTON_INDEX);

        writeUnaligned(space, button + ClassicDialogStructure.FLAGS_OFFSET, 0);

        ScrConsolePlacement placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertTrue(placement.isControlHidden(ScrConsolePlacement.Dialog.COMMAND_CARD, SECOND_BUTTON));
        Assertions.assertFalse(placement.isControlHidden(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON));

        writeUnaligned(space, button + ClassicDialogStructure.FLAGS_OFFSET, ClassicDialogStructure.VISIBLE_FLAG);

        placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertFalse(placement.isControlHidden(ScrConsolePlacement.Dialog.COMMAND_CARD, SECOND_BUTTON));
    }

    @Test
    void a_written_picture_hides_its_dialog_and_shows_at_its_rectangle() {
        ClassicAddressSpace space = createSpace();
        ClassicConsoleGraphics graphics = observe(space);

        writePicture(space);

        ScrConsolePlacement placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertTrue(placement.isDialogHidden(ScrConsolePlacement.Dialog.SELECTION));

        ScrScreenOverlay surfaces = placement.getSurfaces();
        int first = PICTURE_TOP * WIDTH + PICTURE_LEFT;

        Assertions.assertEquals(3, surfaces.getShownCount());
        Assertions.assertEquals(1, surfaces.getPixel(first));
        Assertions.assertEquals(3, surfaces.getPixel(first + 2));
        Assertions.assertFalse(surfaces.isShown(first + 3), "a 0 pixel is transparent");
    }

    @Test
    void a_written_transparency_masks_the_console_and_the_pictures() {
        ClassicAddressSpace space = createSpace();
        ClassicConsoleGraphics graphics = observe(space);

        writePicture(space);

        // Rows above the picture skip 255, 255 and 130 pixels and end; the picture's first row skips 12 pixels, shows 2 and ends.
        for (int row = 0; row < PICTURE_TOP; row++) {
            space.writeDword(ClassicMemory.CONSOLE_TRANSPARENCY_DATA + row * SKIPPED_ROW_SIZE, SKIPPED_ROW_START);
            space.writeDword(ClassicMemory.CONSOLE_TRANSPARENCY_DATA + row * SKIPPED_ROW_SIZE + Integer.BYTES, SKIPPED_ROW_END);
        }

        space.writeDword(ClassicMemory.CONSOLE_TRANSPARENCY_DATA + PICTURE_TOP * SKIPPED_ROW_SIZE, 2 << Byte.SIZE | PICTURE_LEFT + 1);

        ScrConsolePlacement placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertTrue(placement.isMasked());
        Assertions.assertFalse(placement.isShown(0, 0));
        Assertions.assertFalse(placement.isShown(PICTURE_LEFT, PICTURE_TOP));
        Assertions.assertTrue(placement.isShown(PICTURE_LEFT + 1, PICTURE_TOP));
        Assertions.assertTrue(placement.isShown(PICTURE_LEFT + 2, PICTURE_TOP));
        Assertions.assertFalse(placement.isShown(PICTURE_LEFT + 3, PICTURE_TOP));
        Assertions.assertFalse(placement.isShown(PICTURE_LEFT + 1, PICTURE_TOP + 1));

        ScrScreenOverlay surfaces = placement.getSurfaces();
        int first = PICTURE_TOP * WIDTH + PICTURE_LEFT;

        Assertions.assertEquals(2, surfaces.getShownCount());
        Assertions.assertFalse(surfaces.isShown(first));
        Assertions.assertEquals(2, surfaces.getPixel(first + 1));
    }

    @Test
    void written_resource_icons_replace_the_installed_ones() {
        ClassicAddressSpace space = createSpace();
        ClassicConsoleGraphics graphics = observe(space);

        Assertions.assertNull(graphics.findResourceIcons());

        for (int index = 0; index < ICON_GRP.length; index++) {
            space.writeDword(ICONS + index * Integer.BYTES, ICON_GRP[index]);
        }

        SpriteSheet icons = graphics.findResourceIcons();

        Assertions.assertNotNull(icons);
        Assertions.assertSame(icons, graphics.findResourceIcons(), "the same sheet while the icons are unchanged");
        Assertions.assertEquals(1, icons.getFrameCount());

        SpriteFrame frame = icons.getFrame(0);

        Assertions.assertEquals(ICON_LEFT, frame.getX());
        Assertions.assertEquals(ICON_TOP, frame.getY());
        Assertions.assertEquals(ICON_WIDTH, frame.getWidth());
        Assertions.assertEquals(ICON_HEIGHT, frame.getHeight());

        for (int pixel = 0; pixel < ICON_WIDTH * ICON_HEIGHT; pixel++) {
            Assertions.assertEquals(pixel + 1, icons.getColor().getValue(frame.getPixelOffset() + pixel));
        }

        space.writeDword(ICONS, 0);

        Assertions.assertNull(graphics.findResourceIcons(), "no frame is not a GRP");
    }

    @Test
    void a_console_area_owner_shows_its_written_picture_and_leaves_the_dialogs() {
        ClassicAddressSpace space = createSpace();
        ClassicConsoleGraphics graphics = observe(space);

        space.writeDword(AREA_OWNER + ClassicDialogStructure.RECTANGLE_OFFSET, PICTURE_TOP << Short.SIZE | PICTURE_LEFT);
        space.writeDword(AREA_OWNER + ClassicDialogStructure.PICTURE_SIZE_OFFSET, PICTURE_HEIGHT << Short.SIZE | PICTURE_WIDTH);
        space.writeDword(AREA_PICTURE, PICTURE_ROW);

        ScrConsolePlacement placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);

        for (ScrConsolePlacement.Dialog dialog : ScrConsolePlacement.Dialog.values()) {
            Assertions.assertFalse(placement.isDialogHidden(dialog) || placement.isDialogMoved(dialog), dialog.name());
        }

        ScrScreenOverlay surfaces = placement.getSurfaces();
        int first = PICTURE_TOP * WIDTH + PICTURE_LEFT;

        Assertions.assertEquals(3, surfaces.getShownCount());
        Assertions.assertEquals(1, surfaces.getPixel(first));
    }

    @Test
    void script_settings_win_over_eud_memory_item_by_item() {
        ClassicAddressSpace space = createSpace();
        ConsoleSettings settings = new ConsoleSettings();
        ClassicConsoleGraphics graphics = new ClassicConsoleGraphics(() -> settings);
        Picture art = new Picture(2, 1, PixelLayer.ofRgba(new int[]{SCRIPT_COLOR, 0}));
        Picture icon = new Picture(1, 1, PixelLayer.ofRgba(new int[]{SCRIPT_COLOR}));

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));

        Assertions.assertNull(graphics.findConsolePlacement(), "nothing set yet");

        settings.setDialogPosition(ScrConsolePlacement.Dialog.PORTRAIT, SCRIPT_LEFT, SCRIPT_TOP);

        ScrConsolePlacement placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement, "a script setting places the console without EUD writes");
        Assertions.assertEquals(SCRIPT_LEFT, placement.getDialogLeft(ScrConsolePlacement.Dialog.PORTRAIT));

        space.writeDword(getDialog(MENU) + ClassicDialogStructure.RECTANGLE_OFFSET, MENU_TOP << Short.SIZE | MENU_LEFT);
        space.writeDword(getDialog(TEXT_BOX) + ClassicDialogStructure.RECTANGLE_OFFSET, TEXT_BOX_TOP << Short.SIZE | TEXT_BOX_LEFT);
        writePicture(space);
        settings.setDialogPosition(ScrConsolePlacement.Dialog.MENU, SCRIPT_LEFT, SCRIPT_TOP);
        settings.setDialogVisible(ScrConsolePlacement.Dialog.SELECTION, true);
        settings.setDialogVisible(ScrConsolePlacement.Dialog.COMMAND_CARD, false);

        placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertEquals(SCRIPT_LEFT, placement.getDialogLeft(ScrConsolePlacement.Dialog.MENU), "the script position wins");
        Assertions.assertEquals(SCRIPT_TOP, placement.getDialogTop(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(TEXT_BOX_LEFT, placement.getDialogLeft(ScrConsolePlacement.Dialog.TEXT_BOX), "EUD drives the unset items");
        Assertions.assertFalse(placement.isDialogHidden(ScrConsolePlacement.Dialog.SELECTION), "the script visibility wins over the picture");
        Assertions.assertEquals(PICTURE_LEFT, placement.getDialogLeft(ScrConsolePlacement.Dialog.SELECTION));
        Assertions.assertTrue(placement.isDialogHidden(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertEquals(3, placement.getSurfaces().getShownCount(), "the EUD picture stays");

        settings.setDialogVisible(ScrConsolePlacement.Dialog.COMMAND_CARD, true);

        Assertions.assertFalse(graphics.findConsolePlacement().isDialogHidden(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertFalse(graphics.findConsolePlacement().isDialogMoved(ScrConsolePlacement.Dialog.COMMAND_CARD));

        for (int index = 0; index < ICON_GRP.length; index++) {
            space.writeDword(ICONS + index * Integer.BYTES, ICON_GRP[index]);
        }

        settings.setResourceIcon(0, icon);
        settings.setArt(ScrConsoleArt.CONSOLE_RIGHT, art);

        Assertions.assertFalse(graphics.isConsoleHidden());
        Assertions.assertSame(icon, graphics.findResourceIconPicture(0));
        Assertions.assertNull(graphics.findResourceIconPicture(1), "EUD memory drives the other icons");
        Assertions.assertNotNull(graphics.findResourceIcons());
        Assertions.assertSame(art, graphics.findConsoleArt(ScrConsoleArt.CONSOLE_RIGHT));
        Assertions.assertNull(graphics.findConsoleArt(ScrConsoleArt.CONSOLE_LEFT), "each art file on its own");

        settings.setConsoleVisible(false);

        Assertions.assertTrue(graphics.isConsoleHidden());
    }

    @Test
    void script_control_settings_win_over_eud_memory_until_restored() {
        ClassicAddressSpace space = createSpace();
        ConsoleSettings settings = new ConsoleSettings();
        ClassicConsoleGraphics graphics = new ClassicConsoleGraphics(() -> settings);
        int secondButton = getDialog(COMMAND_CARD) + ClassicDialogStructure.getControlOffset(SECOND_BUTTON_INDEX);

        graphics.onAddressSpaceCreated(new ClassicMemoryView(space));
        settings.setControlPosition(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON, SCRIPT_LEFT, SCRIPT_TOP);
        settings.setControlVisible(ScrConsolePlacement.Dialog.MENU, BUTTON, false);

        ScrConsolePlacement placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement, "a script control setting places the console without EUD writes");
        Assertions.assertEquals(SCRIPT_LEFT, placement.getControlLeft(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON));
        Assertions.assertEquals(SCRIPT_TOP, placement.getControlTop(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON));
        Assertions.assertTrue(placement.isControlHidden(ScrConsolePlacement.Dialog.MENU, BUTTON));
        Assertions.assertFalse(placement.isDialogMoved(ScrConsolePlacement.Dialog.COMMAND_CARD));

        writeUnaligned(space, secondButton + ClassicDialogStructure.FLAGS_OFFSET, 0);
        settings.setControlVisible(ScrConsolePlacement.Dialog.COMMAND_CARD, SECOND_BUTTON, true);

        placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertFalse(placement.isControlHidden(ScrConsolePlacement.Dialog.COMMAND_CARD, SECOND_BUTTON), "the script visibility wins");
        Assertions.assertEquals(SCRIPT_LEFT, placement.getControlLeft(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON));

        settings.restoreControl(ScrConsolePlacement.Dialog.COMMAND_CARD, SECOND_BUTTON);
        settings.restoreControl(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON);
        settings.restoreControl(ScrConsolePlacement.Dialog.MENU, BUTTON);

        placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertTrue(placement.isControlHidden(ScrConsolePlacement.Dialog.COMMAND_CARD, SECOND_BUTTON), "EUD memory drives it again");
        Assertions.assertFalse(placement.isControlMoved(ScrConsolePlacement.Dialog.COMMAND_CARD, BUTTON), "a restored control keeps its place");
        Assertions.assertEquals(0, placement.getPlacedControls(ScrConsolePlacement.Dialog.MENU).length);
    }
}
