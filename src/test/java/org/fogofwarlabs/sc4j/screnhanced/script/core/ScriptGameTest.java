package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.List;

/**
 * Tests the game API of {@link ScriptGame} on a game without game data: the type constants with their original data names, the game's frame
 * and map, the timers of {@code game.every}, which the prelude counts, the read-only mode and the sealed {@code core}; and in a live melee
 * game the switches, the countdown timer and the elapsed time of the trigger state.
 */
final class ScriptGameTest {
    private static final int MAP_TILE_WIDTH = 4;
    private static final int MAP_TILE_HEIGHT = 3;
    private static final int MAP_TILESET = 2;
    /**
     * The frames the trigger state test runs after its start: three seconds of the trigger clock.
     */
    private static final int CLOCK_FRAMES = 48;
    /**
     * An instruction budget far below the instructions of the prelude.
     */
    private static final int SMALL_INSTRUCTION_BUDGET = 20_000;
    private static final int UNIT_CAPACITY = 16;

    private static @NotNull Game createGame() {
        return Game.create(null, new GameMap(MAP_TILE_WIDTH, MAP_TILE_HEIGHT, MAP_TILESET, new int[MAP_TILE_WIDTH * MAP_TILE_HEIGHT]),
                           new LcgRandom(0));
    }

    private static @NotNull ScriptGameScenario start(@NotNull Game game, @NotNull String mainSource) throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(game, mainSource);

        scenario.getRuntime().start();

