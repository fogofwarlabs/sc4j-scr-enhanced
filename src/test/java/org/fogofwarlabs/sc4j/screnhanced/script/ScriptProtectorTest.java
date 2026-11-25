package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Tests that {@link ScriptProtector} renames only the local names of a script, keeps the names looked up by name and the text of literals, and
 * that the protected scripts give the same results as the original ones, in Rhino and in a live game.
 */
final class ScriptProtectorTest {
    private static final @NotNull String SCRIPT_NAME = "main.js";
    private static final @NotNull String RESULT_NAME = "result";
    /**
     * The frames the live game runs: the script moves its units every eight frames and prints every 24 frames.
     */
    private static final int LIVE_FRAMES = 96;

    /**
     * Runs a script in a Rhino context with the settings of the runtime and returns the string of its global {@value #RESULT_NAME}.
     */
    private static @NotNull String evaluate(@NotNull String source) {
        try (Context context = new ContextFactory().enterContext()) {
            context.setInterpretedMode(true);
            context.setLanguageVersion(Context.VERSION_ECMASCRIPT);

            ScriptableObject scope = context.initStandardObjects();

            context.evaluateString(scope, source, SCRIPT_NAME, 1, null);

            return Context.toString(ScriptableObject.getProperty(scope, RESULT_NAME));
        }
    }

    /**
     * Protects a script and checks that the protected script gives the same result.
     */
    private static @NotNull String protectSameResult(@NotNull String source) throws IOException {
        String protectedText = ScriptProtector.protect(SCRIPT_NAME, source).getText();

        Assertions.assertEquals(evaluate(source), evaluate(protectedText), protectedText);

        return protectedText;
    }

    @Test
    void protect_renames_the_local_names_and_keeps_hooks_exports_requires_globals_and_properties() throws IOException {
        String protectedText = protectSameResult("""
                                                 var module = {exports: {}};
                                                 function require(id) { return {id: id}; }
                                                 var game = {printed: [], print: function (text) { this.printed.push(text); }};
                                                 function onFrame(frame) {
                                                     var count = frame * 2;
                                                     var helper = require('./lib/util.js');
                                                     game.print('tick ' + count + ' ' + helper.id);
                                                     return count;
                                                 }
                                                 module.exports.scale = function (value, factor) {
                                                     var scaled = value * factor;
                                                     return {scaled: scaled, value: value};
                                                 };
                                                 var result = JSON.stringify([onFrame(3), module.exports.scale(2, 5), game.printed]);
                                                 """);

        for (String kept : new String[]{"function onFrame(", "module.exports.scale=", "require('./lib/util.js')", "game.print(", "{scaled:",
                                        "value:", "JSON.stringify("}) {
            Assertions.assertTrue(protectedText.contains(kept), kept + " in " + protectedText);
        }

        for (String renamed : new String[]{"frame", "count", "helper", "factor", "var scaled"}) {
            Assertions.assertFalse(protectedText.contains(renamed), renamed + " in " + protectedText);
        }
    }

    @Test
    void protect_removes_a_line_comment_at_the_end_of_the_text() throws IOException {
        Assertions.assertEquals("var result=1;", protectSameResult("var result = 1;\n//# sourceMappingURL=main.js.map"));
        Assertions.assertEquals("var result=2;", protectSameResult("var result = 2; // two"));
    }

    @Test
    void protect_keeps_shadowed_names_and_closures_apart() throws IOException {
        protectSameResult("""
                          function makeCounter(start) {
                              var count = start;
                              function next(step) {
                                  var previous = count;
                                  count = count + step;
                                  return previous;
                              }
                              return {next: next, peek: function () { return count; }};
                          }
                          function shadow(value) {
                              var inner = function (value) { return value * 10; };
                              {
                                  let next = value + 1;
                                  let shadowed = (function (value) { let local = value; return local + next; })(100);
                                  return [inner(value), shadowed, value];
                              }
                          }
                          function closures() {
                              var functions = [];
                              for (var index = 0; index < 3; index++) {
                                  let copy = index;
                                  functions.push(function () { return copy * 10 + index; });
                              }
                              return functions.map(function (each) { return each(); });
                          }
                          function shortNames(b, a) {
                              var Math2 = Math.max(a, b);
                              return [a, b, Math2, (function (c) { return a + b + c + Math.min(a, c); })(1)];
                          }
                          var counter = makeCounter(5);
                          var result = JSON.stringify([counter.next(2), counter.next(3), counter.peek(), shadow(1), shadow(2), closures(), shortNames(3, 4)]);
                          """);
    }

