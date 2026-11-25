package org.fogofwarlabs.sc4j.screnhanced.data;

import org.fogofwarlabs.sc4j.scr.client.glue.lobby.FileBrowserScreen;
import org.fogofwarlabs.sc4j.scr.data.extension.ScrDataExtensionUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * Tests that SC:R Enhanced declares its map extension to the SC:R mod, whose file browser and sessions then take such maps.
 */
final class EnhancedMapExtensionTest {
    @Test
    void the_scr_mod_takes_the_declared_map_extension() {
        Assertions.assertEquals(List.of(".sce"), ScrDataExtensionUtils.getExtraMapFileExtensions());
        Assertions.assertTrue(FileBrowserScreen.isMapFile(Path.of("Arena.sce")));
        Assertions.assertTrue(FileBrowserScreen.isMapFile(Path.of("maps", "ARENA.SCE")));
        Assertions.assertTrue(FileBrowserScreen.isMapFile(Path.of("Arena.scx")));
        Assertions.assertFalse(FileBrowserScreen.isMapFile(Path.of("Arena.rep")));
    }
}
