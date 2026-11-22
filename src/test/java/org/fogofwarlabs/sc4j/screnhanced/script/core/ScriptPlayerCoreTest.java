package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerEvents;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests {@code core.player} and the {@code Player} objects of the prelude in a live melee game: identity, resources through the Set
 * Resources routine, supply, alliances, printed texts, game results, the centering of the local view, the counters and unit removals of the
 * trigger routines, AI scripts, mission objectives as registered map strings, the local display routines, the upgrade, technology and unit
 * availability state with its effect on the player's units and production, and the player colors, presentation outside the game state.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptPlayerCoreTest {
    /**
     * The score type of the custom score.
     */
    private static final int CUSTOM_SCORE = 7;
    /**
     * The frames the counter test runs after its start, so that the killed units are gone.
     */
    private static final int COUNTED_FRAMES = 24;
    /**
     * The frames the determinism test runs after its start.
     */
    private static final int CHANGE_FRAMES = 4;

    @Test
    void upgrade_levels_refresh_the_existing_units_and_technologies_change_their_state(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var position = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human}).getPosition();
                                                                                                         var vulture = game.units.create(UnitType.TerranVulture, human, position);
                                                                                                         var speed = vulture.getTopSpeed();
                                                                                                         var thrusters = UpgradeType.IonThrusters;
                                                                                                         log.info(human.upgrades.getLevel(thrusters), human.upgrades.getMaxLevel(thrusters), human.upgrades.isInProgress(thrusters));
                                                                                                         human.upgrades.setLevel(thrusters, 1);
                                                                                                         log.info(human.upgrades.getLevel(thrusters), vulture.getTopSpeed() > speed, core.player.getUpgradeLevel(human.getId(), thrusters.getId()));
                                                                                                         human.upgrades.setMaxLevel(UpgradeType.TerranInfantryWeapons, 5);
                                                                                                         log.info(human.upgrades.getMaxLevel(UpgradeType.TerranInfantryWeapons), game.players.get(2).upgrades.getMaxLevel(UpgradeType.TerranInfantryWeapons));
                                                                                                         log.info(human.techs.isResearched(TechType.StimPacks), human.techs.isAvailable(TechType.Lockdown), human.techs.isInProgress(TechType.StimPacks));
                                                                                                         human.techs.setResearched(TechType.StimPacks, true);
                                                                                                         human.techs.setAvailable(TechType.Lockdown, false);
                                                                                                         log.info(human.techs.isResearched(TechType.StimPacks), human.techs.isAvailable(TechType.Lockdown));
                                                                                                         human.units.setAvailable(UnitType.TerranMarine, true);
                                                                                                         log.info(human.units.isAvailable(UnitType.TerranMarine));
                                                                                                         try {
                                                                                                             human.units.setAvailable(UnitType.TerranMarine, false);
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        Assertions.assertEquals(List.of("0 1 false", "1 true 1", "5 3", "false true false", "true false", "true", "TypeError"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void unit_availability_blocks_production_in_a_map_settings_game(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startUseMapSettings(resources.getGameData(), """
                                                                                                              var human;
                                                                                                              var frames = 0;
                                                                                                              function onStart() {
                                                                                                                  human = game.players.getHumans()[0];
                                                                                                                  var barracks = null;
                                                                                                                  for (var x = 256; barracks === null && x < game.map.getWidth(); x += 256) {
                                                                                                                      for (var y = 256; barracks === null && y < game.map.getHeight(); y += 256) {
                                                                                                                          barracks = game.units.create(UnitType.TerranBarracks, human, Position.of(x, y));
                                                                                                                      }
                                                                                                                  }
                                                                                                                  log.info(barracks.getId(), human.units.isAvailable(UnitType.TerranMarine));
                                                                                                                  human.units.setAvailable(UnitType.TerranMarine, false);
                                                                                                                  log.info(human.units.isAvailable(UnitType.TerranMarine));
                                                                                                              }
                                                                                                              function onFrame() {
                                                                                                                  frames++;
                                                                                                                  if (frames > 1) {
                                                                                                                      human.units.setAvailable(UnitType.TerranMarine, true);
                                                                                                                  }
                                                                                                              }
                                                                                                              """);
        Assertions.assertFalse(scenario.getInfoMessages().isEmpty(), scenario.getLines()::toString);

        Game game = scenario.getGame();
        Unit barracks = game.getUnitSystem().requireUnitPool().getUnit(Integer.parseInt(scenario.getInfoMessages().getFirst().split(" ")[0]));
        boolean unavailable = game.getUnitProduction().canBuild(barracks, ScrUnitIds.TERRAN_MARINE);

        scenario.stepFrame();

        Assertions.assertFalse(unavailable);
        Assertions.assertTrue(game.getUnitProduction().canBuild(barracks, ScrUnitIds.TERRAN_MARINE));
        Assertions.assertEquals(List.of(barracks.getIndex() + " true", "false"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void players_read_and_change_resources_supply_and_alliances(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var humans = game.players.getHumans();
                                                                                                         var human = humans[0];
                                                                                                         var computer = game.units.findOne({type: UnitType.ZergHatchery}).getOwner();
                                                                                                         log.info(humans.length, human === game.players.get(human.getId()), human.getName(), human.getRace(), computer.isHuman(),
                                                                                                                  computer.getRace(), game.players.getAll().length, game.players.get(12));
                                                                                                         log.info(human.upgrades === game.players.get(human.getId()).upgrades, human.deaths !== computer.deaths,
                                                                                                                  human.units.getAll().length === game.units.count({owner: human}) && human.units.getAll().length > 0,
                                                                                                                  human.units.getAll().every(function (unit) { return unit.getOwner() === human; }));
                                                                                                         log.info(human.getMinerals(), human.getGas());
                                                                                                         human.setMinerals(500);
                                                                                                         human.addMinerals(25);
                                                                                                         human.addGas(-10);
                                                                                                         core.player.setGas(game.players.get(11).getId(), 99);
                                                                                                         log.info(human.getMinerals(), human.getGas(), game.players.get(11).getGas());
                                                                                                         human.subtractMinerals(25);
                                                                                                         human.subtractGas(5);
                                                                                                         log.info(human.getMinerals(), human.getGas());
                                                                                                         log.info(computer.lose(), computer.win(), computer.lose(), computer.draw());
                                                                                                         log.info(human.getSupplyUsed(), human.getSupplyProvided(), human.getSupplyMax(), core.player.getSupplyUsed(human.getId(), 1),
                                                                                                                  computer.getSupplyProvided());
                                                                                                         log.info(human.isEnemyOf(computer), human.isAlliedWith(human), core.player.getAlliance(human.getId(), computer.getId()));
                                                                                                         game.print('to all');
                                                                                                         human.print('to the human');
                                                                                                         computer.print('to the computer');
                                                                                                     }
                                                                                                     """);

        Assertions.assertEquals(List.of("1 true tester 1 false 0 12 null",
                                        "true true true true",
                                        "50 0",
                                        "525 -10 0",
                                        "500 -15",
                                        "true true false false",
                                        "4 10 200 8 9",
                                        "true true 0"), scenario.getInfoMessages(), scenario.getLines()::toString);

        MessageLines lines = scenario.getGame().getInterfaceLink().getMessageLines();
        List<String> texts = new ArrayList<>();

        for (int line = 0; line < MessageLines.LINE_COUNT; line++) {
            texts.add(new String(lines.getLine(line), StandardCharsets.UTF_8));
        }

        Assertions.assertTrue(texts.stream().anyMatch(text -> text.contains("to all")), texts::toString);
        Assertions.assertTrue(texts.stream().anyMatch(text -> text.contains("to the human")), texts::toString);
        Assertions.assertTrue(texts.stream().noneMatch(text -> text.contains("to the computer")), texts::toString);
    }

    @Test
    void center_view_centers_the_local_screen_for_the_local_player_only(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var calls = 0;
                                                                                                     function onFrame() {
                                                                                                         calls++;
                                                                                                         if (calls === 2) {
                                                                                                             game.units.findOne({type: UnitType.ZergHatchery}).getOwner().centerView(Position.of(64, 64));
                                                                                                         } else if (calls === 3) {
                                                                                                             game.players.getHumans()[0].centerView(Position.of(1000, 800));
                                                                                                         }
                                                                                                     }
                                                                                                     """);
        LocalView view = scenario.getGame().getInterfaceLink().getLocalView();
        int mapWidth = scenario.getGame().getMap().getWidth() * TerrainTileset.TILE_SIZE;
        int mapHeight = scenario.getGame().getMap().getHeight() * TerrainTileset.TILE_SIZE;

        view.setView(0, 0, 0, 0);
        view.setScreenGeometry(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                               mapWidth - RunningGameScenario.VIEWPORT_WIDTH, mapHeight - RunningGameScenario.VIEWPORT_HEIGHT);
        scenario.stepFrame();

        Assertions.assertEquals(0, view.getScreenX(), "the computer's view is not local");
        Assertions.assertEquals(0, view.getScreenY());

        scenario.stepFrame();

        Assertions.assertEquals(view.calculateCenteredScreenX(1000), view.getScreenX());
        Assertions.assertEquals(view.calculateCenteredScreenY(800), view.getScreenY());
        Assertions.assertTrue(view.takeWritten(), "the client applies the centered screen");
        Assertions.assertEquals(List.of(), scenario.getLines());
    }

    @Test
    void counters_and_unit_removals_take_the_trigger_routines(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var human, computer;
                                                                                                     var frames = 0;
                                                                                                     function onStart() {
                                                                                                         human = game.players.getHumans()[0];
                                                                                                         computer = game.units.findOne({type: UnitType.ZergHatchery}).getOwner();
                                                                                                         log.info(human.getId(), computer.getId());
                                                                                                         human.deaths.set(UnitType.TerranMarine, 7);
                                                                                                         human.deaths.add(UnitType.TerranMarine, 3);
                                                                                                         human.deaths.subtract(UnitType.TerranMarine, 4);
                                                                                                         computer.deaths.subtract(UnitType.ZergZergling, 4);
                                                                                                         log.info(human.deaths.get(UnitType.TerranMarine), core.player.getDeaths(human.getId(), UnitType.TerranMarine.getId()),
                                                                                                                  computer.deaths.get(UnitType.ZergZergling), human.deaths.get(UnitGroup.Men));
                                                                                                         human.score.set(ScoreType.Custom, 50);
                                                                                                         human.score.add(ScoreType.Custom, 5);
                                                                                                         human.score.subtract(ScoreType.Kills, 1);
                                                                                                         log.info(human.score.get(ScoreType.Custom), human.score.get(ScoreType.Kills), human.kills.get(UnitGroup.Men),
                                                                                                                  human.getResources(ResourceType.Minerals) === human.getMinerals(), human.getResources(ResourceType.MineralsAndGas));
                                                                                                         var everywhere = game.map.locations.get(0);
                                                                                                         everywhere.setBounds(0, 0, game.map.getWidth(), game.map.getHeight());
                                                                                                         log.info(human.units.count(UnitType.TerranSCV), human.units.count(UnitGroup.Buildings, {completed: true}),
                                                                                                                  human.units.count(UnitType.TerranSCV, {location: everywhere}), computer.units.count(UnitType.ZergDrone),
                                                                                                                  human.countOpponents(), computer.countOpponents());
                                                                                                         human.units.remove(UnitType.TerranSCV, {count: 1});
                                                                                                         human.units.kill(UnitType.TerranSCV, {location: everywhere, count: 2});
                                                                                                         computer.units.kill(UnitType.ZergDrone);
                                                                                                         log.info(human.units.count(UnitType.TerranSCV));
                                                                                                         try {
                                                                                                             human.deaths.get(UnitType.TerranMarine.getGroundWeapon());
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                         try {
                                                                                                             core.player.setScore(8, 0, 1);
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         frames++;
                                                                                                         if (frames === 24) {
                                                                                                             log.info(human.units.count(UnitType.TerranSCV), computer.units.count(UnitType.ZergDrone), human.deaths.get(UnitType.TerranSCV),
                                                                                                                      computer.deaths.get(UnitType.ZergDrone), human.deaths.get(UnitGroup.Men));
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        for (int frame = 0; frame < COUNTED_FRAMES; frame++) {
            scenario.stepFrame();
        }

        Assertions.assertEquals(List.of("0 1",
                                        "6 6 0 0",
                                        "55 0 0 true 50",
                                        "4 1 4 4 1 1",
                                        "4",
                                        "TypeError",
                                        "RangeError",
                                        "1 0 0 0 0"), scenario.getInfoMessages(), scenario.getLines()::toString);

        TriggerContext triggers = scenario.getGame().getTriggerContext();

        Assertions.assertNotNull(triggers);
        Assertions.assertEquals(6, triggers.getDeaths(0, ScrUnitIds.TERRAN_MARINE));
        Assertions.assertEquals(55, triggers.getScore(0, CUSTOM_SCORE));
        Assertions.assertEquals(1, triggers.countCommandedUnits(0, ScrUnitIds.TERRAN_SCV, false));
    }

    @Test
    void alliances_and_ai_scripts_take_the_trigger_routines(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var computer = game.units.findOne({type: UnitType.ZergHatchery}).getOwner();
                                                                                                         log.info(human.getAlliance(computer) === Alliance.Enemy, Alliance.AlliedVictory.getId(), String(Alliance.Ally));
                                                                                                         human.setAlliance(computer, Alliance.AlliedVictory);
                                                                                                         log.info(human.getAlliance(computer) === Alliance.AlliedVictory, computer.getAlliance(human) === Alliance.Enemy,
                                                                                                                  human.isAlliedWith(computer), human.countOpponents());
                                                                                                         log.info(human.runAiScript('EnBk'), human.runAiScript('TMCx'), computer.runAiScript('EnBk', game.map.locations.get(0)));
                                                                                                         try {
                                                                                                             human.runAiScript('EnB');
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                         try {
                                                                                                             game.players.get(8).setAlliance(human, Alliance.Ally);
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        Assertions.assertEquals(List.of("true 2 Ally",
                                        "true true true 1",
                                        "true false true",
                                        "RangeError",
                                        "RangeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertEquals(PlayerRelations.ALLIED_VICTORY, scenario.getGame().getPlayerTable().requirePlayerRelations().getAlliance(0, 1));
    }

    @Test
    void mission_objectives_register_each_text_once(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var computer = game.units.findOne({type: UnitType.ZergHatchery}).getOwner();
                                                                                                         human.setMissionObjectives('적 기지를 파괴하십시오.');
                                                                                                         computer.setMissionObjectives('적 기지를 파괴하십시오.');
                                                                                                         human.setMissionObjectives('적 기지를 파괴하십시오.');
                                                                                                         game.players.get(2).setMissionObjectives('');
                                                                                                         try {
                                                                                                             human.setMissionObjectives('a' + String.fromCharCode(0) + 'b');
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """);
        TriggerContext triggers = scenario.getGame().getTriggerContext();
        MapStringSection strings = scenario.getGame().getInterfaceLink().getMessageLines().getMapStringSection();

        Assertions.assertNotNull(triggers);
        Assertions.assertNotNull(strings);
        Assertions.assertEquals(List.of("RangeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertEquals(1, strings.getRegisteredStringCount());

        int stringIndex = triggers.getMissionObjectivesStringIndex(0);

        Assertions.assertEquals(stringIndex, triggers.getMissionObjectivesStringIndex(1));
        Assertions.assertEquals(0, triggers.getMissionObjectivesStringIndex(2));
        Assertions.assertEquals("적 기지를 파괴하십시오.", strings.getString(stringIndex));
    }

    @Test
    void display_routines_show_for_the_local_player_only(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var computer = game.units.findOne({type: UnitType.ZergHatchery}).getOwner();
                                                                                                         [computer, human].forEach(function (player) {
                                                                                                             player.print('quiet', {forced: false});
                                                                                                             player.pingMinimap(Position.of(320, 480));
                                                                                                             player.playSound('sound/custom.wav');
                                                                                                             player.showTalkingPortrait(UnitType.TerranMarine, 24);
                                                                                                         });
                                                                                                         human.setUnitSpeechMuted(true);
                                                                                                     }
                                                                                                     """);
        TriggerContext triggers = scenario.getGame().getTriggerContext();

        Assertions.assertNotNull(triggers);

        TriggerEvents events = triggers.getEvents();
        List<String> shown = new ArrayList<>();

        for (int index = 0; index < events.getCount(); index++) {
            byte[] path = events.getPath(index);

            shown.add(events.getKind(index) + " " + events.getFirst(index) + " " + events.getSecond(index) + " " + events.getFourth(index) +
                      (path == null ? "" : " " + new String(path, StandardCharsets.UTF_8)));
        }

        Assertions.assertEquals(List.of(TriggerEvents.TEXT + " 0 0 0",
                                        TriggerEvents.MINIMAP_PING + " 320 480 0",
                                        TriggerEvents.WAV + " 0 0 0 sound/custom.wav",
                                        TriggerEvents.TRANSMISSION + " 0 -1 1008",
                                        TriggerEvents.UNIT_SPEECH_MUTE + " 0 0 0"), shown, scenario.getLines()::toString);
    }

    @Test
    void players_keep_their_gathered_resources_pauses_and_start_locations(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var human;
                                                                                                     function onStart() {
                                                                                                         human = game.players.getHumans()[0];
                                                                                                         var start = human.getStartLocation();
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         log.info(start.getX() === core.player.getStartLocationX(human.getId()), start.getY() === core.player.getStartLocationY(human.getId()),
                                                                                                                  start.getX() === commandCenter.getX(), start.getY() === commandCenter.getY(), game.players.get(11).getStartLocation(),
                                                                                                                  core.player.getStartLocationX(11));
                                                                                                         log.info(human.setStartLocation(Position.of(100, 200)), String(human.getStartLocation()),
                                                                                                                  game.players.get(11).setStartLocation(Position.of(1, 1)));
                                                                                                         log.info(human.getGatheredMinerals(), human.getGatheredGas());
                                                                                                         human.setGatheredMinerals(1234);
                                                                                                         human.setGatheredGas(56);
                                                                                                         log.info(human.getGatheredMinerals(), core.player.getGatheredGas(human.getId()), human.getMinerals());
                                                                                                         log.info(human.getPausesLeft());
                                                                                                         human.setPausesLeft(1);
                                                                                                         log.info(human.getPausesLeft(), core.player.getPausesLeft(human.getId()));
                                                                                                         [function () { game.players.get(8).getPausesLeft(); }, function () { core.player.setPausesLeft(0, 256); },
                                                                                                          function () { core.player.setStartLocation(0, -1, 0); }].forEach(function (action) {
                                                                                                             try {
                                                                                                                 action();
                                                                                                             } catch (error) {
                                                                                                                 log.info(error.name);
                                                                                                             }
                                                                                                         });
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         try {
                                                                                                             human.setPausesLeft(2);
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name + ' ' + human.getPausesLeft() + ' ' + human.getGatheredMinerals());
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("true true true true null -1",
                                        "true (100, 200) false",
                                        "50 0",
                                        "1234 56 50",
                                        "3",
                                        "1 1",
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "local TypeError 1 1234"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void player_colors_are_read_and_written_in_shared_and_local_code(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var human;
                                                                                                     function onStart() {
                                                                                                         human = game.players.getHumans()[0];
                                                                                                         var row = human.getColor();
                                                                                                         log.info(row.length, row[0] === core.player.getUnitColor(human.getId(), 0),
                                                                                                                  human.getMinimapColor() === core.player.getMinimapColor(human.getId()));
                                                                                                         human.setColor([1, 2, 3, 4, 5, 6, 7, 8]);
                                                                                                         human.setMinimapColor(9);
                                                                                                         log.info(human.getColor().join(','), human.getMinimapColor());
                                                                                                         [function () { human.setColor([1, 2]); }, function () { core.player.setUnitColor(0, 8, 1); },
                                                                                                          function () { core.player.setMinimapColor(0, 256); }, function () { core.player.getUnitColor(12, 0); }].forEach(function (call) {
                                                                                                             try {
                                                                                                                 call();
                                                                                                             } catch (error) {
                                                                                                                 log.info(error.name);
                                                                                                             }
                                                                                                         });
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         core.player.setUnitColor(human.getId(), 7, 200);
                                                                                                         log.info('local ' + human.getColor()[7]);
                                                                                                     }
                                                                                                     """);

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("8 true true",
                                        "1,2,3,4,5,6,7,8 9",
                                        "TypeError",
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "local 200"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void player_colors_change_no_game_state(@NotNull InstallationTestResources resources) throws IOException {
        String changes = """
                         function onStart() {
                             var human = game.players.getHumans()[0];
                             human.setColor([1, 2, 3, 4, 5, 6, 7, 8]);
                             human.setMinimapColor(9);
                         }
                         """;

        Assertions.assertEquals(runPlayerChanges(resources, ""), runPlayerChanges(resources, changes));
    }

    @Test
    void the_same_player_state_writes_give_the_same_game_state(@NotNull InstallationTestResources resources) throws IOException {
        String changes = """
                         function onStart() {
                             var human = game.players.getHumans()[0];
                             human.setGatheredMinerals(1234);
                             human.setPausesLeft(1);
                             human.setStartLocation(Position.of(100, 200));
                         }
                         """;
        long first = runPlayerChanges(resources, changes);
        long second = runPlayerChanges(resources, changes);
        long unchanged = runPlayerChanges(resources, "");

        Assertions.assertEquals(first, second);
        Assertions.assertNotEquals(first, unchanged);
    }

    /**
     * Runs a melee game with a script for four frames.
     *
     * @return the state hash of the game at the end
     */
    private static long runPlayerChanges(@NotNull InstallationTestResources resources, @NotNull String source) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), source);

        for (int frame = 0; frame < CHANGE_FRAMES; frame++) {
            scenario.stepFrame();
        }

        return scenario.getGame().computeStateHash();
    }
}
