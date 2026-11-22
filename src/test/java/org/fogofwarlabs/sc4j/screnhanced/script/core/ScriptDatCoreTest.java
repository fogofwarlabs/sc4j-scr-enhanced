package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests {@code core.dat} and the DAT methods of the type objects on the installed DAT tables: every column reads, writes and round-trips
 * through both layers, the writes refuse values the column does not hold, and a change takes effect in a live game where the game reads the
 * table (a unit's cost, a weapon's cooldown, a flingy's top speed).
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptDatCoreTest {
    private static final int MAP_TILE_SIZE = 4;
    private static final int MAP_TILESET = 0;
    /**
     * The frames the cooldown test runs, long enough for the first shot of a Marine next to its target.
     */
    private static final int ATTACK_FRAMES = 24;
    /**
     * The frames the determinism test runs after its start.
     */
    private static final int CHANGE_FRAMES = 32;
    /**
     * Changes of the DAT tables, the units and the players that the game state shows.
     */
    private static final @NotNull String CHANGES = """
                                                   function onStart() {
                                                       var human = game.players.getHumans()[0];
                                                       var position = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human}).getPosition();
                                                       UnitType.TerranVulture.getFlingyType().setTopSpeed(2000);
                                                       UnitType.TerranMarine.setHitPoints(80 * 256);
                                                       var vulture = game.units.create(UnitType.TerranVulture, human, position);
                                                       vulture.timers.setEnsnare(3);
                                                       vulture.issueOrder(Order.Move, Position.of(position.getX() + 160, position.getY()));
                                                       var marine = game.units.create(UnitType.TerranMarine, human, position);
                                                       marine.timers.setLockdown(2);
                                                       marine.setKillCount(4);
                                                       game.units.findOne({type: UnitType.TerranSCV, owner: human}).setType(UnitType.TerranFirebat);
                                                       human.upgrades.setLevel(UpgradeType.IonThrusters, 1);
                                                       human.techs.setResearched(TechType.StimPacks, true);
                                                   }
                                                   """;
    /**
     * The checks of every column, which the round trip script calls.
     */
    private static final @NotNull String ROUND_TRIP_CHECK = """
                                                            var types = {UnitType: UnitType, WeaponType: WeaponType, FlingyType: FlingyType, SpriteType: SpriteType, ImageType: ImageType,
                                                                         UpgradeType: UpgradeType, TechType: TechType, Order: Order};
                                                            var failures = [];
                                                            var checked = 0;
                                                            function fail(table, column, what) {
                                                                failures.push(table + '.' + column + ' ' + what);
                                                            }
                                                            function check(table, type, column, id, writable, reference, flags) {
                                                                var namespace = core.dat[table];
                                                                var entry = types[type].get(id);
                                                                var before = namespace['get' + column](id);
                                                                var expected = reference === null ? before : types[reference].get(before);
                                                                if (flags === null && entry['get' + column]() !== expected) {
                                                                    fail(table, column, 'upper read');
                                                                }
                                                                flags !== null && flags.forEach(function (flag) {
                                                                    if (entry['is' + flag[0]]() !== ((before & flag[1]) !== 0)) {
                                                                        fail(table, column, 'flag read ' + flag[0]);
                                                                    }
                                                                });
                                                                checked++;
                                                                if (!writable) {
                                                                    if (namespace['set' + column] !== undefined || entry['set' + column] !== undefined) {
                                                                        fail(table, column, 'writable');
                                                                    }
                                                                    return;
                                                                }
                                                                var value = before === 1 ? 0 : 1;
                                                                namespace['set' + column](id, value);
                                                                if (namespace['get' + column](id) !== value) {
                                                                    fail(table, column, 'lower write');
                                                                }
                                                                if (flags === null) {
                                                                    entry['set' + column](expected);
                                                                } else {
                                                                    namespace['set' + column](id, before);
                                                                    flags.forEach(function (flag) {
                                                                        var set = (before & flag[1]) !== 0;
                                                                        entry['set' + flag[0]](!set);
                                                                        if (((namespace['get' + column](id) & flag[1]) !== 0) === set || entry['is' + flag[0]]() === set) {
                                                                            fail(table, column, 'flag write ' + flag[0]);
                                                                        }
                                                                        entry['set' + flag[0]](set);
                                                                    });
                                                                }
                                                                if (namespace['get' + column](id) !== before) {
                                                                    fail(table, column, 'upper write');
                                                                }
                                                            }
                                                            """;

    /**
     * Writes the round trip script: one check per column of every table, at the first entry the column holds.
     *
     * @return the script and the number of columns
     */
    private static @NotNull String writeRoundTripScript() {
        StringBuilder script = new StringBuilder(ROUND_TRIP_CHECK);

        for (ScriptDatTables.Table table : ScriptDatTables.getTables()) {
            for (ScriptDatTables.Column column : table.getColumns()) {
                ScriptDatTables.Reference reference = column.getReference();
                List<String> flags = new ArrayList<>();

                for (ScriptDatTables.Flag flag : column.getFlags()) {
                    flags.add("['" + flag.getName() + "', " + Integer.toUnsignedLong(flag.getMask()) + "]");
                }

                script.append("check('").append(table.getName()).append("', '").append(table.getType().getTypeName()).append("', '")
                      .append(column.getName()).append("', ").append(column.getFirstId()).append(", ").append(column.isWritable()).append(", ")
                      .append(reference == null ? "null" : "'" + reference.getTypeName() + "'").append(", ")
                      .append(flags.isEmpty() ? "null" : "[" + String.join(", ", flags) + "]").append(");\n");
            }
        }

        return script.append("log.info(checked, failures.length === 0 ? 'ok' : failures.join(', '));\n").toString();
    }

    private static int countColumns() {
        int count = 0;

        for (ScriptDatTables.Table table : ScriptDatTables.getTables()) {
            count += table.getColumns().size();
        }

        return count;
    }

    private static @NotNull Game createGame(@NotNull InstallationTestResources resources) throws IOException {
        GameMap map = new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, MAP_TILESET, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]);

        return Game.create(resources.getGameData(), map, new LcgRandom(0));
    }

    @Test
    void every_dat_column_round_trips_through_both_layers(@NotNull InstallationTestResources resources) throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(resources), writeRoundTripScript());

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of(countColumns() + " ok"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void unit_types_give_the_shown_maximum_hit_points_and_shields(@NotNull InstallationTestResources resources) throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(resources), """
                                                                                    var marine = UnitType.TerranMarine;
                                                                                    var zealot = UnitType.ProtossZealot;
                                                                                    var before = [marine.getMaxHp(), marine.getHitPoints(), zealot.getMaxShields(), zealot.getShieldAmount()];
                                                                                    marine.setMaxHp(55.5);
                                                                                    zealot.setMaxShields(70);
                                                                                    log.info(before.join(' '), marine.getMaxHp(), marine.getHitPoints(), zealot.getMaxShields(), zealot.getShieldAmount());
                                                                                    """);

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("40 10240 60 60 55.5 14208 70 70"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void dat_writes_refuse_values_that_the_column_does_not_hold(@NotNull InstallationTestResources resources) throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(resources), """
                                                                                    function attempt(write) {
                                                                                        try {
                                                                                            write();
                                                                                            return 'written';
                                                                                        } catch (error) {
                                                                                            return error.name;
                                                                                        }
                                                                                    }
                                                                                    var units = core.dat.units;
                                                                                    log.info(attempt(function () { units.setFlingyType(0, 209); }), attempt(function () { units.setMineralCost(0, 65536); }),
                                                                                             attempt(function () { units.setMineralCost(0, -1); }), attempt(function () { units.setMineralCost(0, 1.5); }),
                                                                                             attempt(function () { units.setInfestedType(0, 1); }), attempt(function () { units.getMineralCost(228); }),
                                                                                             attempt(function () { core.dat.images.setIscript(0, 411); }), attempt(function () { units.setHitPoints(0, 4294967295); }));
                                                                                    log.info(typeof units.getInfestedType(0), units.getHitPoints(0) === 4294967295, attempt(function () { core.dat.sprites.setSelectionCircle(130, 10); }),
                                                                                             typeof core.dat.sprites.getHealthBarWidth(0),
                                                                                             attempt(function () { core.dat.sprites.setHealthBarWidth(129, 1); }), attempt(function () { SpriteType.get(130).setSelectionCircleOffset(-8); }),
                                                                                             core.dat.sprites.getSelectionCircleOffset(130));
                                                                                    var marine = UnitType.TerranMarine;
                                                                                    marine.setGroundWeapon(null);
                                                                                    log.info(units.getGroundWeapon(marine.getId()), marine.getGroundWeapon(), attempt(function () { marine.setFlingyType(null); }));
                                                                                    marine.setGroundWeapon(WeaponType.GaussRifle);
                                                                                    marine.setOrganic(false);
                                                                                    log.info(marine.getGroundWeapon() === WeaponType.GaussRifle, marine.isOrganic(), (units.getFlags(marine.getId()) & 0x10000) === 0,
                                                                                             marine.getFlingyType().getSprite().getImage() === ImageType.UnitTerranMarine, FlingyType.UnitTerranMarine === marine.getFlingyType());
                                                                                    """);

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("RangeError RangeError RangeError TypeError RangeError RangeError RangeError written",
                                        "number true RangeError number RangeError written -8",
                                        "130 null TypeError",
                                        "true false true true true"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void a_dat_change_takes_effect_where_the_game_reads_the_table(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var human;
                                                                                                     var commandCenter;
                                                                                                     var marine;
                                                                                                     var maximumCooldown = 0;
                                                                                                     var frames = 0;
                                                                                                     function onStart() {
                                                                                                         human = game.players.getHumans()[0];
                                                                                                         commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         UnitType.TerranSCV.setMineralCost(7);
                                                                                                         human.setMinerals(100);
                                                                                                         UnitType.TerranMarine.getGroundWeapon().setCooldown(100);
                                                                                                         var flingy = UnitType.TerranVulture.getFlingyType();
                                                                                                         var topSpeed = flingy.getTopSpeed();
                                                                                                         var vulture = game.units.create(UnitType.TerranVulture, human, commandCenter.getPosition());
                                                                                                         flingy.setTopSpeed(topSpeed * 2);
                                                                                                         var fastVulture = game.units.create(UnitType.TerranVulture, human, commandCenter.getPosition());
                                                                                                         log.info(commandCenter.getId(), vulture.getTopSpeed() === topSpeed, fastVulture.getTopSpeed() === topSpeed * 2);
                                                                                                         marine = game.units.create(UnitType.TerranMarine, human, commandCenter.getPosition());
                                                                                                         marine.issueOrder(Order.AttackUnit, game.units.create(UnitType.ZergZergling, game.players.get(1), marine.getPosition()));
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         frames++;
                                                                                                         if (marine.exists()) {
                                                                                                             maximumCooldown = Math.max(maximumCooldown, marine.getGroundCooldown());
                                                                                                         }
                                                                                                         if (frames === 2) {
                                                                                                             log.info(human.getMinerals(), commandCenter.getBuildQueue().join(' '));
                                                                                                         }
                                                                                                         if (frames === 25) {
                                                                                                             log.info(maximumCooldown >= 95);
                                                                                                         }
                                                                                                     }
                                                                                                     """);
        String[] start = scenario.getInfoMessages().getFirst().split(" ");
        Game game = scenario.getGame();
        Unit commandCenter = game.getUnitSystem().requireUnitPool().getUnit(Integer.parseInt(start[0]));

        Assertions.assertTrue(game.getUnitProduction().pushBuildQueue(commandCenter, ScrUnitIds.TERRAN_SCV) >= 0);

        for (int frame = 0; frame < ATTACK_FRAMES; frame++) {
            scenario.stepFrame();
        }

        Assertions.assertEquals(List.of(start[0] + " true true", "93 Terran SCV", "true"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void the_same_data_unit_and_player_changes_give_the_same_game_state(@NotNull InstallationTestResources resources) throws IOException {
        long first = runChanges(resources, CHANGES);
        long second = runChanges(resources, CHANGES);
        long unchanged = runChanges(resources, "");

        Assertions.assertEquals(first, second);
        Assertions.assertNotEquals(first, unchanged);
    }

    /**
     * Runs a melee game with a script for {@link #CHANGE_FRAMES} frames.
     *
     * @return the state hash of the game at the end
     */
    private static long runChanges(@NotNull InstallationTestResources resources, @NotNull String source) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), source);

        for (int frame = 0; frame < CHANGE_FRAMES; frame++) {
            scenario.stepFrame();
        }

        return scenario.getGame().computeStateHash();
    }
}
