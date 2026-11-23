package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SpriteIds;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffectPool;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteSystem;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageReplacements;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.List;

/**
 * Tests {@code core.sprite}, {@code core.image}, the image functions of {@code core.graphics} and the {@code Sprite} and {@code SpriteImage}
 * objects of the prelude in a live melee game: the sprite and images of a unit, a created and a removed effect, the frame and draw function a
 * script sets, the end of an object when its sprite is gone and its slot holds another one, the shared and local code checks, and the same
 * game state from the same effects.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptSpriteCoreTest {
    /**
     * The sprite type of the effects: the short death of a hallucination.
     */
    private static final int EFFECT = SpriteIds.HALLUCINATION_DEATH1;
    /**
     * The frames the end test runs at most until the effect is gone.
     */
    private static final int END_FRAMES = 200;
    /**
     * The frame the script sets on the main image of the Command Center.
     */
    private static final int SCRIPT_FRAME = 3;
    /**
     * The frames the determinism test runs after its start.
     */
    private static final int CHANGE_FRAMES = 4;
    private static final @NotNull String CHANGES = """
                                                   function onStart() {
                                                       var hatchery = game.units.findOne({type: UnitType.ZergHatchery});
                                                       game.createEffect(SpriteType.get(%d), hatchery.getPosition(), {elevation: 12});
                                                       hatchery.getSprite().getMainImage().setFrame(2);
                                                   }
                                                   """.formatted(EFFECT);

    @Test
    void sprites_and_images_read_the_game_and_an_effect_ends_with_its_scripts(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var EFFECT = %d;
                                                                                                     var effect;
                                                                                                     var effectSlot;
                                                                                                     var effectGeneration;
                                                                                                     var main;
                                                                                                     var done = false;
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         var spriteId = core.unit.getSprite(commandCenter.getId());
                                                                                                         var sprite = commandCenter.getSprite();
                                                                                                         main = sprite.getMainImage();
                                                                                                         log.info(spriteId >= 0, sprite.getId() === spriteId, sprite === commandCenter.getSprite(), core.sprite.exists(spriteId),
                                                                                                                  core.sprite.getX(spriteId) === commandCenter.getX(), sprite.getPosition().getY() === commandCenter.getY(),
                                                                                                                  sprite.getOwner() === human, sprite.getType() === SpriteType.get(core.sprite.getType(spriteId)),
                                                                                                                  core.sprite.getIds().indexOf(spriteId) >= 0, core.sprite.getGeneration(spriteId) > 0);
                                                                                                         log.info(main.getType() === commandCenter.getImageType(), main.getSprite() === sprite, sprite.getImages().indexOf(main) >= 0,
                                                                                                                  core.image.exists(main.getId()), core.image.getGeneration(main.getId()) > 0, main.getDrawFunction().getName(),
                                                                                                                  main.getFrame() === core.image.getFrame(main.getId()), sprite.isVisibleTo(human));
                                                                                                         effect = game.createEffect(SpriteType.get(EFFECT), commandCenter.getPosition(), {owner: human, elevation: 12});
                                                                                                         effectSlot = effect.getId();
                                                                                                         effectGeneration = core.sprite.getGeneration(effectSlot);
                                                                                                         log.info(effect.getType().getId(), effect.getElevation(), effect.getOwner() === human,
                                                                                                                  effect.getPosition().getX() === commandCenter.getX(), effect.exists(), core.sprite.getMainImage(effectSlot) >= 0);
                                                                                                         main.setFrame(%d);
                                                                                                         main.setDrawFunction(DrawFunction.Hallucination);
                                                                                                         log.info('main ' + main.getId());
                                                                                                         [function () { core.sprite.createEffect(9999, 0, 0, 0, 0); }, function () { core.sprite.getType(-1); },
                                                                                                          function () { core.image.getType(core.image.getCapacity()); },
                                                                                                          function () { core.graphics.setImageDrawFunction(main.getId(), 18); },
                                                                                                          function () { core.sprite.createEffect(EFFECT, 0, 0, 12, 0); }].forEach(function (call) {
                                                                                                             try {
                                                                                                                 call();
                                                                                                             } catch (error) {
                                                                                                                 log.info(error.name);
                                                                                                             }
                                                                                                         });
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         if (done || effect.exists()) {
                                                                                                             return;
                                                                                                         }
                                                                                                         done = true;
                                                                                                         try {
                                                                                                             effect.getType();
                                                                                                         } catch (error) {
                                                                                                             log.info('gone ' + error.name + ' ' + (error.message.indexOf('does not exist') >= 0) + ' ' + core.sprite.exists(effectSlot));
                                                                                                         }
                                                                                                         var reused = null;
                                                                                                         for (var count = 0; count < 100 && (reused === null || reused.getId() !== effectSlot); count++) {
                                                                                                             reused = game.createEffect(SpriteType.get(EFFECT), Position.of(64, 64));
                                                                                                         }
                                                                                                         log.info('reused ' + (reused.getId() === effectSlot) + ' ' + (core.sprite.getGeneration(effectSlot) > effectGeneration) + ' ' +
                                                                                                                  (reused !== effect) + ' ' + effect.exists() + ' ' + reused.exists());
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         try {
                                                                                                             game.createEffect(SpriteType.get(EFFECT), Position.of(64, 64));
                                                                                                         } catch (error) {
                                                                                                             log.info('local ' + error.name + ' ' + (core.sprite.getIds().length > 0));
                                                                                                         }
                                                                                                         main.setFrame(null);
                                                                                                         log.info('local frame ' + (main.getFrame() === core.image.getFrame(main.getId())));
                                                                                                     }
                                                                                                     """.formatted(EFFECT, SCRIPT_FRAME));

        List<String> started = List.copyOf(scenario.getInfoMessages());
        int mainImage = Integer.parseInt(started.get(3).substring("main ".length()));
        SpriteImage image = scenario.getGame().getSpriteSystem().requireImagePool().getImage(mainImage);
        ImageReplacements replacements = scenario.getRuntime().getImageReplacements();

        Assertions.assertEquals(SCRIPT_FRAME, replacements.findImageFrame(image));
        Assertions.assertEquals(SpriteImage.MODIFIER_HALLUCINATION, replacements.findImageDrawFunction(image));

        for (int frame = 0; frame < END_FRAMES && scenario.getInfoMessages().size() < started.size() + 2; frame++) {
            scenario.stepFrame();
        }

        scenario.runLocalFrame();

        Assertions.assertEquals(-1, replacements.findImageFrame(image), "local code gives the image its own frame back");
        Assertions.assertEquals(List.of("true true true true true true true true true true",
                                        "true true true true true Normal true true",
                                        EFFECT + " 12 true true true true",
                                        "main " + mainImage,
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "RangeError",
                                        "gone Error true false",
                                        "reused true true true false true",
                                        "local TypeError true",
                                        "local frame true"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void remove_takes_an_effect_with_its_images_and_refuses_the_sprite_of_a_unit(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var EFFECT = %d;
                                                                                                     var effect;
                                                                                                     function attempt(call) {
                                                                                                         try {
                                                                                                             return call();
                                                                                                         } catch (error) {
                                                                                                             return error.name;
                                                                                                         }
                                                                                                     }
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         var removed = game.createEffect(SpriteType.get(EFFECT), commandCenter.getPosition());
                                                                                                         var slot = removed.getId();
                                                                                                         var images = removed.getImages();
                                                                                                         log.info(images.length > 0, removed.remove(), removed.exists(), core.sprite.exists(slot),
                                                                                                                  images.every(function (image) { return !image.exists(); }), attempt(function () { return removed.remove(); }),
                                                                                                                  attempt(function () { return core.sprite.remove(-1); }));
                                                                                                         log.info(commandCenter.getSprite().remove(), commandCenter.exists(), commandCenter.getSprite().exists());
                                                                                                         effect = game.createEffect(SpriteType.get(EFFECT), commandCenter.getPosition());
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         log.info('local ' + attempt(function () { return effect.remove(); }) + ' ' + effect.exists());
                                                                                                     }
                                                                                                     """.formatted(EFFECT));

        scenario.runLocalFrame();

        for (int frame = 0; frame < CHANGE_FRAMES; frame++) {
            scenario.stepFrame();
        }

        SpriteSystem sprites = scenario.getGame().getSpriteSystem();
        SpriteEffectPool effects = sprites.getSpriteEffectPool();

        Assertions.assertNotNull(effects);

        for (int index = effects.getFirstActiveIndex(); index >= 0; index = effects.getNextIndex(index)) {
            Sprite sprite = effects.getSpriteEffect(index).getSprite();

            Assertions.assertTrue(sprites.requireSpritePool().isAllocated(sprite.getPoolIndex()), "every active effect keeps an allocated sprite");
        }

        Assertions.assertEquals(List.of("true true false false true Error RangeError",
                                        "false true true",
                                        "local TypeError true"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void a_played_animation_runs_the_iscript_of_the_unit_and_a_held_frame_stays_through_it(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var marine;
                                                                                                     var main;
                                                                                                     function attempt(call) {
                                                                                                         try {
                                                                                                             call();
                                                                                                             return 'played';
                                                                                                         } catch (error) {
                                                                                                             return error.name;
                                                                                                         }
                                                                                                     }
                                                                                                     function onStart() {
                                                                                                         var human = game.players.getHumans()[0];
                                                                                                         var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                                                                         marine = game.units.create(UnitType.TerranMarine, human, commandCenter.getPosition());
                                                                                                         main = marine.getSprite().getMainImage();
                                                                                                         main.holdFrame(%d);
                                                                                                         log.info(attempt(function () { marine.playAnimation(Animation.Walking); }),
                                                                                                                  attempt(function () { marine.playAnimation(Animation.Death); }),
                                                                                                                  attempt(function () { marine.playAnimation(Animation.Init); }),
                                                                                                                  attempt(function () { core.unit.playAnimation(marine.getId(), 28); }), Animation.get(7).getName());
                                                                                                         log.info('main ' + main.getId());
                                                                                                     }
                                                                                                     function onLocalFrame() {
                                                                                                         log.info('local ' + attempt(function () { marine.playAnimation(Animation.Walking); }));
                                                                                                     }
                                                                                                     """.formatted(SCRIPT_FRAME));
        int mainImage = Integer.parseInt(scenario.getInfoMessages().get(1).substring("main ".length()));
        SpriteImage image = scenario.getGame().getSpriteSystem().requireImagePool().getImage(mainImage);
        int playedAnimation = image.getAnimationType();

        for (int frame = 0; frame < CHANGE_FRAMES; frame++) {
            scenario.stepFrame();
        }

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("played RangeError RangeError RangeError CastSpell", "main " + mainImage),
                                scenario.getInfoMessages().subList(0, 2), scenario.getLines()::toString);
        Assertions.assertTrue(scenario.getInfoMessages().contains("local TypeError"), scenario.getLines()::toString);
        Assertions.assertEquals(IscriptExecutor.ANIMATION_WALKING, playedAnimation, "the image runs the played animation");
        Assertions.assertEquals(SCRIPT_FRAME, scenario.getRuntime().getImageReplacements().findImageFrame(image), "the held frame stays");
    }

    @Test
    void the_same_effects_give_the_same_game_state(@NotNull InstallationTestResources resources) throws IOException {
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
