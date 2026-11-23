package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests the Chrome DevTools Protocol of {@link ScriptInspector} with a scripted DevTools client against a script on a game without game data,
 * whose console runs local code: evaluation, remote objects with previews, properties, completion, exceptions, console and exception events,
 * the last result and the modules, the refusals of local code, of side effects and of incomplete text, and the instruction budget.
 */
final class ScriptInspectorTest {
    private static final @NotNull String MAIN_SCRIPT = """
                                                       var counter = 3;
                                                       var state = {name: 'base', items: [1, 2, 3]};
                                                       var util = require('./util');
                                                       function helper() {
                                                           return counter * 2;
                                                       }
                                                       function onFrame(frame) {
                                                           if (frame === 2) {
                                                               log.warn('frame', frame);
                                                           }
                                                           if (frame === 3) {
                                                               state.missing.call();
                                                           }
                                                       }
                                                       """;
    private static final @NotNull String UTIL_NAME = "util.js";
    private static final @NotNull String UTIL_SCRIPT = """
                                                       var secret = 42;
                                                       exports.twice = function (value) {
                                                           return value * 2;
                                                       };
                                                       """;
    private static final int SMALL_BUDGET = 200_000;
    /**
     * The function of DevTools that groups the indices of a long array into ranges, as DevTools sends it.
     */
    private static final @NotNull String PACK_ARRAY_RANGES = """
                                                             function packArrayRanges(fromIndex, toIndex, bucketThreshold, sparseIterationThreshold) {
                                                                 if (fromIndex === undefined || toIndex === undefined || sparseIterationThreshold === undefined ||
                                                                     bucketThreshold === undefined) {
                                                                     return;
                                                                 }
                                                                 let ownPropertyNames = null;
                                                                 const consecutiveRange = (toIndex - fromIndex >= sparseIterationThreshold) && ArrayBuffer.isView(this);
                                                                 function* arrayIndexes(object) {
                                                                     if (toIndex - fromIndex < sparseIterationThreshold) {
                                                                         for (let i = fromIndex; i <= toIndex; ++i) {
                                                                             if (i in object) {
                                                                                 yield i;
                                                                             }
                                                                         }
                                                                     } else {
                                                                         ownPropertyNames = ownPropertyNames || Object.getOwnPropertyNames(object);
                                                                         for (let i = 0; i < ownPropertyNames.length; ++i) {
                                                                             const name = ownPropertyNames[i];
                                                                             const index = Number(name) >>> 0;
                                                                             if ((String(index)) === name && fromIndex <= index && index <= toIndex) {
                                                                                 yield index;
                                                                             }
                                                                         }
                                                                     }
                                                                 }
                                                                 let count = 0;
                                                                 if (consecutiveRange) {
                                                                     count = toIndex - fromIndex + 1;
                                                                 } else {
                                                                     for (const ignored of arrayIndexes(this)) {
                                                                         ++count;
                                                                     }
                                                                 }
                                                                 let bucketSize = count;
                                                                 if (count <= bucketThreshold) {
                                                                     bucketSize = count;
                                                                 } else {
                                                                     bucketSize = Math.pow(bucketThreshold, Math.ceil(Math.log(count) / Math.log(bucketThreshold)) - 1);
                                                                 }
                                                                 const ranges = [];
                                                                 count = 0;
                                                                 let groupStart = -1;
                                                                 let groupEnd = 0;
                                                                 for (const i of arrayIndexes(this)) {
                                                                     if (groupStart === -1) {
                                                                         groupStart = i;
                                                                     }
                                                                     groupEnd = i;
                                                                     if (++count === bucketSize) {
                                                                         ranges.push([groupStart, groupEnd, count]);
                                                                         count = 0;
                                                                         groupStart = -1;
                                                                     }
                                                                 }
                                                                 if (count > 0) {
                                                                     ranges.push([groupStart, groupEnd, count]);
                                                                 }
                                                                 return {ranges};
                                                             }
                                                             """;

