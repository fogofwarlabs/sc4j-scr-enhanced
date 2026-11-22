package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerActionIds;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests {@code core.leaderboard} and the leaderboard functions of {@code game} in a live melee game: each leaderboard form stores the action
 * type of its leaderboard action, a label registers once as a map string, and the inclusion of computer players changes.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptLeaderboardCoreTest {
    /**
     * The leaderboard forms the form test shows, one per frame.
     */
    private static final int LEADERBOARD_FORMS = 11;

    @Test
    void leaderboards_register_each_label_once(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var anywhere = game.map.locations.get(0);
                                                                                                         game.leaderboard.showControl('Marines', UnitType.TerranMarine);
                                                                                                         game.leaderboard.showControl('Marines', UnitType.TerranMarine, {goal: 10});
                                                                                                         game.leaderboard.showControl('Here', UnitGroup.Men, {location: anywhere});
                                                                                                         game.leaderboard.showControl('Here', UnitGroup.Men, {location: anywhere, goal: 5});
                                                                                                         game.leaderboard.showResources('Ore', ResourceType.MineralsAndGas);
                                                                                                         game.leaderboard.showPoints('Points', ScoreType.KillsAndRazings, {goal: 1000});
                                                                                                         game.leaderboard.setComputersIncluded(false);
                                                                                                         log.info(game.leaderboard.areComputersIncluded());
                                                                                                         game.leaderboard.setComputersIncluded(true);
                                                                                                         log.info(game.leaderboard.areComputersIncluded());
                                                                                                         game.leaderboard.showKills('Kills', UnitGroup.AnyUnit, {goal: 20});
                                                                                                         try {
                                                                                                             game.leaderboard.showKills('Kills', ScoreType.Total);
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                         try {
                                                                                                             core.leaderboard.show(18, 0, 0, 'Wrong');
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """);
        TriggerContext triggers = scenario.getGame().getTriggerContext();
        MapStringSection strings = scenario.getGame().getInterfaceLink().getMessageLines().getMapStringSection();

        Assertions.assertNotNull(triggers);
        Assertions.assertNotNull(strings);
        Assertions.assertEquals(List.of("false", "true", "TypeError", "RangeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertEquals(TriggerActionIds.LEADERBOARD_GOAL_KILLS, triggers.getLeaderboardType());
        Assertions.assertEquals("Kills", strings.getString(triggers.getLeaderboardString()));
        Assertions.assertEquals(5, strings.getRegisteredStringCount(), "Marines, Here, Ore, Points and Kills register once each");
        Assertions.assertTrue(triggers.areLeaderboardComputersIncluded());
    }

    @Test
    void each_leaderboard_form_takes_its_action_type(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var anywhere = game.map.locations.get(0);
                                                                                                         var forms = [
                                                                                                             function () { game.leaderboard.showControl('A', UnitType.TerranMarine); },
                                                                                                             function () { game.leaderboard.showControl('A', UnitType.TerranMarine, {location: anywhere}); },
                                                                                                             function () { game.leaderboard.showResources('A', ResourceType.Gas); },
                                                                                                             function () { game.leaderboard.showKills('A', UnitGroup.Factories); },
                                                                                                             function () { game.leaderboard.showPoints('A', ScoreType.Custom); },
                                                                                                             function () { game.leaderboard.showControl('A', UnitType.TerranMarine, {goal: 1}); },
                                                                                                             function () { game.leaderboard.showControl('A', UnitType.TerranMarine, {location: anywhere, goal: 1}); },
                                                                                                             function () { game.leaderboard.showResources('A', ResourceType.Gas, {goal: 1}); },
                                                                                                             function () { game.leaderboard.showKills('A', UnitGroup.Factories, {goal: 1}); },
                                                                                                             function () { game.leaderboard.showPoints('A', ScoreType.Custom, {goal: 1}); },
                                                                                                             function () { game.leaderboard.showGreed(500); }
                                                                                                         ];
                                                                                                         game.every(1, function () {
                                                                                                             var form = forms.shift();
                                                                                                             if (form !== undefined) {
                                                                                                                 form();
                                                                                                             }
                                                                                                         });
                                                                                                     }
                                                                                                     """);
        TriggerContext triggers = scenario.getGame().getTriggerContext();

        Assertions.assertNotNull(triggers);

        List<Integer> types = new ArrayList<>();

        for (int form = 0; form < LEADERBOARD_FORMS; form++) {
            scenario.stepFrame();
            types.add(triggers.getLeaderboardType());
        }

        Assertions.assertEquals(List.of(TriggerActionIds.LEADERBOARD_CONTROL, TriggerActionIds.LEADERBOARD_CONTROL_AT_LOCATION,
                                        TriggerActionIds.LEADERBOARD_RESOURCES, TriggerActionIds.LEADERBOARD_KILLS,
                                        TriggerActionIds.LEADERBOARD_POINTS, TriggerActionIds.LEADERBOARD_GOAL_CONTROL,
                                        TriggerActionIds.LEADERBOARD_GOAL_CONTROL_AT_LOCATION, TriggerActionIds.LEADERBOARD_GOAL_RESOURCES,
                                        TriggerActionIds.LEADERBOARD_GOAL_KILLS, TriggerActionIds.LEADERBOARD_GOAL_POINTS,
                                        TriggerActionIds.LEADERBOARD_GREED), types, scenario.getLines()::toString);
    }
}
