package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqCryptoUtils;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Writes the synthetic map archives, CHK files and script folders of the script storage tests and reads folders back.
 */
public final class ScriptMapScenario {
    static final @NotNull String SOUND_NAME = "staredit\\wav\\sound.wav";
    static final byte @NotNull [] SOUND = "RIFF sound data".getBytes(StandardCharsets.ISO_8859_1);
    static final int ARCHIVE_SIZE_FIELD = 8;
    static final int HASH_TABLE_POSITION_FIELD = 16;
    static final int BLOCK_TABLE_POSITION_FIELD = 20;
    static final int BLOCK_TABLE_SIZE_FIELD = 28;
    static final int TABLE_ENTRY_SIZE = 16;
    static final int HASH_ENTRY_COUNT = 16;

    private static final @NotNull String LISTFILE_NAME = "(listfile)";
    private static final @NotNull String LISTFILE_LINE_SEPARATOR = "\r\n";
    private static final int MPQ_MAGIC = 0x1A51504D;
    private static final int HEADER_SIZE = 32;
    private static final int HEADER_SIZE_FIELD = 4;
    private static final int HASH_TABLE_SIZE_FIELD = 24;
    private static final int TABLE_ENTRY_DWORD_COUNT = 4;
    private static final int HASH_BLOCK_INDEX_DWORD = 3;
    private static final int EMPTY = -1;
    private static final int SECTION_HEADER_SIZE = 8;

    /**
     * Creates a map archive of uncompressed files with a header at offset {@code 0}, a zero sector size shift and a {@code (listfile)}:
     * the scenario and {@link #SOUND_NAME}.
     *
     * @param scenario the content of {@code staredit\scenario.chk}
     * @return the archive bytes
     */
    static byte @NotNull [] createMap(byte @NotNull [] scenario) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String listfile = ChkFile.ARCHIVE_ENTRY + LISTFILE_LINE_SEPARATOR + SOUND_NAME + LISTFILE_LINE_SEPARATOR;

        files.put(ChkFile.ARCHIVE_ENTRY, scenario);
        files.put(SOUND_NAME, SOUND);
        files.put(LISTFILE_NAME, listfile.getBytes(StandardCharsets.ISO_8859_1));

        int hashTablePosition = HEADER_SIZE;
        int blockTablePosition = hashTablePosition + HASH_ENTRY_COUNT * TABLE_ENTRY_SIZE;
        int dataPosition = blockTablePosition + files.size() * TABLE_ENTRY_SIZE;
        int dataSize = files.values().stream().mapToInt(content -> content.length).sum();
        byte[] archive = new byte[dataPosition + dataSize];
        int[] hashTable = new int[HASH_ENTRY_COUNT * TABLE_ENTRY_DWORD_COUNT];
        int[] blockTable = new int[files.size() * TABLE_ENTRY_DWORD_COUNT];
        int blockIndex = 0;
        int filePosition = dataPosition;

        LittleEndianUtils.writeInt32(archive, 0, MPQ_MAGIC);
        LittleEndianUtils.writeInt32(archive, HEADER_SIZE_FIELD, HEADER_SIZE);
        LittleEndianUtils.writeInt32(archive, ARCHIVE_SIZE_FIELD, archive.length);
        LittleEndianUtils.writeInt32(archive, HASH_TABLE_POSITION_FIELD, hashTablePosition);
        LittleEndianUtils.writeInt32(archive, BLOCK_TABLE_POSITION_FIELD, blockTablePosition);
        LittleEndianUtils.writeInt32(archive, HASH_TABLE_SIZE_FIELD, HASH_ENTRY_COUNT);
        LittleEndianUtils.writeInt32(archive, BLOCK_TABLE_SIZE_FIELD, files.size());
        Arrays.fill(hashTable, EMPTY);

        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            int slot = MpqCryptoUtils.hashTableOffset(file.getKey()) & (HASH_ENTRY_COUNT - 1);

            while (hashTable[slot * TABLE_ENTRY_DWORD_COUNT + HASH_BLOCK_INDEX_DWORD] != EMPTY) {
                slot = (slot + 1) & (HASH_ENTRY_COUNT - 1);
            }

            System.arraycopy(new int[]{MpqCryptoUtils.hashNameA(file.getKey()), MpqCryptoUtils.hashNameB(file.getKey()), 0, blockIndex}, 0,
                             hashTable, slot * TABLE_ENTRY_DWORD_COUNT, TABLE_ENTRY_DWORD_COUNT);
            System.arraycopy(new int[]{filePosition, file.getValue().length, file.getValue().length, MpqArchive.MPQ_FILE_EXISTS}, 0,
                             blockTable, blockIndex * TABLE_ENTRY_DWORD_COUNT, TABLE_ENTRY_DWORD_COUNT);
            System.arraycopy(file.getValue(), 0, archive, filePosition, file.getValue().length);

