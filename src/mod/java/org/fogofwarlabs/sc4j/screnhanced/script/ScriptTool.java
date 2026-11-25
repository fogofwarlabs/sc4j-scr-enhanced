package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.FileBrowserScreen;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.screnhanced.script.inspector.ScriptInspector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * The command line tool of SC:R Enhanced Script.
 * <p>
 * Commands: {@code init <folder> [--ts]} writes a new script folder, with {@code --ts} a TypeScript project ({@link ScriptProjectUtils});
 * {@code check <folder>} prints one line per finding of a script folder ({@link ScriptCheckUtils}); {@code pack <map> --script <folder> -o
 * <output> [--protect] [--compatibility <mark>]} writes the map with the package of the script folder to the output file, the bytes of the map followed by the script
 * trailer ({@link ScriptStorageUtils}), which replaces an old one; a script keeps the source map file that tsc wrote for it inside
 * ({@link ScriptPackage#readFolder}), shrunk to its lines ({@link ScriptCompiledMapUtils#shrinkMap}), while {@code run} keeps the whole map.
 * With {@code --protect} the map is protected: its scripts are packed in protected form without their source maps ({@link ScriptProtector})
 * and the source map of the protected scripts and their sources is written next to the output file ({@link ScriptSourceMap#resolveFile}), not
 * into the map. {@code unpack <map> -o <folder>} writes the package of the map to the folder, which
 * must be absent or hold only hidden entries, and refuses a protected map; {@code trace <source map> --at <script>:<line>} prints the original location of a
 * location of a protected script, and {@code trace <source map> --log <file>} prints the file, such as a player's log, with the original
 * locations; {@code run <map> --script <folder> [--melee] [--watch] [--inspect[=<port>]]
 * [--install <folder>]} packs the script folder into a copy of the map and runs SC:R Enhanced of an SC4J install on it until the game ends
 * ({@link ScriptRunner}), as a melee game with {@code --melee}, else as a Use Map Settings game, packing again on every change of the folder
 * with {@code --watch}, with the DevTools console on the port, by default {@value ScriptInspector#DEFAULT_PORT}, with {@code --inspect}. The
 * install is the one {@code --install} names, else {@link ScriptRunner#findInstall()}.
 * <p>
 * The compatibility mark of {@code pack} ({@link ScriptCompatibility}) is the one of {@code --compatibility}, else that of the manifest;
 * with {@code auto} the scan of the scripts picks it ({@link ScriptCompatibilityScan}). The mark decides the extension of the output file: an
 * {@code enhanced-only} map gets {@value ScriptCompatibility#ENHANCED_MAP_EXTENSION} in place of a map extension at the end of the output
 * file name, or after a name without one; a {@code compatible} map keeps the {@code .scm} or {@code .scx} of the output file name, else gets
 * that of the input map or, without one, the one its scenario version gives. {@code pack} prints the file it wrote, the mark and its reasons.
 */
public final class ScriptTool {
    /**
     * The command that writes a new script folder.
     */
    public static final @NotNull String INIT_COMMAND = "init";
    /**
     * The command that checks a script folder.
     */
    public static final @NotNull String CHECK_COMMAND = "check";
    /**
     * The command that packs a script folder into a map.
     */
    public static final @NotNull String PACK_COMMAND = "pack";
    /**
     * The command that unpacks the script package of a map into a folder.
     */
    public static final @NotNull String UNPACK_COMMAND = "unpack";
    /**
     * The command that maps locations of protected scripts back to the original ones with a source map.
     */
    public static final @NotNull String TRACE_COMMAND = "trace";
    /**
     * The command that runs SC:R Enhanced on a map with a script folder.
     */
    public static final @NotNull String RUN_COMMAND = "run";
    /**
     * The option of {@value #INIT_COMMAND} that writes a TypeScript project.
     */
    public static final @NotNull String TYPESCRIPT_OPTION = "--ts";
    /**
     * The option that names the script folder of {@value #PACK_COMMAND} and {@value #RUN_COMMAND}.
     */
    public static final @NotNull String SCRIPT_OPTION = "--script";
    /**
     * The option that names the output file of {@value #PACK_COMMAND} and the output folder of {@value #UNPACK_COMMAND}.
     */
    public static final @NotNull String OUTPUT_OPTION = "-o";
    /**
     * The option of {@value #PACK_COMMAND} that packs a protected map.
     */
    public static final @NotNull String PROTECT_OPTION = "--protect";
    /**
     * The option of {@value #PACK_COMMAND} that names the compatibility mark of the map: {@code enhanced-only}, {@code compatible} or
     * {@code auto}, over that of the manifest.
     */
    public static final @NotNull String COMPATIBILITY_OPTION = "--compatibility";
    /**
     * The option of {@value #TRACE_COMMAND} that names one location {@code <script>:<line>}.
     */
    public static final @NotNull String AT_OPTION = "--at";
    /**
     * The option of {@value #TRACE_COMMAND} that names a file whose locations are mapped.
     */
    public static final @NotNull String LOG_OPTION = "--log";
    /**
     * The option of {@value #RUN_COMMAND} that starts the map as a melee game instead of a Use Map Settings game.
     */
    public static final @NotNull String MELEE_OPTION = "--melee";
    /**
     * The option of {@value #RUN_COMMAND} that packs again on every change of the script folder.
     */
    public static final @NotNull String WATCH_OPTION = "--watch";
    /**
     * The option of {@value #RUN_COMMAND} that switches the DevTools console on, at the default port or, as {@code --inspect=<port>}, at a
     * port.
     */
    public static final @NotNull String INSPECT_OPTION = "--inspect";
    /**
     * The option of {@value #RUN_COMMAND} that names the SC4J install.
     */
    public static final @NotNull String INSTALL_OPTION = "--install";
    /**
     * The usage line.
     */
    public static final @NotNull String USAGE = "Usage: init <folder> [--ts] | check <folder> | " +
                                                "pack <map> --script <folder> -o <output> [--protect] [--compatibility enhanced-only|compatible|auto] | " +
                                                "unpack <map> -o <folder> | " +
                                                "trace <source map> --at <script>:<line> | trace <source map> --log <file> | " +
                                                "run <map> --script <folder> [--melee] [--watch] [--inspect[=<port>]] [--install <folder>]";

    private static final int COMMAND_ARGUMENT = 0;
    private static final int PATH_ARGUMENT = 1;
    private static final int FIRST_OPTION_ARGUMENT = 2;
    private static final int SUCCESS_EXIT = 0;
    private static final int FAILURE_EXIT = 1;
    private static final char LOCATION_SEPARATOR = ':';
    private static final char OPTION_VALUE_SEPARATOR = '=';
    private static final int LARGEST_PORT = 0xFFFF;
    /**
     * The most reasons of each kind that {@value #PACK_COMMAND} prints for its compatibility mark.
     */
    private static final int MAXIMUM_PRINTED_REASONS = 5;
    /**
     * The milliseconds between two looks at the script folder while the game runs.
     */
    private static final long WATCH_INTERVAL_MILLISECONDS = 500;

    /**
     * Runs one command and exits with its exit code.
     *
     * @param arguments the command and its arguments
     */
    public static void main(@NotNull String @NotNull [] arguments) {
        System.exit(run(arguments, System.out, System.err));
    }

    /**
     * Runs one command; {@value #RUN_COMMAND} starts the runtime process with {@link ProcessBuilder#start()}.
     *
     * @param arguments the command and its arguments
     * @param output    the stream of the result lines
     * @param errors    the stream of the failure messages
     * @return {@code 0} if the command ran ({@value #CHECK_COMMAND}: found no error; {@value #RUN_COMMAND}: the game ended normally), {@code 1} otherwise
     */
    public static int run(@NotNull String @NotNull [] arguments, @NotNull PrintStream output, @NotNull PrintStream errors) {
        return run(arguments, output, errors, ProcessBuilder::start);
    }

    /**
     * Runs one command.
     *
     * @param arguments the command and its arguments
     * @param output    the stream of the result lines
     * @param errors    the stream of the failure messages
     * @param starter   starts the runtime process of {@value #RUN_COMMAND}
     * @return {@code 0} if the command ran ({@value #CHECK_COMMAND}: found no error; {@value #RUN_COMMAND}: the game ended normally), {@code 1} otherwise
     */
    public static int run(@NotNull String @NotNull [] arguments, @NotNull PrintStream output, @NotNull PrintStream errors,
                          @NotNull ScriptRunner.ProcessStarter starter) {
        try {
            if (arguments.length <= PATH_ARGUMENT) {
                throw Exceptions.newIllegalArgumentException("A command and a path are needed.");
            }

            Path path = Path.of(arguments[PATH_ARGUMENT]);

            return switch (arguments[COMMAND_ARGUMENT]) {
                case INIT_COMMAND -> runInit(arguments, path, output);
                case CHECK_COMMAND -> runCheck(arguments, path, output);
                case PACK_COMMAND -> runPack(arguments, requireMap(path), output);
                case UNPACK_COMMAND -> runUnpack(arguments, requireMap(path), output);
                case TRACE_COMMAND -> runTrace(arguments, path, output);
                case RUN_COMMAND -> runGame(arguments, requireMap(path), output, errors, starter);
                default -> throw Exceptions.newIllegalArgumentException("Unknown command {0}: expected {1}, {2}, {3}, {4}, {5} or {6}.",
                                                                        arguments[COMMAND_ARGUMENT], INIT_COMMAND, CHECK_COMMAND, PACK_COMMAND,
                                                                        UNPACK_COMMAND, TRACE_COMMAND, RUN_COMMAND);
            };
        } catch (IllegalArgumentException exception) {
            errors.println(exception.getMessage());
            errors.println(USAGE);

            return FAILURE_EXIT;
        } catch (IOException exception) {
            errors.println(exception.getMessage());

            return FAILURE_EXIT;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            errors.println("The run was interrupted.");

            return FAILURE_EXIT;
        }
    }

    private static @NotNull Path requireMap(@NotNull Path map) throws IOException {
        if (!Files.isRegularFile(map)) {
            throw Exceptions.newIOException("The map {0} is not a file.", map);
        }

        return map;
    }

    /**
     * Writes a new script folder, or with {@value #TYPESCRIPT_OPTION} a TypeScript project.
     */
    private static int runInit(@NotNull String @NotNull [] arguments, @NotNull Path folder, @NotNull PrintStream output) throws IOException {
        if (readOptions(arguments, List.of(), List.of(TYPESCRIPT_OPTION), List.of()).containsKey(TYPESCRIPT_OPTION)) {
            ScriptProjectUtils.writeTypeScriptProject(folder);
            output.println(Exceptions.format("Wrote the TypeScript project {0}; its script folder is {1}.", folder,
                                             folder.resolve(ScriptProjectUtils.SCRIPT_FOLDER)));

            return SUCCESS_EXIT;
        }

        ScriptProjectUtils.writeProject(folder);
        output.println(Exceptions.format("Wrote the script folder {0}.", folder));

        return SUCCESS_EXIT;
    }

    private static int runCheck(@NotNull String @NotNull [] arguments, @NotNull Path folder, @NotNull PrintStream output) throws IOException {
        readOptions(arguments, List.of(), List.of(), List.of());

        List<ScriptFinding> findings = ScriptCheckUtils.check(folder);

        for (ScriptFinding finding : findings) {
            output.println(finding);
        }

        if (findings.isEmpty()) {
            output.println(Exceptions.format("No problems in {0}.", folder));
        }

        return ScriptCheckUtils.countErrors(findings) == 0 ? SUCCESS_EXIT : FAILURE_EXIT;
    }

    /**
     * Packs the script folder into the map, with the source maps of the scripts shrunk to their lines, under the output name with the
     * extension of the compatibility mark ({@value #COMPATIBILITY_OPTION}, else the manifest's, scanned with {@code auto}); with
     * {@value #PROTECT_OPTION} it protects the scripts and writes the source map of the protected scripts and of their compiled sources next
     * to the output file.
     */
    private static int runPack(@NotNull String @NotNull [] arguments, @NotNull Path map, @NotNull PrintStream output) throws IOException {
        List<String> options = List.of(SCRIPT_OPTION, OUTPUT_OPTION);
        Map<String, String> values = readOptions(arguments, List.of(SCRIPT_OPTION, OUTPUT_OPTION, COMPATIBILITY_OPTION), List.of(PROTECT_OPTION), options);
        ScriptPackage scriptPackage = ScriptCompiledMapUtils.shrinkMaps(ScriptPackage.readFolder(Path.of(values.get(SCRIPT_OPTION))));
        byte[] mapFile = Files.readAllBytes(map);
        ScriptCompatibility mark = values.containsKey(COMPATIBILITY_OPTION) ? readCompatibility(values.get(COMPATIBILITY_OPTION))
                                                                            : scriptPackage.getManifest().getCompatibility();
        ScriptCompatibilityScan scan = mark == ScriptCompatibility.ENHANCED_ONLY ? null : ScriptCompatibilityScan.scan(scriptPackage);
        ScriptCompatibility compatibility = mark == ScriptCompatibility.AUTO && scan != null ? scan.getCompatibility() : mark;
        Path outputFile = resolveOutputFile(Path.of(values.get(OUTPUT_OPTION)), map, mapFile, compatibility);

        if (!values.containsKey(PROTECT_OPTION)) {
            Files.write(outputFile, ScriptStorageUtils.writePackage(mapFile, map.toString(), scriptPackage));
            printPacked(output, scriptPackage, outputFile);
            printCompatibility(output, values.containsKey(COMPATIBILITY_OPTION), mark, compatibility, scan);

            return SUCCESS_EXIT;
        }

        ScriptSourceMap sourceMap = ScriptSourceMap.readCompiled(scriptPackage.getScripts());
        ScriptPackage protectedPackage = ScriptProtector.protectPackage(scriptPackage, sourceMap);
        Path sourceMapFile = ScriptSourceMap.resolveFile(outputFile);

        Files.write(outputFile, ScriptStorageUtils.writePackage(mapFile, map.toString(), protectedPackage, true));
        sourceMap.write(sourceMapFile);
        printPacked(output, protectedPackage, outputFile);
        printCompatibility(output, values.containsKey(COMPATIBILITY_OPTION), mark, compatibility, scan);
        output.println(Exceptions.format("The map is protected. Wrote the source map {0}; the map does not hold it.", sourceMapFile));

        return SUCCESS_EXIT;
    }

    private static @NotNull ScriptCompatibility readCompatibility(@NotNull String name) {
        ScriptCompatibility compatibility = ScriptCompatibility.findByName(name);

        if (compatibility == null) {
            throw Exceptions.newIllegalArgumentException("The mark {0} of {1} is not {2}, {3} or {4}.", name, COMPATIBILITY_OPTION,
                                                         ScriptCompatibility.ENHANCED_ONLY.getName(), ScriptCompatibility.COMPATIBLE.getName(),
                                                         ScriptCompatibility.AUTO.getName());
        }

        return compatibility;
    }

    /**
     * Gives the output file of a map with a compatibility mark: an {@code enhanced-only} map replaces a map extension at the end of the output
     * name with {@value ScriptCompatibility#ENHANCED_MAP_EXTENSION}, or appends it to a name without one; a {@code compatible} map keeps the
     * {@code .scm} or {@code .scx} of the output name, else takes the one of the input map, else the one of its scenario version.
     *
     * @param outputFile    the output file the command names
     * @param map           the input map
     * @param mapFile       the bytes of the input map
     * @param compatibility the mark, {@code enhanced-only} or {@code compatible}
     * @return the file to write
     * @throws IOException if the scenario of the map cannot be read
     */
    private static @NotNull Path resolveOutputFile(@NotNull Path outputFile, @NotNull Path map, byte @NotNull [] mapFile,
                                                   @NotNull ScriptCompatibility compatibility) throws IOException {
        String name = String.valueOf(outputFile.getFileName());
        String outputExtension = findMapExtension(name);
        String stem = outputExtension == null ? name : name.substring(0, name.length() - outputExtension.length());
        String extension;

        if (compatibility == ScriptCompatibility.ENHANCED_ONLY) {
            extension = ScriptCompatibility.ENHANCED_MAP_EXTENSION;
        } else if (outputExtension != null && !outputExtension.equalsIgnoreCase(ScriptCompatibility.ENHANCED_MAP_EXTENSION)) {
            return outputFile;
        } else {
            String mapExtension = findMapExtension(String.valueOf(map.getFileName()));

            if (mapExtension != null && !mapExtension.equalsIgnoreCase(ScriptCompatibility.ENHANCED_MAP_EXTENSION)) {
                extension = mapExtension.toLowerCase(Locale.ROOT);
            } else {
                extension = isExpansionMap(mapFile, map.toString()) ? FileBrowserScreen.EXPANSION_MAP_EXTENSION : FileBrowserScreen.ORIGINAL_MAP_EXTENSION;
            }
        }

        return outputFile.resolveSibling(stem + extension);
    }

    /**
     * @return the map extension at the end of a file name ({@code .scm}, {@code .scx} or {@value ScriptCompatibility#ENHANCED_MAP_EXTENSION}, in any case) as the name has it, or {@code null} if it ends with none
     */
    private static @Nullable String findMapExtension(@NotNull String fileName) {
        for (String extension : List.of(FileBrowserScreen.ORIGINAL_MAP_EXTENSION, FileBrowserScreen.EXPANSION_MAP_EXTENSION,
                                        ScriptCompatibility.ENHANCED_MAP_EXTENSION)) {
            if (fileName.toLowerCase(Locale.ROOT).endsWith(extension)) {
                return fileName.substring(fileName.length() - extension.length());
            }
        }

        return null;
    }

    private static boolean isExpansionMap(byte @NotNull [] mapFile, @NotNull String description) throws IOException {
        byte[] map = Arrays.copyOf(mapFile, ScriptStorageUtils.findMapEnd(mapFile, description));
        ChkFile scenario = ChkFile.parseTolerant(MpqArchive.open(map, description, true).readFile(ChkFile.ARCHIVE_ENTRY));
        byte[] version = scenario.getSection(ChkFile.VERSION_SECTION);

        return version != null && version.length >= Short.BYTES && ChkFile.isExpansionVersion(LittleEndianUtils.readUnsigned16(version, 0));
    }

    /**
     * Prints the compatibility mark of the packed map with its source and reasons: the first uses of gameplay members and what the scan
     * cannot judge, or for a map marked {@code compatible} by hand whose scripts change the game, the first reason as a warning.
     */
    private static void printCompatibility(@NotNull PrintStream output, boolean fromOption, @NotNull ScriptCompatibility mark,
                                           @NotNull ScriptCompatibility compatibility, @Nullable ScriptCompatibilityScan scan) {
        if (mark != ScriptCompatibility.AUTO) {
            output.println(Exceptions.format("The map is {0}, as {1} says.", compatibility.getName(), fromOption ? COMPATIBILITY_OPTION : "the manifest"));

            if (scan != null && scan.getCompatibility() == ScriptCompatibility.ENHANCED_ONLY) {
                ScriptCompatibilityScan.Reason reason = scan.getUses().isEmpty() ? scan.getWarnings().getFirst() : scan.getUses().getFirst();

                output.println(Exceptions.format("Warning: the scripts may change the game ({0}).", reason));
            }

            return;
        }

        if (scan == null || compatibility == ScriptCompatibility.COMPATIBLE) {
            output.println("The map is compatible: the scripts do not change the game.");

            return;
        }

        output.println(scan.getUses().isEmpty() ? "The map is enhanced-only: the scan cannot check every script."
                                                : "The map is enhanced-only: the scripts change the game.");
        printReasons(output, "  ", scan.getUses());
        printReasons(output, "Warning: ", scan.getWarnings());
    }

    private static void printReasons(@NotNull PrintStream output, @NotNull String prefix, @NotNull List<ScriptCompatibilityScan.Reason> reasons) {
        for (int index = 0; index < reasons.size() && index < MAXIMUM_PRINTED_REASONS; index++) {
            output.println(prefix + reasons.get(index));
        }

        if (reasons.size() > MAXIMUM_PRINTED_REASONS) {
            output.println(Exceptions.format("{0}and {1} more.", prefix, reasons.size() - MAXIMUM_PRINTED_REASONS));
        }
    }

    private static int runUnpack(@NotNull String @NotNull [] arguments, @NotNull Path map, @NotNull PrintStream output) throws IOException {
        List<String> options = List.of(OUTPUT_OPTION);
        Path outputFolder = Path.of(readOptions(arguments, options, List.of(), options).get(OUTPUT_OPTION));
        ScriptTrailer trailer = ScriptStorageUtils.readTrailer(Files.readAllBytes(map), map.toString());

        if (trailer == null) {
            throw Exceptions.newIOException("The map {0} has no script package.", map);
        }

        if (trailer.isProtected()) {
            throw Exceptions.newIOException("The map {0} is protected; its scripts and resources are not unpacked.", map);
        }

        ScriptPackage scriptPackage = trailer.createPackage();

        scriptPackage.writeFolder(outputFolder);
        output.println(Exceptions.format("Unpacked {0} scripts and {1} resources into {2}.", scriptPackage.getScripts().size(),
                                         scriptPackage.getResources().size(), outputFolder));

        return SUCCESS_EXIT;
    }

    /**
     * Prints the original location of {@value #AT_OPTION}, or the file of {@value #LOG_OPTION} with the original locations.
     */
    private static int runTrace(@NotNull String @NotNull [] arguments, @NotNull Path sourceMapFile, @NotNull PrintStream output) throws IOException {
        Map<String, String> values = readOptions(arguments, List.of(AT_OPTION, LOG_OPTION), List.of(), List.of());

        if (values.size() != 1) {
            throw Exceptions.newIllegalArgumentException("The command {0} needs {1} or {2}.", TRACE_COMMAND, AT_OPTION, LOG_OPTION);
        }

        if (!Files.isRegularFile(sourceMapFile)) {
            throw Exceptions.newIOException("The source map {0} is not a file.", sourceMapFile);
        }

        ScriptSourceMap sourceMap = ScriptSourceMap.read(sourceMapFile);
        String location = values.get(AT_OPTION);

        if (location == null) {
            output.print(sourceMap.translate(new String(Files.readAllBytes(Path.of(values.get(LOG_OPTION))), StandardCharsets.UTF_8)));

            return SUCCESS_EXIT;
        }

        int separator = location.lastIndexOf(LOCATION_SEPARATOR);
        String scriptName = location.substring(0, Math.max(separator, 0));
        int line;

        try {
            line = Integer.parseInt(location.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw Exceptions.newIllegalArgumentException("The location {0} is not <script>:<line>.", location);
        }

        String originalLocation = sourceMap.findOriginalLocation(scriptName, line);

        if (originalLocation == null) {
            throw Exceptions.newIOException("The source map {0} has no line {1} of {2}.", sourceMapFile, line, scriptName);
        }

        output.println(originalLocation);

        return SUCCESS_EXIT;
    }

    /**
     * Packs the script folder into a copy of the map, starts SC:R Enhanced on it, as a melee game with {@value #MELEE_OPTION} and with the
     * DevTools console with {@value #INSPECT_OPTION}, and waits for the game to end; with {@value #WATCH_OPTION} it packs again on every change
     * of the script folder meanwhile.
     */
    private static int runGame(@NotNull String @NotNull [] arguments, @NotNull Path map, @NotNull PrintStream output, @NotNull PrintStream errors,
                               @NotNull ScriptRunner.ProcessStarter starter) throws IOException, InterruptedException {
        List<String> remaining = new ArrayList<>(List.of(arguments));
        int inspectPort = readInspectPort(remaining);
        Map<String, String> values = readOptions(remaining.toArray(String[]::new), List.of(SCRIPT_OPTION, INSTALL_OPTION),
                                                 List.of(MELEE_OPTION, WATCH_OPTION), List.of(SCRIPT_OPTION));
        Path install = values.containsKey(INSTALL_OPTION) ? Path.of(values.get(INSTALL_OPTION)) : ScriptRunner.findInstall();

        if (install == null) {
            throw Exceptions.newIllegalArgumentException("No SC4J install is known: name one with {0}.", INSTALL_OPTION);
        }

        ScriptRunner runner = new ScriptRunner(install, map, Path.of(values.get(SCRIPT_OPTION)), values.containsKey(MELEE_OPTION), inspectPort,
                                               starter);
        boolean watching = values.containsKey(WATCH_OPTION);

        printPacked(output, runner.pack(), runner.getPackedMap());

        Process process = runner.start(ScriptRunner.getJavaProgram());

        if (inspectPort != ScriptRunner.NO_INSPECT_PORT) {
            output.println(Exceptions.format("DevTools console on {0}:{1} once the game runs: open chrome://inspect and add {0}:{1} to its targets.",
                                             ScriptInspector.HOST, Integer.toString(inspectPort)));
        }

        if (watching) {
            output.println("Watching the script folder: a change is packed again and loads when the map starts again.");
        }

        while (!process.waitFor(WATCH_INTERVAL_MILLISECONDS, TimeUnit.MILLISECONDS)) {
            if (!watching) {
                continue;
            }

            try {
                if (runner.repackIfChanged()) {
                    output.println("Packed the changed script folder; restart the map to load it.");
                }
            } catch (IOException exception) {
                errors.println(exception.getMessage());
            }
        }

        return process.exitValue() == 0 ? SUCCESS_EXIT : FAILURE_EXIT;
    }

    private static void printPacked(@NotNull PrintStream output, @NotNull ScriptPackage scriptPackage, @NotNull Path packedMap) {
        output.println(Exceptions.format("Packed {0} scripts and {1} resources into {2}.", scriptPackage.getScripts().size(),
                                         scriptPackage.getResources().size(), packedMap));
    }

    /**
     * Removes the option {@value #INSPECT_OPTION} from the options after the path and reads its port.
     *
     * @param arguments the command and its arguments, without the option afterwards
     * @return the port, {@link ScriptInspector#DEFAULT_PORT} without a value, or {@link ScriptRunner#NO_INSPECT_PORT} without the option
     * @throws IllegalArgumentException if the option is repeated or its port is not a port number
     */
    private static int readInspectPort(@NotNull List<String> arguments) {
        int port = ScriptRunner.NO_INSPECT_PORT;

        for (int index = arguments.size() - 1; index >= FIRST_OPTION_ARGUMENT; index--) {
            String argument = arguments.get(index);

            if (!argument.equals(INSPECT_OPTION) && !argument.startsWith(INSPECT_OPTION + OPTION_VALUE_SEPARATOR)) {
                continue;
            }

            if (port != ScriptRunner.NO_INSPECT_PORT) {
                throw Exceptions.newIllegalArgumentException("Repeated option: {0}", INSPECT_OPTION);
            }

            port = argument.equals(INSPECT_OPTION) ? ScriptInspector.DEFAULT_PORT : parsePort(argument.substring(INSPECT_OPTION.length() + 1));

            arguments.remove(index);
        }

        return port;
    }

    private static int parsePort(@NotNull String text) {
        try {
            int port = Integer.parseInt(text);

            if (port > 0 && port <= LARGEST_PORT) {
                return port;
            }
        } catch (NumberFormatException exception) {
            // The text is no number.
        }

        throw Exceptions.newIllegalArgumentException("The port {0} of {1} is not a port number.", text, INSPECT_OPTION);
    }

    /**
     * Reads the options after the path: each option once, a value option followed by its value.
     *
     * @param arguments       the command and its arguments
     * @param valueOptions    the options of the command that take a value
     * @param flagOptions     the options of the command without a value
     * @param requiredOptions the options that are needed
     * @return the option values by option, an empty value for a flag
     * @throws IllegalArgumentException if an option is unknown, repeated, without value or missing
     */
    private static @NotNull Map<String, String> readOptions(@NotNull String @NotNull [] arguments, @NotNull List<String> valueOptions,
                                                            @NotNull List<String> flagOptions, @NotNull List<String> requiredOptions) {
        Map<String, String> values = new HashMap<>();
        int index = FIRST_OPTION_ARGUMENT;

        while (index < arguments.length) {
            String option = arguments[index];
            String value;

            if (flagOptions.contains(option)) {
                value = "";
                index++;
            } else if (valueOptions.contains(option) && index + 1 < arguments.length) {
                value = arguments[index + 1];
                index += 2;
            } else {
                throw Exceptions.newIllegalArgumentException("Unknown option or missing value: {0}", option);
            }

            if (values.putIfAbsent(option, value) != null) {
                throw Exceptions.newIllegalArgumentException("Repeated option: {0}", option);
            }
        }

        for (String option : requiredOptions) {
            if (!values.containsKey(option)) {
                throw Exceptions.newIllegalArgumentException("The command {0} needs {1}.", arguments[COMMAND_ARGUMENT], option);
            }
        }

        return values;
    }

    private ScriptTool() {
    }
}
