package org.fogofwarlabs.sc4j.screnhanced.clone;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests the overlay of a clone: its files replace cloned files or add new ones, the lock records the SC:R mod file each one was made from,
 * a changed or unrecorded SC:R mod file stops the clone with one plain line, the difference shows the base then, the base now and the overlay,
 * and accepting records the files again.
 */
final class CloneOverlayTest {
    private static final @NotNull String TASK_PATH = ":my-mod:";
    private static final @NotNull String REPLACED = "module/src/main/resources/settings.txt";
    private static final @NotNull String ADDED = "module/src/main/resources/added.txt";
    private static final @NotNull String UNTOUCHED = "module/src/main/resources/untouched.txt";
    private static final @NotNull String BASE = "first\nsecond\nthird\n";
    private static final @NotNull String CHANGED_BASE = "first\nsecond changed\nthird\n";
    private static final @NotNull String OVERLAY = "first\nsecond\nthird\nfourth\n";

    /**
     * Creates an SC:R mod folder with one file and an overlay that replaces that file and adds another, without a lock.
     */
    private static @NotNull CloneOverlay createOverlay(@NotNull Path directory) throws IOException {
        Path mod = directory.resolve("scr");
        Path overlay = directory.resolve("overlay");

        write(mod, REPLACED, BASE);
        write(mod, UNTOUCHED, BASE);
        write(overlay, REPLACED, OVERLAY);
        write(overlay, ADDED, "added\n");

        return new CloneOverlay(overlay, directory.resolve("overlay.lock"), directory.resolve("overlay-base"));
    }

