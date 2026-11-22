package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.List;

/**
 * Tests {@code core.creep}, {@code core.terrain.hasCreep} and {@code game.map.terrain.creep} in a live melee game: the creep of a Hatchery,
 * creep a script fills next to the Terran base, its removal and recession, the shared and local code checks, and the same game state from the
 * same creep changes.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptCreepCoreTest {
    /**
     * The frames the recession test runs at most until the receding tile loses its creep.
     */
    private static final int RECESSION_FRAMES = 200;
    /**
     * The frames the determinism test runs after its start.
     */
    private static final int CHANGE_FRAMES = 4;
    private static final @NotNull String CHANGES = """
                                                   function onStart() {
                                                       var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter});
                                                       var spot = Position.of(commandCenter.getX(), commandCenter.getY() + 160);
                                                       game.map.terrain.creep.fill(UnitType.ZergSpawningPool, spot);
                                                       game.map.terrain.creep.spread(UnitType.ZergHatchery, spot);
                                                   }
                                                   """;

    @Test
    void creep_is_filled_removed_and_recedes_through_the_native_routines(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var OCCUPIED = 0x8000000;
                                                                                                     var creep = game.map.terrain.creep;
                                                                                                     var recedingX = -1;
                                                                                                     var recedingY = -1;
                                                                                                     var done = false;
                                                                                                     function tileOf(value) {
                                                                                                         return Math.floor(value / 32);
                                                                                                     }
                                                                                                     function onStart() {
                                                                                                         var hatchery = game.units.findOne({type: UnitType.ZergHatchery});
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter});
                                                                                                         var spot = Position.of(commandCenter.getX(), commandCenter.getY() + 160);
                                                                                                         var tileX = tileOf(spot.getX());
                                                                                                         var tileY = tileOf(spot.getY());
                                                                                                         log.info(core.terrain.hasCreep(tileOf(hatchery.getX()), tileOf(hatchery.getY())), creep.has(tileX, tileY),
                                                                                                                  creep.recede(tileX, tileY), creep.recede(tileOf(hatchery.getX()), tileOf(hatchery.getY())));
                                                                                                         creep.fill(UnitType.ZergSpawningPool, spot);
                                                                                                         var spread = core.creep.spread(UnitType.ZergHatchery.getId(), spot.getX(), spot.getY());
                                                                                                         log.info(creep.has(tileX, tileY), (core.terrain.getTileFlags(tileX, tileY) & 0x400000) !== 0,
                                                                                                                  typeof creep.spread(UnitType.ZergHatchery, spot), [0, 1, 2].indexOf(spread) >= 0);
                                                                                                         creep.remove(tileX, tileY);
                                                                                                         log.info(creep.has(tileX, tileY));
                                                                                                         for (var y = tileY - 3; y <= tileY + 3 && recedingX < 0; y++) {
                                                                                                             for (var x = tileX - 3; x <= tileX + 3 && recedingX < 0; x++) {
                                                                                                                 if ((x !== tileX || y !== tileY) && creep.has(x, y) && (core.terrain.getTileFlags(x, y) & OCCUPIED) === 0) {
                                                                                                                     recedingX = x;
                                                                                                                     recedingY = y;
                                                                                                                 }
                                                                                                             }
                                                                                                         }
                                                                                                         log.info(creep.has(recedingX, recedingY), creep.recede(recedingX, recedingY), creep.recede(recedingX, recedingY));
                                                                                                         [function () { core.creep.fill(228, spot.getX(), spot.getY()); }, function () { core.creep.removeTile(-1, 0); },
                                                                                                          function () { core.creep.fill(UnitType.ZergHatchery.getId(), 0, 0); }].forEach(function (call) {
                                                                                                             try {
                                                                                                                 call();
                                                                                                             } catch (error) {
                                                                                                                 log.info(error.name);
                                                                                                             }
                                                                                                         });
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         if (!done && !creep.has(recedingX, recedingY)) {
                                                                                                             done = true;
                                                                                                             log.info('receded');
                                                                                                         }
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         try {
                                                                                                             creep.remove(recedingX, recedingY);
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name + ' ' + creep.has(recedingX, recedingY));
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        for (int frame = 0; frame < RECESSION_FRAMES && !scenario.getInfoMessages().contains("receded"); frame++) {
            scenario.stepFrame();
        }

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("true false false false",
                                        "true true boolean true",
                                        "false",
                                        "true true false",
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "receded",
                                        "local TypeError false"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void the_same_creep_changes_give_the_same_game_state(@NotNull InstallationTestResources resources) throws IOException {
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
