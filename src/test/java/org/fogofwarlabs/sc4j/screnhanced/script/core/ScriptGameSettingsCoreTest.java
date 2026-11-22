package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.state.GameCheats;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.List;

/**
 * Tests the speed, settings and cheat functions of {@code core.game}, the {@code GameSpeed}, {@code GameMode} and {@code Cheat} constants,
 * {@code game.settings} and the one-shot timers of {@code game.after} in live games: the frame duration table, the settings of a melee and a
 * Use Map Settings game, the stored cheat flags and the cheat routine, the pause of Pause Game and of the game menu, the shared and local code
 * checks, and the same game state from the same writes.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptGameSettingsCoreTest {
    /**
     * The frames the timer test runs after its start.
     */
    private static final int TIMER_FRAMES = 4;
    /**
     * The frames the kept cheat test runs after its start.
     */
    private static final int KEPT_CHEAT_FRAMES = 3;
    /**
     * The steps the pause test runs after its start, of which the pause holds the game for the given number.
     */
    private static final int PAUSE_STEPS = 6;
    private static final int PAUSED_STEPS = 3;
    /**
     * The frames the determinism test runs after its start.
     */
    private static final int CHANGE_FRAMES = 4;
    private static final @NotNull String CHANGES = """
                                                   function onStart() {
                                                       game.setCheatEnabled(Cheat.OperationCwal, true);
                                                       game.setCheatEnabled(Cheat.BlackSheepWall, true);
                                                       core.game.applyCheats(core.game.getCheatFlags() | Cheat.ShowMeTheMoney.getId());
                                                       GameSpeed.Fastest.setFrameDuration(30);
                                                   }
                                                   """;

    @Test
    void the_speed_table_and_the_settings_read_the_game(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario melee = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                  function onStart() {
                                                                                                      var settings = game.settings;
                                                                                                      log.info(game.getSpeed() === GameSpeed.Fastest, core.game.getSpeed(), GameSpeed.Fastest.getFrameDuration(),
                                                                                                               GameSpeed.Normal.getFrameDuration(), core.game.getSpeedDuration(0), GameSpeed.getCount());
                                                                                                      GameSpeed.Fastest.setFrameDuration(30);
                                                                                                      log.info(core.game.getSpeedDuration(6), GameSpeed.Fastest.getFrameDuration());
                                                                                                      log.info(settings.getMode() === GameMode.Melee, core.game.getGameType(), settings.isMultiplayer(), settings.getVictoryCondition(),
                                                                                                               settings.getResourceType(), settings.getStartingUnits(), settings.getTournamentMode(), settings.areCheatsAllowed(),
                                                                                                               settings.isExpansion(), core.game.getExpansion());
                                                                                                      settings.setExpansion(false);
                                                                                                      log.info(settings.isExpansion(), core.game.getExpansion());
                                                                                                      core.game.setExpansion(1);
                                                                                                      try {
                                                                                                          core.game.getSpeedDuration(7);
                                                                                                      } catch (error) {
                                                                                                          log.info(error.name);
                                                                                                      }
                                                                                                      try {
                                                                                                          core.game.setExpansion(256);
                                                                                                      } catch (error) {
                                                                                                          log.info(error.name);
                                                                                                      }
                                                                                                  }
                                                                                                  function onLocalFrame() {
                                                                                                      log.info('local ' + game.getSpeed().getName() + ' ' + game.settings.getMode().getName());
                                                                                                      try {
                                                                                                          GameSpeed.Fastest.setFrameDuration(42);
                                                                                                      } catch (error) {
                                                                                                          log.info('local ' + error.name + ' ' + core.game.getSpeedDuration(6));
                                                                                                      }
                                                                                                  }
                                                                                                  """);

        melee.runLocalFrame();

        Assertions.assertEquals(List.of("true 6 42 67 167 7",
                                        "30 30",
                                        "true 2 false 1 1 2 0 true true 1",
                                        "false 0",
                                        "RangeError",
                                        "RangeError",
                                        "local Fastest Melee",
                                        "local TypeError 30"), melee.getInfoMessages(), melee.getLines()::toString);

        LiveScriptGameScenario useMapSettings = LiveScriptGameScenario.startUseMapSettings(resources.getGameData(), """
                                                                                                                    function onStart() {
                                                                                                                        log.info(game.settings.getMode() === GameMode.UseMapSettings, game.settings.getMode().getId(), GameMode.get(1).getName());
                                                                                                                    }
                                                                                                                    """);

        Assertions.assertEquals(List.of("true 10 Game mode 1"), useMapSettings.getInfoMessages(), useMapSettings.getLines()::toString);
    }

    /**
     * The upper setter writes the cheat flags as an EUD write does, so Black Sheep Wall reveals nothing at once; the lower applyCheats runs the
     * native cheat routine.
     */
    @Test
    void cheats_are_stored_as_flags_or_run_through_the_cheat_routine(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var hatchery = game.units.findOne({type: UnitType.ZergHatchery});
                                                                                                         log.info(core.game.getCheatFlags(), game.isCheatEnabled(Cheat.OperationCwal), Cheat.BlackSheepWall.getId(),
                                                                                                                  Cheat.Noglues.getId(), human.vision.canSee(hatchery.getPosition()));
                                                                                                         game.setCheatEnabled(Cheat.OperationCwal, true);
                                                                                                         game.setCheatEnabled(Cheat.ShowMeTheMoney, true);
                                                                                                         log.info(game.isCheatEnabled(Cheat.OperationCwal), game.isCheatEnabled(Cheat.ShowMeTheMoney), core.game.getCheatFlags(),
                                                                                                                  human.getMinerals());
                                                                                                         game.setCheatEnabled(Cheat.ShowMeTheMoney, false);
                                                                                                         game.setCheatEnabled(Cheat.BlackSheepWall, true);
                                                                                                         log.info(game.isCheatEnabled(Cheat.BlackSheepWall), human.vision.canSee(hatchery.getPosition()));
                                                                                                         core.game.applyCheats(core.game.getCheatFlags() | Cheat.ShowMeTheMoney.getId());
                                                                                                         log.info(game.isCheatEnabled(Cheat.ShowMeTheMoney), human.getMinerals(), core.game.getCheatFlags());
                                                                                                         core.game.setCheatFlags(0);
                                                                                                         log.info(core.game.getCheatFlags(), game.isCheatEnabled(Cheat.BlackSheepWall));
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         try {
                                                                                                             game.setCheatEnabled(Cheat.OperationCwal, true);
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name + ' ' + game.isCheatEnabled(Cheat.OperationCwal));
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("0 false 1 536870912 false",
                                        "true true 18 50",
                                        "true false",
                                        "false 10050 3",
                                        "0 false",
                                        "local TypeError false"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    /**
     * The upper setter keeps the bits it set and cleared: the frame entry point writes them again at the start of every frame, as the
     * always-running trigger of an EUD map does, while a lower write of the flags is a one-time write.
     */
    @Test
    void set_cheat_enabled_writes_its_bits_again_every_frame(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var step = 0;
                                                                                                     function onStart() {
                                                                                                         game.setCheatEnabled(Cheat.WarAintWhatItUsedToBe, true);
                                                                                                         game.setCheatEnabled(Cheat.OperationCwal, false);
                                                                                                         core.game.setCheatFlags(Cheat.OperationCwal.getId());
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         log.info(step, game.isCheatEnabled(Cheat.WarAintWhatItUsedToBe), game.isCheatEnabled(Cheat.OperationCwal));
                                                                                                         if (step === 1) {
                                                                                                             core.game.setCheatFlags(0);
                                                                                                         } else if (step === 2) {
                                                                                                             game.setCheatEnabled(Cheat.WarAintWhatItUsedToBe, false);
                                                                                                             core.game.setCheatFlags(Cheat.WarAintWhatItUsedToBe.getId() | Cheat.PowerOverwhelming.getId());
                                                                                                         }
                                                                                                         step++;
                                                                                                     }
                                                                                                     """);

        for (int frame = 0; frame < KEPT_CHEAT_FRAMES; frame++) {
            scenario.stepFrame();
        }

        Assertions.assertEquals(List.of("0 false true", "1 true false", "2 true false", "3 false false"), scenario.getInfoMessages(),
                                scenario.getLines()::toString);
        Assertions.assertEquals(GameCheats.POWER_OVERWHELMING, scenario.getGame().getCheatFlags(), "a bit the setter never touched stays");
    }

    @Test
    void a_one_shot_timer_runs_once(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var start;
                                                                                                     var once;
                                                                                                     var cancelled;
                                                                                                     function onStart() {
                                                                                                         start = game.getFrame();
                                                                                                         once = game.after(2, function () { log.info('once ' + (game.getFrame() - start)); });
                                                                                                         cancelled = game.after(1, function () { log.info('cancelled'); });
                                                                                                         log.info(cancelled.cancel(), once.getId() !== cancelled.getId());
                                                                                                     }
                                                                                                     function onFrame(frame) {
                                                                                                         if (frame - start === 3) {
                                                                                                             log.info('cancel ' + once.cancel());
                                                                                                         }
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         try {
                                                                                                             game.after(1, function () {});
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        for (int frame = 0; frame < TIMER_FRAMES; frame++) {
            scenario.stepFrame();
        }

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("true true", "once 2", "cancel false", "local TypeError"), scenario.getInfoMessages(),
                                scenario.getLines()::toString);
    }

    @Test
    void pause_and_resume_run_the_pause_game_rule_at_the_next_trigger_step(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var paused = 0;
                                                                                                     var pausedFrame = -1;
                                                                                                     function onStart() {
                                                                                                         log.info(game.isPaused());
                                                                                                         game.pause();
                                                                                                         log.info(game.isPaused());
                                                                                                     }
                                                                                                     function onFrame(frame) {
                                                                                                         if (game.isPaused()) {
                                                                                                             paused++;
                                                                                                             if (pausedFrame < 0) {
                                                                                                                 pausedFrame = frame;
                                                                                                             }
                                                                                                             if (paused === 3) {
                                                                                                                 game.pause();
                                                                                                                 game.resume();
                                                                                                                 log.info('resume ' + game.isPaused() + ' ' + (frame === pausedFrame));
                                                                                                             }
                                                                                                         } else if (paused > 0) {
                                                                                                             log.info('running ' + paused + ' ' + (frame === pausedFrame));
                                                                                                             paused = -1;
                                                                                                         }
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         try {
                                                                                                             game.pause();
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """);
        int start = scenario.getGame().getCurrentFrame();

        for (int frame = 0; frame < PAUSE_STEPS; frame++) {
            scenario.stepFrame();
        }

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("false", "false", "resume true true", "running 3 true", "local TypeError"), scenario.getInfoMessages(),
                                scenario.getLines()::toString);
        Assertions.assertEquals(start + PAUSE_STEPS - PAUSED_STEPS, scenario.getGame().getCurrentFrame(), "a paused step runs no frame");
    }

    @Test
    void local_pause_requests_send_the_pause_and_resume_commands(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onLocalFrame() {
                                                                                                         if (game.isPaused()) {
                                                                                                             local.requestResume();
                                                                                                         } else {
                                                                                                             local.requestPause();
                                                                                                         }
                                                                                                         log.info('local ' + game.isPaused());
                                                                                                     }
                                                                                                     function onStart() {
                                                                                                         try {
                                                                                                             local.requestPause();
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        scenario.runLocalFrame();
        stepWhilePaused(scenario, false);

        Assertions.assertTrue(isPaused(scenario), "the Pause command pauses the game");

        int paused = scenario.getGame().getCurrentFrame();

        scenario.stepFrame();

        Assertions.assertEquals(paused, scenario.getGame().getCurrentFrame(), "the menu pause runs no frame");

        scenario.runLocalFrame();
        stepWhilePaused(scenario, true);

        Assertions.assertFalse(isPaused(scenario), "the Resume command resumes the game");
        Assertions.assertEquals(List.of("TypeError", "local false", "local true"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void the_same_pause_gives_the_same_game_state(@NotNull InstallationTestResources resources) throws IOException {
        String pause = """
                       function onStart() {
                           game.pause();
                       }
                       """;
        long first = runChanges(resources, pause);
        long second = runChanges(resources, pause);
        long unchanged = runChanges(resources, "");

        Assertions.assertEquals(first, second);
        Assertions.assertNotEquals(first, unchanged);
    }

    /**
     * Steps the game while its pause state is the given one, at most {@link #PAUSE_STEPS} times.
     */
    private static void stepWhilePaused(@NotNull LiveScriptGameScenario scenario, boolean paused) {
        for (int step = 0; step < PAUSE_STEPS && isPaused(scenario) == paused; step++) {
            scenario.stepFrame();
        }
    }

    private static boolean isPaused(@NotNull LiveScriptGameScenario scenario) {
        TriggerContext triggers = scenario.getGame().getTriggerContext();

        Assertions.assertNotNull(triggers);

        return triggers.getPhaseGate() != 0;
    }

    @Test
    void the_same_cheats_and_speed_give_the_same_game_state(@NotNull InstallationTestResources resources) throws IOException {
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
