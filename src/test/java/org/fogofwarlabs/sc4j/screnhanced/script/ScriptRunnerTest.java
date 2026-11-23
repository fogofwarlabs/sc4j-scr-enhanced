package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests that {@link ScriptRunner} packs into the mod's storage of the install, builds the runtime command line of the launcher with the
 * packed map as a Use Map Settings or a melee game, starts it through its starter and packs again after a change of the script folder; no
 * process is started.
 */
final class ScriptRunnerTest {
    private static final byte @NotNull [] VERSION_PAYLOAD = {(byte) 0xCD, 0x00};
    private static final @NotNull String MAP_NAME = "Arena.scx";
    private static final @NotNull List<String> LAUNCH_LINES = List.of("-XX:-UsePerfData", "-Djava.io.tmpdir=${APP_HOME}/.cache/tmp", "-cp",
                                                                      "${APP_HOME}/lib/*", "org.fogofwarlabs.sc4j.runtime.RuntimeMain");
    private static final @NotNull Map<String, String> SCRIPT_FOLDER = Map.of(
            ScriptManifest.FILE_NAME, "{\"name\": \"Arena\", \"main\": \"main.js\", \"api\": \"1\"}",
            "main.js", "function onStart() {\n    game.print('first');\n}\n",
            "jsconfig.json", "{}"
    );
    private static final long MODIFIED_TIME = 1_700_000_000_000L;

    /**
     * Records the command lines it is given and returns processes that have ended with an exit value.
     */
    private static final class RecordingStarter implements ScriptRunner.ProcessStarter {
        private final @NotNull List<ProcessBuilder> builders;
        private final int exitValue;

        private RecordingStarter(int exitValue) {
            this.builders  = new ArrayList<>();
            this.exitValue = exitValue;
        }

        @Override
        public @NotNull Process start(@NotNull ProcessBuilder builder) {
            this.builders.add(builder);

            return new EndedProcess(this.exitValue);
        }
    }

    /**
     * A process that has ended.
     */
    private static final class EndedProcess extends Process {
        private final int exitValue;

        private EndedProcess(int exitValue) {
            this.exitValue = exitValue;
        }

        @Override
        public @NotNull OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public @NotNull InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public @NotNull InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return this.exitValue;
        }

        @Override
        public int exitValue() {
            return this.exitValue;
        }

