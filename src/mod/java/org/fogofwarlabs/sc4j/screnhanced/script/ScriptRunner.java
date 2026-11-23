package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.screnhanced.script.inspector.ScriptInspector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.*;
import java.util.stream.Stream;

/**
 * Packs a script folder into a copy of a map and starts SC:R Enhanced of an SC4J install on it, as the launcher starts the runtime:
 * {@snippet lang = "text":
 * <java> <the lines of <install>/runtime/bin/sc4j-runtime.launch, ${APP_HOME} being <install>/runtime>
 *     --install <install> --mod <install>/mods/scr-enhanced.jar -- --ums <packed map>
 *}
 * A melee run ends the command line with {@code --melee <packed map> --computer random} instead: one computer player of a random race against
 * the human of the client's default race, name and slot. A run with a DevTools port puts the system property
 * {@code -D}{@value ScriptInspector#PORT_PROPERTY}{@code =<port>} before the launch file's lines, which starts the client's DevTools server. The runtime takes the settings, storage and log folders of the mod from the install.
 * The packed map is {@code <install>/userdata/mods/scr-enhanced/script-run/<map file name>}, in the mod's storage outside the map folder the
 * client lists; the source map stays as it is.
 * <p>
 * {@link #repackIfChanged()} packs again when a file that the package holds ({@value ScriptManifest#FILE_NAME}, a script or a resource) was
 * added, removed or changed in size or modification time since the last pack; a running game keeps the scripts it loaded.
 */
public final class ScriptRunner {
    /**
     * The mod id of SC:R Enhanced, which names its jar and its storage folder.
     */
    public static final @NotNull String MOD_ID = "scr-enhanced";
    /**
     * The folder of the packed maps in the mod's storage.
     */
    public static final @NotNull String RUN_FOLDER = "script-run";
    /**
     * The system property that names the SC4J install when the command line does not.
     */
    public static final @NotNull String INSTALL_PROPERTY = "sc4j.install";
    /**
     * The DevTools port of a run without the DevTools console.
     */
    public static final int NO_INSPECT_PORT = -1;

    private static final @NotNull String RUNTIME_FOLDER = "runtime";
    private static final @NotNull String BINARY_FOLDER = "bin";
    private static final @NotNull String LAUNCH_FILE = "sc4j-runtime.launch";
    private static final @NotNull String APPLICATION_HOME_TOKEN = "${APP_HOME}";
    private static final @NotNull String MODS_FOLDER = "mods";
    private static final @NotNull String JAR_EXTENSION = ".jar";
    private static final @NotNull String USER_DATA_FOLDER = "userdata";
    private static final @NotNull String JAVA_HOME_PROPERTY = "java.home";
    private static final @NotNull String WINDOWS_JAVA = "java.exe";
    private static final @NotNull String UNIX_JAVA = "java";
    private static final @NotNull String OPERATING_SYSTEM_PROPERTY = "os.name";
    private static final @NotNull String WINDOWS_NAME_PREFIX = "windows";
    private static final @NotNull String INSTALL_OPTION = "--install";
    private static final @NotNull String MOD_OPTION = "--mod";
    private static final @NotNull String MOD_ARGUMENTS_SEPARATOR = "--";
    private static final @NotNull String MAP_SETTINGS_OPTION = "--ums";
    private static final @NotNull String MELEE_OPTION = "--melee";
    private static final @NotNull String COMPUTER_OPTION = "--computer";
    private static final @NotNull String MELEE_COMPUTER_RACES = "random";
    private static final @NotNull String SYSTEM_PROPERTY_PREFIX = "-D";
    private static final @NotNull String TEMPORARY_SUFFIX = ".tmp";
    private static final @NotNull String RESOURCE_PREFIX = ScriptPackage.RESOURCE_FOLDER + "/";

    /**
     * Starts the process of a command line.
     */
    @FunctionalInterface
    public interface ProcessStarter {
        /**
         * Starts a process.
         *
         * @param builder the command line, working folder and streams of the process
         * @return the started process
         * @throws IOException if the process cannot start
         */
        @NotNull Process start(@NotNull ProcessBuilder builder) throws IOException;
    }


