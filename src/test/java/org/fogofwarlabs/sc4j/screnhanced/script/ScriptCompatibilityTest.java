package org.fogofwarlabs.sc4j.screnhanced.script;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests the compatibility mark of {@link ScriptTool}'s {@code pack}: the mark of the option, the manifest or the scan, and the extension of the
 * written map, {@value ScriptCompatibility#ENHANCED_MAP_EXTENSION} for a map that needs SC:R Enhanced; and that the commands take such a map.
 */
final class ScriptCompatibilityTest {
    private static final byte @NotNull [] EXPANSION_VERSION_PAYLOAD = {(byte) 0xCD, 0x00};
    private static final @NotNull String GAMEPLAY_SCRIPT = "function onStart() {\n    game.players.get(0).setMinerals(50);\n}\n";
    private static final @NotNull String DISPLAY_SCRIPT = "function onStart() {\n    game.print('Welcome');\n}\n";

    private static @NotNull String createManifest(@NotNull String compatibility) {
        String field = compatibility.isEmpty() ? "" : ", \"compatibility\": \"" + compatibility + "\"";

        return "{\"name\": \"Mark\", \"main\": \"main.js\", \"api\": \"1\"" + field + "}";
    }

    private static @NotNull Path writeFolder(@NotNull Path folder, @NotNull String compatibility, @NotNull String script) throws IOException {
        Path scriptFolder = folder.resolve("script");

        ScriptMapScenario.writeFolder(scriptFolder, Map.of(ScriptManifest.FILE_NAME, createManifest(compatibility), "main.js", script));

        return scriptFolder;
    }

    private static @NotNull Path writeMap(@NotNull Path folder, @NotNull String fileName) throws IOException {
        Path map = folder.resolve(fileName);

        Files.write(map, ScriptMapScenario.createMap(ScriptMapScenario.createSection(ChkFile.VERSION_SECTION, EXPANSION_VERSION_PAYLOAD)));

        return map;
    }

    /**
     * Packs a map and returns the printed lines; the command must succeed.
     */
    private static @NotNull String pack(@NotNull Path map, @NotNull Path scriptFolder, @NotNull Path output,
                                        @NotNull String @NotNull ... options) {
        ByteArrayOutputStream lines = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        List<String> arguments = new ArrayList<>(List.of(ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, scriptFolder.toString(),
                                                         ScriptTool.OUTPUT_OPTION, output.toString()));

        arguments.addAll(List.of(options));

        int exit = ScriptTool.run(arguments.toArray(String[]::new), new PrintStream(lines, true, StandardCharsets.UTF_8),
                                  new PrintStream(errors, true, StandardCharsets.UTF_8));

        Assertions.assertEquals(0, exit, errors.toString(StandardCharsets.UTF_8));

        return lines.toString(StandardCharsets.UTF_8);
    }

    @Test
    void pack_writes_a_map_whose_scripts_change_the_game_as_an_enhanced_only_map(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder, "map.scx");
        Path scriptFolder = writeFolder(folder, "", GAMEPLAY_SCRIPT);
        String lines = pack(map, scriptFolder, folder.resolve("Out.scx"));

        Assertions.assertTrue(Files.isRegularFile(folder.resolve("Out.sce")), lines);
        Assertions.assertFalse(Files.exists(folder.resolve("Out.scx")));
        Assertions.assertTrue(lines.contains(folder.resolve("Out.sce").toString()), lines);
        Assertions.assertTrue(lines.contains("The map is enhanced-only"), lines);
        Assertions.assertTrue(lines.contains("main.js:2: Player.setMinerals"), lines);
    }

    @Test
    void pack_keeps_the_extension_of_a_map_whose_scripts_do_not_change_the_game(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder, "map.scx");
        Path scriptFolder = writeFolder(folder, "", DISPLAY_SCRIPT);
        String lines = pack(map, scriptFolder, folder.resolve("Out.scm"));

        Assertions.assertTrue(Files.isRegularFile(folder.resolve("Out.scm")), lines);
        Assertions.assertTrue(lines.contains("The map is compatible"), lines);
    }

    @Test
    void the_option_wins_over_the_manifest_and_the_manifest_over_the_scan(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder, "map.scx");
        Path compatibleFolder = writeFolder(folder.resolve("compatible"), ScriptCompatibility.COMPATIBLE.getName(), GAMEPLAY_SCRIPT);
        Path enhancedFolder = writeFolder(folder.resolve("enhanced"), ScriptCompatibility.ENHANCED_ONLY.getName(), DISPLAY_SCRIPT);
        String manifestLines = pack(map, compatibleFolder, folder.resolve("First.scx"));

        Assertions.assertTrue(Files.isRegularFile(folder.resolve("First.scx")), manifestLines);
        Assertions.assertTrue(manifestLines.contains("The map is compatible, as the manifest says."), manifestLines);
        Assertions.assertTrue(manifestLines.contains("Warning: the scripts may change the game (main.js:2: Player.setMinerals)"), manifestLines);

        pack(map, enhancedFolder, folder.resolve("Second.scx"));
        pack(map, enhancedFolder, folder.resolve("Third.scx"), ScriptTool.COMPATIBILITY_OPTION, ScriptCompatibility.AUTO.getName());
        pack(map, compatibleFolder, folder.resolve("Fourth.scx"), ScriptTool.COMPATIBILITY_OPTION, ScriptCompatibility.ENHANCED_ONLY.getName());

        Assertions.assertTrue(Files.isRegularFile(folder.resolve("Second.sce")));
        Assertions.assertTrue(Files.isRegularFile(folder.resolve("Third.scx")));
        Assertions.assertTrue(Files.isRegularFile(folder.resolve("Fourth.sce")));
    }

    @Test
    void pack_names_the_output_by_the_mark(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder, "map.sce");
        Path gameplayFolder = writeFolder(folder.resolve("gameplay"), "", GAMEPLAY_SCRIPT);
        Path displayFolder = writeFolder(folder.resolve("display"), "", DISPLAY_SCRIPT);

        pack(map, gameplayFolder, folder.resolve("plain"));
        pack(map, displayFolder, folder.resolve("Again.SCE"));
        pack(writeMap(folder, "original.scm"), displayFolder, folder.resolve("Copy.sce"));

        Assertions.assertTrue(Files.isRegularFile(folder.resolve("plain.sce")));
        Assertions.assertTrue(Files.isRegularFile(folder.resolve("Again.scx")));
        Assertions.assertTrue(Files.isRegularFile(folder.resolve("Copy.scm")));
    }

    @Test
    void pack_should_fail_when_the_mark_is_unknown(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder, "map.scx");
        Path badManifestFolder = writeFolder(folder.resolve("bad"), "maybe", DISPLAY_SCRIPT);
        Path scriptFolder = writeFolder(folder, "", DISPLAY_SCRIPT);
        PrintStream discarded = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        Assertions.assertEquals(1, ScriptTool.run(new String[]{ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION,
                                                               badManifestFolder.toString(), ScriptTool.OUTPUT_OPTION,
                                                               folder.resolve("Out.scx").toString()}, discarded, discarded));
        Assertions.assertEquals(1, ScriptTool.run(new String[]{ScriptTool.PACK_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION,
                                                               scriptFolder.toString(), ScriptTool.OUTPUT_OPTION, folder.resolve("Out.scx").toString(),
                                                               ScriptTool.COMPATIBILITY_OPTION, "maybe"}, discarded, discarded));
        Assertions.assertFalse(Files.exists(folder.resolve("Out.scx")));
    }

    @Test
    void unpack_takes_an_enhanced_only_map(@TempDir @NotNull Path folder) throws IOException {
        Path map = writeMap(folder, "map.sce");
        Path scriptFolder = writeFolder(folder, "", GAMEPLAY_SCRIPT);
        Path unpackedFolder = folder.resolve("unpacked");
        PrintStream discarded = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        pack(map, scriptFolder, folder.resolve("Packed.sce"));

        Assertions.assertEquals(0, ScriptTool.run(new String[]{ScriptTool.UNPACK_COMMAND, folder.resolve("Packed.sce").toString(),
                                                               ScriptTool.OUTPUT_OPTION, unpackedFolder.toString()}, discarded, discarded));
        Assertions.assertEquals(ScriptMapScenario.readFolder(scriptFolder), ScriptMapScenario.readFolder(unpackedFolder));
    }
}
