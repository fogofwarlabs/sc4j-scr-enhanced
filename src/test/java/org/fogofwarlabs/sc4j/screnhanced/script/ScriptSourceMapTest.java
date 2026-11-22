package org.fogofwarlabs.sc4j.screnhanced.script;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EcmaError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests the file round trip of {@link ScriptSourceMap}, the lookup of original lines, and the translation of the error locations of protected
 * scripts back to the original lines.
 */
final class ScriptSourceMapTest {
    private static final @NotNull String MAIN_SCRIPT = "main.js";
    private static final @NotNull String LIBRARY_SCRIPT = "lib/main.js";

    /**
     * Runs a script in a Rhino context with the settings of the runtime and returns the line of the error it throws.
     */
    private static int findErrorLine(@NotNull String source) {
        try (Context context = new ContextFactory().enterContext()) {
            context.setInterpretedMode(true);
            context.setLanguageVersion(Context.VERSION_ECMASCRIPT);

            EcmaError error = Assertions.assertThrows(EcmaError.class, () -> context.evaluateString(context.initStandardObjects(), source,
                                                                                                    MAIN_SCRIPT, 1, null));

            return error.lineNumber();
        }
    }

    @Test
    void write_then_read_gives_the_same_map(@TempDir @NotNull Path folder) throws IOException {
        ScriptSourceMap sourceMap = new ScriptSourceMap();
        Path file = ScriptSourceMap.resolveFile(folder.resolve("packed.scx"));

        sourceMap.addScript(MAIN_SCRIPT, new int[]{3, 4, 9}, List.of(new ScriptSourceMap.Rename("a", "count", 4)));
        sourceMap.addScript(LIBRARY_SCRIPT, new int[]{1}, List.of());
        sourceMap.write(file);

        ScriptSourceMap read = ScriptSourceMap.read(file);
        ScriptSourceMap.Rename rename = read.getRenames(MAIN_SCRIPT).getFirst();

        Assertions.assertEquals(folder.resolve("packed.scx" + ScriptSourceMap.FILE_SUFFIX), file);
        Assertions.assertEquals(9, read.findOriginalLine(MAIN_SCRIPT, 3));
        Assertions.assertEquals(1, read.findOriginalLine(LIBRARY_SCRIPT, 1));
        Assertions.assertEquals(ScriptSourceMap.NO_LINE, read.findOriginalLine(MAIN_SCRIPT, 4));
        Assertions.assertEquals(ScriptSourceMap.NO_LINE, read.findOriginalLine(MAIN_SCRIPT, 0));
        Assertions.assertEquals(ScriptSourceMap.NO_LINE, read.findOriginalLine("other.js", 1));
        Assertions.assertEquals("a", rename.getName());
        Assertions.assertEquals("count", rename.getOriginal());
        Assertions.assertEquals(4, rename.getLine());
        Assertions.assertTrue(read.getRenames(LIBRARY_SCRIPT).isEmpty());
    }

    @Test
    void read_should_fail_when_the_file_has_another_version(@TempDir @NotNull Path folder) throws IOException {
        Path file = folder.resolve("other.sourcemap.json");

        Files.writeString(file, "{\"version\": 2, \"scripts\": {}}", StandardCharsets.UTF_8);

        IOException refusal = Assertions.assertThrows(IOException.class, () -> ScriptSourceMap.read(file));

        Assertions.assertTrue(refusal.getMessage().contains("version"), refusal.getMessage());
    }

    @Test
    void the_error_line_of_a_protected_script_maps_to_the_original_line() throws IOException {
        String source = """
                        // The error is on line 8.
                        function fail(frame) {
                            var units = [];
                        
                            /* Nothing here,
                               nor here. */
                            var missing = null;
                            return units.length + missing.property + frame;
                        }
                        fail(1);
                        """;
        ScriptProtector protector = ScriptProtector.protect(MAIN_SCRIPT, source);
        ScriptSourceMap sourceMap = new ScriptSourceMap();
        int protectedLine = findErrorLine(protector.getText());

        sourceMap.addScript(MAIN_SCRIPT, protector.getLines(), protector.getRenames());

        Assertions.assertEquals(8, findErrorLine(source));
        Assertions.assertNotEquals(8, protectedLine);
        Assertions.assertEquals(8, sourceMap.findOriginalLine(MAIN_SCRIPT, protectedLine));
        Assertions.assertEquals("(main.js:8)", sourceMap.translate("(main.js:" + protectedLine + ")"));
    }

    @Test
    void translate_replaces_the_locations_of_the_scripts_of_the_map_only() {
        ScriptSourceMap sourceMap = new ScriptSourceMap();

        sourceMap.addScript(MAIN_SCRIPT, new int[]{5, 8}, List.of());
        sourceMap.addScript(LIBRARY_SCRIPT, new int[]{2}, List.of());

        Assertions.assertEquals("ERROR main.js: onFrame failed: TypeError: x (main.js:8)\n\tat lib/main.js:2 (a)\n\tat main.js:3\n" +
                                "\tat other/main.js:1\n\tat xmain.js:1",
                                sourceMap.translate("ERROR main.js: onFrame failed: TypeError: x (main.js:2)\n\tat lib/main.js:1 (a)\n" +
                                                    "\tat main.js:3\n\tat other/main.js:1\n\tat xmain.js:1"));
        Assertions.assertEquals("main.js:5", new ScriptSourceMap().translate("main.js:5"));
    }
}