        return scenario;
    }

    @Test
    void the_prelude_runs_outside_a_small_budget() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), """
                                                                           log.info(UnitType.TerranMarine.getName());
                                                                           function onFrame(frame) { log.info('frame', frame); }
                                                                           """, ScriptResources.EMPTY, SMALL_INSTRUCTION_BUDGET);

        scenario.getRuntime().start();
        scenario.getRuntime().runFrame(0);

        Assertions.assertFalse(scenario.getRuntime().isStopped());
        Assertions.assertEquals(List.of("Terran Marine", "frame 0"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void constants_carry_the_original_data_names() throws IOException {
        ScriptGameScenario scenario = start(createGame(), """
                                                          log.info(UnitType.TerranMarine.getId(), UnitType.TerranMarine.getName(), UnitType.HeroJimRaynorMarine.getId(),
                                                                   UnitType.ZergZergling.getId(), UnitType.TerranSiegeTankTankMode.getId(), UnitType.get(37) === UnitType.ZergZergling);
                                                          log.info(WeaponType.GaussRifle.getId(), UpgradeType.TerranInfantryWeapons.getId(), TechType.StimPacks.getId(),
                                                                   Order.AttackMove.getId(), Order.PlayerGuard.getName());
                                                          log.info(UnitType.getCount(), WeaponType.getCount(), UpgradeType.getCount(), TechType.getCount(), Order.getCount());
                                                          function check(table, namespace) {
                                                              var matching = 0;
                                                              for (var id = 0; id < namespace.getCount(); id++) {
                                                                  var identifier = namespace.getName(id).split(' ').map(function (word) {
                                                                      return word.charAt(0).toUpperCase() + word.substring(1);
                                                                  }).join('');
                                                                  if (table[identifier] === table.get(id) && table.get(id).getId() === id) {
                                                                      matching++;
                                                                  }
                                                              }
                                                              return matching === namespace.getCount();
                                                          }
                                                          log.info(check(UnitType, core.unitType), check(WeaponType, core.weaponType), check(UpgradeType, core.upgradeType),
                                                                   check(TechType, core.techType), check(Order, core.orderType));
                                                          log.info(Object.isFrozen(UnitType), Object.isFrozen(UnitType.TerranMarine), UnitType.get(228), String(UnitType.ZergZergling));
                                                          """);

        Assertions.assertEquals(List.of("0 Terran Marine 20 37 5 true",
                                        "0 7 0 14 PlayerGuard",
                                        "228 130 61 44 189",
                                        "true true true true true",
                                        "true true null Zerg Zergling"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void game_reads_the_frame_and_the_map() throws IOException {
        Game game = createGame();

        game.storeCurrentFrame(7);

        ScriptGameScenario scenario = start(game, """
                                                  log.info(game.getFrame(), game.map.getTileWidth(), game.map.getTileHeight(), game.map.getWidth(), game.map.getHeight(),
                                                           game.map.tileset.getId(), Time.fromSeconds(10), Time.fromMinutes(1), Time.fromSeconds(0.53), Time.fromMilliseconds(1000),
                                                           Time.fromMilliseconds(21), core.game.getFrameDuration());
                                                  """);

        Assertions.assertEquals(List.of("7 4 3 128 96 2 160 960 8 24 1 42"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void the_globals_group_the_game_and_the_display() throws IOException {
        ScriptGameScenario scenario = start(createGame(), """
                                                          var scope = this;
                                                          log.info(['game', 'local', 'display', 'sync', 'log', 'random', 'UnitType', 'Color'].every(function (name) {
                                                              return typeof scope[name] === 'object';
                                                          }), ['tileset', 'terrain', 'resources', 'graphics', 'console', 'strings', 'messages'].filter(function (name) {
                                                              return name in scope;
                                                          }).join(' '));
                                                          log.info(Object.keys(game).join(' '));
                                                          log.info(Object.keys(game.map).join(' '));
                                                          log.info(Object.keys(display).join(' '));
                                                          log.info(typeof game.getPlayer, typeof game.getMapWidth, typeof game.findUnits, typeof game.setSwitch, typeof game.getCountdownTimer,
                                                                   typeof game.showGreedLeaderboard);
                                                          log.info(game.map === game.map, game.map.tileset === game.map.tileset, game.players === game.players,
                                                                   display.images === display.images, Object.isFrozen(game.map), Object.isFrozen(game.units), Object.isFrozen(game.countdown),
                                                                   Object.isFrozen(display.console));
                                                          """);

        Assertions.assertEquals(List.of("true ",
                                        "getFrame getElapsedTime isReadOnly print isPaused pause resume every after getSpeed isCheatEnabled " +
                                        "setCheatEnabled createEffect getBullets createBullet map players units switches countdown leaderboard " +
                                        "settings vision combat",
                                        "getWidth getHeight getTileWidth getTileHeight tileset terrain locations resources",
                                        "console strings messages sound images commandCard",
                                        "undefined undefined undefined undefined undefined undefined",
                                        "true true true true true true true true"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void players_and_units_are_found_through_their_groups() throws IOException {
        Game game = createGame();

        game.getUnitSystem().initializeUnitPool(UNIT_CAPACITY);

        ScriptGameScenario scenario = start(game, """
                                                  var first = game.players.get(0);
                                                  log.info(first === game.players.get(0), game.players.getAll().length, game.players.getAll()[3] === game.players.get(3),
                                                           game.players.get(12), game.players.get(-1));
                                                  log.info(game.units.getAll().length, game.units.count(), game.units.findOne(), game.units.get(1), game.units.find({owner: first}).length);
                                                  """);

        Assertions.assertEquals(List.of("true 12 true null null",
                                        "0 0 null null 0"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void player_groups_are_made_once_per_player() throws IOException {
        ScriptGameScenario scenario = start(createGame(), """
                                                          var first = game.players.get(0);
                                                          var second = game.players.get(1);
                                                          var names = ['upgrades', 'techs', 'units', 'deaths', 'kills', 'score'];
                                                          var groups = [];
                                                          for (var name in first) {
                                                              if (names.indexOf(name) >= 0) {
                                                                  groups.push(name);
                                                              }
                                                          }
                                                          log.info(groups.join(' '), Object.getOwnPropertyNames(first).length);
                                                          log.info(names.every(function (name) {
                                                              return first[name] === game.players.get(0)[name] && first[name] !== second[name] && Object.isFrozen(first[name]);
                                                          }), groups.join(' ') === names.join(' '));
                                                          log.info(typeof first.upgrades.getLevel, typeof first.techs.isResearched, typeof first.units.count, typeof first.deaths.add,
                                                                   typeof first.kills.get, typeof first.score.subtract, typeof first.getDeaths, typeof first.countUnits,
                                                                   typeof first.getUpgradeLevel);
                                                          try {
                                                              first.upgrades = null;
                                                          } catch (error) {
                                                              log.info(error.name);
                                                          }
                                                          log.info(first.upgrades === game.players.get(0).upgrades);
                                                          try {
                                                              first.deaths.get(ScoreType.Custom);
                                                          } catch (error) {
                                                              log.info(error.name);
                                                          }
                                                          """);

        Assertions.assertEquals(List.of("upgrades techs units deaths kills score 1",
                                        "true true",
                                        "function function function function function function undefined undefined undefined",
                                        "true",
                                        "TypeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void timers_run_at_frame_start_in_due_and_registration_order() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = start(game, """
                                                  var third;
                                                  function onStart() {
                                                      game.every(3, function () { log.info('first ' + game.getFrame()); });
                                                      game.every(2, function () { log.info('second ' + game.getFrame()); });
                                                      third = game.every(3, function () { log.info('third ' + game.getFrame()); });
                                                  }
                                                  function onFrame(frame) {
                                                      log.info('frame ' + frame);
                                                      if (frame === 6) {
                                                          log.info('cancel ' + third.cancel() + ' ' + third.cancel());
                                                      }
                                                  }
                                                  """);

        for (int frame = 0; frame <= 9; frame++) {
            game.storeCurrentFrame(frame);
            scenario.getRuntime().runFrame(frame);
        }

        Assertions.assertEquals(List.of("frame 0",
                                        "frame 1",
                                        "second 2", "frame 2",
                                        "first 3", "third 3", "frame 3",
                                        "second 4", "frame 4",
                                        "frame 5",
                                        "first 6", "second 6", "third 6", "frame 6", "cancel true false",
                                        "frame 7",
                                        "second 8", "frame 8",
                                        "first 9", "frame 9"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void timers_run_without_a_frame_hook_and_their_errors_end_only_their_call() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = start(game, """
                                                  game.every(1, function () { throw new Error('broken'); });
                                                  game.every(1, function () { log.info('ran ' + game.getFrame()); });
                                                  """);

        game.storeCurrentFrame(1);
        scenario.getRuntime().runFrame(1);

        Assertions.assertEquals(List.of("ERROR main.js: Timer failed: Error: broken (main.js:1)", "INFO main.js: ran 1"), scenario.getLines());
    }

    @Test
    void read_only_mode_refuses_state_changes_with_a_type_error() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = new ScriptGameScenario(game, """
                                                                   function attempt(name, change) {
                                                                       try {
                                                                           change();
                                                                           log.info(name + ' changed');
                                                                       } catch (error) {
                                                                           log.info(name + ' ' + error.name);
                                                                       }
                                                                   }
                                                                   function onFrame() {
                                                                       log.info('read-only ' + game.isReadOnly() + ' ' + game.getFrame());
                                                                       attempt('minerals', function () { core.player.setMinerals(0, 5); });
                                                                       attempt('create', function () { core.unit.create(0, 0, 32, 32); });
                                                                       attempt('kill', function () { core.unit.kill(1); });
                                                                       attempt('location', function () { core.location.setBounds(0, 0, 0, 32, 32); });
                                                                       attempt('data', function () { UnitType.TerranMarine.setHitPoints(256); });
                                                                   }
                                                                   """);

        scenario.getRuntime().start();
        scenario.getRuntime().setReadOnly(true);
        scenario.getRuntime().runFrame(0);

        Assertions.assertTrue(scenario.getRuntime().isReadOnly());
        Assertions.assertEquals(List.of("read-only true 0", "minerals TypeError", "create TypeError", "kill TypeError", "location TypeError",
                                        "data TypeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void read_only_mode_refuses_the_trigger_writes_with_a_type_error() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = new ScriptGameScenario(game, """
                                                                   var writes = {
                                                                       switch: function () { game.switches.set(0, true); },
                                                                       toggle: function () { game.switches.toggle(0); },
                                                                       countdown: function () { game.countdown.set(5); },
                                                                       add: function () { game.countdown.add(5); },
                                                                       subtract: function () { game.countdown.subtract(5); },
                                                                       pause: function () { game.countdown.pause(); },
                                                                       resume: function () { game.countdown.resume(); },
                                                                       deaths: function () { game.players.get(0).deaths.set(UnitType.TerranMarine, 1); },
                                                                       addDeaths: function () { game.players.get(0).deaths.add(UnitGroup.Men, 1); },
                                                                       subtractDeaths: function () { game.players.get(0).deaths.subtract(UnitType.TerranMarine, 1); },
                                                                       score: function () { game.players.get(0).score.set(ScoreType.Custom, 1); },
                                                                       addScore: function () { game.players.get(0).score.add(ScoreType.Custom, 1); },
                                                                       subtractScore: function () { game.players.get(0).score.subtract(ScoreType.Custom, 1); },
                                                                       kill: function () { game.players.get(0).units.kill(UnitGroup.AnyUnit); },
                                                                       remove: function () { game.players.get(0).units.remove(UnitGroup.AnyUnit); },
                                                                       alliance: function () { game.players.get(0).setAlliance(game.players.get(1), Alliance.Ally); },
                                                                       script: function () { game.players.get(0).runAiScript('EnBk'); },
                                                                       scriptAt: function () { core.player.runAiScriptAt(0, 0, 0); },
                                                                       objectives: function () { game.players.get(0).setMissionObjectives('Objectives'); },
                                                                       leaderboard: function () { game.leaderboard.showKills('Kills', UnitGroup.Men); },
                                                                       leaderboardAt: function () { core.leaderboard.showAt(18, 0, 0, 0, 'Here'); },
                                                                       greed: function () { game.leaderboard.showGreed(10); },
                                                                       computers: function () { game.leaderboard.setComputersIncluded(true); },
                                                                       resources: function () { core.unit.setResources(1, 5); },
                                                                       hangar: function () { core.unit.addHangarCount(1, 1); },
                                                                       enable: function () { core.unit.enableDoodad(1); },
                                                                       disable: function () { core.unit.disableDoodad(1); },
                                                                       invincible: function () { core.unit.setInvincible(1, true); }
                                                                   };
                                                                   function onFrame() {
                                                                       var refused = [];
                                                                       for (var name in writes) {
                                                                           try {
                                                                               writes[name]();
                                                                           } catch (error) {
                                                                               if (error.name === 'TypeError' && error.message.indexOf('read-only') >= 0) {
                                                                                   refused.push(name);
                                                                               }
                                                                           }
                                                                       }
                                                                       var changed = Object.keys(writes).filter(function (name) {
                                                                           return refused.indexOf(name) < 0;
                                                                       });
                                                                       log.info(changed.length === 0 ? 'all refused' : 'changed: ' + changed.join(' '));
                                                                   }
                                                                   """);

        scenario.getRuntime().start();
        scenario.getRuntime().setReadOnly(true);
        scenario.getRuntime().runFrame(0);

        Assertions.assertEquals(List.of("all refused"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    @RequiresInstallation
    @ExtendWith(GameResourcesExtension.class)
    void switches_and_the_countdown_timer_take_the_trigger_routines(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var frames = 0;
                                                                                                     var started;
                                                                                                     function onStart() {
                                                                                                         log.info(game.switches.isSet(3), game.switches.isSet(4), game.countdown.get(), game.countdown.isPaused());
                                                                                                         game.switches.set(3, true);
                                                                                                         game.switches.toggle(4);
                                                                                                         game.switches.toggle(3);
                                                                                                         log.info(game.switches.isSet(3), game.switches.isSet(4), core.game.isSwitchSet(4));
                                                                                                         game.countdown.set(100);
                                                                                                         game.countdown.add(10);
                                                                                                         game.countdown.subtract(5);
                                                                                                         log.info(game.countdown.get());
                                                                                                         game.countdown.subtract(1000);
                                                                                                         log.info(game.countdown.get());
                                                                                                         game.countdown.set(400000);
                                                                                                         log.info(game.countdown.get());
                                                                                                         game.countdown.set(60);
                                                                                                         game.countdown.pause();
                                                                                                         log.info(game.countdown.isPaused());
                                                                                                         game.countdown.resume();
                                                                                                         started = game.getElapsedTime();
                                                                                                         try {
                                                                                                             game.switches.set(256, true);
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         frames++;
                                                                                                         if (frames === 48) {
                                                                                                             log.info(game.countdown.get(), game.getElapsedTime() - started, game.countdown.isPaused());
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        for (int frame = 0; frame < CLOCK_FRAMES; frame++) {
            scenario.stepFrame();
        }

        Assertions.assertEquals(List.of("false false 0 false",
                                        "false true true",
                                        "105",
                                        "0",
                                        "359999",
                                        "true",
                                        "RangeError",
                                        "57 3 false"), scenario.getInfoMessages(), scenario.getLines()::toString);

        TriggerContext triggers = scenario.getGame().getTriggerContext();

        Assertions.assertNotNull(triggers);
        Assertions.assertFalse(triggers.isSwitchSet(3));
        Assertions.assertTrue(triggers.isSwitchSet(4));
        Assertions.assertEquals(57, triggers.getCountdownTimer());
    }

    @Test
    void core_and_the_globals_of_the_prelude_cannot_be_replaced() throws IOException {
        ScriptGameScenario scenario = start(createGame(), """
                                                          function attempt(change) {
                                                              try {
                                                                  change();
                                                                  return 'ignored';
                                                              } catch (error) {
                                                                  return error.name;
                                                              }
                                                          }
                                                          log.info(attempt(function () { core.unit.getType = null; }), attempt(function () { core.unit = null; }),
                                                                   attempt(function () { core.extra = 1; }), attempt(function () { UnitType.TerranMarine = null; }),
                                                                   attempt(function () { globalThis.game = null; }));
                                                          log.info(typeof core.unit.getType, typeof core.extra, UnitType.TerranMarine.getId(), typeof game.getFrame,
                                                                   Object.keys(core).length, Object.isFrozen(core.unit), Object.isFrozen(core));
                                                          """);

        Assertions.assertEquals(List.of("ignored ignored ignored ignored ignored", "function undefined 0 function 0 true true"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }
}
