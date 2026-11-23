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
 * Tests {@code core.unit} and the {@code Unit} objects of the prelude in a live melee game: reading the start units, shown and raw hit
 * points, creating and killing through the trigger routines, damage with and without the weapon damage routine, one object per unit with one
 * {@code timers} object, {@code exists()} after death, and the doodad state, resource amount and hangar routines.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptUnitCoreTest {
    /**
     * The frames the doodad test runs after its start, so that the disabled unit runs its DisableDoodad order.
     */
    private static final int DOODAD_FRAMES = 8;
    /**
     * The frames the status timer test runs after its start, two status steps of eight frames.
     */
    private static final int STATUS_FRAMES = 16;

    @Test
    void units_read_the_start_units_in_slot_order(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var ids = core.unit.getIds();
                                                                                                         var sorted = ids.every(function (id, index) { return index === 0 || ids[index - 1] < id; });
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         log.info(sorted, ids.length === game.units.getAll().length, game.units.count({type: UnitType.TerranSCV, owner: human}),
                                                                                                                  game.units.count({type: UnitType.ZergDrone}), human.units.getAll().length);
                                                                                                         log.info(commandCenter.getOwner() === human, commandCenter.isCompleted(), commandCenter.getHp(),
                                                                                                                  commandCenter.getMaxHp(), core.unit.getHp(commandCenter.getId()), commandCenter.getShields(),
                                                                                                                  commandCenter.getMaxEnergy(), commandCenter.getX() > 0, commandCenter.getPosition().getY() === commandCenter.getY());
                                                                                                         var drone = game.units.findOne({type: UnitType.ZergDrone});
                                                                                                         log.info(drone.getMaxHp(), drone.getOwner().isEnemyOf(human), drone.isBurrowed(), drone.isCloaked(), drone.isInvincible());
                                                                                                     }
                                                                                                     """);

        Assertions.assertEquals(List.of("true true 4 4 5",
                                        "true true 1500 1500 384000 0 0 true true",
                                        "40 true false false false"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void units_show_hit_points_and_take_writes_through_the_trigger_routines(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         var marine = game.units.create(UnitType.TerranMarine, human, commandCenter.getPosition());
                                                                                                         var id = marine.getId();
                                                                                                         log.info(marine.getType() === UnitType.TerranMarine, marine.getOwner() === human, marine.isCompleted(), marine.getHp(),
                                                                                                                  core.unit.getHp(id), core.dat.units.getHitPoints(core.unit.getType(id)));
                                                                                                         marine.setHp(12.5);
                                                                                                         log.info(marine.getHp(), core.unit.getHp(id));
                                                                                                         marine.setHp(1000);
                                                                                                         core.unit.setHp(id, 0);
                                                                                                         log.info(core.unit.getHp(id));
                                                                                                         marine.setHp(40);
                                                                                                         log.info(marine.setShields(10), marine.setEnergy(10), marine.getShields(), marine.getEnergy());
                                                                                                         marine.setInvincible(true);
                                                                                                         log.info(marine.isInvincible(), core.unit.getStatus(id) !== 0);
                                                                                                         marine.issueOrder(Order.Move, Position.of(marine.getX() + 64, marine.getY()));
                                                                                                         log.info(marine.getOrder().getName());
                                                                                                         marine.setHpPercent(50);
                                                                                                         log.info(marine.getHp(), marine.setShieldsPercent(50), marine.setEnergyPercent(50));
                                                                                                         var zergling = game.units.create(UnitType.ZergZergling, game.players.get(11), commandCenter.getPosition());
                                                                                                         log.info(zergling.getOwner().getId(), zergling.setOwner(human), zergling.getOwner() === human);
                                                                                                         log.info(game.units.create(UnitType.TerranMarine, human, Position.of(-5000, -5000)) !== null);
                                                                                                         try {
                                                                                                             core.unit.create(228, 0, 0, 0);
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        Assertions.assertEquals(List.of("true true true 40 10240 10240",
                                        "12.5 3200",
                                        "1",
                                        "false false 0 0",
                                        "true true",
                                        "Move",
                                        "20 false false",
                                        "11 true true",
                                        "true",
                                        "RangeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void a_unit_keeps_one_object_that_stops_existing_when_it_dies(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var marine;
                                                                                                     var removed;
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         marine = game.units.create(UnitType.TerranMarine, human, commandCenter.getPosition());
                                                                                                         removed = game.units.create(UnitType.TerranMarine, human, commandCenter.getPosition());
                                                                                                         var units = new Map([[marine, 'kept']]);
                                                                                                         log.info(game.units.get(marine.getId()) === marine, game.units.find({type: UnitType.TerranMarine}).indexOf(marine) >= 0,
                                                                                                                  units.get(game.units.get(marine.getId())), marine.exists());
                                                                                                         var timers = marine.timers;
                                                                                                         log.info(timers === game.units.get(marine.getId()).timers, timers !== removed.timers, Object.isFrozen(timers),
                                                                                                                  removed.timers.getRemove());
                                                                                                         removed.timers.setRemove(50);
                                                                                                         log.info(removed.timers.getRemove(), core.unit.getRemoveTimer(removed.getId()));
                                                                                                         log.info(marine.kill(), marine.exists(), game.units.get(marine.getId()), core.unit.exists(marine.getId()),
                                                                                                                  removed.remove(), removed.exists());
                                                                                                         try {
                                                                                                             marine.getHp();
                                                                                                         } catch (error) {
                                                                                                             log.info(error.message === 'The unit ' + marine.getId() + ' does not exist.');
                                                                                                         }
                                                                                                         try {
                                                                                                             marine.timers.getStim();
                                                                                                         } catch (error) {
                                                                                                             log.info(marine.timers === timers, error.message === 'The unit ' + marine.getId() + ' does not exist.');
                                                                                                         }
                                                                                                         try {
                                                                                                             core.unit.getHp(marine.getId());
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         log.info('frame ' + marine.exists() + ' ' + removed.exists() + ' ' + game.units.find({type: UnitType.TerranMarine}).length);
                                                                                                     }
                                                                                                     """);

        scenario.stepFrame();
        scenario.stepFrame();

        Assertions.assertEquals(List.of("true true kept true",
                                        "true true true 0",
                                        "50 50",
                                        "true false null false true false",
                                        "true",
                                        "true true",
                                        "RangeError",
                                        "frame false false 0",
                                        "frame false false 0",
                                        "frame false false 0"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void damage_and_kill_with_a_source_credit_the_kill_to_the_source(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var other = game.units.findOne({type: UnitType.ZergHatchery}).getOwner();
                                                                                                         var position = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human}).getPosition();
                                                                                                         var marine = game.units.create(UnitType.TerranMarine, human, position);
                                                                                                         var first = game.units.create(UnitType.ZergZergling, other, position);
                                                                                                         var second = game.units.create(UnitType.ZergZergling, other, position);
                                                                                                         var third = game.units.create(UnitType.ZergZergling, other, position);
                                                                                                         var kills = human.kills.get(UnitType.ZergZergling);
                                                                                                         log.info(first.damage(10, marine), first.getHp(), first.damage(first.getHp(), marine), first.exists(), marine.getKillCount());
                                                                                                         log.info(second.kill(marine), marine.getKillCount(), third.kill(human), marine.getKillCount(),
                                                                                                                  human.kills.get(UnitType.ZergZergling) - kills);
                                                                                                         game.units.create(UnitType.TerranSiegeTankTankMode, human, position);
                                                                                                         var subunit = game.units.findOne({type: UnitType.TerranSiegeTankTankModeTurret, owner: human});
                                                                                                         log.info(subunit.damage(1), subunit.kill(human));
                                                                                                     }
                                                                                                     """);

        Assertions.assertEquals(List.of("true 25 true false 1", "true 2 true 2 3", "false false"), scenario.getInfoMessages(),
                                scenario.getLines()::toString);
    }

    @Test
    void apply_damage_takes_shields_armor_and_the_damage_type_as_a_weapon_hit(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var marine;
                                                                                                     function attempt(call) {
                                                                                                         try {
                                                                                                             return call();
                                                                                                         } catch (error) {
                                                                                                             return error.name;
                                                                                                         }
                                                                                                     }
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var other = game.units.findOne({type: UnitType.ZergHatchery}).getOwner();
                                                                                                         var position = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human}).getPosition();
                                                                                                         marine = game.units.create(UnitType.TerranMarine, human, position);
                                                                                                         var zealot = game.units.create(UnitType.ProtossZealot, other, position);
                                                                                                         var ultralisk = game.units.create(UnitType.ZergUltralisk, other, position);
                                                                                                         var zergling = game.units.create(UnitType.ZergZergling, other, position);
                                                                                                         log.info(zealot.applyDamage(20, marine), zealot.getShields(), zealot.getHp(),
                                                                                                                  marine.applyDamage(10, other, {damageType: DamageType.Explosive}), marine.getHp());
                                                                                                         log.info(ultralisk.applyDamage(10, marine, {damageType: DamageType.Concussive}), ultralisk.getHp(),
                                                                                                                  ultralisk.applyDamage(10, marine, {damageType: DamageType.IgnoreArmor}), ultralisk.getHp());
                                                                                                         log.info(zergling.applyDamage(6, marine, {weapon: WeaponType.GaussRifle}), zergling.getHp(),
                                                                                                                  zergling.applyDamage(100, marine), zergling.exists(), marine.getKillCount());
                                                                                                         game.units.create(UnitType.TerranSiegeTankTankMode, human, position);
                                                                                                         var subunit = game.units.findOne({type: UnitType.TerranSiegeTankTankModeTurret, owner: human});
                                                                                                         log.info(subunit.applyDamage(1, human), attempt(function () { return marine.applyDamage(1); }),
                                                                                                                  attempt(function () { return marine.applyDamage(1, human, {damageType: 3}); }),
                                                                                                                  attempt(function () { return core.unit.applyDamage(marine.getId(), 256, -1, 12, -1, 3); }),
                                                                                                                  attempt(function () { return core.unit.applyDamage(marine.getId(), 256, -1, 0, -1, -1); }),
                                                                                                                  attempt(function () { return core.unit.applyDamage(marine.getId(), 256, -1, 0, -1, 5); }),
                                                                                                                  attempt(function () { return core.unit.applyDamage(marine.getId(), -1, -1, 0, -1, 3); }), marine.getHp());
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         log.info('local ' + attempt(function () { return marine.applyDamage(1, marine); }));
                                                                                                     }
                                                                                                     """);

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("true 40 100 true 35",
                                        "true 397.75 true 387.75",
                                        "true 29 true false 1",
                                        "false TypeError TypeError RangeError RangeError RangeError RangeError 35",
                                        "local TypeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void status_timers_take_the_apply_and_end_routines_of_their_effects(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var marine;
                                                                                                     var hp;
                                                                                                     var frames = 0;
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var position = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human}).getPosition();
                                                                                                         var ensnared = game.units.create(UnitType.TerranVulture, human, position);
                                                                                                         var stimmed = game.units.create(UnitType.TerranVulture, human, position);
                                                                                                         var speed = ensnared.getTopSpeed();
                                                                                                         ensnared.timers.setEnsnare(20);
                                                                                                         stimmed.timers.setStim(20);
                                                                                                         log.info(ensnared.timers.getEnsnare(), ensnared.getTopSpeed() < speed, stimmed.timers.getStim(), stimmed.getTopSpeed() > speed);
                                                                                                         ensnared.timers.setEnsnare(0);
                                                                                                         stimmed.timers.setStim(0);
                                                                                                         log.info(ensnared.timers.getEnsnare(), ensnared.getTopSpeed() === speed, stimmed.getTopSpeed() === speed);
                                                                                                         marine = game.units.create(UnitType.TerranMarine, human, position);
                                                                                                         marine.timers.setLockdown(30);
                                                                                                         log.info(marine.timers.getLockdown(), marine.isDisabled());
                                                                                                         marine.timers.setLockdown(0);
                                                                                                         marine.timers.setStasis(10);
                                                                                                         log.info(marine.isDisabled(), marine.timers.getStasis(), marine.isInvincible());
                                                                                                         marine.timers.setStasis(0);
                                                                                                         marine.timers.setMaelstrom(4);
                                                                                                         log.info(marine.isDisabled(), marine.isInvincible(), marine.timers.getMaelstrom());
                                                                                                         marine.timers.setMaelstrom(0);
                                                                                                         marine.setDefensiveMatrixHp(250);
                                                                                                         marine.timers.setDefensiveMatrix(10);
                                                                                                         log.info(marine.isDisabled(), marine.timers.getDefensiveMatrix(), marine.getDefensiveMatrixHp(), core.unit.getDefensiveMatrixHp(marine.getId()));
                                                                                                         marine.timers.setDefensiveMatrix(0);
                                                                                                         marine.timers.setIrradiate(5);
                                                                                                         marine.setAcidSporeCount(3);
                                                                                                         log.info(marine.timers.getDefensiveMatrix(), marine.getDefensiveMatrixHp(), marine.timers.getIrradiate(), marine.getAcidSporeCount());
                                                                                                         marine.timers.setIrradiate(0);
                                                                                                         marine.setAcidSporeCount(0);
                                                                                                         marine.timers.setStorm(2);
                                                                                                         marine.timers.setPlague(3);
                                                                                                         hp = marine.getHp();
                                                                                                         log.info(marine.timers.getIrradiate(), marine.getAcidSporeCount(), marine.timers.getStorm(), marine.timers.getPlague());
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         frames++;
                                                                                                         if (frames === 16) {
                                                                                                             log.info(marine.getHp() < hp, marine.timers.getPlague() < 3, marine.timers.getStorm() < 2);
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        for (int frame = 0; frame < STATUS_FRAMES; frame++) {
            scenario.stepFrame();
        }

        Assertions.assertEquals(List.of("20 true 20 true",
                                        "0 true true",
                                        "30 true",
                                        "true 10 true",
                                        "true false 4",
                                        "false 10 250 64000",
                                        "0 0 5 3",
                                        "0 0 2 3",
                                        "true true true"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void unit_fields_read_and_write_as_eud_writes_and_the_type_changes_through_the_morph_routine(
            @NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var marine;
                                                                                                     var frames = 0;
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var enemy = game.players.get(1);
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         var position = commandCenter.getPosition();
                                                                                                         marine = game.units.create(UnitType.TerranMarine, human, position);
                                                                                                         marine.setGroundCooldown(40);
                                                                                                         marine.setSpellCooldown(7);
                                                                                                         marine.setKillCount(3);
                                                                                                         marine.setParasitedBy(enemy, true);
                                                                                                         marine.setBlindedBy(enemy, true);
                                                                                                         marine.setDirection(64);
                                                                                                         marine.setMoveTarget(Position.of(100, 200));
                                                                                                         log.info(marine.getGroundCooldown(), marine.getAirCooldown(), marine.getSpellCooldown(), marine.getKillCount(),
                                                                                                                  marine.isParasitedBy(enemy), marine.isParasitedBy(human), core.unit.getParasiteFlags(marine.getId()), marine.isBlind(),
                                                                                                                  marine.getDirection(), marine.getMoveTarget().getX(), marine.getMoveTarget().getY());
                                                                                                         marine.setParasitedBy(enemy, false);
                                                                                                         marine.setBlindedBy(enemy, false);
                                                                                                         log.info(marine.isParasitedBy(enemy), marine.isBlind(), commandCenter.getResearch(), commandCenter.getUpgrade(),
                                                                                                                  commandCenter.getAddon(), commandCenter.getBuildQueue().length, commandCenter.getRemainingBuildTime());
                                                                                                         var rally = Position.of(position.getX() + 96, position.getY());
                                                                                                         log.info(commandCenter.setRallyPosition(rally), commandCenter.getRallyPosition().getX() === rally.getX(),
                                                                                                                  commandCenter.setRallyUnit(marine), commandCenter.getRallyUnit() === marine, marine.setRallyPosition(rally),
                                                                                                                  commandCenter.setRallyUnit(null), commandCenter.getRallyUnit());
                                                                                                         var scv = game.units.findOne({type: UnitType.TerranSCV, owner: human});
                                                                                                         var scvs = human.units.count(UnitType.TerranSCV);
                                                                                                         var marines = human.units.count(UnitType.TerranMarine);
                                                                                                         scv.setHp(30);
                                                                                                         log.info(scv.setType(UnitType.TerranMarine), scv.exists(), game.units.get(scv.getId()) === scv, scv.getType() === UnitType.TerranMarine,
                                                                                                                  scv.getHp(), human.units.count(UnitType.TerranSCV) === scvs - 1, human.units.count(UnitType.TerranMarine) === marines + 1);
                                                                                                         var tank = game.units.create(UnitType.TerranSiegeTankTankMode, human, position);
                                                                                                         log.info(tank.setType(UnitType.TerranMarine), tank.setType(UnitType.TerranSiegeTankSiegeMode), tank.getType().getName(),
                                                                                                                  marine.setType(UnitType.TerranSiegeTankTankMode));
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         frames++;
                                                                                                         if (frames === 8) {
                                                                                                             log.info(marine.getGroundCooldown() < 40);
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        for (int frame = 0; frame < DOODAD_FRAMES; frame++) {
            scenario.stepFrame();
        }

        Assertions.assertEquals(List.of("40 0 7 3 true false 2 true 64 100 200",
                                        "false false null null null 0 0",
                                        "true true true true false true null",
                                        "true true true true 20 true true",
                                        "false true Terran Siege Tank Siege Mode false",
                                        "true"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void units_take_the_doodad_resource_and_hangar_routines(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var doodad;
                                                                                                     var frames = 0;
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         var mineral = game.units.findOne(function (unit) {
                                                                                                             var type = unit.getType().getId();
                                                                                                             return type >= 176 && type <= 178;
                                                                                                         });
                                                                                                         log.info(mineral.getResources() > 0, mineral.setResources(1234), mineral.getResources(), core.unit.getResources(mineral.getId()),
                                                                                                                  commandCenter.setResources(5));
                                                                                                         var carrier = game.units.create(UnitType.ProtossCarrier, human, commandCenter.getPosition());
                                                                                                         log.info(carrier.getHangarCount(), carrier.addHangarCount(3), carrier.getHangarCount(), carrier.addHangarCount(10),
                                                                                                                  carrier.getHangarCount(), commandCenter.addHangarCount(1));
                                                                                                         doodad = game.units.create(UnitType.TerranMarine, human, commandCenter.getPosition());
                                                                                                         log.info(doodad !== null, doodad.isDisabled());
                                                                                                         doodad.disableDoodad();
                                                                                                         try {
                                                                                                             mineral.setResources(65536);
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         frames++;
                                                                                                         if (frames === 8) {
                                                                                                             log.info(doodad.isDisabled());
                                                                                                             doodad.enableDoodad();
                                                                                                             log.info(doodad.isDisabled());
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        for (int frame = 0; frame < DOODAD_FRAMES; frame++) {
            scenario.stepFrame();
        }

        Assertions.assertEquals(List.of("true true 1234 1234 false",
                                        "0 true 3 true 4 false",
                                        "true false",
                                        "RangeError",
                                        "true",
                                        "false"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void units_queue_orders_and_keep_their_secondary_order_and_gains(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var marine;
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         marine = game.units.create(UnitType.TerranMarine, human, commandCenter.getPosition());
                                                                                                         var position = marine.getPosition();
                                                                                                         marine.issueOrder(Order.Move, Position.of(position.getX() + 64, position.getY()));
                                                                                                         marine.issueOrder(Order.Move, Position.of(position.getX() + 128, position.getY()), {queued: true});
                                                                                                         marine.issueOrder(Order.AttackUnit, commandCenter, {queued: true});
                                                                                                         var queue = marine.getOrderQueue();
                                                                                                         log.info(marine.getOrder().getName(), queue.length, queue[0].order.getName(), queue[0].position.getX() - position.getX(),
                                                                                                                  queue[0].target, queue[0].unitType, queue[1].target === commandCenter, Object.isFrozen(queue[0]));
                                                                                                         var record = core.unit.getOrderQueue(marine.getId())[1];
                                                                                                         log.info(record.order === Order.AttackUnit.getId(), record.target === commandCenter.getId(),
                                                                                                                  record.targetGeneration === core.unit.getGeneration(commandCenter.getId()), record.unitType);
                                                                                                         marine.issueOrder(Order.Stop);
                                                                                                         log.info(marine.getOrderQueue().length);
                                                                                                         log.info(commandCenter.getSecondaryOrder().getName(), commandCenter.getSecondaryOrderState());
                                                                                                         marine.setSecondaryOrder(Order.Hallucination2);
                                                                                                         log.info(marine.getSecondaryOrder().getName(), marine.getSecondaryOrderState());
                                                                                                         marine.setSecondaryOrder(Order.Nothing);
                                                                                                         log.info(marine.getHpGain(), marine.getShieldGain(), marine.getPreviousHp());
                                                                                                         marine.setHpGain(1.5);
                                                                                                         core.unit.setShieldGain(marine.getId(), 64);
                                                                                                         log.info(core.unit.getHpGain(marine.getId()), marine.getShieldGain());
                                                                                                         try {
                                                                                                             core.unit.setHpGain(marine.getId(), 0x10000);
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                         try {
                                                                                                             core.unit.issueOrder(marine.getId(), Order.Move.getId(), -1, 0, 0);
                                                                                                         } catch (error) {
                                                                                                             log.info(error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         log.info('local ' + marine.getOrderQueue().length + ' ' + marine.getSecondaryOrder().getName());
                                                                                                         try {
                                                                                                             marine.issueOrder(Order.Move, null, {queued: true});
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        scenario.stepFrame();
        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("Move 2 Move 128 null null true true",
                                        "true true true -1",
                                        "0",
                                        "Nothing 0",
                                        "Hallucination2 0",
                                        "0.1015625 0 1",
                                        "384 0.25",
                                        "RangeError",
                                        "TypeError",
                                        "local 0 Nothing",
                                        "local TypeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void the_unit_finder_finds_units_and_units_tell_who_sees_them(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var commandCenter;
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         var x = commandCenter.getX();
                                                                                                         var y = commandCenter.getY();
                                                                                                         var inRectangle = game.units.findInRectangle(Position.of(x + 5, y + 5), Position.of(x - 5, y - 5));
                                                                                                         var scvs = game.units.findInRadius(commandCenter.getPosition(), 128, {type: UnitType.TerranSCV});
                                                                                                         log.info(inRectangle.indexOf(commandCenter) >= 0, core.unit.findAtPoint(x, y).indexOf(commandCenter.getId()) >= 0,
                                                                                                                  scvs.length, game.units.findInRadius(commandCenter.getPosition(), 0, {type: UnitType.TerranCommandCenter}).length,
                                                                                                                  core.unit.findInRectangle(0, 0, 1, 1).length);
                                                                                                         log.info(commandCenter.getAiStrength(false) === core.unit.getGroundStrength(commandCenter.getId()),
                                                                                                                  commandCenter.getAiStrength(true) === core.unit.getAirStrength(commandCenter.getId()),
                                                                                                                  UnitType.TerranMarine.getAiStrength(false) > 0, UnitType.TerranMarine.getAiStrength(true) > 0,
                                                                                                                  UnitType.TerranVulture.getAiStrength(true));
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         try {
                                                                                                             game.units.findInRadius(commandCenter.getPosition(), 32);
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name);
                                                                                                         }
                                                                                                         try {
                                                                                                             core.unit.findAtPoint(0, 0);
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """);

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("true true 4 1 0",
                                        "true true true true 0",
                                        "local TypeError",
                                        "local TypeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void the_same_order_and_unit_field_writes_give_the_same_game_state(@NotNull InstallationTestResources resources) throws IOException {
        String changes = """
                         function onStart() {
                             var human = game.players.getHumans()[0];
                             var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                             var marine = game.units.create(UnitType.TerranMarine, human, commandCenter.getPosition());
                             marine.issueOrder(Order.Move, Position.of(marine.getX() + 64, marine.getY()));
                             marine.issueOrder(Order.Move, Position.of(marine.getX() + 128, marine.getY()), {queued: true});
                             marine.setSecondaryOrder(Order.Hallucination2);
                             marine.setHpGain(1.5);
                             game.units.findInRadius(commandCenter.getPosition(), 128);
                         }
                         """;
        long first = runUnitChanges(resources, changes);
        long second = runUnitChanges(resources, changes);
        long unchanged = runUnitChanges(resources, "");

        Assertions.assertEquals(first, second);
        Assertions.assertNotEquals(first, unchanged);
    }

    /**
     * Runs a melee game with a script for the status frames.
     *
     * @return the state hash of the game at the end
     */
    private static long runUnitChanges(@NotNull InstallationTestResources resources, @NotNull String source) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), source);

        for (int frame = 0; frame < STATUS_FRAMES; frame++) {
            scenario.stepFrame();
        }

        return scenario.getGame().computeStateHash();
    }
}
