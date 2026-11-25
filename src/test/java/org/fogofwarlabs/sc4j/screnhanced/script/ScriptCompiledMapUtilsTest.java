package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Tests the source maps of compiled scripts: the reference on the last line, the map file put into the script, and the lines of the base64 VLQ
 * mappings with the source names that tsc gives.
 */
final class ScriptCompiledMapUtilsTest {
    private static final @NotNull String SCRIPT_NAME = "lib/util.js";
    /**
     * A script as tsc compiles {@code src/lib/util.ts} into {@code script/lib/util.js}.
     */
    private static final @NotNull String SCRIPT = """
                                                  "use strict";
                                                  var a = 1;
                                                  function f() {
                                                      return a.b.c;
                                                  }
                                                  //# sourceMappingURL=util.js.map""";
    /**
     * The map of {@link #SCRIPT}: line 1 has no segment, line 2 maps to line 40 (a VLQ of two digits), line 3 back to line 2 (a negative
     * delta), line 4 to line 3 with a second segment, line 5 to line 4.
     */
    private static final @NotNull String MAP = "{\"version\":3,\"file\":\"util.js\",\"sourceRoot\":\"\",\"sources\":[\"../../src/lib/util.ts\"]," +
                                               "\"names\":[],\"mappings\":\";AAuCA;AAtCA;IACI,KAAK;AACJ\"}";

    private static @NotNull String toDataUrl(@NotNull String map) {
        return "data:application/json;base64," + Base64.getEncoder().encodeToString(map.getBytes(StandardCharsets.UTF_8));
    }

    private static @NotNull String withReference(@NotNull String reference) {
        return SCRIPT.replace("util.js.map", reference);
    }

    @Test
    void find_reference_should_read_the_last_line_only() {
        Assertions.assertEquals("util.js.map", ScriptCompiledMapUtils.findReference(SCRIPT + "\n\n"));
        Assertions.assertEquals("main.js.map", ScriptCompiledMapUtils.findReference("var a;\r\n//@ sourceMappingURL=main.js.map\r\n"));
        Assertions.assertNull(ScriptCompiledMapUtils.findReference(SCRIPT + "\nvar b = 2;"));
        Assertions.assertNull(ScriptCompiledMapUtils.findReference("var a; //# sourceMappingURL=util.js.map"));
        Assertions.assertNull(ScriptCompiledMapUtils.findReference(""));
    }

    @Test
    void inline_map_should_put_the_named_map_file_into_the_script_with_its_lines(@TempDir @NotNull Path folder) throws IOException {
        Files.createDirectories(folder.resolve("lib"));
        Files.writeString(folder.resolve("lib/util.js.map"), MAP, StandardCharsets.UTF_8);

        String inlined = new String(ScriptCompiledMapUtils.inlineMap(folder, SCRIPT_NAME, SCRIPT.getBytes(StandardCharsets.UTF_8)),
                                    StandardCharsets.UTF_8);
        ScriptSourceMap sourceMap = ScriptSourceMap.readCompiled(Map.of(SCRIPT_NAME, inlined.getBytes(StandardCharsets.UTF_8)));

        Assertions.assertEquals(withReference(toDataUrl(MAP)), inlined);
        Assertions.assertEquals(toDataUrl(MAP), ScriptCompiledMapUtils.findReference(inlined));
        Assertions.assertEquals("lib/util.js:1", sourceMap.findOriginalLocation(SCRIPT_NAME, 1));
        Assertions.assertEquals("src/lib/util.ts:40", sourceMap.findOriginalLocation(SCRIPT_NAME, 2));
        Assertions.assertEquals("src/lib/util.ts:2", sourceMap.findOriginalLocation(SCRIPT_NAME, 3));
        Assertions.assertEquals("src/lib/util.ts:3", sourceMap.findOriginalLocation(SCRIPT_NAME, 4));
        Assertions.assertEquals("src/lib/util.ts:4", sourceMap.findOriginalLocation(SCRIPT_NAME, 5));
        Assertions.assertEquals("lib/util.js:6", sourceMap.findOriginalLocation(SCRIPT_NAME, 6));
        Assertions.assertEquals("TypeError (src/lib/util.ts:3)", sourceMap.translate("TypeError (lib/util.js:4)"));
    }

    @Test
    void inline_map_should_keep_a_script_without_a_map_file_reference(@TempDir @NotNull Path folder) throws IOException {
        byte[] plain = "var a = 1;\n".getBytes(StandardCharsets.UTF_8);
        byte[] inline = withReference(toDataUrl(MAP)).getBytes(StandardCharsets.UTF_8);

        Assertions.assertSame(plain, ScriptCompiledMapUtils.inlineMap(folder, SCRIPT_NAME, plain));
        Assertions.assertSame(inline, ScriptCompiledMapUtils.inlineMap(folder, SCRIPT_NAME, inline));
    }

