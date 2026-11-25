package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests the init, pack, unpack and trace commands of {@link ScriptTool} on map files and script folders, protected maps and compiled TypeScript
 * included.
 */
final class ScriptToolTest {
    private static final byte @NotNull [] VERSION_PAYLOAD = {(byte) 0xCD, 0x00};
    private static final @NotNull Map<String, String> SCRIPT_FOLDER = Map.of(
            ScriptManifest.FILE_NAME, "{\n  \"name\": \"Round Trip\",\n  \"main\": \"main.js\",\n  \"api\": \"1\"\n}\n",
            "main.js", "const util = require('lib/util.js');\nfunction onStart() {\n    util.greet();\n}\n",
            "lib/util.js", "exports.greet = function () {\n    game.print('안녕');\n};\n",
            "resources/images/marine.grp", "GRP data",
            "resources/sounds/hit.wav", "RIFF data"
    );

    /**
     * A script folder whose main script fails on line 5 of the original, line 3 of the protected script.
     */
    private static final @NotNull Map<String, String> FAILING_FOLDER = Map.of(
            ScriptManifest.FILE_NAME, "{\"name\": \"Failing\", \"main\": \"main.js\", \"api\": \"1\"}",
            "main.js", "// Line 1.\n\nfunction onStart() {\n    var missing = null;\n    return missing.value;\n}\n"
    );

    /**
     * A script folder with the output of tsc: {@code main.js}, whose line 5 fails, with its source map {@code main.js.map}, which maps lines 3 to 6
     * to lines 2 to 5 of {@code src/main.ts}; the source and a resource named {@code .ts}.
     */
    private static final @NotNull Map<String, String> TYPESCRIPT_FOLDER = Map.of(
            ScriptManifest.FILE_NAME, "{\"name\": \"Compiled\", \"main\": \"main.js\", \"api\": \"1\"}",
            "main.js", "\"use strict\";\nObject.defineProperty(exports, \"__esModule\", { value: true });\nfunction onStart() {\n" +
                       "    var missing = null;\n    return missing.value;\n}\n//# sourceMappingURL=main.js.map",
            "main.js.map", "{\"version\":3,\"file\":\"main.js\",\"sourceRoot\":\"\",\"sources\":[\"src/main.ts\"],\"names\":[]," +
                           "\"mappings\":\";;AACA;IACI;IACA;AACJ\"}",
            "src/main.ts", "// The error is on line 4.\nfunction onStart(): number {\n    const missing: { value: number } | null = null;\n" +
                           "    return missing!.value;\n}\n",
            "resources/notes.ts", "Kept as a resource."
    );

    private static int runTool(@NotNull String @NotNull ... arguments) {
        return runTool(new ByteArrayOutputStream(), new ByteArrayOutputStream(), arguments);
    }

    private static int runTool(@NotNull ByteArrayOutputStream output, @NotNull ByteArrayOutputStream errors, @NotNull String @NotNull ... arguments) {
        return ScriptTool.run(arguments, new PrintStream(output, true, StandardCharsets.UTF_8),
                              new PrintStream(errors, true, StandardCharsets.UTF_8));
    }

    private static @NotNull Path writeMap(@NotNull Path folder) throws IOException {
        Path map = folder.resolve("map.scx");

        Files.write(map, ScriptMapScenario.createMap(ScriptMapScenario.createSection(ChkFile.VERSION_SECTION, VERSION_PAYLOAD)));

        return map;
    }

    @Test
    void pack_then_unpack_gives_the_same_folder(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder);
        Path scriptFolder = folder.resolve("script");
        Path packedMap = folder.resolve("packed.scx");
        Path unpackedFolder = folder.resolve("unpacked");

        ScriptMapScenario.writeFolder(scriptFolder, SCRIPT_FOLDER);

