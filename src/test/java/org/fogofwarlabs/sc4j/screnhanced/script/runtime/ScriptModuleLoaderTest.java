package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.screnhanced.script.ScriptPackage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

/**
 * Tests the CommonJS-style modules of {@link ScriptModuleLoader}: name resolution, own scopes, caching, cycles and failed loads.
 */
final class ScriptModuleLoaderTest {
    @Test
    void resolve_name() {
        Assertions.assertEquals("util.js", ScriptModuleLoader.resolveName("main.js", "./util"));
        Assertions.assertEquals("util.js", ScriptModuleLoader.resolveName("main.js", "./util.js"));
        Assertions.assertEquals("util.js", ScriptModuleLoader.resolveName("main.js", "util"));
        Assertions.assertEquals("lib/util.js", ScriptModuleLoader.resolveName("lib/main.js", "./util"));
        Assertions.assertEquals("util.js", ScriptModuleLoader.resolveName("lib/main.js", "../util"));
        Assertions.assertEquals("lib/util.js", ScriptModuleLoader.resolveName("lib/deep/main.js", "./.././util"));
        Assertions.assertEquals("other/util.js", ScriptModuleLoader.resolveName("lib/main.js", "other/util"));
        Assertions.assertNull(ScriptModuleLoader.resolveName("main.js", "../util"));
        Assertions.assertNull(ScriptModuleLoader.resolveName("main.js", "/util"));
        Assertions.assertNull(ScriptModuleLoader.resolveName("main.js", ""));
        Assertions.assertNull(ScriptModuleLoader.resolveName("main.js", "./"));
        Assertions.assertNull(ScriptModuleLoader.resolveName("main.js", "lib//util"));
    }

    @Test
    void require_loads_modules_relative_to_the_requiring_script_once() throws IOException {
        ScriptPackage scriptPackage = ScriptRuntimeScenario.createPackage(
                ScriptRuntimeScenario.MAIN_NAME, """
                                                 const first = require('./lib/first');
                                                 const again = require('lib/first.js');
                                                 log.info(first.name, first.shared, again === first, typeof helper, typeof secret);
                                                 log.info(module.id, require('./shared').count);
                                                 """,
                "lib/first.js", """
                                const second = require('./second.js');
                                exports.name = 'first+' + second.name;
                                exports.shared = require('../shared').count;
                                """,
                "lib/second.js", """
                                 var secret = 1;
                                 function helper() {}
                                 module.exports = { name: 'second ' + module.id };
                                 """,
                "shared.js", """
                             log.info('shared loads');
                             exports.count = 7;
                             """
        );

        Assertions.assertEquals(List.of("INFO shared.js: shared loads", "INFO main.js: first+second lib/second.js 7 true undefined undefined",
                                        "INFO main.js: main.js 7"), ScriptRuntimeScenario.run(scriptPackage, ScriptRuntimeScenario.SEED, 0));
    }

    @Test
    void require_cycles_see_the_partial_exports() throws IOException {
        ScriptPackage scriptPackage = ScriptRuntimeScenario.createPackage(
                ScriptRuntimeScenario.MAIN_NAME, """
                                                 exports.ready = false;
                                                 const other = require('./other');
                                                 exports.ready = true;
                                                 log.info('main sees', other.seen, other.after());
                                                 """,
                "other.js", """
                            const main = require('./main');
                            exports.seen = main.ready;
                            exports.after = () => main.ready;
                            """
        );

        Assertions.assertEquals(List.of("INFO main.js: main sees false true"), ScriptRuntimeScenario.run(scriptPackage, ScriptRuntimeScenario.SEED, 0));
    }

    @Test
    void require_failures_are_script_errors() throws IOException {
        ScriptPackage scriptPackage = ScriptRuntimeScenario.createPackage(
                ScriptRuntimeScenario.MAIN_NAME, """
                                                 for (let id of ['./missing', '../outside', '/absolute', './broken', './broken']) {
                                                     try { require(id); log.info('loaded', id); } catch (e) { log.info(e.name, e.message); }
                                                 }
                                                 log.info(typeof onFrame);
                                                 """,
                "broken.js", """
                             log.info('broken runs');
                             function onFrame() { log.info('broken frame'); }
                             throw new Error('broken fails');
                             """
        );
        ScriptRuntimeScenario scenario = new ScriptRuntimeScenario(scriptPackage, ScriptRuntimeScenario.SEED, ScriptRuntime.INSTRUCTION_BUDGET);

        scenario.getRuntime().start();
        scenario.getRuntime().runFrame(0);

        Assertions.assertEquals(List.of("Error The script package has no script missing.js.", "Error The script name ../outside is not valid.",
                                        "Error The script name /absolute is not valid.", "broken runs", "Error broken fails", "broken runs",
                                        "Error broken fails", "undefined"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }
}
