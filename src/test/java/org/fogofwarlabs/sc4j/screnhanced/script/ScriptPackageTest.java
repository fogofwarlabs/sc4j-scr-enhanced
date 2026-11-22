package org.fogofwarlabs.sc4j.screnhanced.script;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tests the script folder reading and the manifest and name checks of {@link ScriptPackage}.
 */
final class ScriptPackageTest {
    private static final @NotNull String MANIFEST = "{\"name\": \"Test\", \"main\": \"main.js\", \"api\": \"1\"}";
    private static final byte @NotNull [] CONTENT = "1;".getBytes(StandardCharsets.UTF_8);

    private static @NotNull ScriptManifest createManifest() throws IOException {
        return ScriptManifest.parse(MANIFEST.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertFolderRefused(@NotNull Path folder, @NotNull Map<String, String> files, @NotNull String messagePart) throws IOException {
        ScriptMapScenario.writeFolder(folder, files);

        IOException refusal = Assertions.assertThrows(IOException.class, () -> ScriptPackage.readFolder(folder));

        Assertions.assertTrue(refusal.getMessage().contains(messagePart), refusal.getMessage());
    }

    private static void assertNamesRefused(@NotNull Map<String, byte[]> scripts, @NotNull Map<String, byte[]> resources) throws IOException {
        ScriptManifest manifest = createManifest();

        Assertions.assertThrows(IOException.class, () -> ScriptPackage.create(manifest, scripts, resources),
                                () -> scripts.keySet() + " " + resources.keySet());
    }

    @Test
    void read_folder_reads_the_scripts_and_resources_by_name(@TempDir @NotNull Path folder) throws IOException {
        ScriptMapScenario.writeFolder(folder, Map.of(ScriptManifest.FILE_NAME, MANIFEST, "main.js", "1;", "lib/util.js", "2;", "notes.txt", "-",
                                                     "jsconfig.json", "{}", "resources/images/a.grp", "GRP", "resources/data.js", "3;"));

        ScriptPackage scriptPackage = ScriptPackage.readFolder(folder);

        Assertions.assertEquals("Test", scriptPackage.getManifest().getName());
        Assertions.assertEquals("main.js", scriptPackage.getManifest().getMain());
        Assertions.assertEquals("1", scriptPackage.getManifest().getApi());
        Assertions.assertEquals(List.of("lib/util.js", "main.js"), List.copyOf(scriptPackage.getScripts().keySet()));
        Assertions.assertEquals(List.of("data.js", "images/a.grp"), List.copyOf(scriptPackage.getResources().keySet()));
    }

    @Test
    void read_folder_should_fail_without_a_manifest(@TempDir @NotNull Path folder) throws IOException {
        assertFolderRefused(folder, Map.of("main.js", "1;"), ScriptManifest.FILE_NAME);
    }

    @Test
    void read_folder_should_fail_when_the_manifest_is_not_a_json_object(@TempDir @NotNull Path folder) throws IOException {
        assertFolderRefused(folder.resolve("text"), Map.of(ScriptManifest.FILE_NAME, "{\"name\": ", "main.js", "1;"), "not valid JSON");
        assertFolderRefused(folder.resolve("array"), Map.of(ScriptManifest.FILE_NAME, "[]", "main.js", "1;"), "not a JSON object");
    }

    @Test
    void read_folder_should_fail_when_the_manifest_lacks_a_field(@TempDir @NotNull Path folder) throws IOException {
        assertFolderRefused(folder.resolve("name"), Map.of(ScriptManifest.FILE_NAME, "{\"main\": \"main.js\", \"api\": \"1\"}", "main.js", "1;"),
                            "\"name\"");
        assertFolderRefused(folder.resolve("main"), Map.of(ScriptManifest.FILE_NAME, "{\"name\": \"A\", \"main\": \"\", \"api\": \"1\"}",
                                                           "main.js", "1;"), "\"main\"");
        assertFolderRefused(folder.resolve("api"), Map.of(ScriptManifest.FILE_NAME, "{\"name\": \"A\", \"main\": \"main.js\", \"api\": 1}",
                                                          "main.js", "1;"), "\"api\"");
    }

    @Test
    void read_folder_should_fail_when_the_main_script_is_not_packed(@TempDir @NotNull Path folder) throws IOException {
        assertFolderRefused(folder.resolve("missing"), Map.of(ScriptManifest.FILE_NAME, MANIFEST, "start.js", "1;"), "main.js");
        assertFolderRefused(folder.resolve("resource"), Map.of(ScriptManifest.FILE_NAME, MANIFEST, "resources/main.js", "1;"), "main.js");
    }

    @Test
    void create_should_refuse_names_that_are_not_unique_ignoring_letter_case() throws IOException {
        assertNamesRefused(Map.of("main.js", CONTENT, "Main.js", CONTENT), Map.of());
        assertNamesRefused(Map.of("main.js", CONTENT), Map.of("Images/a.grp", CONTENT, "images/A.grp", CONTENT));
    }

    @Test
    void create_should_refuse_names_that_are_not_one_file_of_a_folder() throws IOException {
        for (String name : List.of("../main.js", "lib//main.js", "/main.js", "lib/./main.js", "lib\\util.js", "c:util.js", "tab\tutil.js")) {
            assertNamesRefused(Map.of("main.js", CONTENT, name, CONTENT), Map.of());
        }

        assertNamesRefused(Map.of("main.js", CONTENT), Map.of("images", CONTENT, "images/a.grp", CONTENT));
        assertNamesRefused(Map.of("main.js", CONTENT), Map.of("images/", CONTENT));
    }

    @Test
    void create_should_refuse_scripts_outside_the_script_files() throws IOException {
        assertNamesRefused(Map.of("main.js", CONTENT, "util.ts", CONTENT), Map.of());
        assertNamesRefused(Map.of("main.js", CONTENT, "resources/util.js", CONTENT), Map.of());
    }
}