        Assertions.assertEquals(0, runTool(ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, scriptFolder.toString(),
                                           ScriptTool.OUTPUT_OPTION, packedMap.toString()));
        Assertions.assertEquals(0, runTool(ScriptTool.UNPACK_COMMAND, packedMap.toString(), ScriptTool.OUTPUT_OPTION, unpackedFolder.toString()));
        Assertions.assertEquals(ScriptMapScenario.readFolder(scriptFolder), ScriptMapScenario.readFolder(unpackedFolder));
    }

    @Test
    void unpack_should_fail_when_the_map_has_no_script_package(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder);

        Assertions.assertEquals(1, runTool(ScriptTool.UNPACK_COMMAND, map.toString(), ScriptTool.OUTPUT_OPTION, folder.resolve("out").toString()));
        Assertions.assertFalse(Files.exists(folder.resolve("out")));
    }

    @Test
    void unpack_should_fail_when_the_folder_is_not_empty(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder);
        Path scriptFolder = folder.resolve("script");
        Path packedMap = folder.resolve("packed.scx");

        ScriptMapScenario.writeFolder(scriptFolder, SCRIPT_FOLDER);

        Assertions.assertEquals(0, runTool(ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, scriptFolder.toString(),
                                           ScriptTool.OUTPUT_OPTION, packedMap.toString()));
        Assertions.assertEquals(1, runTool(ScriptTool.UNPACK_COMMAND, packedMap.toString(), ScriptTool.OUTPUT_OPTION, scriptFolder.toString()));
    }

    @Test
    void pack_protect_writes_a_protected_map_and_its_source_map_that_unpack_refuses(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder);
        Path scriptFolder = folder.resolve("script");
        Path packedMap = folder.resolve("packed.scx");
        Path unpackedFolder = folder.resolve("unpacked");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        ScriptMapScenario.writeFolder(scriptFolder, SCRIPT_FOLDER);

        Assertions.assertEquals(0, runTool(ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, scriptFolder.toString(),
                                           ScriptTool.OUTPUT_OPTION, packedMap.toString(), ScriptTool.PROTECT_OPTION));

        ScriptTrailer trailer = ScriptStorageUtils.readTrailer(Files.readAllBytes(packedMap), packedMap.toString());

        Assertions.assertNotNull(trailer);
        Assertions.assertTrue(trailer.isProtected());
        Assertions.assertEquals("const util=require('lib/util.js');\nfunction onStart(){\nutil.greet();\n}",
                                new String(trailer.getScripts().getScripts().get("main.js"), StandardCharsets.UTF_8));
        Assertions.assertEquals(2, trailer.getResources().getEntries().size());
        Assertions.assertTrue(Files.isRegularFile(ScriptSourceMap.resolveFile(packedMap)));
        Assertions.assertEquals(1, runTool(new ByteArrayOutputStream(), errors, ScriptTool.UNPACK_COMMAND, packedMap.toString(),
                                           ScriptTool.OUTPUT_OPTION, unpackedFolder.toString()));
        Assertions.assertTrue(errors.toString(StandardCharsets.UTF_8).contains("is protected"), errors.toString(StandardCharsets.UTF_8));
        Assertions.assertFalse(Files.exists(unpackedFolder));
    }

    @Test
    void trace_prints_the_original_locations_of_a_protected_script(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder);
        Path scriptFolder = folder.resolve("script");
        Path packedMap = folder.resolve("packed.scx");
        Path log = folder.resolve("player.log");
        ByteArrayOutputStream location = new ByteArrayOutputStream();
        ByteArrayOutputStream translatedLog = new ByteArrayOutputStream();

        ScriptMapScenario.writeFolder(scriptFolder, FAILING_FOLDER);
        Files.writeString(log, "ERROR main.js: onStart failed: TypeError: Cannot read property \"value\" from null (main.js:3)\n",
                          StandardCharsets.UTF_8);

        Assertions.assertEquals(0, runTool(ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, scriptFolder.toString(),
                                           ScriptTool.OUTPUT_OPTION, packedMap.toString(), ScriptTool.PROTECT_OPTION));

        String sourceMap = ScriptSourceMap.resolveFile(packedMap).toString();

        Assertions.assertEquals(0, runTool(location, new ByteArrayOutputStream(), ScriptTool.TRACE_COMMAND, sourceMap, ScriptTool.AT_OPTION,
                                           "main.js:3"));
        Assertions.assertEquals(0, runTool(translatedLog, new ByteArrayOutputStream(), ScriptTool.TRACE_COMMAND, sourceMap, ScriptTool.LOG_OPTION,
                                           log.toString()));
        Assertions.assertEquals("main.js:5", location.toString(StandardCharsets.UTF_8).strip());
        Assertions.assertEquals("ERROR main.js: onStart failed: TypeError: Cannot read property \"value\" from null (main.js:5)\n",
                                translatedLog.toString(StandardCharsets.UTF_8));
        Assertions.assertEquals(1, runTool(ScriptTool.TRACE_COMMAND, sourceMap, ScriptTool.AT_OPTION, "main.js:99"));
        Assertions.assertEquals(1, runTool(ScriptTool.TRACE_COMMAND, sourceMap, ScriptTool.AT_OPTION, "main.js"));
        Assertions.assertEquals(1, runTool(ScriptTool.TRACE_COMMAND, sourceMap));
        Assertions.assertEquals(1, runTool(ScriptTool.TRACE_COMMAND, folder.resolve("missing.json").toString(), ScriptTool.AT_OPTION, "main.js:3"));
    }

    @Test
    void pack_leaves_typescript_sources_and_map_files_out_and_keeps_the_source_maps_in_the_scripts(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder);
        Path scriptFolder = folder.resolve("script");
        Path packedMap = folder.resolve("packed.scx");

        ScriptMapScenario.writeFolder(scriptFolder, TYPESCRIPT_FOLDER);

        Assertions.assertEquals(0, runTool(ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, scriptFolder.toString(),
                                           ScriptTool.OUTPUT_OPTION, packedMap.toString()));

        ScriptTrailer trailer = ScriptStorageUtils.readTrailer(Files.readAllBytes(packedMap), packedMap.toString());

        Assertions.assertNotNull(trailer);

        ScriptPackage packed = trailer.getScripts();
        String reference = ScriptCompiledMapUtils.findReference(new String(packed.getScripts().get("main.js"), StandardCharsets.UTF_8));

        Assertions.assertNotNull(reference);

        Map<?, ?> inlineMap = (Map<?, ?>) JsonReader.parse(Base64.getDecoder().decode(reference.substring(reference.indexOf(',') + 1)));

        Assertions.assertEquals(Set.of("main.js"), packed.getScripts().keySet());
        Assertions.assertEquals(List.of("notes.ts"), trailer.getResources().getEntries().stream().map(ScriptResources.Entry::getName).toList());
        Assertions.assertEquals(Map.of("version", 3L, "sources", List.of("src/main.ts"), "mappings", ";;AACA;AACA;AACA;AACA"), inlineMap);
        Assertions.assertEquals("src/main.ts:4", ScriptSourceMap.readCompiled(packed.getScripts()).findOriginalLocation("main.js", 5));
        Assertions.assertFalse(Files.exists(ScriptSourceMap.resolveFile(packedMap)));
    }

    @Test
    void trace_prints_the_typescript_locations_of_a_protected_compiled_script(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder);
        Path scriptFolder = folder.resolve("script");
        Path packedMap = folder.resolve("packed.scx");
        Path log = folder.resolve("player.log");
        ByteArrayOutputStream location = new ByteArrayOutputStream();
        ByteArrayOutputStream translatedLog = new ByteArrayOutputStream();

        ScriptMapScenario.writeFolder(scriptFolder, TYPESCRIPT_FOLDER);
        Files.writeString(log, "ERROR main.js: onStart failed: TypeError: Cannot read property \"value\" from null (main.js:5)\n",
                          StandardCharsets.UTF_8);

        Assertions.assertEquals(0, runTool(ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, scriptFolder.toString(),
                                           ScriptTool.OUTPUT_OPTION, packedMap.toString(), ScriptTool.PROTECT_OPTION));

        ScriptTrailer trailer = ScriptStorageUtils.readTrailer(Files.readAllBytes(packedMap), packedMap.toString());
        String sourceMap = ScriptSourceMap.resolveFile(packedMap).toString();

        Assertions.assertNotNull(trailer);

        String protectedScript = new String(trailer.getScripts().getScripts().get("main.js"), StandardCharsets.UTF_8);

        Assertions.assertNull(ScriptCompiledMapUtils.findReference(protectedScript));
        Assertions.assertEquals(0, runTool(location, new ByteArrayOutputStream(), ScriptTool.TRACE_COMMAND, sourceMap, ScriptTool.AT_OPTION,
                                           "main.js:5"));
        Assertions.assertEquals(0, runTool(translatedLog, new ByteArrayOutputStream(), ScriptTool.TRACE_COMMAND, sourceMap, ScriptTool.LOG_OPTION,
                                           log.toString()));
        Assertions.assertEquals("src/main.ts:4", location.toString(StandardCharsets.UTF_8).strip());
        Assertions.assertEquals("ERROR main.js: onStart failed: TypeError: Cannot read property \"value\" from null (src/main.ts:4)\n",
                                translatedLog.toString(StandardCharsets.UTF_8));
    }

    @Test
    void pack_should_fail_when_a_script_names_a_missing_source_map(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder);
        Path scriptFolder = folder.resolve("script");
        Path packedMap = folder.resolve("packed.scx");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        ScriptMapScenario.writeFolder(scriptFolder, TYPESCRIPT_FOLDER);
        Files.delete(scriptFolder.resolve("main.js.map"));

        Assertions.assertEquals(1, runTool(new ByteArrayOutputStream(), errors, ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION,
                                           scriptFolder.toString(), ScriptTool.OUTPUT_OPTION, packedMap.toString()));
        Assertions.assertEquals("The script main.js names the source map main.js.map, which is not a file.",
                                errors.toString(StandardCharsets.UTF_8).strip());
        Assertions.assertFalse(Files.exists(packedMap));
    }

    @Test
    void init_ts_writes_a_typescript_project(@TempDir @NotNull Path folder) {
        Path project = folder.resolve("my-map");

        Assertions.assertEquals(0, runTool(ScriptTool.INIT_COMMAND, project.toString(), ScriptTool.TYPESCRIPT_OPTION));
        Assertions.assertTrue(Files.isRegularFile(project.resolve(ScriptProjectUtils.TSCONFIG_FILE_NAME)));
        Assertions.assertTrue(Files.isRegularFile(project.resolve(ScriptProjectUtils.SCRIPT_FOLDER).resolve(ScriptManifest.FILE_NAME)));
        Assertions.assertEquals(1, runTool(ScriptTool.INIT_COMMAND, project.toString(), ScriptTool.TYPESCRIPT_OPTION));
        Assertions.assertEquals(1, runTool(ScriptTool.INIT_COMMAND, folder.resolve("other").toString(), "--typescript"));
    }

    @Test
    void run_should_fail_when_an_option_is_missing_or_unknown(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder);

        Assertions.assertEquals(1, runTool(ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, folder.toString()));
        Assertions.assertEquals(1, runTool(ScriptTool.UNPACK_COMMAND, map.toString(), "--output", folder.toString()));
        Assertions.assertEquals(1, runTool("verify", map.toString()));
        Assertions.assertEquals(1, runTool(ScriptTool.RUN_COMMAND, map.toString(), ScriptTool.WATCH_OPTION));
        Assertions.assertEquals(1, runTool(ScriptTool.RUN_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, folder.toString(),
                                           ScriptTool.MELEE_OPTION, ScriptTool.MELEE_OPTION));
        Assertions.assertEquals(1, runTool(ScriptTool.RUN_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, folder.toString(),
                                           ScriptTool.INSPECT_OPTION + "=port"));
        Assertions.assertEquals(1, runTool(ScriptTool.RUN_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, folder.toString(),
                                           ScriptTool.INSPECT_OPTION + "=0"));
        Assertions.assertEquals(1, runTool(ScriptTool.RUN_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, folder.toString(),
                                           ScriptTool.INSPECT_OPTION, ScriptTool.INSPECT_OPTION + "=9340"));
        Assertions.assertEquals(1, runTool(ScriptTool.PACK_COMMAND));
    }
}