    @Test
    void protect_keeps_the_arguments_object_and_its_link_to_the_parameters() throws IOException {
        String protectedText = protectSameResult("""
                                                 function sum(first, second) {
                                                     var total = 0;
                                                     for (var index = 0; index < arguments.length; index++) {
                                                         total += arguments[index];
                                                     }
                                                     arguments[0] = 100;
                                                     return [total, first, second, arguments.length];
                                                 }
                                                 function keep() {
                                                     var saved = arguments;
                                                     return function (value) { return saved[0] + value + arguments.length; };
                                                 }
                                                 var result = JSON.stringify([sum(1, 2, 3), keep(4)(5, 6)]);
                                                 """);

        Assertions.assertTrue(protectedText.contains("arguments.length"), protectedText);
        Assertions.assertFalse(protectedText.contains("total"), protectedText);
    }

    @Test
    void protect_keeps_the_names_of_scopes_with_eval_or_with() throws IOException {
        String protectedText = protectSameResult("""
                                                 function withEval(value) {
                                                     var local = value + 1;
                                                     return eval('local * 2 + value');
                                                 }
                                                 function withWith(value) {
                                                     var kept = value;
                                                     var object = {other: 3};
                                                     with (object) {
                                                         kept = kept + other;
                                                     }
                                                     return kept;
                                                 }
                                                 function outside(value) {
                                                     var renamed = value;
                                                     return renamed;
                                                 }
                                                 var result = JSON.stringify([withEval(2), withWith(4), outside(5)]);
                                                 """);

        Assertions.assertTrue(protectedText.contains("var local=value+1"), protectedText);
        Assertions.assertTrue(protectedText.contains("kept=kept+other"), protectedText);
        Assertions.assertFalse(protectedText.contains("renamed"), protectedText);
    }

    @Test
    void protect_renames_destructured_names_and_keeps_their_property_names() throws IOException {
        String protectedText = protectSameResult("""
                                                 function destructure(object, array) {
                                                     var {first, second: other, third = 3} = object;
                                                     var [one, , two = 22] = array;
                                                     var shorthand = {first, other};
                                                     return [first, other, third, one, two, shorthand];
                                                 }
                                                 function parameters({first, second}, [third]) {
                                                     return {first, second, third};
                                                 }
                                                 var result = JSON.stringify([destructure({first: 1, second: 2}, [5, 6]), parameters({first: 7, second: 8}, [9])]);
                                                 """);

        Assertions.assertTrue(protectedText.contains("{first:"), protectedText);
        Assertions.assertTrue(protectedText.contains("second:"), protectedText);
        Assertions.assertFalse(protectedText.contains("shorthand"), protectedText);
    }

    @Test
    void protect_keeps_labels_and_catch_variables_apart_from_variables() throws IOException {
        String protectedText = protectSameResult("""
                                                 function labels(limit) {
                                                     var outer = 0;
                                                     outer: for (var index = 0; index < limit; index++) {
                                                         for (var other = 0; other < limit; other++) {
                                                             if (other === 2) {
                                                                 continue outer;
                                                             }
                                                             if (index === 3) {
                                                                 break outer;
                                                             }
                                                             outer++;
                                                         }
                                                     }
                                                     return outer;
                                                 }
                                                 function catches() {
                                                     var caught = [];
                                                     try { throw 1; } catch (error) { caught.push(error); }
                                                     var hidden = 'outer';
                                                     try { throw 'inner'; } catch (hidden) { caught.push(hidden); }
                                                     caught.push(hidden);
                                                     return caught;
                                                 }
                                                 var result = JSON.stringify([labels(5), catches()]);
                                                 """);

        Assertions.assertTrue(protectedText.contains("continue outer;"), protectedText);
        Assertions.assertTrue(protectedText.contains("break outer;"), protectedText);
        Assertions.assertTrue(protectedText.contains("catch(hidden)"), protectedText);
        Assertions.assertFalse(protectedText.contains("caught"), protectedText);
    }