        @Override
        public void destroy() {
        }
    }

    /**
     * Writes an install with the runtime launch file and the SC:R Enhanced jar.
     */
    private static @NotNull Path writeInstall(@NotNull Path folder) throws IOException {
        Path install = folder.resolve("install");

        Files.createDirectories(install.resolve("runtime/bin"));
        Files.createDirectories(install.resolve("mods"));
        Files.write(install.resolve("runtime/bin/sc4j-runtime.launch"), LAUNCH_LINES, StandardCharsets.UTF_8);
        Files.write(install.resolve("mods/scr-enhanced.jar"), new byte[]{0});

        return install;
    }

    private static @NotNull Path writeMap(@NotNull Path folder) throws IOException {
        Path map = folder.resolve(MAP_NAME);

        Files.write(map, ScriptMapScenario.createMap(ScriptMapScenario.createSection(ChkFile.VERSION_SECTION, VERSION_PAYLOAD)));

        return map;
    }

    private static @Nullable ScriptPackage readPackedPackage(@NotNull Path packedMap) throws IOException {
        return ScriptStorageUtils.readPackage(Files.readAllBytes(packedMap), packedMap.toString());
    }

    @Test
    void pack_should_write_the_map_copy_into_the_mod_storage_of_the_install(@TempDir @NotNull Path folder) throws IOException {
        Path install = writeInstall(folder);
        Path map = writeMap(folder);
        byte[] original = Files.readAllBytes(map);
        Path scriptFolder = folder.resolve("script");

        ScriptMapScenario.writeFolder(scriptFolder, SCRIPT_FOLDER);

        ScriptRunner runner = new ScriptRunner(install, map, scriptFolder, false, ScriptRunner.NO_INSPECT_PORT, new RecordingStarter(0));
        ScriptPackage packed = runner.pack();
        ScriptPackage read = readPackedPackage(runner.getPackedMap());

        Assertions.assertEquals(install.toAbsolutePath().resolve("userdata/mods/scr-enhanced/script-run").resolve(MAP_NAME), runner.getPackedMap());
        Assertions.assertNotNull(read);
        Assertions.assertEquals(packed.getScripts().keySet(), read.getScripts().keySet());
        Assertions.assertArrayEquals(original, Files.readAllBytes(map));
    }

    @Test
    void start_should_run_the_launch_file_of_the_install_on_the_packed_map(@TempDir @NotNull Path folder) throws IOException {
        Path install = writeInstall(folder).toAbsolutePath();
        Path map = writeMap(folder);
        Path scriptFolder = folder.resolve("script");
        RecordingStarter starter = new RecordingStarter(0);
        Path java = Path.of("jdk", "bin", "java.exe");

        ScriptMapScenario.writeFolder(scriptFolder, SCRIPT_FOLDER);

        ScriptRunner runner = new ScriptRunner(install, map, scriptFolder, false, ScriptRunner.NO_INSPECT_PORT, starter);

        runner.pack();
        runner.start(java);

        Path runtime = install.resolve("runtime");

        Assertions.assertEquals(1, starter.builders.size());
        Assertions.assertEquals(List.of(java.toString(), "-XX:-UsePerfData", "-Djava.io.tmpdir=" + runtime + "/.cache/tmp", "-cp", runtime + "/lib/*",
                                        "org.fogofwarlabs.sc4j.runtime.RuntimeMain", "--install", install.toString(), "--mod",
                                        install.resolve("mods/scr-enhanced.jar").toString(), "--", "--ums", runner.getPackedMap().toString()),
                                starter.builders.getFirst().command());
        Assertions.assertEquals(install.toFile(), starter.builders.getFirst().directory());
    }

    @Test
    void start_should_run_the_packed_map_as_a_melee_game_against_one_random_computer(@TempDir @NotNull Path folder) throws IOException {
        Path install = writeInstall(folder).toAbsolutePath();
        Path scriptFolder = folder.resolve("script");
        RecordingStarter starter = new RecordingStarter(0);

        ScriptMapScenario.writeFolder(scriptFolder, SCRIPT_FOLDER);

        ScriptRunner runner = new ScriptRunner(install, writeMap(folder), scriptFolder, true, ScriptRunner.NO_INSPECT_PORT, starter);

        runner.pack();
        runner.start(Path.of("java"));

        List<String> command = starter.builders.getFirst().command();

        Assertions.assertEquals(install.resolve("userdata/mods/scr-enhanced/script-run").resolve(MAP_NAME), runner.getPackedMap());
        Assertions.assertEquals(List.of("--", "--melee", runner.getPackedMap().toString(), "--computer", "random"),
                                command.subList(command.size() - 5, command.size()));
    }

    @Test
    void create_command_should_switch_the_devtools_console_on_with_a_port(@TempDir @NotNull Path folder) throws IOException {
        Path install = writeInstall(folder);
        ScriptRunner runner = new ScriptRunner(install, writeMap(folder), folder.resolve("script"), false, 9340, new RecordingStarter(0));
        List<String> command = runner.createCommand(Path.of("java"));

        Assertions.assertEquals(List.of("java", "-Dsc4j.scr-enhanced.inspect=9340"), command.subList(0, 2));
        Assertions.assertEquals(List.of("--ums", runner.getPackedMap().toString()), command.subList(command.size() - 2, command.size()));
    }

    @Test
    void create_command_should_fail_without_runtime_or_mod(@TempDir @NotNull Path folder) throws IOException {
        Path install = writeInstall(folder);
        ScriptRunner runner = new ScriptRunner(install, writeMap(folder), folder.resolve("script"), false, ScriptRunner.NO_INSPECT_PORT,
                                               new RecordingStarter(0));

        Files.delete(install.resolve("mods/scr-enhanced.jar"));
        Assertions.assertThrows(IOException.class, () -> runner.createCommand(Path.of("java")));

        Files.delete(install.resolve("runtime/bin/sc4j-runtime.launch"));
        Assertions.assertThrows(IOException.class, () -> runner.createCommand(Path.of("java")));
    }

    @Test
    void repack_if_changed_should_pack_after_a_change_of_the_script_folder_only(@TempDir @NotNull Path folder) throws IOException {
        Path install = writeInstall(folder);
        Path scriptFolder = folder.resolve("script");
        Path main = scriptFolder.resolve("main.js");

        ScriptMapScenario.writeFolder(scriptFolder, SCRIPT_FOLDER);
        Files.setLastModifiedTime(main, FileTime.fromMillis(MODIFIED_TIME));

        ScriptRunner runner = new ScriptRunner(install, writeMap(folder), scriptFolder, false, ScriptRunner.NO_INSPECT_PORT,
                                               new RecordingStarter(0));

        runner.pack();

        Assertions.assertFalse(runner.repackIfChanged());

        Files.writeString(scriptFolder.resolve("jsconfig.json"), "{\"changed\": true}");
        Assertions.assertFalse(runner.repackIfChanged());

        Files.writeString(main, "function onStart() {\n    game.print('second');\n}\n");
        Files.setLastModifiedTime(main, FileTime.fromMillis(MODIFIED_TIME + 1));
        Assertions.assertTrue(runner.repackIfChanged());

        ScriptPackage read = readPackedPackage(runner.getPackedMap());

        Assertions.assertNotNull(read);
        Assertions.assertEquals("function onStart() {\n    game.print('second');\n}\n",
                                new String(read.getScripts().get("main.js"), StandardCharsets.UTF_8));
        Assertions.assertFalse(runner.repackIfChanged());

        Files.writeString(scriptFolder.resolve("lib.js"), "exports.size = 1;\n");
        Assertions.assertTrue(runner.repackIfChanged());
        Assertions.assertEquals(2, readPackedPackage(runner.getPackedMap()).getScripts().size());
    }

    @Test
    void run_command_should_pack_start_and_wait_for_the_game(@TempDir @NotNull Path folder) throws IOException {
        Path install = writeInstall(folder);
        Path map = writeMap(folder);
        Path scriptFolder = folder.resolve("script");
        RecordingStarter starter = new RecordingStarter(0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        ScriptMapScenario.writeFolder(scriptFolder, SCRIPT_FOLDER);

        int exit = ScriptTool.run(new String[]{ScriptTool.RUN_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION, scriptFolder.toString(),
                                               ScriptTool.WATCH_OPTION, ScriptTool.INSTALL_OPTION, install.toString()},
                                  new PrintStream(output, true, StandardCharsets.UTF_8),
                                  new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), starter);

        Assertions.assertEquals(0, exit, output::toString);
        Assertions.assertEquals(1, starter.builders.size());
        Assertions.assertEquals("--ums", starter.builders.getFirst().command().get(starter.builders.getFirst().command().size() - 2));
        Assertions.assertTrue(Files.isRegularFile(install.resolve("userdata/mods/scr-enhanced/script-run").resolve(MAP_NAME)));
        Assertions.assertEquals(0, ScriptTool.run(new String[]{ScriptTool.RUN_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION,
                                                               scriptFolder.toString(), ScriptTool.MELEE_OPTION, ScriptTool.INSTALL_OPTION,
                                                               install.toString()},
                                                  new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                                                  new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), starter));

        List<String> meleeCommand = starter.builders.getLast().command();
        Path packedMap = install.toAbsolutePath().resolve("userdata/mods/scr-enhanced/script-run").resolve(MAP_NAME);

        Assertions.assertEquals(2, starter.builders.size());
        Assertions.assertEquals(List.of("--melee", packedMap.toString(), "--computer", "random"),
                                meleeCommand.subList(meleeCommand.size() - 4, meleeCommand.size()));
        Assertions.assertEquals(1, ScriptTool.run(new String[]{ScriptTool.RUN_COMMAND, map.toString(), ScriptTool.SCRIPT_OPTION,
                                                               scriptFolder.toString(), ScriptTool.INSTALL_OPTION, install.toString()},
                                                  new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                                                  new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                                                  new RecordingStarter(1)));
    }
}
