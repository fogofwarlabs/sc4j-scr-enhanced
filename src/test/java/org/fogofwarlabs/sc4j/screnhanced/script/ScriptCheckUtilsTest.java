package org.fogofwarlabs.sc4j.screnhanced.script;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests the findings of {@link ScriptCheckUtils} for each rule on one script folder each, and the exit code of the check command.
 */
final class ScriptCheckUtilsTest {
    private static final @NotNull String MANIFEST = "{\"name\": \"Check\", \"main\": \"main.js\", \"api\": \"1\"}";

    private static @NotNull List<String> check(@NotNull Path folder, @NotNull Map<String, String> files) throws IOException {
        ScriptMapScenario.writeFolder(folder, files);

        return ScriptCheckUtils.check(folder).stream().map(ScriptFinding::toString).toList();
    }

    /**
     * @return the files of a folder with the manifest and a main script, then the given files
     */
    private static @NotNull Map<String, String> withManifest(@NotNull String @NotNull ... namesAndContents) {
        Map<String, String> files = new LinkedHashMap<>();

        files.put(ScriptManifest.FILE_NAME, MANIFEST);
        files.put("main.js", "function onStart() {\n}\n");

        for (int index = 0; index < namesAndContents.length; index += 2) {
            files.put(namesAndContents[index], namesAndContents[index + 1]);
        }

        return files;
    }

    private static int runCheckCommand(@NotNull Path folder) {
        return ScriptTool.run(new String[]{ScriptTool.CHECK_COMMAND, folder.toString()},
                              new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                              new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @Test
    void check_should_find_nothing_in_a_clean_folder(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of(), check(folder, withManifest(
                "main.js", """
                           const util = require('./lib/util.js');
                           const SIZE = 32;
                           function onStart() {
                               var total = 0;
                               for (let i = 0; i < 3; i++) {
                                   let copy = i;
                                   total += copy;
                                   game.every(SIZE, function () { log.info(copy); });
                               }
                               [1, 2].forEach(function (value) {
                                   const doubled = value * 2;
                                   util.greet(doubled, Math.max(total, doubled));
                               });
                           }
                           function onUnitDied(event) {
                               var record = {Date: event.getFrame()};
                               log.info(record.Date);
                           }
                           """,
                "lib/util.js", "exports.greet = function (value) {\n    log.info(value);\n};\n",
                "resources/sounds/hit.wav", "RIFF data"
        )));
        Assertions.assertEquals(0, runCheckCommand(folder));
    }

    @Test
    void check_should_report_a_syntax_error_with_its_line(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("main.js:3: error: syntax error"),
                                check(folder, withManifest("main.js", "function onStart() {\n    var total = 1;\n    total = total +;\n}\n")));
        Assertions.assertEquals(1, runCheckCommand(folder));
    }