    @Test
    void protect_keeps_function_expression_names_and_hoisted_functions() throws IOException {
        protectSameResult("""
                          var factorial = 'outer';
                          var compute = function factorial(value) { return value <= 1 ? 1 : value * factorial(value - 1); };
                          function named() {
                              var self = function self(self) { return self; };
                              var recursive = function count(value) { var count2 = value; return count2 > 0 ? count(count2 - 1) + 1 : 0; };
                              return [self(7), recursive(3)];
                          }
                          function hoisted() {
                              return later();
                              function later() { return 'later'; }
                          }
                          var result = JSON.stringify([compute(5), factorial, named(), hoisted()]);
                          """);
    }

    @Test
    void protect_removes_comments_and_whitespace_and_keeps_line_breaks_between_tokens() throws IOException {
        String protectedText = protectSameResult("""
                                                 // A removed comment.
                                                 function lines() {
                                                     /* Another removed comment. */
                                                     var a = 1
                                                     var b = 2
                                                     var c = a
                                                     ++b
                                                     var d = a - -b
                                                     var e = a + +b
                                                     var f = 1 .toString() + 2..toString()
                                                     var g = 10 / 2 / /5/.source.length
                                                     var h = 'text  with   spaces /* kept */ // kept'
                                                     var i = `template
                                                   keeps   its   whitespace ${a  +  b}`
                                                     var j = a++ + ++b
                                                     return [a, b, c, d, e, f, g, h, i, j]
                                                 }
                                                 function early() {
                                                     return
                                                     1
                                                 }
                                                 var result = JSON.stringify([lines(), early()]);
                                                 """);

        Assertions.assertFalse(protectedText.contains("removed"), protectedText);
        Assertions.assertTrue(protectedText.contains("'text  with   spaces /* kept */ // kept'"), protectedText);
        Assertions.assertTrue(protectedText.contains("keeps   its   whitespace"), protectedText);
        Assertions.assertFalse(protectedText.contains("    "), protectedText);
    }

    @Test
    void protect_copies_a_parameter_list_with_a_destructured_default() throws IOException {
        String protectedText = protectSameResult("""
                                                 function withDefaults({first, second} = {first: 1, second: 2}, third = 7) {
                                                     var sum = first + second + third;
                                                     return sum;
                                                 }
                                                 var result = JSON.stringify([withDefaults(), withDefaults({first: 3, second: 4}, 5)]);
                                                 """);

        Assertions.assertTrue(protectedText.contains("({first, second} = {first: 1, second: 2}, third = 7)"), protectedText);
        Assertions.assertFalse(protectedText.contains("sum"), protectedText);
    }

    @Test
    void protect_maps_each_protected_line_to_its_original_line() throws IOException {
        ScriptProtector protector = ScriptProtector.protect(SCRIPT_NAME, """
                                                                         // Comment line 1.
                                                                         
                                                                         function onFrame(frame) {
                                                                             /* Comment line 4,
                                                                                line 5. */
                                                                             var value = frame +
                                                                                         1;
                                                                             return value;
                                                                         }
                                                                         """);

        Assertions.assertEquals("function onFrame(a){\nvar b=a+\n1;\nreturn b;\n}", protector.getText());
        Assertions.assertArrayEquals(new int[]{3, 6, 7, 8, 9}, protector.getLines());
        Assertions.assertEquals(2, protector.getRenames().size());
        Assertions.assertEquals("frame", protector.getRenames().getFirst().getOriginal());
        Assertions.assertEquals(3, protector.getRenames().getFirst().getLine());
    }

