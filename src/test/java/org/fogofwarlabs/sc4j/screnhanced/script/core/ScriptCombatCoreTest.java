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
 * Tests {@code core.combat}, {@code core.ai} and the damage overlay counts of {@code core.imageType} with the {@code DamageType},
 * {@code UnitSize} and {@code game.combat} names of the prelude in a live melee game: the native damage multipliers and flee distances, the
 * computer players' unit strengths, the shared and local code checks, and the same game state from the same writes.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptCombatCoreTest {
    /**
     * The frames the determinism test runs after its start.
     */
    private static final int CHANGE_FRAMES = 4;
    private static final @NotNull String CHANGES = """
                                                   function onStart() {
                                                       DamageType.Concussive.setMultiplier(UnitSize.Large, 1);
                                                       game.combat.setFleeDistances([96, 64, 48, 32, 16]);
                                                       UnitType.TerranMarine.setAiStrength(false, 1000);
                                                       ImageType.get(core.unit.getImageType(game.units.findOne({type: UnitType.TerranCommandCenter}).getId())).setDamageOverlayCount(0);
                                                   }
                                                   """;

    @Test
    void the_combat_tables_hold_the_native_values_and_take_writes(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var commandCenter;
                                                                                                     function onStart() {
                                                                                                         commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter});
                                                                                                         log.info(DamageType.Concussive.getMultiplier(UnitSize.Small), DamageType.Concussive.getMultiplier(UnitSize.Large),
                                                                                                                  DamageType.Explosive.getMultiplier(UnitSize.Medium), DamageType.Independent.getMultiplier(UnitSize.Large),
                                                                                                                  core.combat.getDamageMultiplier(3, 3), JSON.stringify(game.combat.getFleeDistances()));
                                                                                                         DamageType.Concussive.setMultiplier(UnitSize.Large, 1);
                                                                                                         game.combat.setFleeDistances([96, 64, 48, 32, 16]);
                                                                                                         log.info(core.combat.getDamageMultiplier(2, 3), core.combat.getFleeDistance(0), core.combat.getFleeDistanceCount());
                                                                                                         var strength = UnitType.TerranMarine.getAiStrength(false);
                                                                                                         UnitType.TerranMarine.setAiStrength(false, strength + 1);
                                                                                                         log.info(core.ai.getUnitStrength(UnitType.TerranMarine.getId(), false) - strength, UnitType.TerranMarine.getAiStrength(true) > 0);
                                                                                                         var image = commandCenter.getImageType();
                                                                                                         var count = image.getDamageOverlayCount();
                                                                                                         image.setDamageOverlayCount(2);
                                                                                                         log.info(count > 0, count % 2, core.imageType.getDamageOverlayCount(image.getId()));
                                                                                                         [function () { core.combat.getDamageMultiplier(5, 0); }, function () { core.combat.getDamageMultiplier(0, 4); },
                                                                                                          function () { core.combat.getFleeDistance(5); }, function () { core.ai.getUnitStrength(228, true); },
                                                                                                          function () { core.imageType.setDamageOverlayCount(0, 256); }].forEach(function (action) {
                                                                                                             try {
                                                                                                                 action();
                                                                                                             } catch (error) {
                                                                                                                 log.info(error.name);
                                                                                                             }
                                                                                                         });
                                                                                                         try {
                                                                                                             game.combat.setFleeDistances([1]);
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         [function () { DamageType.Normal.setMultiplier(UnitSize.Small, 0); }, function () { core.combat.setFleeDistance(0, 1); },
                                                                                                          function () { UnitType.TerranMarine.setAiStrength(true, 0); }, function () { commandCenter.getImageType().setDamageOverlayCount(0); }]
                                                                                                                 .forEach(function (action) {
                                                                                                             try {
                                                                                                                 action();
                                                                                                             } catch (error) {
                                                                                                                 log.info('local ' + error.name);
                                                                                                             }
                                                                                                         });
                                                                                                         log.info('local ' + DamageType.Normal.getMultiplier(UnitSize.Small) + ' ' + game.combat.getFleeDistances()[0]);
                                                                                                     }
                                                                                                     """);

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("1 0.25 0.75 0 256 [192,128,64,32,16]",
                                        "256 96 5",
                                        "1 true",
                                        "true 0 2",
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "TypeError",
                                        "local TypeError",
                                        "local TypeError",
                                        "local TypeError",
                                        "local TypeError",
                                        "local 1 96"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void the_same_combat_table_writes_give_the_same_game_state(@NotNull InstallationTestResources resources) throws IOException {
        Assertions.assertEquals(runChanges(resources, CHANGES), runChanges(resources, CHANGES));
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
