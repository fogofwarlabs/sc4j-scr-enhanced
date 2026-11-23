package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrInputModifiers;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrKeyInput;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalSelection;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tests the script of a map with a script package in a live melee game, run by the game's {@link ScriptSimulationPlugin}: the start and
 * frame hooks, the frame hook while the script pauses the game, live and in the replay, the event hooks with their captured values and the
 * position of a death, the decision hooks that change damage, also of {@code applyDamage}, and reject orders, the read-only game in a decision
 * with its refused trigger writes and its local display, the channel messages of shared code and of the local hooks that the game's
 * {@link ScriptInputPlugin} calls, live and in the replay, the hooks of {@code defineHooks}, which play the game as top-level function hooks
 * do, protected too, and a map without a script package.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptSimulationPluginTest {
    /**
     * Defines {@code spawn()}, which creates a Marine of the human player a little toward the map center from the human's Command Center and,
     * unless {@code withoutZergling} is set, a Zergling of the computer player next to the Marine; {@code onFrame} calls it once the
     * Command Center exists, then {@code afterSpawn(frame)} if the script has it.
     */
    private static final @NotNull String SPAWN_SCRIPT = """
                                                        var human, computer, marine, zergling, spawnFrame, withoutZergling;
                                                        function spawn() {
                                                            human = game.players.getHumans()[0];
                                                            computer = game.units.findOne({type: UnitType.ZergHatchery}).getOwner();
                                                            var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                            var directionX = commandCenter.getX() < game.map.getWidth() / 2 ? 1 : -1;
                                                            var directionY = commandCenter.getY() < game.map.getHeight() / 2 ? 1 : -1;
                                                            marine = game.units.create(UnitType.TerranMarine, human,
                                                                                     Position.of(commandCenter.getX() + 160 * directionX, commandCenter.getY() + 160 * directionY));
                                                            if (!withoutZergling) {
                                                                zergling = game.units.create(UnitType.ZergZergling, computer, Position.of(marine.getX() + 48 * directionX, marine.getY()));
                                                            }
                                                        }
                                                        function onFrame(frame) {
                                                            if (spawnFrame === undefined && game.units.findOne({type: UnitType.TerranCommandCenter}) !== null) {
                                                                spawnFrame = frame;
                                                                spawn();
                                                            }
                                                            if (spawnFrame !== undefined && typeof afterSpawn === 'function') {
                                                                afterSpawn(frame);
                                                            }
                                                        }
                                                        """;
    private static final int FIGHT_FRAMES = 400;
    private static final int KEY_Q = 0x51;
    private static final int KEY_W = 0x57;
    /**
     * The stat_txt string that onLocalFrame writes what it reads to.
     */
    private static final int LOCAL_TEXT = 1000;
    /**
     * The frames the selection test runs before the start units are in place, and the slot of the human player.
     */
    private static final int START_FRAMES = 8;
    private static final int HUMAN = 0;
    /**
     * The switches the channel test reads: the 32 bits of the digest of the handler calls, then one switch per call.
     */
    private static final int CHANNEL_SWITCHES = 36;
    /**
     * The switches the pause test script sets when its third paused frame resumes the game and when the game runs again, the frame it runs again
     * at, and the most frames the test waits for that.
     */
    private static final int PAUSED_SWITCH = 1;
    private static final int RESUMED_SWITCH = 2;
    private static final int PAUSE_RESUMED_FRAME = 4;
    private static final int PAUSE_FRAMES = 20;

    private static @NotNull ScriptInputPlugin findInputPlugin(@NotNull Game game) {
        for (ScrGamePlugin plugin : game.getPlugins()) {
            if (plugin instanceof ScriptInputPlugin inputPlugin) {
                return inputPlugin;
            }
        }

        return Assertions.fail("The game has no script input plugin.");
    }

    /**
     * Plays a replay up to a frame.
     *
     * @return the game of the replay at the frame
     */
    private static @NotNull Game playReplay(@NotNull InstallationTestResources resources, byte @NotNull [] replay, int frames) throws IOException {
        ReplayRuntime playback = RunningGameScenario.createRuntime(ReplayFile.parse(replay), resources.getGameData());
        Game game = playback.getGame();

        playback.start(0, 0);

        while (game.getCurrentFrame() < frames) {
            Assertions.assertTrue(playback.step(0, game.getCurrentFrame() * playback.getFrameDuration()));
        }

        return game;
    }

    private static @NotNull String readSwitches(@NotNull Game game) {
        TriggerContext context = game.getTriggerContext();
        StringBuilder switches = new StringBuilder();

        Assertions.assertNotNull(context);

        for (int index = 0; index < CHANNEL_SWITCHES; index++) {
            switches.append(context.isSwitchSet(index) ? '1' : '0');
        }

        return switches.toString();
    }

    @Test
    void a_map_script_starts_runs_its_frames_and_receives_the_start_units(@NotNull InstallationTestResources resources,
                                                                          @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, """
                                                                                                           var frames = [];
                                                                                                           var commandCenter = null;
                                                                                                           function onStart() {
                                                                                                               frames.push('start ' + game.getFrame());
                                                                                                           }
                                                                                                           function onUnitCreated(event) {
                                                                                                               if (event.getUnitType() === UnitType.TerranCommandCenter) {
                                                                                                                   commandCenter = event;
                                                                                                               }
                                                                                                           }
                                                                                                           function onFrame(frame) {
                                                                                                               frames.push(frame);
                                                                                                               if (frames.length === 4) {
                                                                                                                   var unit = commandCenter.getUnit();
                                                                                                                   game.print('ES frames ' + frames.join(' ') + ', center ' + (unit === game.units.findOne({type: UnitType.TerranCommandCenter})) +
                                                                                                                              ' ' + unit.exists() + ' ' + commandCenter.getOwner().isHuman() + ' ' + commandCenter.getFrame());
                                                                                                               }
                                                                                                           }
                                                                                                           """);

        scenario.stepFrames(4);

        ScriptSimulationPlugin plugin = ScriptMapGameScenario.findPlugin(scenario.getGame());

        Assertions.assertNotNull(plugin);

        ScriptRuntime runtime = plugin.getRuntime();

        Assertions.assertFalse(runtime.isStopped());
        Assertions.assertFalse(scenario.getGame().isSaveAllowed(), "a script game cannot be saved");
        Assertions.assertEquals(List.of("frames start 1 1 2 3, center true true true 0"), scenario.getPrints());
    }

    @Test
    void a_paused_game_runs_the_frame_hook_until_the_script_resumes_it_live_and_in_the_replay(@NotNull InstallationTestResources resources,
                                                                                              @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, """
                                                                                                           var paused = 0;
                                                                                                           var pausedFrame = -1;
                                                                                                           var reported = false;
                                                                                                           function onFrame(frame) {
                                                                                                               if (frame === 3 && pausedFrame < 0 && !game.isPaused()) {
                                                                                                                   game.pause();
                                                                                                               }
                                                                                                               if (game.isPaused()) {
                                                                                                                   paused++;
                                                                                                                   if (pausedFrame < 0) {
                                                                                                                       pausedFrame = frame;
                                                                                                                   }
                                                                                                                   if (paused === 3) {
                                                                                                                       game.switches.set(1, true);
                                                                                                                       game.resume();
                                                                                                                   }
                                                                                                               } else if (paused > 0 && !reported) {
                                                                                                                   reported = true;
                                                                                                                   game.switches.set(2, true);
                                                                                                                   game.print('ES paused ' + paused + ' at ' + pausedFrame + ', now ' + frame);
                                                                                                               }
                                                                                                           }
                                                                                                           """);

        scenario.stepUntilPrinted("paused", PAUSE_FRAMES);

        Game live = scenario.getGame();

        Assertions.assertEquals(List.of("paused 3 at 3, now 4"), scenario.getPrints(), "the frame hook runs at every trigger step of the pause");
        Assertions.assertEquals(PAUSE_RESUMED_FRAME, live.getCurrentFrame());

        ReplayRuntime playback = RunningGameScenario.createRuntime(ReplayFile.parse(scenario.writeReplay()), resources.getGameData());
        Game game = playback.getGame();

        playback.start(0, 0);

        while (game.getCurrentFrame() < PAUSE_RESUMED_FRAME) {
            Assertions.assertTrue(playback.step(0, game.getCurrentFrame() * playback.getFrameDuration()));
        }

        TriggerContext triggers = game.getTriggerContext();

        Assertions.assertNotNull(triggers);
        Assertions.assertTrue(triggers.isSwitchSet(PAUSED_SWITCH), "the replay pauses and runs the paused frames");
        Assertions.assertTrue(triggers.isSwitchSet(RESUMED_SWITCH));
        Assertions.assertEquals(0, triggers.getPhaseGate());
    }

    @Test
    void on_unit_died_receives_the_killer_and_the_weapon(@NotNull InstallationTestResources resources,
                                                         @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, SPAWN_SCRIPT + """
                                                                                                                          var victim;
                                                                                                                          function afterSpawn(frame) {
                                                                                                                              if (frame === spawnFrame) {
                                                                                                                                  zergling.setHp(1);
                                                                                                                                  victim = game.units.create(UnitType.TerranMarine, human, marine.getPosition());
                                                                                                                                  victim.kill();
                                                                                                                              }
                                                                                                                          }
                                                                                                                          function onUnitDied(event) {
                                                                                                                              if (event.getUnit() === zergling) {
                                                                                                                                  game.print('ES zergling ' + (event.getKiller() === marine) + ' ' + (event.getKillerPlayer() === human) + ' ' +
                                                                                                                                             event.getWeapon() + ' ' + event.getUnitType() + ' ' + (event.getOwner() === computer) + ' ' +
                                                                                                                                             event.getUnit().exists());
                                                                                                                              }
                                                                                                                              if (event.getUnit() === victim) {
                                                                                                                                  game.print('ES victim ' + event.getKiller() + ' ' + event.getKillerPlayer() + ' ' + event.getWeapon() + ' ' +
                                                                                                                                             victim.exists());
                                                                                                                              }
                                                                                                                          }
                                                                                                                          """);

        scenario.stepUntilPrinted("zergling", FIGHT_FRAMES);

        Assertions.assertEquals(List.of("victim null null null false", "zergling true true Gauss Rifle Zerg Zergling true false"),
                                scenario.getPrints());
    }

    @Test
    void a_kill_with_a_source_names_the_killer_in_on_unit_died(@NotNull InstallationTestResources resources,
                                                               @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, SPAWN_SCRIPT + """
                                                                                                                          var victim;
                                                                                                                          function afterSpawn(frame) {
                                                                                                                              if (frame === spawnFrame) {
                                                                                                                                  victim = game.units.create(UnitType.ZergZergling, computer, marine.getPosition());
                                                                                                                                  zergling.kill(marine);
                                                                                                                                  victim.damage(victim.getHp(), human);
                                                                                                                              }
                                                                                                                          }
                                                                                                                          function onUnitDied(event) {
                                                                                                                              if (event.getUnit() === zergling || event.getUnit() === victim) {
                                                                                                                                  game.print('ES ' + (event.getUnit() === zergling ? 'zergling ' : 'victim ') +
                                                                                                                                             (event.getKiller() === marine) + ' ' + (event.getKillerPlayer() === human) + ' ' +
                                                                                                                                             event.getWeapon());
                                                                                                                              }
                                                                                                                          }
                                                                                                                          """);

        scenario.stepUntilPrinted("victim", FIGHT_FRAMES);

        Assertions.assertEquals(List.of("victim false true null", "zergling true true null"), scenario.getPrints());
    }

    @Test
    void on_before_damage_doubles_the_damage_of_a_weapon_hit(@NotNull InstallationTestResources resources,
                                                             @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, SPAWN_SCRIPT + """
                                                                                                                          var printed = false;
                                                                                                                          function onBeforeDamage(damage) {
                                                                                                                              if (damage.getTarget() === zergling && damage.getWeapon() === WeaponType.GaussRifle) {
                                                                                                                                  damage.setAmount(damage.getAmount() * 2);
                                                                                                                              }
                                                                                                                          }
                                                                                                                          function onUnitDamaged(event) {
                                                                                                                              if (event.getUnit() === zergling && !printed) {
                                                                                                                                  printed = true;
                                                                                                                                  game.print('ES damaged ' + event.getAmount() + ' ' + (event.getSource() === marine) + ' ' + zergling.getHp());
                                                                                                                              }
                                                                                                                          }
                                                                                                                          """);

        scenario.stepUntilPrinted("damaged", FIGHT_FRAMES);

        Assertions.assertEquals(List.of("damaged 12 true 23"), scenario.getPrints());
    }

    @Test
    void on_before_damage_cancels_every_weapon_hit(@NotNull InstallationTestResources resources,
                                                   @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, SPAWN_SCRIPT + """
                                                                                                                          var cancelled = 0;
                                                                                                                          function onBeforeDamage(damage) {
                                                                                                                              damage.cancel();
                                                                                                                              cancelled++;
                                                                                                                          }
                                                                                                                          function afterSpawn(frame) {
                                                                                                                              if (frame === spawnFrame + 200) {
                                                                                                                                  game.print('ES hit points ' + marine.getHp() + ' ' + zergling.getHp() + ' ' + (cancelled > 1));
                                                                                                                              }
                                                                                                                          }
                                                                                                                          """);

        scenario.stepUntilPrinted("hit points", FIGHT_FRAMES);

        Assertions.assertEquals(List.of("hit points 40 35 true"), scenario.getPrints());
    }

    @Test
    void apply_damage_runs_on_before_damage_and_the_death_keeps_the_position(@NotNull InstallationTestResources resources,
                                                                             @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, SPAWN_SCRIPT + """
                                                                                                                          withoutZergling = true;
                                                                                                                          var target, deathX, deathY;
                                                                                                                          var decided = [];
                                                                                                                          function onBeforeDamage(damage) {
                                                                                                                              if (damage.getTarget() === target) {
                                                                                                                                  decided.push((damage.getWeapon() === WeaponType.GaussRifle) + ' ' + damage.getAmount() + ' ' +
                                                                                                                                               (damage.getSource() === marine) + ' ' + (damage.getSourcePlayer() === human));
                                                                                                                                  damage.setAmount(damage.getAmount() * 2);
                                                                                                                              }
                                                                                                                          }
                                                                                                                          function afterSpawn(frame) {
                                                                                                                              if (frame === spawnFrame) {
                                                                                                                                  target = game.units.create(UnitType.ZergZergling, computer, marine.getPosition());
                                                                                                                                  target.applyDamage(6, marine, {weapon: WeaponType.GaussRifle});
                                                                                                                                  var hit = target.getHp();
                                                                                                                                  target.damage(5, marine);
                                                                                                                                  game.print('ES hit ' + hit + ' ' + target.getHp() + ' ' + decided.join(','));
                                                                                                                                  deathX = target.getX();
                                                                                                                                  deathY = target.getY();
                                                                                                                                  target.applyDamage(100, human);
                                                                                                                              }
                                                                                                                          }
                                                                                                                          function onUnitDied(event) {
                                                                                                                              if (event.getUnit() === target) {
                                                                                                                                  game.print('ES died ' + (event.getPosition().getX() === deathX) + ' ' + (event.getPosition().getY() === deathY) +
                                                                                                                                             ' ' + (deathX > 0) + ' ' + event.getUnit().exists() + ' ' + (event.getKillerPlayer() === human) + ' ' +
                                                                                                                                             event.getWeapon() + ' ' + decided[1]);
                                                                                                                              }
                                                                                                                          }
                                                                                                                          """);

        scenario.stepUntilPrinted("died", FIGHT_FRAMES);

        Assertions.assertEquals(List.of("hit 23 18 true 6 true true", "died true true true false true null false 100 false true"), scenario.getPrints());
    }

    @Test
    void on_before_order_rejects_an_order(@NotNull InstallationTestResources resources, @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, SPAWN_SCRIPT + """
                                                                                                                          withoutZergling = true;
                                                                                                                          var moved = false;
                                                                                                                          function onBeforeOrder(order) {
                                                                                                                              if (order.getUnit() === marine && order.getOrder() === Order.Move) {
                                                                                                                                  order.reject();
                                                                                                                              }
                                                                                                                          }
                                                                                                                          function onOrderIssued(event) {
                                                                                                                              if (event.getUnit() === marine && event.getOrder() === Order.Move) {
                                                                                                                                  moved = true;
                                                                                                                              }
                                                                                                                          }
                                                                                                                          function afterSpawn(frame) {
                                                                                                                              if (frame === spawnFrame) {
                                                                                                                                  marine.issueOrder(Order.Move, Position.of(marine.getX() + 64, marine.getY()));
                                                                                                                                  game.print('ES order ' + (marine.getOrder() === Order.Move));
                                                                                                                              } else if (frame === spawnFrame + 2) {
                                                                                                                                  game.print('ES issued ' + moved);
                                                                                                                              }
                                                                                                                          }
                                                                                                                          """);

        scenario.stepUntilPrinted("issued", FIGHT_FRAMES);

        Assertions.assertEquals(List.of("order false", "issued false"), scenario.getPrints());
    }

    @Test
    void a_state_change_in_a_decision_throws_and_leaves_the_decision(@NotNull InstallationTestResources resources,
                                                                     @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, SPAWN_SCRIPT + """
                                                                                                                          var errors = [];
                                                                                                                          var printed = false;
                                                                                                                          function onBeforeDamage(damage) {
                                                                                                                              if (damage.getTarget() !== zergling) {
                                                                                                                                  return;
                                                                                                                              }
                                                                                                                              try {
                                                                                                                                  zergling.setHp(1);
                                                                                                                              } catch (error) {
                                                                                                                                  errors.push(error.name + ' ' + game.isReadOnly());
                                                                                                                              }
                                                                                                                              damage.setAmount(0);
                                                                                                                              zergling.kill();
                                                                                                                          }
                                                                                                                          function onUnitDamaged(event) {
                                                                                                                              if (event.getUnit() === zergling && !printed) {
                                                                                                                                  printed = true;
                                                                                                                                  game.print('ES damaged ' + event.getAmount() + ' ' + errors[0] + ' ' + game.isReadOnly() + ' ' + zergling.getHp());
                                                                                                                              }
                                                                                                                          }
                                                                                                                          """);

        scenario.stepUntilPrinted("damaged", FIGHT_FRAMES);

        Assertions.assertEquals(List.of("damaged 6 TypeError true false 29"), scenario.getPrints());
    }

    @Test
    void trigger_writes_in_a_decision_throw_while_the_local_display_runs(@NotNull InstallationTestResources resources,
                                                                         @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, SPAWN_SCRIPT + """
                                                                                                                          var refused = null;
                                                                                                                          var displayed = false;
                                                                                                                          var printed = false;
                                                                                                                          function onBeforeDamage(damage) {
                                                                                                                              if (damage.getTarget() !== zergling || refused !== null) {
                                                                                                                                  return;
                                                                                                                              }
                                                                                                                              var writes = [
                                                                                                                                  function () { game.switches.set(0, true); },
                                                                                                                                  function () { game.countdown.set(30); },
                                                                                                                                  function () { human.deaths.set(UnitType.TerranMarine, 1); },
                                                                                                                                  function () { human.score.set(ScoreType.Custom, 1); },
                                                                                                                                  function () { computer.units.kill(UnitType.ZergDrone); },
                                                                                                                                  function () { human.setAlliance(computer, Alliance.Ally); },
                                                                                                                                  function () { computer.runAiScript('EnBk'); },
                                                                                                                                  function () { human.setMissionObjectives('Objectives'); },
                                                                                                                                  function () { game.leaderboard.showGreed(10); },
                                                                                                                                  function () { zergling.disableDoodad(); }
                                                                                                                              ];
                                                                                                                              refused = writes.filter(function (write) {
                                                                                                                                  try {
                                                                                                                                      write();
                                                                                                                                      return false;
                                                                                                                                  } catch (error) {
                                                                                                                                      return error.name === 'TypeError';
                                                                                                                                  }
                                                                                                                              }).length + '/' + writes.length;
                                                                                                                              human.pingMinimap(marine.getPosition());
                                                                                                                              human.print('a decision text');
                                                                                                                              human.deaths.get(UnitType.TerranMarine);
                                                                                                                              human.units.count(UnitType.TerranMarine);
                                                                                                                              displayed = true;
                                                                                                                          }
                                                                                                                          function onUnitDamaged(event) {
                                                                                                                              if (event.getUnit() === zergling && !printed) {
                                                                                                                                  printed = true;
                                                                                                                                  game.print('ES refused ' + refused + ' ' + displayed + ' ' + game.switches.isSet(0) + ' ' + game.countdown.get() + ' ' +
                                                                                                                                             human.deaths.get(UnitType.TerranMarine) + ' ' + computer.units.count(UnitType.ZergDrone));
                                                                                                                              }
                                                                                                                          }
                                                                                                                          """);

        scenario.stepUntilPrinted("refused", FIGHT_FRAMES);

        Assertions.assertEquals(List.of("refused 10/10 true false 0 0 4"), scenario.getPrints());
    }

    @Test
    void channel_messages_of_shared_code_and_local_hooks_reach_the_handler_live_and_in_the_replay(@NotNull InstallationTestResources resources,
                                                                                                  @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, """
                                                                                                           var DIGEST_BITS = 32;
                                                                                                           var human;
                                                                                                           var digest = 0;
                                                                                                           var count = 0;
                                                                                                           var score = sync.createPlayerValue(0);
                                                                                                           var channel = sync.createChannel(function (player, value) {
                                                                                                               var text = 'channel ' + (player === human) + ' ' + (typeof value === 'string' ? value : JSON.stringify(value)) + ' ' +
                                                                                                                          score.get(player);
                                                                                                               game.print('ES ' + text);
                                                                                                               for (var index = 0; index < text.length; index++) {
                                                                                                                   digest = (digest * 31 + text.charCodeAt(index)) | 0;
                                                                                                               }
                                                                                                               for (var bit = 0; bit < DIGEST_BITS; bit++) {
                                                                                                                   game.switches.set(bit, (digest >>> bit & 1) === 1);
                                                                                                               }
                                                                                                               game.switches.set(DIGEST_BITS + count++, true);
                                                                                                           });
                                                                                                           function onStart() {
                                                                                                               human = game.players.getHumans()[0];
                                                                                                               channel.send({start: [1, 'two', true, null]});
                                                                                                               game.print('ES started');
                                                                                                           }
                                                                                                           function onLocalKeyDown(event) {
                                                                                                               if (event.getKey() === Key.Q) {
                                                                                                                   score.set(7);
                                                                                                                   channel.send('key ' + event.isShift() + ' ' + event.getMousePosition());
                                                                                                                   event.consume();
                                                                                                               }
                                                                                                           }
                                                                                                           function onLocalChat(event) {
                                                                                                               if (event.getText().charAt(0) === '!') {
                                                                                                                   channel.send(event.getText());
                                                                                                                   event.consume();
                                                                                                               }
                                                                                                           }
                                                                                                           """);

        scenario.stepUntilPrinted("channel", FIGHT_FRAMES);

        ScriptInputPlugin plugin = findInputPlugin(scenario.getGame());

        scenario.getGame().getInterfaceLink().getLocalView().setView(640, 320, 100, 50);
        Assertions.assertTrue(plugin.onKeyInput(new ScrKeyInput(KEY_Q, true, ScrInputModifiers.SHIFT)));
        Assertions.assertFalse(plugin.onKeyInput(new ScrKeyInput(KEY_W, true, 0)));
        Assertions.assertTrue(plugin.onChatInput("!go"));
        Assertions.assertFalse(plugin.onChatInput("hello"));

        scenario.stepUntilPrinted("channel true !go", FIGHT_FRAMES);

        List<String> expected = List.of("started",
                                        "channel true {\"start\":[1,\"two\",true,null]} 0",
                                        "channel true key true (740, 370) 7",
                                        "channel true !go 7");

        Assertions.assertEquals(expected, scenario.getPrints());
        Assertions.assertTrue(readSwitches(scenario.getGame()).endsWith("1110"), "one switch per handler call");

        int frames = scenario.getGame().getCurrentFrame();
        ReplayRuntime playback = RunningGameScenario.createRuntime(ReplayFile.parse(scenario.writeReplay()), resources.getGameData());
        Game game = playback.getGame();

        playback.start(0, 0);

        while (game.getCurrentFrame() < frames) {
            Assertions.assertTrue(playback.step(0, game.getCurrentFrame() * playback.getFrameDuration()));
        }

        Assertions.assertEquals(readSwitches(scenario.getGame()), readSwitches(game), "the replay calls the handler with the recorded commands");
        Assertions.assertFalse(findInputPlugin(game).onKeyInput(new ScrKeyInput(KEY_Q, true, 0)), "a replay calls no local hook");
    }

    @Test
    void player_selections_follow_the_selection_commands_and_set_selection_live_and_in_the_replay(@NotNull InstallationTestResources resources,
                                                                                                  @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, """
                                                                                                           var human, computer, last = null, set = false;
                                                                                                           var LOCAL_TEXT = 1000;
                                                                                                           function describe(units) {
                                                                                                               return units.map(function (unit) { return unit.getId(); }).join(',');
                                                                                                           }
                                                                                                           function onStart() {
                                                                                                               human = game.players.getHumans()[0];
                                                                                                               computer = game.units.findOne({type: UnitType.ZergHatchery}).getOwner();
                                                                                                           }
                                                                                                           function onFrame(frame) {
                                                                                                               var selection = human.getSelection();
                                                                                                               var text = describe(selection);
                                                                                                               if (text !== last) {
                                                                                                                   last = text;
                                                                                                                   game.print('ES selection [' + text + ']');
                                                                                                               }
                                                                                                               if (selection.length === 4 && !set) {
                                                                                                                   set = true;
                                                                                                                   var scvs = game.units.find({type: UnitType.TerranSCV, owner: human});
                                                                                                                   var center = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                                   var neutral = game.units.findOne(function (unit) { return unit.getOwner().getId() === 11; });
                                                                                                                   var drones = game.units.find({type: UnitType.ZergDrone, owner: computer});
                                                                                                                   var tooMany = 'none';
                                                                                                                   try {
                                                                                                                       human.setSelection(scvs.concat(scvs, scvs, scvs));
                                                                                                                   } catch (error) {
                                                                                                                       tooMany = error.name;
                                                                                                                   }
                                                                                                                   var mixed = human.setSelection([scvs[1], center, neutral, scvs[2]]);
                                                                                                                   var computerSet = computer.setSelection(drones.slice(0, 2));
                                                                                                                   game.print('ES set ' + tooMany + ' ' + mixed + ' [' + describe(human.getSelection()) + '] ' + computerSet + ' ' +
                                                                                                                              (describe(computer.getSelection()) === describe(drones.slice(0, 2))));
                                                                                                               }
                                                                                                           }
                                                                                                           function onLocalFrame(frame) {
                                                                                                               display.strings.setStatText(LOCAL_TEXT, 'local ' + local.getPlayer() + ' ' + local.isReplay() + ' [' + describe(local.getSelection()) + ']');
                                                                                                               if (local.isReplay() && local.getPlayer() === null && local.getSelection().length === 0) {
                                                                                                                   local.setScreenPosition(Position.of(64, 96));
                                                                                                               }
                                                                                                           }
                                                                                                           """);

        scenario.stepFrames(START_FRAMES);

        Game live = scenario.getGame();
        UnitPool pool = live.getUnitSystem().requireUnitPool();
        List<Integer> scvs = new ArrayList<>();

        for (int id = 1; id <= pool.getCapacity(); id++) {
            Unit unit = pool.isAllocated(id) ? pool.getUnit(id) : null;

            if (unit != null && unit.getSpriteOrNull() != null && unit.getUnitId() == ScrUnitIds.TERRAN_SCV && unit.getOwnerIndex() == HUMAN) {
                scvs.add(id);
            }
        }

        long[] handles = new long[scvs.size()];

        for (int index = 0; index < handles.length; index++) {
            handles[index] = Integer.toUnsignedLong(pool.getHandle(scvs.get(index)));
        }

        Assertions.assertEquals(4, scvs.size(), "the start SCVs");
        Assertions.assertTrue(scenario.submit(ReplayCommandIds.SELECT_121, ReplayCommandCodecs.encode(
                ReplayCommandCodecs.SELECT_121, ReplaySelectionCommand.create(SelectionRequest.Operation.REPLACE, Integer.BYTES, handles)
        )));

        scenario.stepUntilPrinted("set", FIGHT_FRAMES);
        scenario.stepFrames(1);

        String all = scvs.stream().map(String::valueOf).collect(Collectors.joining(","));
        String kept = scvs.get(1) + "," + scvs.get(2);

        Assertions.assertEquals(List.of("selection []", "selection [" + all + "]", "set RangeError false [" + kept + "] true true",
                                        "selection [" + kept + "]"), scenario.getPrints());

        LocalSelection shown = live.getInterfaceLink().getLocalSelection();

        Assertions.assertEquals(scvs.get(1), shown.getSelectedPoolIndex(0), "the local player sees the selection the script set");
        Assertions.assertEquals(scvs.get(2), shown.getSelectedPoolIndex(1));
        Assertions.assertEquals(LocalSelection.EMPTY_SLOT, shown.getSelectedPoolIndex(2));

        findInputPlugin(live).onFramePlayed(live.getCurrentFrame());

        Assertions.assertEquals("local Player 0 false [" + kept + "]", live.getInterfaceLink().getStatTextOverrides().find(LOCAL_TEXT),
                                "onLocalFrame reads the local values");

        int frames = live.getCurrentFrame();
        byte[] replay = scenario.writeReplay();
        Game game = playReplay(resources, replay, frames);
        Game otherPeer = playReplay(resources, replay, frames);

        UnitSelection liveSelection = live.requireSelection();
        UnitSelection replaySelection = game.requireSelection();

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
                Assertions.assertEquals(liveSelection.getSelectedPoolIndexRaw(player, position), replaySelection.getSelectedPoolIndexRaw(player, position),
                                        "the selection of player " + player + " at " + position);
            }
        }

        Assertions.assertEquals(game.computeStateHash(), otherPeer.computeStateHash(), "two peers of one identity have one state");

        LocalView view = game.getInterfaceLink().getLocalView();

        view.setView(0, 0, 0, 0);
        view.setScreenGeometry(640, 480, 2000, 1000);
        findInputPlugin(game).onFramePlayed(game.getCurrentFrame());

        Assertions.assertEquals(64, view.getScreenX(), "a replay calls onLocalFrame without a local player or a local selection");
        Assertions.assertEquals(96, view.getScreenY());
    }

    @Test
    void hooks_of_define_hooks_play_the_game_as_top_level_function_hooks(@NotNull InstallationTestResources resources,
                                                                         @TempDir @NotNull Path folder) throws IOException {
        String state = """
                       var damaged = 0, drawn = 0;
                       function report(frame) {
                           game.print('ES report ' + frame + ' ' + damaged + ' ' + drawn + ' ' + [marine, zergling].map(function (unit) {
                               return unit.exists() ? unit.getHp() : 'dead';
                           }).join(' '));
                       }
                       """;
        String functionHooks = """
                               function onStart() {
                                   game.every(16, function () { drawn += random.nextInt(100); });
                               }
                               function afterSpawn(frame) {
                                   if ((frame - spawnFrame) % 50 === 0) {
                                       report(frame);
                                   }
                               }
                               function onBeforeDamage(damage) {
                                   if (random.chance(0.5)) {
                                       damage.setAmount(damage.getAmount() * 2);
                                   }
                               }
                               function onUnitDamaged(event) {
                                   damaged++;
                               }
                               function onUnitDied(event) {
                                   game.print('ES died ' + event.getUnitType() + ' ' + event.getFrame());
                               }
                               """;
        String definedHooks = """
                              function afterSpawn(frame) {
                                  if ((frame - spawnFrame) % 50 === 0) {
                                      report(frame);
                                  }
                              }
                              defineHooks({
                                  onStart() {
                                      game.every(16, function () { drawn += random.nextInt(100); });
                                  },
                                  onBeforeDamage(damage) {
                                      if (random.chance(0.5)) {
                                          damage.setAmount(damage.getAmount() * 2);
                                      }
                                  },
                                  onUnitDamaged(event) {
                                      damaged++;
                                  },
                                  onUnitDied(event) {
                                      game.print('ES died ' + event.getUnitType() + ' ' + event.getFrame());
                                  }
                              });
                              """;
        List<ScriptMapGameScenario> scenarios = List.of(
                ScriptMapGameScenario.startMelee(resources.getGameData(), folder.resolve("function"), SPAWN_SCRIPT + state + functionHooks),
                ScriptMapGameScenario.startMelee(resources.getGameData(), folder.resolve("defined"), SPAWN_SCRIPT + state + definedHooks),
                ScriptMapGameScenario.startMelee(resources.getGameData(), folder.resolve("protected"), SPAWN_SCRIPT + state + definedHooks, Map.of(),
                                                 true)
        );

        for (ScriptMapGameScenario scenario : scenarios) {
            scenario.stepFrames(FIGHT_FRAMES);
        }

        ScriptMapGameScenario expected = scenarios.getFirst();

        Assertions.assertTrue(expected.getPrints().stream().anyMatch(text -> text.startsWith("died ")), expected.getPrints()::toString);

        for (ScriptMapGameScenario scenario : scenarios.subList(1, scenarios.size())) {
            Assertions.assertEquals(expected.getPrints(), scenario.getPrints());
            Assertions.assertEquals(expected.getGame().computeStateHash(), scenario.getGame().computeStateHash());
        }
    }

    @Test
    void a_map_without_a_script_package_gets_no_plugin(@NotNull InstallationTestResources resources,
                                                       @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, null);

        scenario.stepFrames(3);

        Assertions.assertNull(ScriptMapGameScenario.findPlugin(scenario.getGame()));
        Assertions.assertNull(scenario.getGame().getSimulationPlugins(), "a map without a script package gets no simulation plugin");
        Assertions.assertEquals(List.of(), scenario.getPrints());
    }
}
