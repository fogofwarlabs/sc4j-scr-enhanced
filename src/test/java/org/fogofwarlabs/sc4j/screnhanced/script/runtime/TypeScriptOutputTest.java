package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.screnhanced.script.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs the checked-in output of a TypeScript project that tsc compiled with target ES5, module commonjs and source maps (the test resource
 * folder {@code typescript}: the sources in {@code src}, the script folder in {@code script}): check finds nothing in it, and its classes with
 * inheritance, closures in loops, destructuring, spread, template strings, optional chaining, enums, namespaces, generators, for...of, async
 * functions, imports between modules and the hooks of {@code defineHooks} run as the sources say, protected too; an error names the line of the
 * TypeScript source, also with the source maps shrunk as pack shrinks them, and of the protected script once protected, which the source map of
 * the protected map traces to the TypeScript source.
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
     * The line of {@code src/main.ts} that throws at frame 2.
     */
    private static final @NotNull String THROWING_SOURCE_LINE = "(src/main.ts:72)";
    /**
     * The line of the compiled and of the protected {@code main.js} that throws at frame 2.
     */
    private static final @NotNull String THROWING_SCRIPT_LINE = "(main.js:73)";

    private static @NotNull Path getScriptFolder() throws URISyntaxException {
        URL folder = TypeScriptOutputTest.class.getResource(SCRIPT_FOLDER);

        Assertions.assertNotNull(folder, SCRIPT_FOLDER);

        return Path.of(folder.toURI());
    }

    private static void assertRun(@NotNull ScriptPackage runPackage, @NotNull String throwingLine) {
        List<String> lines = ScriptRuntimeScenario.run(runPackage, ScriptRuntimeScenario.SEED, FRAMES);

        Assertions.assertEquals(EXPECTED_LINES, lines.subList(0, Math.min(EXPECTED_LINES.size(), lines.size())), lines::toString);
        Assertions.assertEquals(EXPECTED_LINES.size() + 1, lines.size(), lines::toString);
        Assertions.assertTrue(lines.getLast().startsWith("ERROR main.js: ") && lines.getLast().contains("stopped at frame 2") &&
                              lines.getLast().contains(throwingLine), lines::toString);
    }

    @Test
    void check_should_find_nothing_in_the_compiled_output() throws IOException, URISyntaxException {
        Assertions.assertEquals(List.of(), ScriptCheckUtils.check(getScriptFolder()));
    }

    @Test
    void the_compiled_output_should_run_as_its_sources_with_errors_at_source_lines() throws IOException, URISyntaxException {
        assertRun(ScriptPackage.readFolder(getScriptFolder()), THROWING_SOURCE_LINE);
    }

    @Test
    void shrunk_source_maps_should_give_every_line_the_location_of_the_full_maps() throws IOException, URISyntaxException {
        ScriptPackage scriptPackage = ScriptPackage.readFolder(getScriptFolder());
        ScriptPackage shrunkPackage = ScriptCompiledMapUtils.shrinkMaps(scriptPackage);
        ScriptSourceMap fullMap = ScriptSourceMap.readCompiled(scriptPackage.getScripts());
        ScriptSourceMap shrunkMap = ScriptSourceMap.readCompiled(shrunkPackage.getScripts());

        for (Map.Entry<String, byte[]> script : scriptPackage.getScripts().entrySet()) {
            int lineCount = new String(script.getValue(), StandardCharsets.UTF_8).split("\n", -1).length;

            Assertions.assertTrue(shrunkPackage.getScripts().get(script.getKey()).length < script.getValue().length, script.getKey());

            for (int line = 1; line <= lineCount; line++) {
                Assertions.assertEquals(fullMap.findOriginalLocation(script.getKey(), line), shrunkMap.findOriginalLocation(script.getKey(), line),
                                        script.getKey() + ":" + line);
            }
        }

        assertRun(shrunkPackage, THROWING_SOURCE_LINE);
    }

    @Test
    void the_protected_output_should_run_as_its_sources_with_errors_that_trace_to_source_lines() throws IOException, URISyntaxException {
        ScriptPackage scriptPackage = ScriptPackage.readFolder(getScriptFolder());
        ScriptSourceMap sourceMap = ScriptSourceMap.readCompiled(scriptPackage.getScripts());
        ScriptPackage protectedPackage = ScriptProtector.protectPackage(scriptPackage, sourceMap);

        assertRun(protectedPackage, THROWING_SCRIPT_LINE);
        Assertions.assertEquals(THROWING_SOURCE_LINE, sourceMap.translate(THROWING_SCRIPT_LINE));
    }
}