    @Test
    void inline_map_should_fail_when_the_named_map_is_missing_or_no_source_map(@TempDir @NotNull Path folder) throws IOException {
        byte[] script = SCRIPT.getBytes(StandardCharsets.UTF_8);

        IOException missing = Assertions.assertThrows(IOException.class, () -> ScriptCompiledMapUtils.inlineMap(folder, SCRIPT_NAME, script));

        Assertions.assertEquals("The script lib/util.js names the source map util.js.map, which is not a file.", missing.getMessage());

        Files.createDirectories(folder.resolve("lib"));
        Files.writeString(folder.resolve("lib/util.js.map"), "{\"version\":2,\"sources\":[],\"mappings\":\"\"}", StandardCharsets.UTF_8);

        Assertions.assertThrows(IOException.class, () -> ScriptCompiledMapUtils.inlineMap(folder, SCRIPT_NAME, script));

        Files.writeString(folder.resolve("lib/util.js.map"), MAP.replace("AAuCA", "AA!A"), StandardCharsets.UTF_8);

        byte[] textDataUrl = withReference("data:,{}").getBytes(StandardCharsets.UTF_8);

        Assertions.assertThrows(IOException.class, () -> ScriptCompiledMapUtils.inlineMap(folder, SCRIPT_NAME, script));
        Assertions.assertThrows(IOException.class, () -> ScriptCompiledMapUtils.inlineMap(folder, SCRIPT_NAME, textDataUrl));
    }

    @Test
    void shrink_map_should_keep_one_segment_per_mapped_line_and_the_same_locations() throws IOException {
        String full = withReference(toDataUrl(MAP.replace("\"names\":[]", "\"names\":[\"a\"],\"sourcesContent\":[\"var a = 1;\"]")));
        String shrunk = new String(ScriptCompiledMapUtils.shrinkMap(SCRIPT_NAME, full.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        String reference = ScriptCompiledMapUtils.findReference(shrunk);

        Assertions.assertNotNull(reference);

        Map<?, ?> map = (Map<?, ?>) JsonReader.parse(Base64.getDecoder().decode(reference.substring(reference.indexOf(',') + 1)));
        ScriptSourceMap fullMap = ScriptSourceMap.readCompiled(Map.of(SCRIPT_NAME, full.getBytes(StandardCharsets.UTF_8)));
        ScriptSourceMap shrunkMap = ScriptSourceMap.readCompiled(Map.of(SCRIPT_NAME, shrunk.getBytes(StandardCharsets.UTF_8)));
        byte[] plain = SCRIPT.getBytes(StandardCharsets.UTF_8);

        Assertions.assertEquals(SCRIPT.substring(0, SCRIPT.lastIndexOf('\n')), shrunk.substring(0, shrunk.lastIndexOf('\n')));
        Assertions.assertEquals(Map.of("version", 3L, "sources", List.of("../../src/lib/util.ts"), "mappings", ";AAuCA;AAtCA;AACA;AACA"), map);

        for (int line = 1; line <= 6; line++) {
            Assertions.assertEquals(fullMap.findOriginalLocation(SCRIPT_NAME, line), shrunkMap.findOriginalLocation(SCRIPT_NAME, line));
        }

        Assertions.assertSame(plain, ScriptCompiledMapUtils.shrinkMap(SCRIPT_NAME, plain));
    }

    @Test
    void read_compiled_maps_the_lines_of_the_first_source_only_and_resolves_the_source_root() throws IOException {
        String map = "{\"version\":3,\"sourceRoot\":\"ts/\",\"sources\":[\"main.ts\",\"other.ts\"],\"mappings\":\"AAAA;ACAA;ADEA\"}";
        ScriptSourceMap sourceMap = ScriptSourceMap.readCompiled(Map.of("main.js", ("a;\nb;\nc;\n//# sourceMappingURL=" + toDataUrl(map))
                .getBytes(StandardCharsets.UTF_8)));

        Assertions.assertEquals("ts/main.ts:1", sourceMap.findOriginalLocation("main.js", 1));
        Assertions.assertEquals("main.js:2", sourceMap.findOriginalLocation("main.js", 2));
        Assertions.assertEquals("ts/main.ts:3", sourceMap.findOriginalLocation("main.js", 3));
        Assertions.assertNull(sourceMap.findOriginalLocation("other.js", 1));
    }
}