    @Test
    void protect_should_fail_when_the_script_has_a_syntax_error() {
        IOException refusal = Assertions.assertThrows(IOException.class, () -> ScriptProtector.protect(SCRIPT_NAME, "function (\n"));

        Assertions.assertTrue(refusal.getMessage().contains(SCRIPT_NAME), refusal.getMessage());
    }

    @Test
    void protect_package_protects_every_script_and_keeps_the_manifest_and_the_resources() throws IOException {
        byte[] resource = {1, 2, 3};
        ScriptPackage scriptPackage = ScriptPackage.create(
                ScriptManifest.parse("{\"name\": \"Map\", \"main\": \"main.js\", \"api\": \"1\"}".getBytes(StandardCharsets.UTF_8)),
                Map.of("main.js", "function onStart() {\n    var value = 1;\n}\n".getBytes(StandardCharsets.UTF_8),
                       "lib/util.js", "exports.add = function (first, second) {\n    return first + second;\n};\n".getBytes(StandardCharsets.UTF_8)),
                Map.of("data.bin", resource)
        );
        ScriptSourceMap sourceMap = new ScriptSourceMap();
        ScriptPackage protectedPackage = ScriptProtector.protectPackage(scriptPackage, sourceMap);

        Assertions.assertArrayEquals(scriptPackage.getManifest().getContent(), protectedPackage.getManifest().getContent());
        Assertions.assertSame(resource, protectedPackage.getResources().get("data.bin"));
        Assertions.assertEquals("function onStart(){\nvar a=1;\n}", new String(protectedPackage.getScripts().get("main.js"), StandardCharsets.UTF_8));
        Assertions.assertEquals("exports.add=function(a,b){\nreturn a+b;\n};",
                                new String(protectedPackage.getScripts().get("lib/util.js"), StandardCharsets.UTF_8));
        Assertions.assertEquals(2, sourceMap.findOriginalLine("lib/util.js", 2));
        Assertions.assertEquals("first", sourceMap.getRenames("lib/util.js").getFirst().getOriginal());
    }

    @Test
    @RequiresInstallation
    @ExtendWith(GameResourcesExtension.class)
    void a_protected_map_plays_as_the_original_one(@NotNull InstallationTestResources resources, @TempDir @NotNull Path folder) throws IOException {
        String source = """
                        // Moves four marines with random offsets and prints their positions.
                        var created = [];
                        function onStart() {
                            var human = game.players.getHumans()[0];
                            var center = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                            for (let index = 0; index < 4; index++) {
                                let unit = game.units.create(UnitType.TerranMarine, human, center.getPosition());
                                created.push(unit);
                            }
                            game.every(8, function () {
                                var moved = 0;
                                created.forEach(function (unit, index) {
                                    var offset = random.nextInt(64) + index * 8;
                                    unit.issueOrder(Order.Move, Position.of(unit.getX() + offset, unit.getY() + (index % 2 === 0 ? offset : -offset)));
                                    moved++;
                                });
                                game.print('ES moved ' + moved + ' at ' + game.getFrame());
                            });
                        }
                        function onFrame(frame) {
                            if (frame % 24 === 0) {
                                var total = 0;
                                created.forEach(function (unit) { total += unit.getX() * 3 + unit.getY(); });
                                game.print('ES total ' + frame + ' ' + total);
                            }
                        }
                        """;
        ScriptMapGameScenario original = ScriptMapGameScenario.startMelee(resources.getGameData(), folder.resolve("original"), source, Map.of(),
                                                                          false);
        ScriptMapGameScenario protectedGame = ScriptMapGameScenario.startMelee(resources.getGameData(), folder.resolve("protected"), source,
                                                                               Map.of(), true);

        original.stepFrames(LIVE_FRAMES);
        protectedGame.stepFrames(LIVE_FRAMES);

        Assertions.assertTrue(original.getPrints().size() > 4, original.getPrints()::toString);
        Assertions.assertEquals(original.getPrints(), protectedGame.getPrints());
        Assertions.assertEquals(original.getGame().computeStateHash(), protectedGame.getGame().computeStateHash());
    }
}
