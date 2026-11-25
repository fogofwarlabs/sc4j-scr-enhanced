package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads the source maps of compiled scripts: source map version {@value #SOURCE_MAP_VERSION}, as tsc writes it with {@code sourceMap} or
 * {@code inlineSourceMap}.
 * <p>
 * The last line of a compiled script is the comment {@code //# sourceMappingURL=<url>}: the URL is a file relative to the folder of the script,
 * such as {@code main.js.map}, or a base64 {@code data:} URL that holds the map. Only lines are read: each line of the script maps to the
 * original line of its first mapping segment; a line without a segment of the script's source has no original line. The source of a script is
 * the source of its first segment; the lines of other sources have no original line. The source name is resolved against the folder of the
 * script and the map's {@code sourceRoot}, relative to the script folder, without leading {@code ..} segments: {@code ../src/main.ts} of
 * {@code main.js} becomes {@code src/main.ts}. A map file named in another folder than its script ({@code mapRoot} of tsc) is not supported: its
 * sources are resolved against the script.
 * <p>
 * {@link #shrinkMap} keeps of a map only what the lines need, so that a packed map stays small.
 */
public final class ScriptCompiledMapUtils {
    /**
     * The version of the source maps read.
     */
    public static final int SOURCE_MAP_VERSION = 3;
    /**
     * The extension of a source map file that tsc writes next to a script.
     */
    public static final @NotNull String SOURCE_MAP_EXTENSION = ".map";

    private static final @NotNull List<String> REFERENCE_PREFIXES = List.of("//# sourceMappingURL=", "//@ sourceMappingURL=");
    private static final @NotNull String DATA_URL_PREFIX = "data:";
    private static final @NotNull String BASE64_MARKER = ";base64,";
    private static final @NotNull String INLINE_URL_PREFIX = "data:application/json;base64,";
    private static final @NotNull String VERSION_FIELD = "version";
    private static final @NotNull String SOURCES_FIELD = "sources";
    private static final @NotNull String SOURCE_ROOT_FIELD = "sourceRoot";
    private static final @NotNull String MAPPINGS_FIELD = "mappings";
    private static final char LINE_SEPARATOR = ';';
    private static final char SEGMENT_SEPARATOR = ',';
    private static final char NAME_SEPARATOR = '/';
    private static final char SCHEME_SEPARATOR = ':';
    private static final @NotNull String CURRENT_FOLDER_SEGMENT = ".";
    private static final @NotNull String PARENT_FOLDER_SEGMENT = "..";
    private static final @NotNull String BASE64_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    /**
     * The bits of a base64 VLQ digit: five value bits and the continuation bit.
     */
    private static final int VLQ_VALUE_BITS = 5;
    private static final int VLQ_VALUE_MASK = 0x1F;
    private static final int VLQ_CONTINUATION_BIT = 0x20;
    /**
     * The largest shift of a VLQ value that fits an {@code int}.
     */
    private static final int VLQ_MAXIMUM_SHIFT = 30;
    /**
     * The fields of a mapping segment: the generated column, then the source index, the original line and column, then the name index.
     */
    private static final int SEGMENT_SOURCE_FIELD = 1;
    private static final int SEGMENT_LINE_FIELD = 2;
    private static final int SEGMENT_MAXIMUM_FIELDS = 5;
    private static final int NO_SOURCE = -1;

    /**
     * The decoded lines of a source map: the path of the script's source, with the source root, and the source line of each line.
     */
    private static final class CompiledLines {
        private final @NotNull String sourcePath;
        private final int @NotNull [] lines;

        private CompiledLines(@NotNull String sourcePath, int @NotNull [] lines) {
            this.sourcePath = sourcePath;
            this.lines      = lines;
        }
    }

    /**
     * Finds the URL of the source map of a script: the comment {@code //# sourceMappingURL=<url>} on its last line that is not blank.
     *
     * @param text the script text
     * @return the URL, or {@code null} if the script names no source map
     */
    public static @Nullable String findReference(@NotNull String text) {
        String lastLine = text.substring(text.stripTrailing().lastIndexOf('\n') + 1).strip();

        for (String prefix : REFERENCE_PREFIXES) {
            if (lastLine.startsWith(prefix) && lastLine.length() > prefix.length() && lastLine.indexOf(' ', prefix.length()) < 0) {
                return lastLine.substring(prefix.length());
            }
        }

        return null;
    }

    /**
     * Puts the source map file that a script of a script folder names into the script as a base64 {@code data:} URL, so that the script
     * carries its map; the lines of the script stay as they are. A script without a reference, or with a {@code data:} URL, stays as it is.
     *
     * @param folder     the script folder
     * @param scriptName the script name
     * @param content    the script content
     * @return the script content with the map inside
     * @throws IOException if the named map is not a file, cannot be read or is not a source map
     */
    public static byte @NotNull [] inlineMap(@NotNull Path folder, @NotNull String scriptName, byte @NotNull [] content) throws IOException {
        String text = new String(content, StandardCharsets.UTF_8);
        String reference = findReference(text);

        if (reference == null) {
            return content;
        }

        if (reference.startsWith(DATA_URL_PREFIX)) {
            readLines(scriptName, decodeDataUrl(scriptName, reference), new ScriptSourceMap());

            return content;
        }

        int folderEnd = scriptName.lastIndexOf(NAME_SEPARATOR);
        Path scriptFolder = folderEnd < 0 ? folder : folder.resolve(scriptName.substring(0, folderEnd));
        Path mapFile;

        try {
            String fileName = URLDecoder.decode(reference.replace("+", "%2B"), StandardCharsets.UTF_8);

            mapFile = fileName.indexOf(SCHEME_SEPARATOR) < 0 ? scriptFolder.resolve(fileName).normalize() : null;
        } catch (IllegalArgumentException exception) {
            mapFile = null;
        }

        if (mapFile == null || !Files.isRegularFile(mapFile)) {
            throw Exceptions.newIOException("The script {0} names the source map {1}, which is not a file.", scriptName, reference);
        }

        byte[] map = Files.readAllBytes(mapFile);

        readLines(scriptName, map, new ScriptSourceMap());

        return replaceReference(text, reference, map);
    }

    /**
     * Adds the lines of the source map that a script holds as a {@code data:} URL to a source map ({@link ScriptSourceMap#addSource}). A script
     * without such a URL adds nothing.
     *
     * @param sourceMap  receives the lines
     * @param scriptName the script name
     * @param text       the script text
     * @throws IOException if the URL is not base64 or does not hold a source map
     */
    static void addSource(@NotNull ScriptSourceMap sourceMap, @NotNull String scriptName, @NotNull String text) throws IOException {
        String reference = findReference(text);

        if (reference != null && reference.startsWith(DATA_URL_PREFIX)) {
            readLines(scriptName, decodeDataUrl(scriptName, reference), sourceMap);
        }
    }

    private static byte @NotNull [] decodeDataUrl(@NotNull String scriptName, @NotNull String url) throws IOException {
        int marker = url.indexOf(BASE64_MARKER);

        if (marker < 0) {
            throw Exceptions.newIOException("The source map URL of the script {0} is no base64 data URL.", scriptName);
        }

        try {
            return Base64.getDecoder().decode(url.substring(marker + BASE64_MARKER.length()));
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException("The source map URL of the script {0} is no base64 data URL.", scriptName);
        }
    }

    /**
     * Replaces the source map of each script that holds one as a {@code data:} URL with a map of its lines only
     * ({@link #shrinkMap}).
     *
     * @param scriptPackage the package
     * @return the package with the shrunk maps, the same manifest and the same resources
     * @throws IOException if a map is not a source map
     */
    public static @NotNull ScriptPackage shrinkMaps(@NotNull ScriptPackage scriptPackage) throws IOException {
        Map<String, byte[]> scripts = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> script : scriptPackage.getScripts().entrySet()) {
            scripts.put(script.getKey(), shrinkMap(script.getKey(), script.getValue()));
        }

        return ScriptPackage.create(scriptPackage.getManifest(), scripts, scriptPackage.getResources());
    }

    /**
     * Replaces the source map that a script holds as a {@code data:} URL with a map of its lines only: {@code version}, the one source of the
     * script in {@code sources}, and {@code mappings} with one segment at column {@code 0} per line that has a source line; no names and no
     * source contents. The source lines and the source name stay as they were. A script without such a URL stays as it is.
     *
     * @param scriptName the script name
     * @param content    the script content
     * @return the script content with the shrunk map
     * @throws IOException if the map is not a source map
     */
    public static byte @NotNull [] shrinkMap(@NotNull String scriptName, byte @NotNull [] content) throws IOException {
        String text = new String(content, StandardCharsets.UTF_8);
        String reference = findReference(text);

        if (reference == null || !reference.startsWith(DATA_URL_PREFIX)) {
            return content;
        }

        CompiledLines compiled = decodeLines(scriptName, decodeDataUrl(scriptName, reference));
        Map<String, Object> map = new LinkedHashMap<>();
        StringBuilder mappings = new StringBuilder();
        int previousLine = 0;

        for (int index = 0; compiled != null && index < compiled.lines.length; index++) {
            if (index > 0) {
                mappings.append(LINE_SEPARATOR);
            }

            if (compiled.lines[index] == ScriptSourceMap.NO_LINE) {
                continue;
            }

            // Column 0, source 0, the line as a delta, column 0.
            mappings.append(BASE64_DIGITS.charAt(0)).append(BASE64_DIGITS.charAt(0));
            appendVlq(mappings, compiled.lines[index] - 1 - previousLine);
            mappings.append(BASE64_DIGITS.charAt(0));
            previousLine = compiled.lines[index] - 1;
        }

        map.put(VERSION_FIELD, SOURCE_MAP_VERSION);
        map.put(SOURCES_FIELD, compiled == null ? List.of() : List.of(compiled.sourcePath));
        map.put(MAPPINGS_FIELD, mappings.toString());

        return replaceReference(text, reference, JsonWriterUtils.write(map).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Replaces the URL of the reference on the last line of a script with a {@code data:} URL of a map.
     */
    private static byte @NotNull [] replaceReference(@NotNull String text, @NotNull String reference, byte @NotNull [] map) {
        int referenceStart = text.lastIndexOf(reference);

        return (text.substring(0, referenceStart) + INLINE_URL_PREFIX + Base64.getEncoder().encodeToString(map) +
                text.substring(referenceStart + reference.length())).getBytes(StandardCharsets.UTF_8);
    }

    private static void appendVlq(@NotNull StringBuilder builder, int value) {
        int encoded = value < 0 ? -value << 1 | 1 : value << 1;

        do {
            int digit = encoded & VLQ_VALUE_MASK;

            encoded >>>= VLQ_VALUE_BITS;
            builder.append(BASE64_DIGITS.charAt(encoded == 0 ? digit : digit | VLQ_CONTINUATION_BIT));
        } while (encoded != 0);
    }

    /**
     * Reads the lines of a source map and adds them to a source map.
     *
     * @param scriptName the name of the script the map belongs to
     * @param map        the UTF-8 JSON of the map
     * @param sourceMap  receives the lines
     * @throws IOException if the map is not a source map of version {@value #SOURCE_MAP_VERSION} with sources and mappings
     */
    private static void readLines(@NotNull String scriptName, byte @NotNull [] map, @NotNull ScriptSourceMap sourceMap) throws IOException {
        CompiledLines compiled = decodeLines(scriptName, map);

        if (compiled != null) {
            sourceMap.addSource(scriptName, resolveSourceName(scriptName, compiled.sourcePath), compiled.lines);
        }
    }

    /**
     * Decodes the lines of a source map.
     *
     * @param scriptName the name of the script the map belongs to
     * @param map        the UTF-8 JSON of the map
     * @return the source path and the source line of each line, or {@code null} if no segment has a source
     * @throws IOException if the map is not a source map of version {@value #SOURCE_MAP_VERSION} with sources and mappings
     */
    private static @Nullable CompiledLines decodeLines(@NotNull String scriptName, byte @NotNull [] map) throws IOException {
        Object value;

        try {
            value = JsonReader.parse(map);
        } catch (IOException exception) {
            throw Exceptions.newIOException(exception, "The source map of the script {0} is no JSON: {1}", scriptName, exception.getMessage());
        }

        if (!(value instanceof Map<?, ?> fields) || !(fields.get(VERSION_FIELD) instanceof Long version) || version != SOURCE_MAP_VERSION ||
            !(fields.get(SOURCES_FIELD) instanceof List<?> sources) || !(fields.get(MAPPINGS_FIELD) instanceof String mappings)) {
            throw Exceptions.newIOException("The source map of the script {0} is no source map of version {1} with sources and mappings.",
                                            scriptName, SOURCE_MAP_VERSION);
        }

        String sourceRoot = fields.get(SOURCE_ROOT_FIELD) instanceof String root ? root : "";
        int[] lines = new int[mappings.length() + 1];
        int[] segment = new int[SEGMENT_MAXIMUM_FIELDS];
        int lineCount = 1;
        int sourceIndex = 0;
        int originalLine = 0;
        int scriptSource = NO_SOURCE;
        int index = 0;

        while (index < mappings.length()) {
            char character = mappings.charAt(index);

            if (character == LINE_SEPARATOR) {
                lineCount++;
                index++;

                continue;
            }

            if (character == SEGMENT_SEPARATOR) {
                index++;

                continue;
            }

            int fieldCount = 0;

            while (index < mappings.length() && mappings.charAt(index) != LINE_SEPARATOR && mappings.charAt(index) != SEGMENT_SEPARATOR) {
                if (fieldCount == SEGMENT_MAXIMUM_FIELDS) {
                    throw Exceptions.newIOException("The source map of the script {0} has a segment of more than {1} fields.", scriptName,
                                                    SEGMENT_MAXIMUM_FIELDS);
                }

                int shift = 0;
                int encoded = 0;
                int digit;

                do {
                    digit = index < mappings.length() ? BASE64_DIGITS.indexOf(mappings.charAt(index)) : -1;

                    if (digit < 0 || shift > VLQ_MAXIMUM_SHIFT) {
                        throw Exceptions.newIOException("The source map of the script {0} has mappings that are not base64 VLQ.", scriptName);
                    }

                    encoded |= (digit & VLQ_VALUE_MASK) << shift;
                    shift += VLQ_VALUE_BITS;
                    index++;
                } while ((digit & VLQ_CONTINUATION_BIT) != 0);

                segment[fieldCount++] = (encoded & 1) == 0 ? encoded >>> 1 : -(encoded >>> 1);
            }

            if (fieldCount <= SEGMENT_LINE_FIELD) {
                continue;
            }

            sourceIndex += segment[SEGMENT_SOURCE_FIELD];
            originalLine += segment[SEGMENT_LINE_FIELD];

            if (scriptSource == NO_SOURCE) {
                scriptSource = sourceIndex;
            }

            if (lines[lineCount - 1] == ScriptSourceMap.NO_LINE && sourceIndex == scriptSource && originalLine >= 0) {
                lines[lineCount - 1] = originalLine + 1;
            }
        }

        if (scriptSource == NO_SOURCE) {
            return null;
        }

        if (scriptSource >= sources.size() || !(sources.get(scriptSource) instanceof String source)) {
            throw Exceptions.newIOException("The source map of the script {0} names the source {1}, which it does not have.", scriptName,
                                            scriptSource);
        }

        int[] sourceLines = new int[lineCount];
        String sourcePath = sourceRoot.isEmpty() || sourceRoot.endsWith(String.valueOf(NAME_SEPARATOR)) ? sourceRoot + source
                                                                                                        : sourceRoot + NAME_SEPARATOR + source;

        System.arraycopy(lines, 0, sourceLines, 0, lineCount);

        return new CompiledLines(sourcePath, sourceLines);
    }

    /**
     * Resolves the path of a source, with the source root, against the folder of its script: relative to the script folder with {@code /}
     * separators and without leading {@code ..} segments; a path with a scheme or an absolute path stays as it is.
     */
    private static @NotNull String resolveSourceName(@NotNull String scriptName, @NotNull String path) {
        if (path.indexOf(SCHEME_SEPARATOR) >= 0 || path.startsWith(String.valueOf(NAME_SEPARATOR))) {
            return path;
        }

        int folderEnd = scriptName.lastIndexOf(NAME_SEPARATOR);
        String joined = folderEnd < 0 ? path : scriptName.substring(0, folderEnd + 1) + path;
        Deque<String> segments = new ArrayDeque<>();

        for (String segment : joined.split(String.valueOf(NAME_SEPARATOR))) {
            if (segment.equals(PARENT_FOLDER_SEGMENT)) {
                segments.pollLast();
            } else if (!segment.isEmpty() && !segment.equals(CURRENT_FOLDER_SEGMENT)) {
                segments.addLast(segment);
            }
        }

        return String.join(String.valueOf(NAME_SEPARATOR), segments);
    }

    private ScriptCompiledMapUtils() {
    }
}
