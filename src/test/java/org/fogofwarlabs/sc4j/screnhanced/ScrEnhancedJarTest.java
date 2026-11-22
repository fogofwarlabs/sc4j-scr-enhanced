package org.fogofwarlabs.sc4j.screnhanced;

import org.fogofwarlabs.sc4j.engine.mod.identity.ModContentDigestUtils;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentityMismatch;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.ModManifestReader;
import org.fogofwarlabs.sc4j.manifest.settings.SettingDefinition;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.fogofwarlabs.sc4j.scr.ScrMod;
import org.fogofwarlabs.sc4j.screnhanced.clone.CloneOverlay;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Tests the built jar of SC:R Enhanced against the built SC:R mod jar: it holds every entry of the SC:R mod jar plus only its own classes
 * (package {@code org.fogofwarlabs.sc4j.screnhanced}), its own resources ({@code src/mod/resources}), the files the overlay adds and the
 * bundled Rhino (its classes without {@code module-info.class}, and its licence text), and of the shared entries exactly the files the
 * overlay replaces differ (a replaced source as its class and nested classes) besides the notice of each build ({@code META-INF/NOTICE});
 * its manifest has its own mod id with the SC:R mod's entry, engine version and settings; and its mod identity differs from the SC:R mod's
 * first in the mod id.
 */
final class ScrEnhancedJarTest {
    private static final @NotNull String MOD_ID = "scr-enhanced";
    private static final @NotNull String RESOURCES_FOLDER = "/src/main/resources/";
    private static final @NotNull String SOURCES_FOLDER = "/src/main/java/";
    private static final @NotNull String SOURCE_SUFFIX = ".java";
    private static final @NotNull String CLASS_SUFFIX = ".class";
    private static final @NotNull String NESTED_CLASS_SEPARATOR = "$";
    private static final @NotNull String ENHANCED_PACKAGE_FOLDER = "org/fogofwarlabs/sc4j/screnhanced/";
    private static final @NotNull String NOTICE_ENTRY = "META-INF/NOTICE";
    private static final @NotNull String BUNDLED_LIBRARY_FOLDER = "org/mozilla/";
    private static final @NotNull String BUNDLED_LICENCE_ENTRY = "META-INF/MPL-2.0.txt";
    private static final @NotNull String BUNDLED_CONTEXT_ENTRY = "org/mozilla/javascript/Context.class";
    private static final @NotNull String MODULE_INFO_ENTRY = "module-info.class";

