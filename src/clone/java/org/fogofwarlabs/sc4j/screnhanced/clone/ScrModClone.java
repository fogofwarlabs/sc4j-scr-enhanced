package org.fogofwarlabs.sc4j.screnhanced.clone;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Makes the clone of the SC:R mod that a patch mod builds: it copies the main sources and resources ({@code src/main}) of every module of the
 * SC:R mod into a new folder, keeping the module folders ({@code scr-client/src/main/...}), and copies the patch mod's overlay over them
 * ({@link CloneOverlay}) after checking that no SC:R mod file changed under an overlay file. The build runs it before it compiles the clone.
 * <p>
 * Arguments: the mode, the SC:R mod folder ({@code scr}), the overlay folder, the lock file, the base folder and the path prefix of the
 * build's tasks for messages (such as {@code :}); for the mode {@value #CLONE_MODE} also the output folder (absent or empty) and
 * the module folder names to copy, such as {@code scr-data}. The mode {@value #DIFF_MODE} prints the changes of the SC:R mod files under the
 * overlay ({@link CloneOverlay#describeChanges}), the mode {@value #ACCEPT_MODE} records them ({@link CloneOverlay#accept}).
 */
public final class ScrModClone {
    /**
     * The mode that makes the clone.
     */
    public static final @NotNull String CLONE_MODE = "clone";
    /**
     * The mode that prints the changes of the SC:R mod files under the overlay.
     */
    public static final @NotNull String DIFF_MODE = "diff";
    /**
     * The mode that records the SC:R mod files under the overlay.
     */
    public static final @NotNull String ACCEPT_MODE = "accept";

    private static final int MODE_ARGUMENT = 0;
    private static final int MOD_FOLDER_ARGUMENT = 1;
    private static final int OVERLAY_FOLDER_ARGUMENT = 2;
    private static final int LOCK_FILE_ARGUMENT = 3;
    private static final int BASE_FOLDER_ARGUMENT = 4;
    private static final int TASK_PATH_ARGUMENT = 5;
    private static final int SHARED_ARGUMENTS = 6;
    private static final int CLONE_ARGUMENTS = SHARED_ARGUMENTS + 2;
    private static final @NotNull String MAIN_SOURCES = "src/main";
    private static final int SUCCESS_EXIT = 0;
    private static final int FAILURE_EXIT = 1;

    /**
     * Runs one mode and exits with {@code 0}, or prints why it cannot and exits with {@code 1}.
     *
     * @param arguments the mode and its arguments
     */
    public static void main(@NotNull String @NotNull [] arguments) {
        System.exit(run(arguments, System.out, System.err));
    }

    /**
     * Runs one mode.
     *
     * @param arguments the mode and its arguments
     * @param output    the stream of the printed changes and the recorded files
     * @param errors    the stream of the failure message, one line
     * @return {@code 0} if the mode ran, {@code 1} otherwise
     */
    public static int run(@NotNull String @NotNull [] arguments, @NotNull PrintStream output, @NotNull PrintStream errors) {
        if (arguments.length < SHARED_ARGUMENTS || CLONE_MODE.equals(arguments[MODE_ARGUMENT]) && arguments.length < CLONE_ARGUMENTS) {
            errors.println("Usage: ScrModClone clone|diff|accept <SC:R mod folder> <overlay folder> <lock file> <base folder> <task path> " +
                           "[<output folder> <module>...]");

            return FAILURE_EXIT;
        }

        Path modFolder = Path.of(arguments[MOD_FOLDER_ARGUMENT]);
        CloneOverlay overlay = new CloneOverlay(Path.of(arguments[OVERLAY_FOLDER_ARGUMENT]), Path.of(arguments[LOCK_FILE_ARGUMENT]),
                                                Path.of(arguments[BASE_FOLDER_ARGUMENT]));
        String taskPath = arguments[TASK_PATH_ARGUMENT];

        try {
            switch (arguments[MODE_ARGUMENT]) {
                case CLONE_MODE -> {
                    Path clone = Path.of(arguments[SHARED_ARGUMENTS]);

                    overlay.check(modFolder, taskPath);
                    copyModules(modFolder, List.of(arguments).subList(SHARED_ARGUMENTS + 1, arguments.length), clone);
                    overlay.apply(clone);
                }
                case DIFF_MODE -> output.print(overlay.describeChanges(modFolder));
                case ACCEPT_MODE -> {
                    for (String entry : overlay.accept(modFolder)) {
                        output.println(entry);
                    }
                }
                default ->
                        throw Exceptions.newIllegalArgumentException("Unknown mode {0}: expected clone, diff or accept.", arguments[MODE_ARGUMENT]);
            }

            return SUCCESS_EXIT;
        } catch (IllegalArgumentException | IOException exception) {
            errors.println(exception.getMessage());

            return FAILURE_EXIT;
        }
    }

    /**
     * Copies the main sources and resources of the modules into the output folder, under the same module folders.
     *
     * @param modFolder the SC:R mod folder
     * @param modules   the module folder names
     * @param output    the output folder, absent or empty
     * @throws IOException if a folder cannot be read or written, a module has no main sources or the output folder holds files
     */
    public static void copyModules(@NotNull Path modFolder, @NotNull List<@NotNull String> modules, @NotNull Path output) throws IOException {
        if (Files.isDirectory(output)) {
            try (Stream<Path> entries = Files.list(output)) {
                if (entries.findAny().isPresent()) {
                    throw Exceptions.newIOException("The clone folder is not empty: {0}", output);
                }
            }
        }

        for (String module : modules) {
            Path source = modFolder.resolve(module).resolve(MAIN_SOURCES);
            Path destination = output.resolve(module).resolve(MAIN_SOURCES);

            if (!Files.isDirectory(source)) {
                throw Exceptions.newIOException("The SC:R mod module {0} has no main sources: {1}", module, source);
            }

            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.sorted().toList()) {
                    Path target = destination.resolve(source.relativize(file).toString());

                    if (Files.isDirectory(file)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(file, target);
                    }
                }
            }
        }
    }

    private ScrModClone() {
    }
}
