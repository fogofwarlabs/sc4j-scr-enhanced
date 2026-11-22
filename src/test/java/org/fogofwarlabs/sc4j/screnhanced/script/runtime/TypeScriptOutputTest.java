package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.screnhanced.script.ScriptCheckUtils;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptPackage;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptProtector;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptSourceMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the checked-in output of a TypeScript project that tsc compiled with target ES5 and module commonjs (the test resource folder
 * {@code typescript}: the sources in {@code src}, the script folder in {@code script}): check finds nothing in it, and its classes with
 * inheritance, closures in loops, destructuring, spread, template strings, optional chaining, enums, namespaces, generators, for...of, async
 * functions, imports between modules and the hooks of {@code defineHooks} run as the sources say, protected too; an error names the line of the
 * compiled script.
 */
final class TypeScriptOutputTest {
    private static final @NotNull String SCRIPT_FOLDER = "typescript/script";
    /**
     * The frames run: the hook {@code onFrame} of {@code defineHooks} logs at frame 1 and throws at frame 2.
     */
    private static final int FRAMES = 3;
    private static final @NotNull List<String> EXPECTED_LINES = List.of(
            "INFO main.js: classes=rectangle 6,square rectangle 9 | instanceof=true/1 | loops=0,3,6 | destructuring=1 9 4+5 none 7 12 | " +
            "spread=10 4 | optional=missing undefined | enum=5 Insane 3 | namespace=20 10 | generator=1,2,3,6,9",
            "INFO main.js: async=42",
            "INFO main.js: started",
            "INFO main.js: frame 1"
    );
    /**
     * The line of the compiled {@code main.js} that throws at frame 2.
     */
    private static final @NotNull String THROWING_LINE = "(main.js:73)";

    private static @NotNull Path getScriptFolder() throws URISyntaxException {
        URL folder = TypeScriptOutputTest.class.getResource(SCRIPT_FOLDER);

        Assertions.assertNotNull(folder, SCRIPT_FOLDER);

        return Path.of(folder.toURI());
    }

    @Test
    void check_should_find_nothing_in_the_compiled_output() throws IOException, URISyntaxException {
        Assertions.assertEquals(List.of(), ScriptCheckUtils.check(getScriptFolder()));
    }

    @Test
    void the_compiled_output_should_run_as_its_sources_with_errors_at_compiled_lines() throws IOException, URISyntaxException {
        ScriptPackage scriptPackage = ScriptPackage.readFolder(getScriptFolder());
        ScriptPackage protectedPackage = ScriptProtector.protectPackage(scriptPackage, new ScriptSourceMap());

        for (ScriptPackage runPackage : List.of(scriptPackage, protectedPackage)) {
            List<String> lines = ScriptRuntimeScenario.run(runPackage, ScriptRuntimeScenario.SEED, FRAMES);

            Assertions.assertEquals(EXPECTED_LINES, lines.subList(0, Math.min(EXPECTED_LINES.size(), lines.size())), lines::toString);
            Assertions.assertEquals(EXPECTED_LINES.size() + 1, lines.size(), lines::toString);
            Assertions.assertTrue(lines.getLast().startsWith("ERROR main.js: ") && lines.getLast().contains("stopped at frame 2") &&
                                  lines.getLast().contains(THROWING_LINE), lines::toString);
        }
    }
}
