package org.fogofwarlabs.sc4j.screnhanced.script;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Tests that {@link ScriptCompatibilityScan} finds the gameplay members the scripts use, keeps graphics and conveniences compatible and warns of
 * what it cannot judge, with the gameplay members of the declaration files ({@link ScriptDeclarations}).
 */
final class ScriptCompatibilityScanTest {
    private static @NotNull ScriptCompatibilityScan scan(@NotNull String source) throws IOException {
        return new ScriptCompatibilityScan(ScriptDeclarations.read(), Map.of("main.js", source.getBytes(StandardCharsets.UTF_8)));
    }

    private static @NotNull List<String> readTexts(@NotNull List<ScriptCompatibilityScan.Reason> reasons) {
        return reasons.stream().map(ScriptCompatibilityScan.Reason::toString).toList();
    }

    @Test
    void the_gameplay_members_are_the_members_of_the_declarations_that_change_the_game() throws IOException {
        Map<String, List<ScriptDeclarations.Member>> members = ScriptDeclarations.read().getGameplayMembers();

        Assertions.assertTrue(members.containsKey("setMinerals"));
        Assertions.assertTrue(members.containsKey("kill"));
        Assertions.assertTrue(members.containsKey("issueOrder"));
        Assertions.assertTrue(members.get("setHitPoints").stream().anyMatch(member -> member.getOwner().equals("UnitTypeData")));
        Assertions.assertTrue(members.get("setHitPoints").stream().anyMatch(member -> member.getOwner().equals("CoreDatUnits")));
        Assertions.assertTrue(members.get("setStim").stream().anyMatch(member -> member.getOwner().equals("UnitTimers")));
        Assertions.assertFalse(members.containsKey("print"));
        Assertions.assertFalse(members.containsKey("every"));
        Assertions.assertFalse(members.containsKey("findInRadius"));
        Assertions.assertFalse(members.containsKey("createChannel"));
        Assertions.assertFalse(members.containsKey("getHp"));
        Assertions.assertFalse(members.containsKey("drawText"));
    }

    @Test
    void a_gameplay_member_of_a_followed_value_makes_the_map_enhanced_only() throws IOException {
        ScriptCompatibilityScan scan = scan("function onStart() {\n" +
                                            "    var player = game.players.get(0);\n" +
                                            "    player.deaths.set(null, 5);\n" +
                                            "}\n");

        Assertions.assertEquals(ScriptCompatibility.ENHANCED_ONLY, scan.getCompatibility());
        Assertions.assertEquals(List.of("main.js:3: PlayerDeaths.set"), readTexts(scan.getUses()));
        Assertions.assertEquals(List.of(), scan.getWarnings());
    }

    @Test
    void graphics_and_conveniences_keep_the_map_compatible() throws IOException {
        ScriptCompatibilityScan scan = scan("var counts = new Map();\n" +
                                            "var shape = {kill: function () {}};\n" +
                                            "game.every(30, function () {\n" +
                                            "    game.print('tick');\n" +
                                            "});\n" +
                                            "function onUnitCreated(event) {\n" +
                                            "    counts.set(event.getUnit(), [1, 2].every(function (value) { return value > 0; }));\n" +
                                            "    shape.kill();\n" +
                                            "}\n" +
                                            "function onRender(screen) {\n" +
                                            "    screen.drawText('' + screen.getWidth(), 10, 10);\n" +
                                            "    screen.fillRectangle(0, 0, 4, 4);\n" +
                                            "}\n");

        Assertions.assertEquals(List.of(), scan.getUses());
        Assertions.assertEquals(List.of(), scan.getWarnings());
        Assertions.assertEquals(ScriptCompatibility.COMPATIBLE, scan.getCompatibility());
    }