    private static @NotNull InspectedRuntimeScenario start() throws IOException {
        return InspectedRuntimeScenario.start(MAIN_SCRIPT, UTIL_NAME, UTIL_SCRIPT);
    }

    private static @NotNull List<String> readPropertyNames(@NotNull Map<String, Object> properties, @NotNull String key) {
        List<String> names = new ArrayList<>();

        for (Object property : DevToolsClientScenario.asList(properties.get(key))) {
            names.add((String) DevToolsClientScenario.asObject(property).get("name"));
        }

        return names;
    }

    private static @NotNull String readExceptionDescription(@NotNull Map<String, Object> result) {
        Map<String, Object> details = DevToolsClientScenario.asObject(result.get("exceptionDetails"));

        return (String) DevToolsClientScenario.asObject(details.get("exception")).get("description");
    }

    @Test
    void runtime_enable_announces_the_local_context_of_a_game_without_single_player() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            client.call("Runtime.enable", Map.of());

            Map<String, Object> context = DevToolsClientScenario.asObject(client.awaitEvent("Runtime.executionContextCreated").get("context"));

            Assertions.assertEquals("Local code", context.get("name"));
            Assertions.assertEquals(Boolean.TRUE, DevToolsClientScenario.asObject(context.get("auxData")).get("isDefault"));
            Assertions.assertFalse(ScriptInspector.isSharedGame(scenario.getGame()), "a game without triggers is no single-player game");
        }
    }

    @Test
    void evaluate_returns_primitives_by_value_and_objects_with_previews() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            Assertions.assertEquals(4L, client.evaluateValue("counter + 1").get("value"));
            Assertions.assertEquals(6L, client.evaluateValue("helper()").get("value"));
            Assertions.assertEquals(0.5, client.evaluateValue("1 / 2").get("value"));
            Assertions.assertEquals("ab", client.evaluateValue("'a' + 'b'").get("value"));
            Assertions.assertEquals("undefined", client.evaluateValue("undefined").get("type"));
            Assertions.assertEquals("null", client.evaluateValue("null").get("subtype"));
            Assertions.assertEquals("NaN", client.evaluateValue("0 / 0").get("unserializableValue"));
            Assertions.assertEquals("-0", client.evaluateValue("-0").get("unserializableValue"));
            Assertions.assertEquals("10n", client.evaluateValue("10n").get("unserializableValue"));

            Map<String, Object> state = client.evaluateValue("state");
            Map<String, Object> preview = DevToolsClientScenario.asObject(state.get("preview"));

            Assertions.assertEquals("object", state.get("type"));
            Assertions.assertEquals("Object", state.get("className"));
            Assertions.assertNotNull(state.get("objectId"));
            Assertions.assertEquals(List.of("name", "items"), readPropertyNames(preview, "properties"));
            Assertions.assertEquals("Array(3)", DevToolsClientScenario.asObject(DevToolsClientScenario.asList(preview.get("properties")).get(1))
                                                                      .get("value"));

            Map<String, Object> items = client.evaluateValue("state.items");

            Assertions.assertEquals("array", items.get("subtype"));
            Assertions.assertEquals("Array(3)", items.get("description"));
            Assertions.assertEquals("function", client.evaluateValue("helper").get("type"));

            Map<String, Object> byValue = client.call("Runtime.evaluate", Map.of("expression", "state", "returnByValue", true));

            Assertions.assertEquals(Map.of("name", "base", "items", List.of(1L, 2L, 3L)),
                                    DevToolsClientScenario.asObject(byValue.get("result")).get("value"));
        }
    }

    @Test
    void get_properties_lists_own_properties_the_prototype_and_inherited_accessors() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            String objectId = (String) client.evaluateValue("state").get("objectId");
            Map<String, Object> properties = client.call("Runtime.getProperties", Map.of("objectId", objectId, "ownProperties", true,
                                                                                         "generatePreview", true));

            Assertions.assertEquals(List.of("name", "items"), readPropertyNames(properties, "result"));
            Assertions.assertEquals(List.of("[[Prototype]]"), readPropertyNames(properties, "internalProperties"));

            Map<String, Object> items = DevToolsClientScenario.asObject(DevToolsClientScenario.asObject(
                    DevToolsClientScenario.asList(properties.get("result")).get(1)).get("value"));
            Map<String, Object> elements = client.call("Runtime.getProperties", Map.of("objectId", items.get("objectId"), "ownProperties", true));

            Assertions.assertEquals(List.of("0", "1", "2", "length"), readPropertyNames(elements, "result"));

            String withGetter = (String) client.evaluateValue("Object.create({get doubled() { return 2; }})").get("objectId");
            Map<String, Object> accessors = client.call("Runtime.getProperties", Map.of("objectId", withGetter, "accessorPropertiesOnly", true));

            Assertions.assertTrue(readPropertyNames(accessors, "result").contains("doubled"), accessors::toString);

            client.call("Runtime.releaseObjectGroup", Map.of("objectGroup", "console"));

            Map<String, Object> released = client.send("Runtime.getProperties", Map.of("objectId", objectId, "ownProperties", true));

            Assertions.assertEquals("Could not find object with given id", DevToolsClientScenario.asObject(released.get("error")).get("message"));
        }
    }

    @Test
    void call_function_on_calls_a_declaration_with_this_and_arguments() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            String objectId = (String) client.evaluateValue("state").get("objectId");
            Map<String, Object> result = client.call("Runtime.callFunctionOn", Map.of(
                    "functionDeclaration", "function (first, second) { return this.items.length + first + second.length; }",
                    "objectId", objectId,
                    "arguments", List.of(Map.of("value", 4), Map.of("value", List.of(1, 2))),
                    "returnByValue", true
            ));

            Assertions.assertEquals(9L, DevToolsClientScenario.asObject(result.get("result")).get("value"));
        }
    }

    @Test
    void completion_lists_the_names_of_the_console_the_main_script_and_the_globals() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            Map<String, Object> scope = client.call("Runtime.evaluate", Map.of("expression", "this", "objectGroup", "completion",
                                                                               "throwOnSideEffect", true));
            String scopeId = (String) DevToolsClientScenario.asObject(scope.get("result")).get("objectId");
            Map<String, Object> result = client.call("Runtime.callFunctionOn", Map.of(
                    "functionDeclaration", "function getCompletions(type) { const result = []; return result; }",
                    "objectId", scopeId,
                    "arguments", List.of(Map.of("value", "object")),
                    "returnByValue", true
            ));
            List<String> names = new ArrayList<>();

            for (Object group : DevToolsClientScenario.asList(DevToolsClientScenario.asObject(result.get("result")).get("value"))) {
                for (Object item : DevToolsClientScenario.asList(DevToolsClientScenario.asObject(group).get("items"))) {
                    names.add((String) item);
                }
            }

            Assertions.assertTrue(names.containsAll(List.of("$_", "modules", "counter", "helper", "require", "log", "game", "UnitType", "Math")),
                                  names::toString);
        }
    }

    @Test
    void global_completion_lists_the_names_of_the_console_the_main_script_and_the_globals() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            Map<String, Object> global = client.call("Runtime.evaluate", Map.of("expression", "globalThis", "objectGroup", "completion",
                                                                                "includeCommandLineAPI", true, "silent", true, "throwOnSideEffect", true,
                                                                                "timeout", 500, "replMode", true));
            String globalId = (String) DevToolsClientScenario.asObject(global.get("result")).get("objectId");

            Assertions.assertNotNull(globalId, global::toString);
            Map<String, Object> properties = client.call("Runtime.getProperties", Map.of("objectId", globalId, "ownProperties", false,
                                                                                         "accessorPropertiesOnly", false, "generatePreview", false,
                                                                                         "nonIndexedPropertiesOnly", true));

            Assertions.assertTrue(readPropertyNames(properties, "result").containsAll(List.of("$_", "modules", "counter", "helper", "require", "log",
                                                                                              "game", "UnitType", "Math")),
                                  properties::toString);
        }
    }

    @Test
    void array_ranges_of_devtools_run_with_their_const_declarations_as_let() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            String arrayId = (String) client.evaluateValue("var long = []; for (var index = 0; index < 250; index++) { long.push(index); } long")
                                            .get("objectId");
            Map<String, Object> result = client.call("Runtime.callFunctionOn", Map.of(
                    "functionDeclaration", PACK_ARRAY_RANGES,
                    "objectId", arrayId,
                    "arguments", List.of(Map.of("value", 0), Map.of("value", 249), Map.of("value", 100), Map.of("value", 250000)),
                    "returnByValue", true
            ));

            Assertions.assertEquals(Map.of("ranges", List.of(List.of(0L, 99L, 100L), List.of(100L, 199L, 100L), List.of(200L, 249L, 50L))),
                                    DevToolsClientScenario.asObject(result.get("result")).get("value"));
        }
    }

    @Test
    void exceptions_give_the_error_and_its_location() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            Map<String, Object> typeError = client.evaluate("null.x");

            Assertions.assertTrue(readExceptionDescription(typeError).startsWith("TypeError: "), typeError::toString);

            Map<String, Object> thrown = client.evaluate("throw new RangeError('boom')");
            Map<String, Object> thrownObject = DevToolsClientScenario.asObject(thrown.get("result"));

            Assertions.assertEquals("error", thrownObject.get("subtype"));
            Assertions.assertTrue(((String) thrownObject.get("description")).startsWith("RangeError: boom"), thrownObject::toString);
            Assertions.assertTrue(readExceptionDescription(client.evaluate("1 +* 2")).startsWith("SyntaxError: "));

            Map<String, Object> inScript = client.evaluate("util.twice(Symbol())");
            Map<String, Object> details = DevToolsClientScenario.asObject(inScript.get("exceptionDetails"));
            Map<String, Object> frame = DevToolsClientScenario.asObject(DevToolsClientScenario.asList(
                    DevToolsClientScenario.asObject(details.get("stackTrace")).get("callFrames")).getFirst());

            Assertions.assertEquals(UTIL_NAME, frame.get("url"));
            Assertions.assertEquals(2L, frame.get("lineNumber"));
        }
    }

    @Test
    void script_log_messages_and_errors_become_console_and_exception_events() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            client.call("Runtime.enable", Map.of());
            scenario.getRuntime().runFrame(2);
            scenario.getRuntime().runFrame(3);

            Map<String, Object> message = client.awaitEvent("Runtime.consoleAPICalled");
            Map<String, Object> argument = DevToolsClientScenario.asObject(DevToolsClientScenario.asList(message.get("args")).getFirst());

            Assertions.assertEquals("warning", message.get("type"));
            Assertions.assertEquals("main.js: frame 2", argument.get("value"));

            Map<String, Object> details = DevToolsClientScenario.asObject(client.awaitEvent("Runtime.exceptionThrown").get("exceptionDetails"));
            String description = (String) DevToolsClientScenario.asObject(details.get("exception")).get("description");

            Assertions.assertEquals(InspectedRuntimeScenario.MAIN_NAME, details.get("url"));
            Assertions.assertEquals(11L, details.get("lineNumber"));
            Assertions.assertTrue(description.startsWith("TypeError: "), description);
        }
    }

    @Test
    void the_last_result_and_the_modules_reach_the_script_state() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            client.evaluateValue("counter + 4");

            Assertions.assertEquals(70L, client.evaluateValue("$_ * 10").get("value"));
            Assertions.assertEquals(42L, client.evaluateValue("modules['util.js'].secret").get("value"));
            Assertions.assertEquals(3L, client.evaluateValue("modules['main.js'].counter").get("value"));
            Assertions.assertEquals(List.of("main.js", "util.js"), DevToolsClientScenario.asObject(client.call("Runtime.evaluate", Map.of(
                    "expression", "Object.keys(modules).sort()", "returnByValue", true)).get("result")).get("value"));
        }
    }

    @Test
    void local_code_keeps_the_script_and_the_game_unchanged() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            Assertions.assertEquals("TypeError: Local code cannot change the variable counter of the script.",
                                    readExceptionDescription(client.evaluate("counter = 5")).lines().findFirst().orElseThrow());
            Assertions.assertEquals(3L, client.evaluateValue("counter").get("value"));
            Assertions.assertTrue(readExceptionDescription(client.evaluate("random.next()")).startsWith("TypeError: "));
            Assertions.assertTrue(readExceptionDescription(client.evaluate("game.every(10, function () {})")).startsWith("TypeError: "));
            Assertions.assertTrue(readExceptionDescription(client.evaluate("require('./other')")).startsWith("TypeError: "));
            Assertions.assertEquals(8L, client.evaluateValue("var mine = 4; mine * 2").get("value"));
            Assertions.assertEquals(8L, client.evaluateValue("util.twice(mine)").get("value"));
        }
    }

    @Test
    void side_effect_free_evaluation_runs_only_property_reads() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            Map<String, Object> read = client.call("Runtime.evaluate", Map.of("expression", "state.items[1]", "throwOnSideEffect", true));
            Map<String, Object> call = client.call("Runtime.evaluate", Map.of("expression", "helper()", "throwOnSideEffect", true));

            Assertions.assertEquals(2L, DevToolsClientScenario.asObject(read.get("result")).get("value"));
            Assertions.assertEquals("EvalError: Possible side-effect in debug-evaluate", readExceptionDescription(call));
        }
    }

    @Test
    void compile_script_tells_incomplete_text_from_errors() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            Assertions.assertEquals(Map.of(), client.call("Runtime.compileScript", Map.of("expression", "1 + 1", "sourceURL", "",
                                                                                          "persistScript", false)));
            Assertions.assertEquals("SyntaxError: Unexpected end of input",
                                    readExceptionDescription(client.call("Runtime.compileScript", Map.of("expression", "function f() {",
                                                                                                         "sourceURL", "", "persistScript", false))));
            Assertions.assertTrue(readExceptionDescription(client.call("Runtime.compileScript", Map.of("expression", "1 +* 2", "sourceURL", "",
                                                                                                       "persistScript", false)))
                                          .startsWith("SyntaxError: "));
        }
    }

    @Test
    void console_code_over_the_budget_ends_without_stopping_the_script() throws Exception {
        try (InspectedRuntimeScenario scenario = InspectedRuntimeScenario.start(SMALL_BUDGET, MAIN_SCRIPT, UTIL_NAME, UTIL_SCRIPT);
             DevToolsClientScenario client = scenario.connect()) {
            Assertions.assertTrue(readExceptionDescription(client.evaluate("while (true) {}")).contains("ran more than the budget of instructions"));
            Assertions.assertFalse(scenario.getRuntime().isStopped());
            Assertions.assertEquals(3L, client.evaluateValue("counter").get("value"));
        }
    }

    @Test
    void other_domains_get_empty_results() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            Assertions.assertEquals(Map.of(), client.call("Profiler.enable", Map.of()));
            Assertions.assertEquals(Map.of(), client.call("Page.getResourceTree", Map.of()));
            Assertions.assertNotNull(client.call("Debugger.enable", Map.of()).get("debuggerId"));
            Assertions.assertEquals(Map.of("names", List.of()), client.call("Runtime.globalLexicalScopeNames", Map.of()));
        }
    }

    @Test
    void debugger_enable_lists_the_scripts_and_their_sources() throws Exception {
        try (InspectedRuntimeScenario scenario = start();
             DevToolsClientScenario client = scenario.connect()) {
            client.call("Debugger.enable", Map.of());

            Map<String, Object> main = client.awaitEvent("Debugger.scriptParsed");
            Map<String, Object> util = client.awaitEvent("Debugger.scriptParsed");

            Assertions.assertEquals(InspectedRuntimeScenario.MAIN_NAME, main.get("url"));
            Assertions.assertEquals(UTIL_NAME, util.get("url"));
            Assertions.assertEquals(4, ((Number) util.get("endLine")).intValue());
            Assertions.assertEquals(UTIL_SCRIPT, client.call("Debugger.getScriptSource", Map.of("scriptId", util.get("scriptId"))).get(
                    "scriptSource"));
            Assertions.assertNotNull(client.send("Debugger.getScriptSource", Map.of("scriptId", "missing")).get("error"));
            Assertions.assertNotNull(client.send("Debugger.setBreakpoint", Map.of("location", Map.of("scriptId", "1", "lineNumber", 1))).get(
                    "error"));
            Assertions.assertEquals(Map.of("locations", List.of()), client.call("Debugger.getPossibleBreakpoints", Map.of()));
        }
    }

    @Test
    void a_breakpoint_holds_the_frame_which_evaluates_in_its_scope_and_steps() throws Exception {
        String main = """
                      var total = 0;
                      function add(value) {
                          var doubled = value * 2;
                          total += doubled;
                          return total;
                      }
                      function onFrame(frame) {
                          add(frame);
                      }
                      """;

        try (InspectedRuntimeScenario scenario = InspectedRuntimeScenario.start(main);
             DevToolsClientScenario client = DevToolsClientScenario.connect(scenario.getPort(), () -> {
             })) {
            client.call("Debugger.enable", Map.of());

            Map<String, Object> breakpoint = client.call("Debugger.setBreakpointByUrl", Map.of("url", InspectedRuntimeScenario.MAIN_NAME,
                                                                                               "lineNumber", 3));
            Thread gameThread = Thread.ofPlatform().start(() -> scenario.getRuntime().runFrame(1));

            Map<String, Object> paused = client.awaitEvent("Debugger.paused");
            Map<String, Object> top = DevToolsClientScenario.asObject(DevToolsClientScenario.asList(paused.get("callFrames")).getFirst());
            Map<String, Object> scope = DevToolsClientScenario.asObject(DevToolsClientScenario.asList(top.get("scopeChain")).getFirst());

            Assertions.assertEquals(List.of(breakpoint.get("breakpointId")), paused.get("hitBreakpoints"));
            Assertions.assertEquals("add", top.get("functionName"));
            Assertions.assertEquals(3, ((Number) DevToolsClientScenario.asObject(top.get("location")).get("lineNumber")).intValue());
            Assertions.assertEquals("local", scope.get("type"));
            Assertions.assertEquals(2, ((Number) DevToolsClientScenario.asObject(client.call("Debugger.evaluateOnCallFrame", Map.of(
                    "callFrameId", top.get("callFrameId"), "expression", "doubled")).get("result")).get("value")).intValue());

            client.call("Debugger.stepOver", Map.of());
            client.awaitEvent("Debugger.resumed");

            Map<String, Object> stepped = client.awaitEvent("Debugger.paused");
            Map<String, Object> next = DevToolsClientScenario.asObject(DevToolsClientScenario.asList(stepped.get("callFrames")).getFirst());

            Assertions.assertEquals(4, ((Number) DevToolsClientScenario.asObject(next.get("location")).get("lineNumber")).intValue());

            client.call("Debugger.removeBreakpoint", Map.of("breakpointId", breakpoint.get("breakpointId")));
            client.call("Debugger.resume", Map.of());
            client.awaitEvent("Debugger.resumed");
            gameThread.join(10_000);

            Assertions.assertFalse(gameThread.isAlive(), "the frame ends after the resume");
        }
    }

    @Test
    void requests_without_a_game_are_answered_at_once() throws Exception {
        ScriptInspector inspector = new ScriptInspector();
        int port = inspector.start(0);

        try (DevToolsClientScenario client = DevToolsClientScenario.connect(port, () -> {
        })) {
            Assertions.assertEquals("Error: No game with a map script runs.", readExceptionDescription(client.call("Runtime.evaluate", Map.of(
                    "expression", "1"))));
        } finally {
            inspector.stop();
        }
    }
}