    /**
     * Creates the identity the runtime computes for a jar of the SC:R mod or its patch with the supported installation, whose build is the
     * mod's identity data.
     */
    static @NotNull ModIdentity createIdentity(@NotNull Path jar) throws IOException, ManifestException {
        return ModIdentity.create(EngineVersion.CURRENT, ModManifestReader.read(jar).getManifest(), ModContentDigestUtils.compute(jar),
                                  List.of(ScrMod.SUPPORTED_BUILD.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Lists the jar entries of the overlay files: a resource as its entry, a source as the entry of its class.
     */
    private static @NotNull List<@NotNull String> getOverlayEntries() throws IOException {
        List<String> overlaid = new ArrayList<>();

        for (String path : CloneOverlay.listPaths(ScrEnhancedTestResources.getOverlay())) {
            int resources = path.indexOf(RESOURCES_FOLDER);
            int sources = path.indexOf(SOURCES_FOLDER);

            if (resources > 0) {
                overlaid.add(path.substring(resources + RESOURCES_FOLDER.length()));
            } else {
                Assertions.assertTrue(sources > 0 && path.endsWith(SOURCE_SUFFIX), () -> "the overlay holds a main source or resource: " + path);
                overlaid.add(path.substring(sources + SOURCES_FOLDER.length(), path.length() - SOURCE_SUFFIX.length()) + CLASS_SUFFIX);
            }
        }

        return overlaid;
    }

    /**
     * @return {@code true} if the entry is an overlay entry or a nested class of an overlay source, {@code false} otherwise
     */
    private static boolean isOverlaid(@NotNull List<@NotNull String> overlaid, @NotNull String name) {
        for (String entry : overlaid) {
            if (name.equals(entry) ||
                entry.endsWith(CLASS_SUFFIX) &&
                name.startsWith(entry.substring(0, entry.length() - CLASS_SUFFIX.length()) + NESTED_CLASS_SEPARATOR)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Lists the jar entries of the mod's own resources.
     */
    private static @NotNull List<@NotNull String> getModResourceEntries() throws IOException {
        Path folder = ScrEnhancedTestResources.getModResources();

        if (!Files.isDirectory(folder)) {
            return List.of();
        }

        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).map(file -> folder.relativize(file).toString().replace(File.separatorChar, '/')).toList();
        }
    }

    private static @NotNull Map<@NotNull String, byte @NotNull []> readEntries(@NotNull Path jar) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();

        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : file.stream().toList()) {
                if (entry.isDirectory()) {
                    continue;
                }

                try (InputStream input = file.getInputStream(entry)) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }

        return entries;
    }


    @Test
    void the_enhanced_jar_holds_the_sc_r_mod_entries_and_differs_only_in_the_overlay() throws IOException {
        Map<String, byte[]> enhanced = readEntries(ScrEnhancedTestResources.getModJar());
        Map<String, byte[]> scr = readEntries(ScrEnhancedTestResources.getScrModJar());
        List<String> overlaid = getOverlayEntries();
        List<String> resources = getModResourceEntries();
        List<String> changed = new ArrayList<>();

        Assertions.assertTrue(enhanced.keySet().containsAll(scr.keySet()), "the enhanced jar holds every entry of the SC:R mod jar");

        for (String name : enhanced.keySet()) {
            Assertions.assertTrue(scr.containsKey(name) || name.startsWith(ENHANCED_PACKAGE_FOLDER) || resources.contains(name) ||
                                  isOverlaid(overlaid, name) || name.startsWith(BUNDLED_LIBRARY_FOLDER) || name.equals(BUNDLED_LICENCE_ENTRY),
                                  () -> "an entry beyond the SC:R mod: " + name);
        }

        for (Map.Entry<String, byte[]> entry : scr.entrySet()) {
            if (!entry.getKey().equals(NOTICE_ENTRY) && !Arrays.equals(entry.getValue(), enhanced.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }

        for (String name : changed) {
            Assertions.assertTrue(isOverlaid(overlaid, name), () -> "an entry the overlay does not replace differs: " + name);
        }

        for (String name : overlaid) {
            Assertions.assertTrue(changed.contains(name) || !scr.containsKey(name) && enhanced.containsKey(name),
                                  () -> "an entry of the overlay is unchanged or missing: " + name);
        }
    }

    @Test
    void the_enhanced_jar_carries_rhino_and_its_licence() throws IOException {
        Map<String, byte[]> enhanced = readEntries(ScrEnhancedTestResources.getModJar());

        Assertions.assertTrue(enhanced.containsKey(BUNDLED_CONTEXT_ENTRY), BUNDLED_CONTEXT_ENTRY);
        Assertions.assertTrue(enhanced.containsKey(BUNDLED_LICENCE_ENTRY), BUNDLED_LICENCE_ENTRY);
        Assertions.assertFalse(enhanced.containsKey(MODULE_INFO_ENTRY), MODULE_INFO_ENTRY);
    }

    @Test
    void the_enhanced_manifest_has_its_own_id_and_the_sc_r_mod_entry_and_settings() throws ManifestException {
        ModManifest enhanced = ModManifestReader.read(ScrEnhancedTestResources.getModJar()).getManifest();
        ModManifest scr = ModManifestReader.read(ScrEnhancedTestResources.getScrModJar()).getManifest();
        SettingDefinition enhancedFolder = enhanced.getSettings().findDefinition(ScrMod.GAME_FOLDER_SETTING);
        SettingDefinition scrFolder = scr.getSettings().findDefinition(ScrMod.GAME_FOLDER_SETTING);

        Assertions.assertEquals(MOD_ID, enhanced.getId());
        Assertions.assertNotEquals(scr.getId(), enhanced.getId());
        Assertions.assertNotEquals(scr.getName().get("en"), enhanced.getName().get("en"));
        Assertions.assertEquals(ScrMod.class.getName(), enhanced.getEntry());
        Assertions.assertEquals(scr.getEngine(), enhanced.getEngine());
        Assertions.assertNotNull(enhancedFolder);
        Assertions.assertNotNull(scrFolder);
        Assertions.assertNotNull(scrFolder.getSharedName());
        Assertions.assertEquals(scrFolder.getSharedName(), enhancedFolder.getSharedName());
        Assertions.assertNotNull(enhanced.getSettings().findDefinition(ScrMod.LOCALE_SETTING));
    }

    @Test
    void the_enhanced_identity_differs_from_the_sc_r_mod_identity_in_the_mod_id() throws IOException, ManifestException {
        ModIdentity enhanced = createIdentity(ScrEnhancedTestResources.getModJar());
        ModIdentity scr = createIdentity(ScrEnhancedTestResources.getScrModJar());

        Assertions.assertEquals(ModIdentityMismatch.MOD_ID, scr.findMismatch(enhanced));
        Assertions.assertNotEquals(scr, enhanced);
    }
}