    @Test
    void check_should_name_the_syntax_that_rhino_lacks(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("async.js:2: error: missing ; before statement (Rhino 1.8.1 has no async and await; use promises, or " +
                                        "compile TypeScript with target ES5)",
                                        "class.js:1: error: identifier is a reserved word: class (Rhino 1.8.1 has no class syntax; use functions " +
                                        "and prototypes, or compile TypeScript with target ES5)",
                                        "const-count.js:1: error: syntax error (Rhino 1.8.1 has no for (const ...); use let, or compile TypeScript " +
                                        "with target ES5)",
                                        "const-in.js:1: error: syntax error (Rhino 1.8.1 has no for (const ...); use let, or compile TypeScript " +
                                        "with target ES5)",
                                        "const.js:1: error: syntax error (Rhino 1.8.1 has no for (const ...); use let, or compile TypeScript with " +
                                        "target ES5)",
                                        "export.js:1: error: identifier is a reserved word: export (Rhino 1.8.1 has no import and export; use " +
                                        "require and module.exports, or compile TypeScript with module commonjs)",
                                        "spread.js:1: error: syntax error (Rhino 1.8.1 has no spread ... and no rest element in destructuring, " +
                                        "only rest parameters; compile TypeScript with target ES5)",
                                        "super.js:2: error: super should be inside a shorthand function (Rhino 1.8.1 has no class syntax and takes " +
                                        "super only in object literal methods; use functions and prototypes, or compile TypeScript with target ES5)"),
                                check(folder, withManifest("class.js", "class Marine {}\n",
                                                           "async.js", "var x = 1;\nasync function load() {}\n",
                                                           "spread.js", "log.info(...[1, 2]);\n",
                                                           "super.js", "function Marine() {\n    super(...arguments);\n}\n",
                                                           "const.js", "for (const unit of game.units.getAll()) {}\n",
                                                           "const-in.js", "for (const key in game) {}\n",
                                                           "const-count.js", "for (const index = 0; index < 1; ) {}\n",
                                                           "export.js", "export var size = 1;\n",
                                                           "rest.js", "function sum(...values) {\n    return values.length;\n}\n")));
    }

    @Test
    void check_should_warn_of_unknown_hooks(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("main.js:1: warning: onUnitDeath is not a known hook and is not called.",
                                        "main.js:3: warning: onTick is not a known hook and is not called."),
                                check(folder, withManifest("main.js", """
                                                                      function onUnitDeath(event) {
                                                                      }
                                                                      var onTick = function () {};
                                                                      function onStart() {
                                                                          function onInner() {}
                                                                      }
                                                                      var onValue = 1;
                                                                      """)));
        Assertions.assertEquals(0, runCheckCommand(folder));
    }

    @Test
    void check_should_warn_of_exported_hooks(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("hud.js:4: warning: onLocalFrame is exported, but its name makes it a hook of this module: the runtime " +
                                        "calls it besides any caller; give a helper another name.",
                                        "hud.js:5: warning: onRender is exported, but its name makes it a hook of this module: the runtime calls " +
                                        "it besides any caller; give a helper another name."),
                                check(folder, withManifest("main.js", "var hud = require('./hud');\nfunction onLocalFrame(frame) {\n}\n",
                                                           "hud.js", """
                                                                     "use strict";
                                                                     exports.onLocalFrame = exports.draw = void 0;
                                                                     function onLocalFrame(frame) {}
                                                                     exports.onLocalFrame = onLocalFrame;
                                                                     module.exports.onRender = function (screen) {};
                                                                     exports.onLocalFrame = onLocalFrame;
                                                                     exports.draw = function () {};
                                                                     """)));
        Assertions.assertEquals(0, runCheckCommand(folder));
    }

    @Test
    void check_should_report_what_define_hooks_throws_for(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("main.js:3: error: onUnitDie is not a known hook: defineHooks throws.",
                                        "main.js:6: error: The hook onFrame is defined twice with defineHooks: defineHooks throws.",
                                        "main.js:8: error: The hook onStart is both a top-level function and defined with defineHooks: the module " +
                                        "fails to load.",
                                        "main.js:10: error: local.getPlayer throws in onBeforeOrder, which is shared code: read it in a local hook " +
                                        "(onLocal*, onRender).",
                                        "main.js:13: warning: onUnitDance is not a known hook and is not called."),
                                check(folder, withManifest("main.js", """
                                                                      defineHooks({
                                                                          onFrame(frame) {},
                                                                          onUnitDie(event) {},
                                                                          'onLocalFrame': function (frame) { log.info(local.getPlayer()); }
                                                                      });
                                                                      defineHooks({onFrame: function () {}});
                                                                      defineHooks({
                                                                          onStart() {},
                                                                          onRender(screen) { log.info(local.getPlayer()); },
                                                                          onBeforeOrder(decision) { log.info(local.getPlayer()); }
                                                                      });
                                                                      function onStart() {}
                                                                      function onUnitDance() {}
                                                                      """, "own.js", """
                                                                                     function defineHooks(hooks) {}
                                                                                     defineHooks({onTick() {}});
                                                                                     """)));
    }

    @Test
    void check_should_report_globals_that_scripts_lack(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("main.js:2: error: eval is not available to scripts.",
                                        "main.js:3: error: Date is not available to scripts.",
                                        "main.js:4: error: java is not available to scripts.",
                                        "main.js:5: error: Packages is not available to scripts.",
                                        "main.js:6: error: The Function constructor throws: scripts cannot create functions from strings.",
                                        "main.js:7: error: The Function constructor throws: scripts cannot create functions from strings."),
                                check(folder, withManifest("main.js", """
                                                                      function onStart() {
                                                                          eval('1');
                                                                          log.info(Date.now());
                                                                          var system = java.lang.System;
                                                                          var packages = Packages.java;
                                                                          var add = new Function('a', 'return a;');
                                                                          var subtract = Function('a', 'return -a;');
                                                                          var record = {Date: 1, eval: 2};
                                                                          log.info(record.Date, record.eval, typeof add === 'function' instanceof Function);
                                                                      }
                                                                      """, "own.js", "var Date = {now: function () { return 0; }};\nlog.info(Date.now());\n")));
    }

    @Test
    void check_should_report_names_that_core_dat_lacks(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("main.js:2: error: core.dat has no table unit.",
                                        "main.js:3: error: core.dat.units has no function getMineralCosts.",
                                        "main.js:4: error: core.dat.images has no function setGraphicsFile."),
                                check(folder, withManifest("main.js", """
                                                                      function onStart() {
                                                                          core.dat.unit.getMineralCost(0);
                                                                          core.dat.units.getMineralCosts(0);
                                                                          core.dat.images.setGraphicsFile(0, 1);
                                                                          core.dat.units.setMineralCost(0, core.dat.units.getMineralCost(0) + 1);
                                                                          core.dat.weapons.getTargetFlags(0);
                                                                      }
                                                                      """, "own.js", "var core = {dat: {unit: 1}};\nlog.info(core.dat.unit);\n")));
    }

    @Test
    void check_should_report_the_local_information_in_shared_code(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("main.js:1: error: local.getPlayer throws in the top level of a module, which is shared code: read it in a " +
                                        "local hook (onLocal*, onRender).",
                                        "main.js:3: error: local.getSelection throws in onFrame, which is shared code: read it in a local hook " +
                                        "(onLocal*, onRender)."),
                                check(folder, withManifest("main.js", """
                                                                      var first = local.getPlayer();
                                                                      function onFrame(frame) {
                                                                          log.info(local.getSelection());
                                                                          game.every(1, function () { log.info(local.isReplay()); });
                                                                      }
                                                                      function onLocalFrame(frame) {
                                                                          log.info(local.getMousePosition());
                                                                      }
                                                                      function onRender(screen) {
                                                                          log.info(local.getPlayer());
                                                                      }
                                                                      function helper() {
                                                                          return local.getScreenPosition();
                                                                      }
                                                                      """, "own.js", "var local = {getPlayer: function () { return 1; }};\nlog.info(local.getPlayer());\n")));
    }

    @Test
    void check_should_warn_of_a_const_in_a_loop(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("main.js:4: warning: The const value in a loop keeps its first value in Rhino 1.8.1; use let.",
                                        "main.js:8: warning: The const next in a loop keeps its first value in Rhino 1.8.1; use let."),
                                check(folder, withManifest("main.js", """
                                                                      const LIMIT = 3;
                                                                      function onStart() {
                                                                          for (var index = 0; index < LIMIT; index++) {
                                                                              const value = index * 2;
                                                                              log.info(value);
                                                                          }
                                                                          while (game.getFrame() < 0) {
                                                                              { const next = 1; log.info(next); }
                                                                          }
                                                                          game.units.getAll().forEach(function (unit) {
                                                                              const hp = unit.getHp();
                                                                              log.info(hp);
                                                                          });
                                                                      }
                                                                      """)));
    }

    @Test
    void check_should_warn_of_functions_that_use_a_for_let_variable(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("main.js:3: warning: The function uses index of the for (let ...) loop at line 2, which all iterations " +
                                        "share in Rhino 1.8.1; copy it into a let inside the loop body.",
                                        "main.js:6: warning: The function uses unit of the for (let ...) loop at line 5, which all iterations " +
                                        "share in Rhino 1.8.1; copy it into a let inside the loop body."),
                                check(folder, withManifest("main.js", """
                                                                      function onStart() {
                                                                          for (let index = 0; index < 3; index++) {
                                                                              game.every(1, function () { log.info(index); });
                                                                          }
                                                                          for (let unit of game.units.getAll()) {
                                                                              game.every(1, () => unit.kill());
                                                                          }
                                                                          for (let other = 0; other < 3; other++) {
                                                                              let copy = other;
                                                                              game.every(1, function () { log.info(copy); });
                                                                              game.every(1, function (other) { log.info(other); });
                                                                          }
                                                                      }
                                                                      """)));
    }

    @Test
    void check_should_report_manifest_problems(@TempDir @NotNull Path folder) throws IOException {
        Assertions.assertEquals(List.of("manifest.json: error: The script folder has no manifest.json."),
                                check(folder.resolve("missing"), Map.of("main.js", "function onStart() {}\n")));
        Assertions.assertEquals(List.of("manifest.json: error: The script manifest has no nonempty string \"main\"."),
                                check(folder.resolve("field"), Map.of(ScriptManifest.FILE_NAME, "{\"name\": \"A\", \"api\": \"1\"}",
                                                                      "main.js", "function onStart() {}\n")));
        Assertions.assertEquals(List.of("manifest.json: error: The main script start.js is not in the script folder.",
                                        "manifest.json: warning: The manifest names the script API 2; SC:R Enhanced has the script API 1."),
                                check(folder.resolve("main"), Map.of(ScriptManifest.FILE_NAME,
                                                                     "{\"name\": \"A\", \"main\": \"start.js\", \"api\": \"2\"}",
                                                                     "main.js", "function onStart() {}\n")));
    }

    @Test
    void check_should_read_grp_and_anim_resources_as_the_runtime(@TempDir @NotNull Path folder) throws IOException {
        List<String> findings = check(folder, withManifest("resources/images/bad.grp", "no GRP", "resources/images/bad.anim", "no anim",
                                                           "resources/sounds/hit.wav", "RIFF data"));

        Assertions.assertEquals(2, findings.size(), findings::toString);
        Assertions.assertTrue(findings.get(0).startsWith("resources/images/bad.anim: error: The resource cannot be read: "), findings::toString);
        Assertions.assertTrue(findings.get(1).startsWith("resources/images/bad.grp: error: The resource cannot be read: "), findings::toString);
        Assertions.assertEquals(1, runCheckCommand(folder));
    }

    @Test
    void check_should_report_a_source_map_that_does_not_read(@TempDir @NotNull Path folder) throws IOException {
        List<String> findings = check(folder, withManifest("main.js", "function onStart() {\n}\n//# sourceMappingURL=main.js.map",
                                                           "main.js.map", "{\"version\": 3}",
                                                           "lib/util.js", "var a;\n//# sourceMappingURL=util.js.map\n"));

        Assertions.assertEquals(List.of("lib/util.js: error: The script lib/util.js names the source map util.js.map, which is not a file.",
                                        "main.js: error: The source map of the script main.js is no source map of version 3 with sources and " +
                                        "mappings."),
                                findings);
        Assertions.assertEquals(1, runCheckCommand(folder));
    }

    @Test
    void check_should_fail_for_a_path_that_is_no_folder(@TempDir @NotNull Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("main.js"), "");

        Assertions.assertThrows(IOException.class, () -> ScriptCheckUtils.check(file));
        Assertions.assertEquals(1, runCheckCommand(file));
    }
}
