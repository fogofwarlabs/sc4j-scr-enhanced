package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.net.lan.LanMapTransfer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Tests {@link ScriptStorageUtils} with synthetic map archives; the layout tests read the footer and the bytes before the trailer because their
 * layout is the map format.
 */
final class ScriptStorageUtilsTest {
    private static final @NotNull String MAP_NAME = "script-map.scx";
    private static final @NotNull String MAIN_SCRIPT = "main.js";
    private static final @NotNull String MODULE_SCRIPT = "lib/module.js";
    private static final @NotNull String RESOURCE_NAME = "images/marine.grp";
    private static final @NotNull String OTHER_RESOURCE_NAME = "sounds/hit.wav";
    private static final byte @NotNull [] VERSION_PAYLOAD = {(byte) 0xCD, 0x00};
    private static final byte @NotNull [] DIMENSIONS_PAYLOAD = {0x40, 0x00, 0x40, 0x00};
    private static final byte @NotNull [] MAGIC = {'S', 'C', 'E', 'S', 0x1A, '\r', '\n', 0};
    private static final int FOOTER_FLAGS_OFFSET = 2;
    private static final int FOOTER_PADDING_OFFSET = 4;
    private static final int FOOTER_DIRECTORY_OFFSET = 8;
    private static final int FOOTER_TRAILER_SIZE_OFFSET = 12;
    private static final int INCOMPRESSIBLE_SIZE = 4096;
    private static final int RANDOM_SEED = 17;
    /**
     * The bytes the hash table of the padding test reaches past the end of the map.
     */
    private static final int TABLE_OVERHANG = 100;

    private static byte @NotNull [] createMap() {
        return ScriptMapScenario.createMap(ScriptMapScenario.join(ScriptMapScenario.createSection(ChkFile.VERSION_SECTION, VERSION_PAYLOAD),
                                                                  ScriptMapScenario.createSection(ChkFile.DIMENSIONS_SECTION, DIMENSIONS_PAYLOAD)));
    }

    private static @NotNull ScriptPackage createPackage(@NotNull String name, @NotNull Map<String, String> scripts,
                                                        @NotNull Map<String, byte[]> resources) throws IOException {
        String manifest = "{\"name\": \"" + name + "\", \"main\": \"" + MAIN_SCRIPT + "\", \"api\": \"1\"}";

        return ScriptPackage.create(ScriptManifest.parse(manifest.getBytes(StandardCharsets.UTF_8)), toContents(scripts), resources);
    }

    private static @NotNull Map<String, byte[]> toContents(@NotNull Map<String, String> texts) {
        Map<String, byte[]> contents = new LinkedHashMap<>();

        for (Map.Entry<String, String> text : texts.entrySet()) {
            contents.put(text.getKey(), text.getValue().getBytes(StandardCharsets.UTF_8));
        }

        return contents;
    }

    private static @NotNull Map<String, String> toTexts(@NotNull Map<String, byte[]> contents) {
        Map<String, String> texts = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> content : contents.entrySet()) {
            texts.put(content.getKey(), new String(content.getValue(), StandardCharsets.UTF_8));
        }

