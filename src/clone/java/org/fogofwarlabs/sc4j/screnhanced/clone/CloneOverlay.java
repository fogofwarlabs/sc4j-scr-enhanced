package org.fogofwarlabs.sc4j.screnhanced.clone;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/**
 * Holds the overlay of a clone: a folder that mirrors the clone's module folders ({@code scr-client/src/main/...}), whose files replace the
 * cloned files of the same path wholesale or, where the SC:R mod has none, add new ones.
 * <p>
 * The lock file records, for every overlay file, the SHA-256 of the SC:R mod file it was made from ({@value #ADDED} for a file the SC:R mod does
 * not have), one {@code <hash>  <path>} line per file in path order; lines starting with {@code #} are comments. The hash does not count line
 * endings, so a checkout with other line endings has the same hashes. The base folder keeps a copy of each of those SC:R mod files, so the
 * difference between the base then, the base now and the overlay can be shown after the SC:R mod changed one of them.
 * <p>
 * {@link #check} stops the clone with one plain line when the SC:R mod changed a recorded file or the lock does not match the overlay;
 * {@link #describeChanges} shows the differences, and {@link #accept} records the current SC:R mod files after the modder merged by hand.
 */
public final class CloneOverlay {
    /**
     * The lock value of an overlay file that the SC:R mod does not have.
     */
    public static final @NotNull String ADDED = "added";

    private static final @NotNull String HASH_ALGORITHM = "SHA-256";
    private static final @NotNull String LOCK_SEPARATOR = "  ";
    private static final @NotNull String LOCK_COMMENT = "#";
    private static final @NotNull List<@NotNull String> LOCK_HEADER = List.of(
            "# The SC:R mod files the overlay files are made from: the SHA-256 of each (line endings not counted), or \"added\" for a file",
            "# the SC:R mod does not have. The build stops when one of them changes; the acceptOverlay task records them again."
    );
    private static final @NotNull String OVERLAY_TASK = "overlayDiff";
    private static final @NotNull String ACCEPT_TASK = "acceptOverlay";
    private static final @NotNull String LIST_SEPARATOR = ", ";
    private static final char PATH_SEPARATOR = '/';
    /**
     * The folder of a module's main sources and resources, after the module folder; the clone copies only these.
     */
    private static final @NotNull String MAIN_SOURCES = "/src/main/";
    private static final byte CARRIAGE_RETURN = '\r';
    private static final byte LINE_FEED = '\n';

    /**
     * Lists the files of an overlay folder.
     *
     * @param overlayFolder the overlay folder
     * @return the paths of its files relative to the folder, with {@code /} separators, in path order
     * @throws IOException if the folder does not exist or cannot be read
     */
    public static @NotNull List<@NotNull String> listPaths(@NotNull Path overlayFolder) throws IOException {
        if (!Files.isDirectory(overlayFolder)) {
            throw Exceptions.newIOException("The overlay folder does not exist: {0}", overlayFolder);
        }

        try (Stream<Path> files = Files.walk(overlayFolder)) {
            return files.filter(Files::isRegularFile)
                        .map(file -> overlayFolder.relativize(file).toString().replace(file.getFileSystem().getSeparator().charAt(0), PATH_SEPARATOR))
                        .sorted()
                        .toList();
        }
    }

    /**
     * Computes the lock hash of a file: the SHA-256 of its contents with every carriage return before a line feed left out.
     *
     * @param file the file
     * @return the hash as lower case hexadecimal digits
     * @throws IOException if the file cannot be read
     */
    public static @NotNull String computeHash(@NotNull Path file) throws IOException {
        byte[] contents = Files.readAllBytes(file);
        byte[] normalized = new byte[contents.length];
        int length = 0;

        for (int index = 0; index < contents.length; index++) {
            if (contents[index] != CARRIAGE_RETURN || index + 1 == contents.length || contents[index + 1] != LINE_FEED) {
                normalized[length++] = contents[index];
            }
        }

        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);