    @Test
    void a_member_that_changes_graphics_only_keeps_the_map_compatible() throws IOException {
        ScriptCompatibilityScan scan = scan("var tile = game.map.tileset.getTile(12, 0);\n" +
                                            "game.map.terrain.replaceTileImage(tile, game.map.resources.getImage('tiles/wall.png'));\n" +
                                            "core.terrain.replaceTileImage(0, 0);\n");

        Assertions.assertFalse(ScriptDeclarations.read().getGameplayMembers().containsKey("replaceTileImage"));
        Assertions.assertEquals(List.of(), scan.getUses());
        Assertions.assertEquals(List.of(), scan.getWarnings());
        Assertions.assertEquals(ScriptCompatibility.COMPATIBLE, scan.getCompatibility());
    }

    @Test
    void a_gameplay_name_on_a_value_of_an_unknown_type_is_a_use() throws IOException {
        ScriptCompatibilityScan scan = scan("function hurt(unit) {\n    unit.kill();\n}\n");

        Assertions.assertEquals(ScriptCompatibility.ENHANCED_ONLY, scan.getCompatibility());
        Assertions.assertEquals(1, scan.getUses().size());
        Assertions.assertTrue(scan.getUses().getFirst().getText().contains("Unit.kill"), scan.getUses().getFirst().getText());
        Assertions.assertEquals(2, scan.getUses().getFirst().getLine());
    }

    @Test
    void a_name_of_the_scripts_own_objects_counts_only_on_a_followed_value() throws IOException {
        ScriptCompatibilityScan scan = scan("function Shape() {}\n" +
                                            "Shape.prototype.kill = function () {};\n" +
                                            "function clear(shape) {\n    shape.kill();\n}\n" +
                                            "function onUnitDied(event) {\n    event.getUnit().kill();\n}\n");

        Assertions.assertEquals(List.of("main.js:7: Unit.kill"), readTexts(scan.getUses()));
    }

    @Test
    void a_decision_hook_is_a_use() throws IOException {
        ScriptCompatibilityScan scan = scan("defineHooks({\n    onBeforeDamage: function (decision) {}\n});\n");

        Assertions.assertEquals(ScriptCompatibility.ENHANCED_ONLY, scan.getCompatibility());
        Assertions.assertEquals(List.of("main.js:2: onBeforeDamage, a decision hook"), readTexts(scan.getUses()));
    }

    @Test
    void a_shared_name_on_a_value_of_an_unknown_type_cannot_be_checked() throws IOException {
        ScriptCompatibilityScan scan = scan("function store(table) {\n    table.set('a', 1);\n}\n");

        Assertions.assertEquals(ScriptCompatibility.ENHANCED_ONLY, scan.getCompatibility());
        Assertions.assertEquals(List.of(), scan.getUses());
        Assertions.assertEquals(1, scan.getWarnings().size());
        Assertions.assertTrue(scan.getWarnings().getFirst().toString().startsWith("main.js:2: table.set cannot be checked"),
                              scan.getWarnings().getFirst().toString());
    }

    @Test
    void a_computed_member_of_an_object_that_changes_the_game_cannot_be_checked() throws IOException {
        ScriptCompatibilityScan scan = scan("var values = [1, 2];\n" +
                                            "function onUnitCreated(event) {\n" +
                                            "    var name = 'kill';\n" +
                                            "    values[event.getUnit().getId()] = 3;\n" +
                                            "    event.getUnit()[name]();\n" +
                                            "}\n");

        Assertions.assertEquals(ScriptCompatibility.ENHANCED_ONLY, scan.getCompatibility());
        Assertions.assertEquals(List.of(), scan.getUses());
        Assertions.assertEquals(1, scan.getWarnings().size());
        Assertions.assertEquals(5, scan.getWarnings().getFirst().getLine());
    }

    @Test
    void a_script_that_does_not_parse_cannot_be_checked() throws IOException {
        ScriptCompatibilityScan scan = scan("function onStart() {\n    var = 1;\n}\n");

        Assertions.assertEquals(ScriptCompatibility.ENHANCED_ONLY, scan.getCompatibility());
        Assertions.assertEquals(1, scan.getWarnings().size());
        Assertions.assertTrue(scan.getWarnings().getFirst().getText().startsWith("the script cannot be read"));
    }
}