    /**
     * Finds the SC4J install to run in: the value of {@value #INSTALL_PROPERTY}, else the install whose {@value #MODS_FOLDER} folder holds the
     * jar of this class.
     *
     * @return the install folder, or {@code null} if neither names one
     */
    public static @Nullable Path findInstall() {
        String property = System.getProperty(INSTALL_PROPERTY);

        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }

        CodeSource codeSource = ScriptRunner.class.getProtectionDomain().getCodeSource();

        if (codeSource == null) {
            return null;
        }

        try {
            Path location = Path.of(codeSource.getLocation().toURI());
            Path parent = location.getParent();

            if (location.getFileName().toString().endsWith(JAR_EXTENSION) && parent != null && parent.getFileName() != null &&
                parent.getFileName().toString().equals(MODS_FOLDER)) {
                return parent.getParent();
            }
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return null;
        }

        return null;
    }

    /**
     * Lists the folders of an install that hold maps of SC:R Enhanced: the map folder of the mod's storage
     * ({@code <install>/userdata/mods/scr-enhanced/maps}, downloads included) and the folder of the packed maps ({@value #RUN_FOLDER}).
     *
     * @param install the SC4J install
     * @return the folders in search order
     */
    public static @NotNull List<Path> getMapFolders(@NotNull Path install) {
        Path storage = install.resolve(USER_DATA_FOLDER).resolve(MODS_FOLDER).resolve(MOD_ID);

        return List.of(storage.resolve(InstallLayout.MAP_FOLDER), storage.resolve(RUN_FOLDER));
    }

    /**
     * @return the Java program of the Java runtime that runs this class
     */
    public static @NotNull Path getJavaProgram() {
        boolean windows = System.getProperty(OPERATING_SYSTEM_PROPERTY, "").toLowerCase(Locale.ROOT).startsWith(WINDOWS_NAME_PREFIX);

        return Path.of(System.getProperty(JAVA_HOME_PROPERTY), BINARY_FOLDER, windows ? WINDOWS_JAVA : UNIX_JAVA);
    }


    private final @NotNull Path install;
    private final @NotNull Path map;
    private final @NotNull Path scriptFolder;
    private final boolean melee;
    private final int inspectPort;
    private final @NotNull ProcessStarter starter;

    private final @NotNull Path packedMap;
    private @Nullable Map<String, String> packedFiles;

    /**
     * Creates a {@link ScriptRunner} instance that has not packed.
     *
     * @param install      the SC4J install
     * @param map          the map to pack the scripts into
     * @param scriptFolder the script folder
     * @param melee        whether the packed map starts as a melee game instead of a Use Map Settings game
     * @param inspectPort  the port of the client's DevTools console, or {@link #NO_INSPECT_PORT} for none
     * @param starter      starts the runtime process
     */
    public ScriptRunner(@NotNull Path install, @NotNull Path map, @NotNull Path scriptFolder, boolean melee, int inspectPort,
                        @NotNull ProcessStarter starter) {
        this.install      = install.toAbsolutePath().normalize();
        this.map          = map.toAbsolutePath().normalize();
        this.scriptFolder = scriptFolder.toAbsolutePath().normalize();
        this.melee        = melee;
        this.inspectPort  = inspectPort;
        this.starter      = starter;

        Path mapName = this.map.getFileName();

        this.packedMap   = this.install.resolve(USER_DATA_FOLDER).resolve(MODS_FOLDER).resolve(MOD_ID).resolve(RUN_FOLDER)
                                       .resolve(mapName == null ? this.map.toString() : mapName.toString());
        this.packedFiles = null;
    }

    /**
     * @return the packed map: {@code <install>/userdata/mods/scr-enhanced/script-run/<map file name>}
     */
    public @NotNull Path getPackedMap() {
        return this.packedMap;
    }

    /**
     * Packs the script folder into a copy of the map ({@link #getPackedMap()}), which replaces the previous one only when the packing
     * succeeded.
     *
     * @return the packed script package
     * @throws IOException if the map or the script folder cannot be read, the package is not valid or the packed map cannot be written
     */
    public @NotNull ScriptPackage pack() throws IOException {
        Map<String, String> files = this.readPackedFiles();
        ScriptPackage scriptPackage = ScriptPackage.readFolder(this.scriptFolder);
        byte[] packed = ScriptStorageUtils.writePackage(Files.readAllBytes(this.map), this.map.toString(), scriptPackage);
        Path temporary = this.packedMap.resolveSibling(this.packedMap.getFileName() + TEMPORARY_SUFFIX);

        Files.createDirectories(this.packedMap.getParent());
        Files.write(temporary, packed);
        Files.move(temporary, this.packedMap, StandardCopyOption.REPLACE_EXISTING);

        this.packedFiles = files;

        return scriptPackage;
    }

    /**
     * Packs again when a file of the package changed since the last pack ({@link #pack()}).
     *
     * @return {@code true} if it packed, {@code false} if no file changed
     * @throws IOException if the script folder cannot be read or the packing fails; the next call tries again
     */
    public boolean repackIfChanged() throws IOException {
        if (this.readPackedFiles().equals(this.packedFiles)) {
            return false;
        }

        this.pack();

        return true;
    }

    /**
     * Reads the state of the files the package holds: the size and modification time by file name.
     */
    private @NotNull Map<String, String> readPackedFiles() throws IOException {
        Map<String, String> files = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(this.scriptFolder)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String name = this.scriptFolder.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");

                if (name.equals(ScriptManifest.FILE_NAME) || name.startsWith(RESOURCE_PREFIX) || name.endsWith(ScriptPackage.SCRIPT_EXTENSION)) {
                    files.put(name, Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis());
                }
            }
        }

        return files;
    }

    /**
     * Creates the command line that starts SC:R Enhanced on the packed map, as a melee or a Use Map Settings game, with the DevTools console
     * when the run has a port for it.
     *
     * @param javaProgram the Java program
     * @return the command line, one argument per element
     * @throws IOException if the install has no runtime launch file or no SC:R Enhanced jar, or the launch file cannot be read
     */
    public @NotNull List<@NotNull String> createCommand(@NotNull Path javaProgram) throws IOException {
        Path runtime = this.install.resolve(RUNTIME_FOLDER);
        Path launchFile = runtime.resolve(BINARY_FOLDER).resolve(LAUNCH_FILE);
        Path modJar = this.install.resolve(MODS_FOLDER).resolve(MOD_ID + JAR_EXTENSION);

        if (!Files.isRegularFile(launchFile)) {
            throw Exceptions.newIOException("The SC4J install {0} has no runtime: {1} is missing.", this.install, launchFile);
        }

        if (!Files.isRegularFile(modJar)) {
            throw Exceptions.newIOException("The SC4J install {0} has no SC:R Enhanced: {1} is missing.", this.install, modJar);
        }

        List<String> command = new ArrayList<>();

        command.add(javaProgram.toString());

        if (this.inspectPort != NO_INSPECT_PORT) {
            command.add(SYSTEM_PROPERTY_PREFIX + ScriptInspector.PORT_PROPERTY + "=" + this.inspectPort);
        }

        for (String line : Files.readAllLines(launchFile, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                command.add(line.strip().replace(APPLICATION_HOME_TOKEN, runtime.toString()));
            }
        }

        command.addAll(List.of(INSTALL_OPTION, this.install.toString(), MOD_OPTION, modJar.toString(), MOD_ARGUMENTS_SEPARATOR));

        if (this.melee) {
            command.addAll(List.of(MELEE_OPTION, this.packedMap.toString(), COMPUTER_OPTION, MELEE_COMPUTER_RACES));
        } else {
            command.addAll(List.of(MAP_SETTINGS_OPTION, this.packedMap.toString()));
        }

        return command;
    }

    /**
     * Starts SC:R Enhanced on the packed map in the install folder, with the streams of this process.
     *
     * @param javaProgram the Java program
     * @return the started process
     * @throws IOException if the command line cannot be created ({@link #createCommand}) or the process cannot start
     */
    public @NotNull Process start(@NotNull Path javaProgram) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(this.createCommand(javaProgram));

        builder.directory(this.install.toFile());
        builder.inheritIO();

        return this.starter.start(builder);
    }
}