            digest.update(normalized, 0, length);

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The Java runtime has no {0}.", HASH_ALGORITHM);
        }
    }

    private static @NotNull String computeBaseHash(@NotNull Path modFolder, @NotNull String path) throws IOException {
        Path base = modFolder.resolve(path);

        return Files.isRegularFile(base) ? computeHash(base) : ADDED;
    }

    private static @NotNull List<@NotNull String> readLines(@NotNull Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    }


    private final @NotNull Path overlayFolder;
    private final @NotNull Path lockFile;
    private final @NotNull Path baseFolder;

    /**
     * Creates a {@link CloneOverlay} instance.
     *
     * @param overlayFolder the overlay folder, which mirrors the clone's module folders
     * @param lockFile      the lock file, which may not exist yet
     * @param baseFolder    the folder of the copies of the SC:R mod files the overlay files are made from, which may not exist yet
     */
    public CloneOverlay(@NotNull Path overlayFolder, @NotNull Path lockFile, @NotNull Path baseFolder) {
        this.overlayFolder = overlayFolder;
        this.lockFile      = lockFile;
        this.baseFolder    = baseFolder;
    }

    /**
     * Checks the overlay against the lock and the SC:R mod: the lock records every overlay file and no other, and no SC:R mod file under an
     * overlay file changed after it was recorded. The failure message is one plain line.
     *
     * @param modFolder the SC:R mod folder, which holds the module folders
     * @param taskPath  the path prefix of the build's tasks named in the message, such as {@code :}
     * @throws IOException if a file cannot be read or the check fails
     */
    public void check(@NotNull Path modFolder, @NotNull String taskPath) throws IOException {
        List<String> paths = listPaths(this.overlayFolder);
        Map<String, String> lock = this.readLock();
        List<String> changed = new ArrayList<>();
        List<String> unrecorded = new ArrayList<>();

        for (String path : paths) {
            String recorded = lock.get(path);

            if (recorded == null) {
                unrecorded.add(path);
            } else if (!recorded.equals(computeBaseHash(modFolder, path))) {
                changed.add(path);
            }
        }

        if (!changed.isEmpty()) {
            throw Exceptions.newIOException("The SC:R mod changed {0} after the overlay file{1} made from it: see {2}{3}, merge the change into the " +
                                            "overlay file{4} by hand, then run {2}{5}.",
                                            String.join(LIST_SEPARATOR, changed), changed.size() == 1 ? " was" : "s were", taskPath, OVERLAY_TASK,
                                            changed.size() == 1 ? "" : "s", ACCEPT_TASK);
        }

        if (!unrecorded.isEmpty()) {
            throw Exceptions.newIOException("{0} does not record the overlay file{1} {2}: run {3}{4} to record the SC:R mod file{1} {5} made from.",
                                            this.lockFile.getFileName(), unrecorded.size() == 1 ? "" : "s", String.join(LIST_SEPARATOR, unrecorded),
                                            taskPath, ACCEPT_TASK, unrecorded.size() == 1 ? "it is" : "they are");
        }

        List<String> stale = lock.keySet().stream().filter(path -> !paths.contains(path)).toList();

        if (!stale.isEmpty()) {
            throw Exceptions.newIOException("{0} records {1}, which the overlay does not hold: run {2}{3}.",
                                            this.lockFile.getFileName(), String.join(LIST_SEPARATOR, stale), taskPath, ACCEPT_TASK);
        }
    }

    /**
     * Copies the overlay files into a clone: each replaces the cloned file of the same path or adds a new one.
     *
     * @param clone the clone folder, which holds the cloned module folders
     * @throws IOException if a file cannot be read or written, or an overlay file is not in a cloned module folder
     */
    public void apply(@NotNull Path clone) throws IOException {
        for (String path : listPaths(this.overlayFolder)) {
            String module = path.substring(0, Math.max(0, path.indexOf(PATH_SEPARATOR)));

            if (module.isEmpty() || !Files.isDirectory(clone.resolve(module))) {
                throw Exceptions.newIOException("The overlay file {0} is not in a cloned module (no folder {1} in the clone).", path, module);
            }

            if (!path.startsWith(module + MAIN_SOURCES)) {
                throw Exceptions.newIOException("The overlay file {0} is not in the main sources of its module ({1}{2}).", path, module, MAIN_SOURCES);
            }

            Path target = clone.resolve(path);

            Files.createDirectories(target.getParent());
            Files.copy(this.overlayFolder.resolve(path), target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Describes, for every overlay file whose SC:R mod file changed after it was recorded, the SC:R mod's change (base then against base now)
     * and the overlay's change (base then against the overlay), as unified differences; an overlay file the lock does not record shows the
     * SC:R mod file against the overlay.
     *
     * @param modFolder the SC:R mod folder, which holds the module folders
     * @return the description, each line ended by {@code \n}
     * @throws IOException if a file cannot be read
     */
    public @NotNull String describeChanges(@NotNull Path modFolder) throws IOException {
        Map<String, String> lock = this.readLock();
        StringBuilder builder = new StringBuilder();

        for (String path : listPaths(this.overlayFolder)) {
            String recorded = lock.get(path);

            if (recorded != null && recorded.equals(computeBaseHash(modFolder, path))) {
                continue;
            }

            List<String> now = readLines(modFolder.resolve(path));
            List<String> overlay = readLines(this.overlayFolder.resolve(path));

            if (recorded == null) {
                builder.append(path).append(": not recorded in ").append(this.lockFile.getFileName()).append('\n');
                builder.append(LineDiffUtils.formatUnified("base now", now, "overlay", overlay));
            } else {
                List<String> then = readLines(this.baseFolder.resolve(path));

                builder.append(path).append(": the SC:R mod's change\n");
                builder.append(LineDiffUtils.formatUnified("base then", then, "base now", now));
                builder.append(path).append(": the overlay's change\n");
                builder.append(LineDiffUtils.formatUnified("base then", then, "overlay", overlay));
            }
        }

        return builder.isEmpty() ? "No SC:R mod file changed after the overlay files were made from it.\n" : builder.toString();
    }

    /**
     * Records the current SC:R mod file of every overlay file: rewrites the lock and replaces the base folder with copies of those files.
     *
     * @param modFolder the SC:R mod folder, which holds the module folders
     * @return the lock lines without the comment lines
     * @throws IOException if a file cannot be read or written
     */
    public @NotNull List<@NotNull String> accept(@NotNull Path modFolder) throws IOException {
        List<String> entries = new ArrayList<>();

        this.deleteBaseFolder();

        for (String path : listPaths(this.overlayFolder)) {
            Path base = modFolder.resolve(path);

            if (Files.isRegularFile(base)) {
                Path copy = this.baseFolder.resolve(path);

                Files.createDirectories(copy.getParent());
                Files.copy(base, copy);
            }

            entries.add(computeBaseHash(modFolder, path) + LOCK_SEPARATOR + path);
        }

        List<String> lines = new ArrayList<>(LOCK_HEADER);

        lines.addAll(entries);

        Files.createDirectories(this.lockFile.toAbsolutePath().getParent());
        Files.writeString(this.lockFile, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        return entries;
    }

    /**
     * Reads the lock: the recorded value of every path. A missing lock file records nothing.
     */
    private @NotNull Map<@NotNull String, @NotNull String> readLock() throws IOException {
        Map<String, String> lock = new TreeMap<>();

        if (!Files.exists(this.lockFile)) {
            return lock;
        }

        List<String> lines = Files.readAllLines(this.lockFile, StandardCharsets.UTF_8);

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);

            if (line.isBlank() || line.startsWith(LOCK_COMMENT)) {
                continue;
            }

            int separator = line.indexOf(LOCK_SEPARATOR);

            if (separator <= 0) {
                throw Exceptions.newIOException("{0}:{1}: expected <hash>  <path>, found: {2}", this.lockFile, index + 1, line);
            }

            lock.put(line.substring(separator + LOCK_SEPARATOR.length()), line.substring(0, separator));
        }

        return lock;
    }

    private void deleteBaseFolder() throws IOException {
        if (!Files.isDirectory(this.baseFolder)) {
            return;
        }

        try (Stream<Path> files = Files.walk(this.baseFolder)) {
            for (Path file : files.sorted((first, second) -> second.compareTo(first)).toList()) {
                Files.delete(file);
            }
        }
    }
}