        return texts;
    }

    private static byte @NotNull [] createIncompressible() {
        byte[] content = new byte[INCOMPRESSIBLE_SIZE];

        new Random(RANDOM_SEED).nextBytes(content);

        return content;
    }

    private static byte @NotNull [] digest(byte @NotNull [] content) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }

    private static @NotNull ByteBuffer readFooter(byte @NotNull [] file) {
        return ByteBuffer.wrap(file, file.length - ScriptStorageUtils.FOOTER_SIZE, ScriptStorageUtils.FOOTER_SIZE).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static @NotNull ScriptTrailer requireTrailer(byte @NotNull [] file) throws IOException {
        ScriptTrailer trailer = ScriptStorageUtils.readTrailer(file, MAP_NAME);

        Assertions.assertNotNull(trailer);

        return trailer;
    }

    private static int indexOf(byte @NotNull [] data, byte @NotNull [] part) {
        for (int start = 0; start + part.length <= data.length; start++) {
            if (Arrays.equals(data, start, start + part.length, part, 0, part.length)) {
                return start;
            }
        }

        return Assertions.fail("The bytes are not in the data.");
    }

    private static void assertSameEntries(@Nullable MpqArchive.MpqEntry expected, @Nullable MpqArchive.MpqEntry actual) {
        Assertions.assertEquals(expected == null, actual == null);

        if (expected != null && actual != null) {
            Assertions.assertEquals(expected.getFilePosition(), actual.getFilePosition());
            Assertions.assertEquals(expected.getCompressedSize(), actual.getCompressedSize());
            Assertions.assertEquals(expected.getFileSize(), actual.getFileSize());
            Assertions.assertEquals(expected.getFlags(), actual.getFlags());
        }
    }

    /**
     * Checks that the map reader reads the same archive from a map and from the map with its trailer.
     */
    private static void assertSameArchive(byte @NotNull [] map, byte @NotNull [] packedMap) throws IOException {
        MpqArchive archive = MpqArchive.open(map, MAP_NAME, true);
        MpqArchive packedArchive = MpqArchive.open(packedMap, MAP_NAME, true);
        List<MpqArchive.MpqEntry> entries = archive.getEntries();
        List<MpqArchive.MpqEntry> packedEntries = packedArchive.getEntries();

        Assertions.assertEquals(archive.getHeaderOffset(), packedArchive.getHeaderOffset());
        Assertions.assertEquals(entries.size(), packedEntries.size());

        for (int index = 0; index < entries.size(); index++) {
            assertSameEntries(entries.get(index), packedEntries.get(index));
        }

        for (String name : List.of(ChkFile.ARCHIVE_ENTRY, ScriptMapScenario.SOUND_NAME, "(listfile)")) {
            assertSameEntries(archive.findEntry(name), packedArchive.findEntry(name));
        }
    }

    @Test
    void write_package_should_append_the_trailer_after_the_unchanged_map() throws IOException {
        byte[] map = createMap();
        ScriptPackage scriptPackage = createPackage("Layout", Map.of(MAIN_SCRIPT, "game.print(1);"), Map.of(RESOURCE_NAME, createIncompressible()));
        byte[] packedMap = ScriptStorageUtils.writePackage(map, MAP_NAME, scriptPackage);
        ByteBuffer footer = readFooter(packedMap);

        Assertions.assertArrayEquals(map, Arrays.copyOf(packedMap, map.length));
        Assertions.assertArrayEquals(MAGIC, Arrays.copyOfRange(packedMap, packedMap.length - MAGIC.length, packedMap.length));
        Assertions.assertEquals(ScriptStorageUtils.FORMAT_VERSION, footer.getShort(0));
        Assertions.assertEquals(0, footer.getShort(FOOTER_FLAGS_OFFSET));
        Assertions.assertEquals(0, footer.getInt(FOOTER_PADDING_OFFSET));
        Assertions.assertTrue(footer.getInt(FOOTER_DIRECTORY_OFFSET) > 0);
        Assertions.assertEquals(packedMap.length - map.length, footer.getLong(FOOTER_TRAILER_SIZE_OFFSET));
        Assertions.assertEquals(map.length, ScriptStorageUtils.findMapEnd(packedMap, MAP_NAME));
        assertSameArchive(map, packedMap);
        Assertions.assertArrayEquals(ScriptMapScenario.SOUND, MpqArchive.open(packedMap, MAP_NAME, false).readFile(ScriptMapScenario.SOUND_NAME));
    }

    @Test
    void read_package_gives_back_the_written_package() throws IOException {
        Map<String, byte[]> resources = new LinkedHashMap<>();

        resources.put(RESOURCE_NAME, createIncompressible());
        resources.put(OTHER_RESOURCE_NAME, "RIFF RIFF RIFF RIFF RIFF RIFF".getBytes(StandardCharsets.ISO_8859_1));

        ScriptPackage scriptPackage = createPackage("Round Trip", Map.of(MAIN_SCRIPT, "require('lib/module.js');", MODULE_SCRIPT, "2;"), resources);
        ScriptPackage readPackage = ScriptStorageUtils.readPackage(ScriptStorageUtils.writePackage(createMap(), MAP_NAME, scriptPackage), MAP_NAME);

        Assertions.assertNotNull(readPackage);
        Assertions.assertArrayEquals(scriptPackage.getManifest().getContent(), readPackage.getManifest().getContent());
        Assertions.assertEquals(toTexts(scriptPackage.getScripts()), toTexts(readPackage.getScripts()));
        Assertions.assertEquals(List.of(RESOURCE_NAME, OTHER_RESOURCE_NAME), List.copyOf(readPackage.getResources().keySet()));

        for (Map.Entry<String, byte[]> resource : resources.entrySet()) {
            Assertions.assertArrayEquals(resource.getValue(), readPackage.getResources().get(resource.getKey()));
        }
    }

    @Test
    void write_package_again_replaces_the_trailer() throws IOException {
        byte[] map = createMap();
        byte[] firstMap = ScriptStorageUtils.writePackage(map, MAP_NAME, createPackage("First", Map.of(MAIN_SCRIPT, "1;"),
                                                                                       Map.of(RESOURCE_NAME, createIncompressible())));
        ScriptPackage secondPackage = createPackage("Second", Map.of(MAIN_SCRIPT, "2;"), Map.of());
        byte[] secondMap = ScriptStorageUtils.writePackage(firstMap, MAP_NAME, secondPackage);
        ScriptPackage readPackage = ScriptStorageUtils.readPackage(secondMap, MAP_NAME);

        Assertions.assertArrayEquals(ScriptStorageUtils.writePackage(map, MAP_NAME, secondPackage), secondMap);
        Assertions.assertArrayEquals(map, Arrays.copyOf(secondMap, map.length));
        Assertions.assertNotNull(readPackage);
        Assertions.assertEquals("Second", readPackage.getManifest().getName());
        Assertions.assertTrue(readPackage.getResources().isEmpty());
    }

    @Test
    void write_package_should_deflate_only_files_that_get_smaller() throws IOException {
        String repeated = "game.print('again');\n".repeat(64);
        byte[] incompressible = createIncompressible();
        ScriptTrailer trailer = requireTrailer(ScriptStorageUtils.writePackage(createMap(), MAP_NAME, createPackage(
                "Stored", Map.of(MAIN_SCRIPT, repeated), Map.of(RESOURCE_NAME, incompressible))));
        ScriptResources.Entry resource = trailer.getResources().findEntry(RESOURCE_NAME);

        Assertions.assertNotNull(resource);
        Assertions.assertEquals(incompressible.length, resource.getStoredSize(), "an incompressible file is stored as it is");
        Assertions.assertEquals(incompressible.length, resource.getOriginalSize());
        Assertions.assertArrayEquals(incompressible, trailer.getResources().read(resource));
        Assertions.assertEquals(repeated, new String(trailer.getScripts().getScripts().get(MAIN_SCRIPT), StandardCharsets.UTF_8));

        byte[] compressibleMap = ScriptStorageUtils.writePackage(createMap(), MAP_NAME, createPackage(
                "Stored", Map.of(MAIN_SCRIPT, "1;"), Map.of(RESOURCE_NAME, repeated.getBytes(StandardCharsets.UTF_8))));
        ScriptResources resources = requireTrailer(compressibleMap).getResources();
        ScriptResources.Entry compressible = resources.findEntry(RESOURCE_NAME);

        Assertions.assertNotNull(compressible);
        Assertions.assertTrue(compressible.getStoredSize() < compressible.getOriginalSize(), "a compressible file is stored as a zlib stream");
        Assertions.assertEquals(repeated, new String(resources.read(compressible), StandardCharsets.UTF_8));
    }

    @Test
    void read_trailer_should_read_resources_only_on_demand_and_check_their_digest() throws IOException, NoSuchAlgorithmException {
        byte[] incompressible = createIncompressible();
        byte[] packedMap = ScriptStorageUtils.writePackage(createMap(), MAP_NAME, createPackage("Lazy", Map.of(MAIN_SCRIPT, "1;"),
                                                                                                Map.of(RESOURCE_NAME, incompressible)));

        // A changed resource byte leaves the trailer readable until the resource is read.
        packedMap[indexOf(packedMap, incompressible) + 1] ^= 1;

        ScriptTrailer trailer = requireTrailer(packedMap);
        ScriptResources.Entry resource = trailer.getResources().findEntry(RESOURCE_NAME);

        Assertions.assertNotNull(resource);
        Assertions.assertArrayEquals(digest(incompressible), resource.getDigest());

        IOException mismatch = Assertions.assertThrows(IOException.class, () -> trailer.getResources().read(resource));

        Assertions.assertTrue(mismatch.getMessage().contains("SHA-256"), mismatch.getMessage());
        Assertions.assertThrows(IOException.class, trailer::createPackage);
    }

    @Test
    void read_trailer_should_reject_a_changed_script() throws IOException {
        String script = "var text = 'a script';";
        byte[] packedMap = ScriptStorageUtils.writePackage(createMap(), MAP_NAME, createPackage("Changed", Map.of(MAIN_SCRIPT, script), Map.of()));

        packedMap[indexOf(packedMap, script.getBytes(StandardCharsets.UTF_8))] ^= 1;

        Assertions.assertThrows(IOException.class, () -> ScriptStorageUtils.readTrailer(packedMap, MAP_NAME));
    }

    @Test
    void read_trailer_should_return_null_only_without_the_magic() throws IOException {
        byte[] map = createMap();
        byte[] packedMap = ScriptStorageUtils.writePackage(map, MAP_NAME, createPackage("Footer", Map.of(MAIN_SCRIPT, "1;"), Map.of()));
        byte[] withoutMagic = Arrays.copyOf(packedMap, packedMap.length - 1);
        byte[] otherVersion = packedMap.clone();
        byte[] tooLarge = packedMap.clone();
        byte[] truncated = new byte[packedMap.length - 1];
        int footerStart = packedMap.length - ScriptStorageUtils.FOOTER_SIZE;

        otherVersion[footerStart] = 2;
        ByteBuffer.wrap(tooLarge).order(ByteOrder.LITTLE_ENDIAN).putLong(footerStart + FOOTER_TRAILER_SIZE_OFFSET, packedMap.length + 1L);
        // One trailer byte cut out of the data: the stored sizes no longer fit.
        System.arraycopy(packedMap, 0, truncated, 0, map.length);
        System.arraycopy(packedMap, map.length + 1, truncated, map.length, truncated.length - map.length);

        IOException versionRefusal = Assertions.assertThrows(IOException.class, () -> ScriptStorageUtils.readTrailer(otherVersion, MAP_NAME));
        ScriptPackage repacked = createPackage("Repacked", Map.of(MAIN_SCRIPT, "1;"), Map.of());

        Assertions.assertNull(ScriptStorageUtils.readTrailer(map, MAP_NAME));
        Assertions.assertNull(ScriptStorageUtils.readPackage(map, MAP_NAME));
        Assertions.assertNull(ScriptStorageUtils.readTrailer(withoutMagic, MAP_NAME));
        Assertions.assertEquals(map.length, ScriptStorageUtils.findMapEnd(map, MAP_NAME));
        Assertions.assertTrue(versionRefusal.getMessage().contains("version"), versionRefusal.getMessage());
        Assertions.assertThrows(IOException.class, () -> ScriptStorageUtils.readTrailer(tooLarge, MAP_NAME));
        Assertions.assertThrows(IOException.class, () -> ScriptStorageUtils.readTrailer(truncated, MAP_NAME));
        Assertions.assertThrows(IOException.class, () -> ScriptStorageUtils.writePackage(truncated, MAP_NAME, repacked));
    }

    @Test
    void write_package_keeps_a_map_with_its_block_table_at_offset_zero() throws IOException {
        // As protectors do, the block table overlaps the header: the reader decrypts the header bytes as block entries.
        byte[] map = ScriptMapScenario.setHeaderField(createMap(), ScriptMapScenario.BLOCK_TABLE_POSITION_FIELD, 0);
        byte[] packedMap = ScriptStorageUtils.writePackage(map, MAP_NAME, createPackage("Protected", Map.of(MAIN_SCRIPT, "1;"),
                                                                                        Map.of(RESOURCE_NAME, createIncompressible())));

        Assertions.assertArrayEquals(map, Arrays.copyOf(packedMap, map.length));
        Assertions.assertEquals(0, readFooter(packedMap).getInt(FOOTER_PADDING_OFFSET));
        assertSameArchive(map, packedMap);
    }

    @Test
    void write_package_should_pad_mpq_ranges_past_the_end_of_the_map() throws IOException {
        byte[] plainMap = createMap();
        int hashTableSize = ScriptMapScenario.HASH_ENTRY_COUNT * ScriptMapScenario.TABLE_ENTRY_SIZE;
        // The hash table starts inside the map and ends past its end, where the reader reads zeros.
        byte[] map = ScriptMapScenario.setHeaderField(plainMap, ScriptMapScenario.HASH_TABLE_POSITION_FIELD,
                                                      plainMap.length - hashTableSize + TABLE_OVERHANG);
        byte[] archiveSizeMap = ScriptMapScenario.setHeaderField(plainMap, ScriptMapScenario.ARCHIVE_SIZE_FIELD, plainMap.length + TABLE_OVERHANG);
        byte[] absurdSizeMap = ScriptMapScenario.setHeaderField(plainMap, ScriptMapScenario.ARCHIVE_SIZE_FIELD, -1);
        ScriptPackage scriptPackage = createPackage("Padded", Map.of(MAIN_SCRIPT, "1;"), Map.of(RESOURCE_NAME, createIncompressible()));
        byte[] packedMap = ScriptStorageUtils.writePackage(map, MAP_NAME, scriptPackage);

        Assertions.assertArrayEquals(map, Arrays.copyOf(packedMap, map.length));
        Assertions.assertEquals(TABLE_OVERHANG, readFooter(packedMap).getInt(FOOTER_PADDING_OFFSET));
        Assertions.assertArrayEquals(new byte[TABLE_OVERHANG], Arrays.copyOfRange(packedMap, map.length, map.length + TABLE_OVERHANG));
        Assertions.assertEquals(map.length, ScriptStorageUtils.findMapEnd(packedMap, MAP_NAME));
        assertSameArchive(map, packedMap);
        Assertions.assertArrayEquals(ScriptStorageUtils.writePackage(map, MAP_NAME, scriptPackage),
                                     ScriptStorageUtils.writePackage(packedMap, MAP_NAME, scriptPackage));
        Assertions.assertEquals(TABLE_OVERHANG, readFooter(ScriptStorageUtils.writePackage(archiveSizeMap, MAP_NAME, scriptPackage))
                .getInt(FOOTER_PADDING_OFFSET));
        Assertions.assertEquals(0, readFooter(ScriptStorageUtils.writePackage(absurdSizeMap, MAP_NAME, scriptPackage)).getInt(FOOTER_PADDING_OFFSET));
    }

    @Test
    void replay_section_carries_the_scripts_and_references_the_resources() throws IOException {
        byte[] incompressible = createIncompressible();
        ScriptTrailer trailer = requireTrailer(ScriptStorageUtils.writePackage(createMap(), MAP_NAME, createPackage(
                "Replay", Map.of(MAIN_SCRIPT, "require('lib/module.js');", MODULE_SCRIPT, "2;"), Map.of(RESOURCE_NAME, incompressible))));
        byte[] section = ScriptStorageUtils.createReplaySection(trailer);
        ScriptTrailer recorded = ScriptStorageUtils.readReplaySection(section);
        ScriptResources.Entry reference = recorded.getResources().findEntry(RESOURCE_NAME);
        ScriptResources.Entry resource = trailer.getResources().findEntry(RESOURCE_NAME);

        Assertions.assertTrue(section.length < incompressible.length, "the section holds no resource bytes");
        Assertions.assertEquals("Replay", recorded.getScripts().getManifest().getName());
        Assertions.assertEquals(toTexts(trailer.getScripts().getScripts()), toTexts(recorded.getScripts().getScripts()));
        Assertions.assertNotNull(reference);
        Assertions.assertNotNull(resource);
        Assertions.assertEquals(0, reference.getStoredSize());
        Assertions.assertTrue(reference.isSameFile(resource));
        Assertions.assertThrows(IOException.class, () -> recorded.getResources().read(reference));

        ScriptResources selected = trailer.getResources().select(recorded.getResources().getEntries());

        Assertions.assertNotNull(selected);
        Assertions.assertArrayEquals(incompressible, selected.read(selected.getEntries().iterator().next()));

        ScriptTrailer otherTrailer = requireTrailer(ScriptStorageUtils.writePackage(createMap(), MAP_NAME, createPackage(
                "Other", Map.of(MAIN_SCRIPT, "1;"), Map.of(RESOURCE_NAME, "other".getBytes(StandardCharsets.UTF_8)))));
        byte[] mapTrailer = ScriptStorageUtils.writePackage(createMap(), MAP_NAME, createPackage("Map", Map.of(MAIN_SCRIPT, "1;"), Map.of()));

        Assertions.assertNull(otherTrailer.getResources().select(recorded.getResources().getEntries()));
        Assertions.assertThrows(IOException.class, () -> ScriptStorageUtils.readReplaySection(Arrays.copyOf(section, section.length - 1)));
        Assertions.assertThrows(IOException.class, () -> ScriptStorageUtils.readReplaySection(mapTrailer));
    }

    @Test
    void write_package_marks_a_protected_map_and_its_replay_section_keeps_the_mark(@TempDir @NotNull Path folder) throws IOException {
        ScriptPackage scriptPackage = createPackage("Protected", Map.of(MAIN_SCRIPT, "var a=1;"), Map.of(RESOURCE_NAME, createIncompressible()));
        byte[] protectedMap = ScriptStorageUtils.writePackage(createMap(), MAP_NAME, scriptPackage, true);
        byte[] plainMap = ScriptStorageUtils.writePackage(createMap(), MAP_NAME, scriptPackage);
        byte[] unknownFlagMap = protectedMap.clone();
        ScriptTrailer trailer = requireTrailer(protectedMap);
        byte[] section = ScriptStorageUtils.createReplaySection(trailer);
        ScriptTrailer recorded = ScriptStorageUtils.readReplaySection(section);
        Path protectedFile = folder.resolve("protected.scx");

        unknownFlagMap[unknownFlagMap.length - ScriptStorageUtils.FOOTER_SIZE + FOOTER_FLAGS_OFFSET] = 0x4;
        Files.write(protectedFile, protectedMap);

        Assertions.assertEquals(ScriptStorageUtils.PROTECTED_FLAG, readFooter(protectedMap).getShort(FOOTER_FLAGS_OFFSET));
        Assertions.assertEquals(0, readFooter(plainMap).getShort(FOOTER_FLAGS_OFFSET));
        Assertions.assertTrue(trailer.isProtected());
        Assertions.assertFalse(requireTrailer(plainMap).isProtected());
        Assertions.assertEquals(ScriptStorageUtils.REFERENCED_RESOURCES_FLAG | ScriptStorageUtils.PROTECTED_FLAG,
                                readFooter(section).getShort(FOOTER_FLAGS_OFFSET));
        Assertions.assertTrue(recorded.isProtected());
        Assertions.assertEquals(toTexts(trailer.getScripts().getScripts()), toTexts(recorded.getScripts().getScripts()));
        Assertions.assertFalse(ScriptStorageUtils.readReplaySection(ScriptStorageUtils.createReplaySection(requireTrailer(plainMap))).isProtected());
        Assertions.assertNotNull(ScriptStorageUtils.readResourceIndex(protectedFile));
        Assertions.assertEquals(plainMap.length, protectedMap.length);
        Assertions.assertThrows(IOException.class, () -> ScriptStorageUtils.readTrailer(unknownFlagMap, MAP_NAME));
    }

    @Test
    void maps_that_differ_only_in_their_script_are_different_lobby_maps(@TempDir @NotNull Path folder) throws IOException {
        byte[] firstMap = ScriptStorageUtils.writePackage(createMap(), MAP_NAME, createPackage("Same", Map.of(MAIN_SCRIPT, "1;"), Map.of()));
        byte[] secondMap = ScriptStorageUtils.writePackage(createMap(), MAP_NAME, createPackage("Same", Map.of(MAIN_SCRIPT, "2;"), Map.of()));
        int firstChecksum = LanMapTransfer.calculateChecksum(firstMap, firstMap.length);
        int secondChecksum = LanMapTransfer.calculateChecksum(secondMap, secondMap.length);
        Path secondFile = folder.resolve("second.scx");

        Files.write(secondFile, secondMap);

        Assertions.assertEquals(firstMap.length, secondMap.length);
        Assertions.assertNotEquals(firstChecksum, secondChecksum);
        Assertions.assertFalse(LanMapTransfer.createFileMatch(firstMap.length, firstChecksum).matches(secondFile));
        Assertions.assertTrue(LanMapTransfer.createFileMatch(secondMap.length, secondChecksum).matches(secondFile));
    }

    @Test
    void read_resource_index_reads_the_resources_of_a_map_file(@TempDir @NotNull Path folder) throws IOException {
        ScriptPackage scriptPackage = createPackage("Index", Map.of(MAIN_SCRIPT, "1;"), Map.of(RESOURCE_NAME, createIncompressible()));
        Path packedMap = folder.resolve("packed.scx");
        Path plainMap = folder.resolve("plain.scx");

        Files.write(packedMap, ScriptStorageUtils.writePackage(createMap(), MAP_NAME, scriptPackage));
        Files.write(plainMap, createMap());

        ScriptResources index = ScriptStorageUtils.readResourceIndex(packedMap);
        ScriptResources resources = requireTrailer(Files.readAllBytes(packedMap)).getResources();

        Assertions.assertNotNull(index);
        Assertions.assertNotNull(index.select(resources.getEntries()));
        Assertions.assertEquals(1, index.getEntries().size());
        Assertions.assertNull(ScriptStorageUtils.readResourceIndex(plainMap));
    }
}