    private static void write(@NotNull Path root, @NotNull String path, @NotNull String text) throws IOException {
        Path file = root.resolve(path);

        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static @NotNull String read(@NotNull Path root, @NotNull String path) throws IOException {
        return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
    }


    @Test
    void apply_should_replace_a_cloned_file_and_add_a_new_one(@TempDir @NotNull Path directory) throws IOException {
        CloneOverlay overlay = createOverlay(directory);
        Path clone = directory.resolve("clone");

        write(clone, REPLACED, BASE);
        write(clone, UNTOUCHED, BASE);

        overlay.apply(clone);

        Assertions.assertEquals(List.of(ADDED, REPLACED), CloneOverlay.listPaths(directory.resolve("overlay")));
        Assertions.assertEquals(OVERLAY, read(clone, REPLACED));
        Assertions.assertEquals("added\n", read(clone, ADDED));
        Assertions.assertEquals(BASE, read(clone, UNTOUCHED));
    }

    @Test
    void apply_should_refuse_a_file_outside_the_cloned_modules(@TempDir @NotNull Path directory) throws IOException {
        CloneOverlay overlay = createOverlay(directory);
        Path clone = directory.resolve("clone");

        Files.createDirectories(clone.resolve("other"));

        IOException failure = Assertions.assertThrows(IOException.class, () -> overlay.apply(clone));

        Assertions.assertEquals("The overlay file " + ADDED + " is not in a cloned module (no folder module in the clone).", failure.getMessage());
    }

    @Test
    void accept_should_record_the_base_files_and_let_the_check_pass(@TempDir @NotNull Path directory) throws IOException {
        CloneOverlay overlay = createOverlay(directory);
        Path mod = directory.resolve("scr");

        overlay.accept(mod);
        overlay.check(mod, TASK_PATH);

        List<String> lock = Files.readAllLines(directory.resolve("overlay.lock"), StandardCharsets.UTF_8);

        Assertions.assertTrue(lock.contains("added  " + ADDED), lock::toString);
        Assertions.assertTrue(lock.contains(CloneOverlay.computeHash(mod.resolve(REPLACED)) + "  " + REPLACED), lock::toString);
        Assertions.assertEquals(BASE, read(directory.resolve("overlay-base"), REPLACED));
        Assertions.assertFalse(Files.exists(directory.resolve("overlay-base").resolve(ADDED)));
    }

    @Test
    void check_should_stop_with_one_plain_line_when_a_base_file_changed(@TempDir @NotNull Path directory) throws IOException {
        CloneOverlay overlay = createOverlay(directory);
        Path mod = directory.resolve("scr");

        overlay.accept(mod);
        write(mod, REPLACED, CHANGED_BASE);

        IOException failure = Assertions.assertThrows(IOException.class, () -> overlay.check(mod, TASK_PATH));

        Assertions.assertEquals("The SC:R mod changed " + REPLACED + " after the overlay file was made from it: see :my-mod:overlayDiff, " +
                                "merge the change into the overlay file by hand, then run :my-mod:acceptOverlay.", failure.getMessage());
    }

    @Test
    void check_should_stop_when_an_overlay_file_is_not_recorded_or_the_lock_names_a_missing_one(@TempDir @NotNull Path directory) throws IOException {
        CloneOverlay overlay = createOverlay(directory);
        Path mod = directory.resolve("scr");

        IOException unrecorded = Assertions.assertThrows(IOException.class, () -> overlay.check(mod, TASK_PATH));

        Assertions.assertEquals("overlay.lock does not record the overlay files " + ADDED + ", " + REPLACED +
                                ": run :my-mod:acceptOverlay to record the SC:R mod files they are made from.", unrecorded.getMessage());

        overlay.accept(mod);
        Files.delete(directory.resolve("overlay").resolve(ADDED));

        IOException stale = Assertions.assertThrows(IOException.class, () -> overlay.check(mod, TASK_PATH));

        Assertions.assertEquals("overlay.lock records " + ADDED + ", which the overlay does not hold: run :my-mod:acceptOverlay.",
                                stale.getMessage());
    }

    @Test
    void check_should_stop_when_the_sc_r_mod_gains_a_file_the_overlay_adds(@TempDir @NotNull Path directory) throws IOException {
        CloneOverlay overlay = createOverlay(directory);
        Path mod = directory.resolve("scr");

        overlay.accept(mod);
        write(mod, ADDED, BASE);

        IOException failure = Assertions.assertThrows(IOException.class, () -> overlay.check(mod, TASK_PATH));

        Assertions.assertTrue(failure.getMessage().startsWith("The SC:R mod changed " + ADDED + " after"), failure.getMessage());
    }

    @Test
    void check_should_not_count_line_endings(@TempDir @NotNull Path directory) throws IOException {
        CloneOverlay overlay = createOverlay(directory);
        Path mod = directory.resolve("scr");

        overlay.accept(mod);
        write(mod, REPLACED, BASE.replace("\n", "\r\n"));

        overlay.check(mod, TASK_PATH);
    }

    @Test
    void check_should_not_count_hidden_files_of_the_overlay(@TempDir @NotNull Path directory) throws IOException {
        CloneOverlay overlay = createOverlay(directory);
        Path mod = directory.resolve("scr");

        overlay.accept(mod);
        write(directory.resolve("overlay"), ".DS_Store", "-");
        write(directory.resolve("overlay"), "module/src/main/resources/._settings.txt", "-");

        overlay.check(mod, TASK_PATH);

        Assertions.assertEquals(List.of(ADDED, REPLACED), CloneOverlay.listPaths(directory.resolve("overlay")));
    }

    @Test
    void describe_changes_should_show_the_base_then_the_base_now_and_the_overlay(@TempDir @NotNull Path directory) throws IOException {
        CloneOverlay overlay = createOverlay(directory);
        Path mod = directory.resolve("scr");

        overlay.accept(mod);

        Assertions.assertEquals("No SC:R mod file changed after the overlay files were made from it.\n", overlay.describeChanges(mod));

        write(mod, REPLACED, CHANGED_BASE);

        Assertions.assertEquals("""
                                module/src/main/resources/settings.txt: the SC:R mod's change
                                --- base then
                                +++ base now
                                @@ -1,3 +1,3 @@
                                 first
                                -second
                                +second changed
                                 third
                                module/src/main/resources/settings.txt: the overlay's change
                                --- base then
                                +++ overlay
                                @@ -1,3 +1,4 @@
                                 first
                                 second
                                 third
                                +fourth
                                """, overlay.describeChanges(mod));
    }
}
