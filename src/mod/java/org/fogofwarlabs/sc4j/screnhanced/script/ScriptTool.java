package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.screnhanced.script.inspector.ScriptInspector;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The command line tool of SC:R Enhanced Script.
 * <p>
 * Commands: {@code init <folder>} writes a new script folder ({@link ScriptProjectUtils}); {@code check <folder>} prints one line per
 * finding of a script folder ({@link ScriptCheckUtils}); {@code pack <map> --script <folder> -o <output> [--protect]} writes the map with the
 * package of the script folder to the output file, the bytes of the map followed by the script trailer ({@link ScriptStorageUtils}), which
 * replaces an old one; with {@code --protect} the map is protected: its scripts are packed in protected form ({@link ScriptProtector}) and the
 * source map is written next to the output file ({@link ScriptSourceMap#resolveFile}), not into the map. {@code unpack <map> -o <folder>}
 * writes the package of the map to the folder, which must be absent or empty, and refuses a protected map; {@code trace <source map> --at
 * <script>:<line>} prints the original location of a location of a protected script, and {@code trace <source map> --log <file>} prints the
 * file, such as a player's log, with the original locations; {@code run <map> --script <folder> [--melee] [--watch] [--inspect[=<port>]]
 * [--install <folder>]} packs the script folder into a copy of the map and runs SC:R Enhanced of an SC4J install on it until the game ends
 * ({@link ScriptRunner}), as a melee game with {@code --melee}, else as a Use Map Settings game, packing again on every change of the folder
 * with {@code --watch}, with the DevTools console on the port, by default {@value ScriptInspector#DEFAULT_PORT}, with {@code --inspect}. The
 * install is the one {@code --install} names, else {@link ScriptRunner#findInstall()}.
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
    public static final @NotNull String USAGE = "Usage: init <folder> | check <folder> | pack <map> --script <folder> -o <output> [--protect] | " +
                                                "unpack <map> -o <folder> | trace <source map> --at <script>:<line> | " +
                                                "trace <source map> --log <file> | run <map> --script <folder> [--melee] [--watch] " +
                                                "[--inspect[=<port>]] [--install <folder>]";

    private static final int COMMAND_ARGUMENT = 0;
    private static final int PATH_ARGUMENT = 1;
    private static final int FIRST_OPTION_ARGUMENT = 2;
    private static final int SUCCESS_EXIT = 0;
    private static final int FAILURE_EXIT = 1;
    private static final char LOCATION_SEPARATOR = ':';
    private static final char OPTION_VALUE_SEPARATOR = '=';
    private static final int LARGEST_PORT = 0xFFFF;
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

    private static int runInit(@NotNull String @NotNull [] arguments, @NotNull Path folder, @NotNull PrintStream output) throws IOException {
        readOptions(arguments, List.of(), List.of(), List.of());
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
     * Packs the script folder into the map; with {@value #PROTECT_OPTION} it protects the scripts and writes the source map next to the output
     * file.
     */
    private static int runPack(@NotNull String @NotNull [] arguments, @NotNull Path map, @NotNull PrintStream output) throws IOException {
        List<String> options = List.of(SCRIPT_OPTION, OUTPUT_OPTION);
        Map<String, String> values = readOptions(arguments, options, List.of(PROTECT_OPTION), options);
        Path outputFile = Path.of(values.get(OUTPUT_OPTION));
        ScriptPackage scriptPackage = ScriptPackage.readFolder(Path.of(values.get(SCRIPT_OPTION)));

        if (!values.containsKey(PROTECT_OPTION)) {
            Files.write(outputFile, ScriptStorageUtils.writePackage(Files.readAllBytes(map), map.toString(), scriptPackage));
            printPacked(output, scriptPackage, outputFile);

            return SUCCESS_EXIT;
        }

        ScriptSourceMap sourceMap = new ScriptSourceMap();
        ScriptPackage protectedPackage = ScriptProtector.protectPackage(scriptPackage, sourceMap);
        Path sourceMapFile = ScriptSourceMap.resolveFile(outputFile);

        Files.write(outputFile, ScriptStorageUtils.writePackage(Files.readAllBytes(map), map.toString(), protectedPackage, true));
        sourceMap.write(sourceMapFile);
        printPacked(output, protectedPackage, outputFile);
        output.println(Exceptions.format("The map is protected. Wrote the source map {0}; the map does not hold it.", sourceMapFile));

        return SUCCESS_EXIT;
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

        int originalLine = sourceMap.findOriginalLine(scriptName, line);

        if (originalLine == ScriptSourceMap.NO_LINE) {
            throw Exceptions.newIOException("The source map {0} has no line {1} of {2}.", sourceMapFile, line, scriptName);
        }

        output.println(scriptName + LOCATION_SEPARATOR + originalLine);

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
