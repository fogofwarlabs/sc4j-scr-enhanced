package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests the context rule of {@link ScriptInspector} on games of an installed map: a single-player game has a context of shared code, which
 * changes the game and the script, and one of local code, which cannot; its replay has the local context only. Also tests the remote objects of
 * game objects, described by their {@code toString} and showing the values of their getters.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptInspectorGameTest {
    private static final @NotNull String MAIN_SCRIPT = """
                                                       var counter = 3;
                                                       """;

    private static @NotNull String readExceptionDescription(@NotNull Map<String, Object> result) {
        Map<String, Object> details = DevToolsClientScenario.asObject(result.get("exceptionDetails"));

        return (String) DevToolsClientScenario.asObject(details.get("exception")).get("description");
    }

    private static @NotNull List<Map<String, Object>> readContexts(@NotNull DevToolsClientScenario client, int count) {
        List<Map<String, Object>> contexts = new ArrayList<>();

        client.call("Runtime.enable", Map.of());

        for (int index = 0; index < count; index++) {
            contexts.add(DevToolsClientScenario.asObject(client.awaitEvent("Runtime.executionContextCreated").get("context")));
        }

        return contexts;
    }

    private static @NotNull Map<String, Object> evaluateIn(@NotNull DevToolsClientScenario client, @NotNull Map<String, Object> context,
                                                           @NotNull String expression) {
        return client.call("Runtime.evaluate", Map.of("expression", expression, "contextId", context.get("id"), "returnByValue", true));
    }

    private static @NotNull List<String> readNames(@NotNull Map<String, Object> holder, @NotNull String key) {
        List<String> names = new ArrayList<>();

        for (Object property : DevToolsClientScenario.asList(holder.get(key))) {
            names.add((String) DevToolsClientScenario.asObject(property).get("name"));
        }

        return names;
    }

    @Test
    void a_single_player_game_runs_shared_code_by_default_and_local_code_on_request(@NotNull InstallationTestResources resources) throws Exception {
        try (InspectedGameScenario scenario = InspectedGameScenario.startLive(resources.getGameData(), MAIN_SCRIPT);
             DevToolsClientScenario client = scenario.connect()) {
            Assertions.assertTrue(ScriptInspector.isSharedGame(scenario.getGame()));

            List<Map<String, Object>> contexts = readContexts(client, 2);
            Map<String, Object> shared = contexts.get(0);
            Map<String, Object> local = contexts.get(1);

            Assertions.assertEquals(List.of("Shared code", "Local code"), List.of(shared.get("name"), local.get("name")));
            Assertions.assertEquals(Boolean.TRUE, DevToolsClientScenario.asObject(shared.get("auxData")).get("isDefault"));

            long minerals = (Long) client.evaluateValue("game.players.get(0).getMinerals()").get("value");

            client.evaluateValue("game.players.get(0).addMinerals(25)");
            client.evaluateValue("counter = 5");

            Assertions.assertEquals(minerals + 25, client.evaluateValue("game.players.get(0).getMinerals()").get("value"));
            Assertions.assertEquals(5L, client.evaluateValue("modules['main.js'].counter").get("value"));
            Assertions.assertEquals("number", client.evaluateValue("random.next()").get("type"));
            Assertions.assertTrue(readExceptionDescription(client.evaluate("local.getPlayer()")).startsWith("TypeError: "));

            Assertions.assertTrue(readExceptionDescription(evaluateIn(client, local, "game.players.get(0).addMinerals(25)")).startsWith("TypeError: "));
            Assertions.assertTrue(readExceptionDescription(evaluateIn(client, local, "random.next()")).startsWith("TypeError: "));
            Assertions.assertTrue(readExceptionDescription(evaluateIn(client, local, "counter = 9")).startsWith("TypeError: "));
            Assertions.assertEquals(0L, DevToolsClientScenario.asObject(evaluateIn(client, local, "local.getPlayer().getId()").get("result"))
                                                              .get("value"));
            Assertions.assertEquals(minerals + 25, client.evaluateValue("game.players.get(0).getMinerals()").get("value"));
            Assertions.assertEquals(5L, client.evaluateValue("counter").get("value"));
        }
    }

    @Test
    void a_replay_runs_local_code_only(@NotNull InstallationTestResources resources) throws Exception {
        try (InspectedGameScenario live = InspectedGameScenario.startLive(resources.getGameData(), MAIN_SCRIPT);
             InspectedGameScenario replay = live.startReplay(resources.getGameData(), MAIN_SCRIPT);
             DevToolsClientScenario client = replay.connect()) {
            Assertions.assertFalse(ScriptInspector.isSharedGame(replay.getGame()));

            List<Map<String, Object>> contexts = readContexts(client, 1);

            Assertions.assertEquals("Local code", contexts.getFirst().get("name"));
            Assertions.assertTrue(readExceptionDescription(client.evaluate("game.players.get(0).addMinerals(25)")).startsWith("TypeError: "));
            Assertions.assertTrue(readExceptionDescription(client.evaluate("random.next()")).startsWith("TypeError: "));
            Assertions.assertEquals(3L, client.evaluateValue("counter").get("value"));
        }
    }

    @Test
    void game_objects_are_described_by_their_name_and_show_their_values(@NotNull InstallationTestResources resources) throws Exception {
        try (InspectedGameScenario scenario = InspectedGameScenario.startLive(resources.getGameData(), MAIN_SCRIPT);
             DevToolsClientScenario client = scenario.connect()) {
            Map<String, Object> player = client.evaluateValue("game.players.get(0)");
            Map<String, Object> preview = DevToolsClientScenario.asObject(player.get("preview"));

            Assertions.assertEquals("Player 0", player.get("description"));
            Assertions.assertEquals(List.of("getId()", "getName()", "getRace()", "isHuman()", "getMinerals()"), readNames(preview, "properties"));
            Assertions.assertEquals(Boolean.TRUE, preview.get("overflow"));
            Assertions.assertEquals("Terran Marine", client.evaluateValue("UnitType.TerranMarine").get("description"));

            Map<String, Object> commandCenter = client.evaluateValue("game.units.findOne({type: UnitType.TerranCommandCenter})");

            Assertions.assertTrue(((String) commandCenter.get("description")).startsWith("Unit "), commandCenter::toString);

            Map<String, Object> properties = client.call("Runtime.getProperties", Map.of("objectId", commandCenter.get("objectId"),
                                                                                         "ownProperties", true));
            Map<String, Object> type = null;

            for (Object property : DevToolsClientScenario.asList(properties.get("internalProperties"))) {
                Map<String, Object> internal = DevToolsClientScenario.asObject(property);

                if ("getType()".equals(internal.get("name"))) {
                    type = DevToolsClientScenario.asObject(internal.get("value"));
                }
            }

            Assertions.assertNotNull(type, properties::toString);
            Assertions.assertEquals("Terran Command Center", type.get("description"));
            Assertions.assertTrue(readNames(properties, "internalProperties").containsAll(List.of("getHp()", "getOwner()", "[[Prototype]]")));
        }
    }
}