            filePosition += file.getValue().length;
            blockIndex++;
        }

        MpqCryptoUtils.encrypt(hashTable, MpqCryptoUtils.hashKey("(hash table)"));
        MpqCryptoUtils.encrypt(blockTable, MpqCryptoUtils.hashKey("(block table)"));
        putInts(archive, hashTablePosition, hashTable);
        putInts(archive, blockTablePosition, blockTable);

        return archive;
    }

    private static void putInts(byte @NotNull [] archive, int position, int @NotNull [] values) {
        for (int valueIndex = 0; valueIndex < values.length; valueIndex++) {
            LittleEndianUtils.writeInt32(archive, position + valueIndex * Integer.BYTES, values[valueIndex]);
        }
    }

    /**
     * Creates the bytes of one CHK section.
     *
     * @param name    the four-character tag
     * @param payload the payload
     * @return the header and the payload
     */
    static byte @NotNull [] createSection(@NotNull String name, byte @NotNull [] payload) {
        return createSection(name, payload.length, payload);
    }

    /**
     * Creates the bytes of one CHK section with a declared size that may differ from the payload length.
     *
     * @param name         the four-character tag
     * @param declaredSize the size DWORD
     * @param payload      the payload
     * @return the header and the payload
     */
    static byte @NotNull [] createSection(@NotNull String name, int declaredSize, byte @NotNull [] payload) {
        byte[] section = new byte[SECTION_HEADER_SIZE + payload.length];

        System.arraycopy(name.getBytes(StandardCharsets.ISO_8859_1), 0, section, 0, Integer.BYTES);
        LittleEndianUtils.writeInt32(section, Integer.BYTES, declaredSize);
        System.arraycopy(payload, 0, section, SECTION_HEADER_SIZE, payload.length);

        return section;
    }

    /**
     * Joins byte arrays.
     *
     * @param parts the arrays in order
     * @return the joined bytes
     */
    static byte @NotNull [] join(byte @NotNull [] @NotNull ... parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();

        for (byte[] part : parts) {
            joined.writeBytes(part);
        }

        return joined.toByteArray();
    }

    /**
     * Writes files into a folder.
     *
     * @param folder the folder
     * @param files  the UTF-8 file texts by name relative to the folder, with {@code /} separators
     * @throws IOException if a file cannot be written
     */
    static void writeFolder(@NotNull Path folder, @NotNull Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = folder.resolve(file.getKey());

            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
    }

    /**
     * Reads every file of a folder.
     *
     * @param folder the folder
     * @return the UTF-8 file texts by name relative to the folder, with {@code /} separators, ordered by name
     * @throws IOException if a file cannot be read
     */
    static @NotNull Map<String, String> readFolder(@NotNull Path folder) throws IOException {
        Map<String, String> files = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(folder.relativize(path).toString().replace('\\', '/'), Files.readString(path));
            }
        }

        return files;
    }

    /**
     * Copies a map archive with one header field changed.
     *
     * @param map   the archive bytes
     * @param field the offset of the field in the header at offset {@code 0}
     * @param value the new field value
     * @return the changed copy
     */
    static byte @NotNull [] setHeaderField(byte @NotNull [] map, int field, int value) {
        byte[] changed = map.clone();

        LittleEndianUtils.writeInt32(changed, field, value);

        return changed;
    }

    /**
     * Creates the resources of a script package packed into a synthetic map, as a game reads them from the map's script trailer.
     *
     * @param resources the resource contents by name
     * @return the resources, read on demand
     * @throws IOException if the package or the trailer is not valid
     */
    public static @NotNull ScriptResources createResources(@NotNull Map<String, byte[]> resources) throws IOException {
        String manifest = "{\"name\": \"Resources\", \"main\": \"main.js\", \"api\": \"1\"}";
        ScriptPackage scriptPackage = ScriptPackage.create(ScriptManifest.parse(manifest.getBytes(StandardCharsets.UTF_8)),
                                                           Map.of("main.js", new byte[0]), resources);
        ScriptTrailer trailer = ScriptStorageUtils.readTrailer(ScriptStorageUtils.writePackage(createMap(new byte[0]), "resources.scx", scriptPackage),
                                                               "resources.scx");

        if (trailer == null) {
            throw Exceptions.newIOException("The packed map has no script trailer.");
        }

        return trailer.getResources();
    }

    private ScriptMapScenario() {
    }
}
