package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.combat.ProjectilePhase;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.BulletCreatedEvent;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationUnit;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitHandle;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.List;

/**
 * Tests {@code core.bullet}, {@code core.unit.useWeapon}, the {@code Bullet} objects of the prelude and the bullet of the bullet event records
 * in a live melee game: a created bullet and its fields, the writes of its target and time, the end of an object when its bullet is gone and
 * its entry holds another one, the shared and local code checks, and the same game state from the same bullets.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptBulletCoreTest {
    /**
     * The weapon of the bullets: the Gauss Rifle of the Marine, whose bullet appears at the target and dies at once.
     */
    private static final int WEAPON = ScrWeaponIds.GAUSS_RIFLE;
    /**
     * The frames the end test runs at most until the bullet is gone.
     */
    private static final int END_FRAMES = 100;
    /**
     * The frames the determinism test runs after its start.
     */
    private static final int CHANGE_FRAMES = 4;
    private static final @NotNull String CHANGES = """
                                                   function onStart() {
                                                       var human = game.players.getHumans()[0];
                                                       var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                       var marine = game.units.create(UnitType.TerranMarine, human, commandCenter.getPosition());
                                                       var bullet = game.createBullet(WeaponType.get(%d), marine, marine.getPosition());
                                                       bullet.setRemainingTime(40);
                                                       marine.useWeapon(WeaponType.get(%d));
                                                   }
                                                   """.formatted(WEAPON, WEAPON);

    @Test
    void bullets_read_and_write_their_fields_and_end_when_released(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var WEAPON = %d;
                                                                                                     var bullet;
                                                                                                     var bulletId;
                                                                                                     var bulletGeneration;
                                                                                                     var marine;
                                                                                                     var done = false;
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         var hatchery = game.units.findOne({type: UnitType.ZergHatchery});
                                                                                                         marine = game.units.create(UnitType.TerranMarine, human, commandCenter.getPosition());
                                                                                                         var before = core.bullet.getActiveCount();
                                                                                                         bullet = game.createBullet(WeaponType.get(WEAPON), marine, marine.getPosition());
                                                                                                         bulletId = bullet.getId();
                                                                                                         bulletGeneration = core.bullet.getGeneration(bulletId);
                                                                                                         log.info('created ' + bulletId);
                                                                                                         log.info(core.bullet.getActiveCount() === before + 1, core.bullet.getIds().indexOf(bulletId) >= 0,
                                                                                                                  game.getBullets().indexOf(bullet) >= 0, bullet.getWeapon() === WeaponType.get(WEAPON), bullet.getPlayer() === human,
                                                                                                                  bullet.getSource() === marine, bullet.getTarget(), bullet.getState().getName(),
                                                                                                                  bullet.getPosition().getX() === marine.getX(), core.sprite.exists(core.bullet.getSprite(bulletId)),
                                                                                                                  bullet.getFlingyType() === FlingyType.get(core.bullet.getFlingyType(bulletId)), bulletGeneration > 0);
                                                                                                         bullet.setTarget(hatchery);
                                                                                                         bullet.setRemainingTime(77);
                                                                                                         log.info(bullet.getTarget() === hatchery, bullet.getRemainingTime());
                                                                                                         bullet.setTarget(Position.of(96, 160));
                                                                                                         bullet.setTarget(null);
                                                                                                         log.info(bullet.getTargetPosition().getX(), bullet.getTargetPosition().getY(), bullet.getTarget());
                                                                                                         marine.useWeapon(WeaponType.get(WEAPON));
                                                                                                         log.info(core.bullet.getActiveCount() === before + 2);
                                                                                                         [function () { core.bullet.setRemainingTime(bulletId, 256); }, function () { core.bullet.getWeapon(-1); },
                                                                                                          function () { core.bullet.create(130, marine.getId(), 0, 0); }, function () { core.unit.useWeapon(marine.getId(), 130); }]
                                                                                                             .forEach(function (call) {
                                                                                                                 try {
                                                                                                                     call();
                                                                                                                 } catch (error) {
                                                                                                                     log.info(error.name);
                                                                                                                 }
                                                                                                             });
                                                                                                     }
                                                                                                     function onBulletCreated(event) {
                                                                                                         log.info('event ' + (event.getBullet() === bullet) + ' ' + event.getRecord().bullet + ' ' +
                                                                                                                  (event.getRecord().bulletGeneration === bulletGeneration));
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         if (done || bullet.exists()) {
                                                                                                             return;
                                                                                                         }
                                                                                                         done = true;
                                                                                                         try {
                                                                                                             bullet.getWeapon();
                                                                                                         } catch (error) {
                                                                                                             log.info('gone ' + error.name + ' ' + (error.message.indexOf('does not exist') >= 0) + ' ' + core.bullet.exists(bulletId));
                                                                                                         }
                                                                                                         var reused = null;
                                                                                                         for (var count = 0; count < 100 && (reused === null || reused.getId() !== bulletId); count++) {
                                                                                                             reused = game.createBullet(WeaponType.get(WEAPON), marine, marine.getPosition());
                                                                                                         }
                                                                                                         log.info('reused ' + (reused.getId() === bulletId) + ' ' + (core.bullet.getGeneration(bulletId) > bulletGeneration) + ' ' +
                                                                                                                  (reused !== bullet) + ' ' + bullet.exists() + ' ' + reused.exists());
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         try {
                                                                                                             game.createBullet(WeaponType.get(WEAPON), marine, marine.getPosition());
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name + ' ' + (core.bullet.getIds().length >= 0));
                                                                                                         }
                                                                                                         try {
                                                                                                             marine.useWeapon(WeaponType.get(WEAPON));
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name);
                                                                                                         }
                                                                                                     }
                                                                                                     """.formatted(WEAPON));

        int bulletId = Integer.parseInt(scenario.getInfoMessages().getFirst().substring("created ".length()));
        ProjectilePhase.Bullet bullet = scenario.getGame().requireFrame().getProjectiles().getBullet(bulletId);
        Unit source = bullet.getSourceUnit();
        UnitPool pool = scenario.getGame().getUnitSystem().requireUnitPool();

        Assertions.assertNotNull(source);

        int handle = UnitHandle.encode(source.getIndex(), pool.getGeneration(source.getIndex()), pool.getCapacity());
        SimulationUnit captured = new SimulationUnit(source, handle, source.getUnitId(), source.getOwnerIndex());

        scenario.getRuntime().queueEvent(new BulletCreatedEvent(bullet, WEAPON, source.getOwnerIndex(), captured, null, 0, 0),
                                         scenario.getGame().getCurrentFrame());

        for (int frame = 0; frame < END_FRAMES && scenario.getInfoMessages().size() < 13; frame++) {
            scenario.stepFrame();
        }

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("created " + bulletId,
                                        "true true true true true true null Init true true true true",
                                        "true 77",
                                        "96 160 null",
                                        "true",
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "event true " + bulletId + " true",
                                        "gone Error true false",
                                        "reused true true true false true",
                                        "local TypeError true",
                                        "local TypeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void the_same_bullets_give_the_same_game_state(@NotNull InstallationTestResources resources) throws IOException {
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
