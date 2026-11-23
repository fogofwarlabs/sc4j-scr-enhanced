package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpritePool;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ClassicImageGraphics;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageGraphics;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageReplacements;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptContentExtension;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptMapScenario;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests the types a script registers in a live melee game ({@code ImageType.create}, {@code SpriteType.create}, {@code FlingyType.create} and
 * their lower layer): ids in call order after the native entries, names, counts and DAT values; the end of the reserved entries; the
 * registration at the module top level and in onStart only and not in local code; an effect of a registered sprite type with the canvas of its
 * sheet and its Death; a unit type with a registered flingy type; a bullet with a registered flingy type, a target unit and a direction, ended
 * by its detonation; and the same game state from the same script, local code included.
 */
@RequiresInstallation
final class ScriptContentCoreTest {
    private static final int TREE_WIDTH = 16;
    private static final int TREE_HEIGHT = 32;
    private static final int TREE_ANCHOR_Y = 28;
    private static final int BOLT_SIZE = 8;
    private static final int MARINE_FRAME_SIZE = 16;
    private static final int TURNING_DIRECTIONS = 17;
    /**
     * The Vulture's Fragmentation Grenade, whose bullet flies to its target.
     */
    private static final int FLYING_WEAPON = 5;
    private static final int OPAQUE_GREEN = 0xFF20A040;
    private static final int TRANSLUCENT_BLACK = 0x80000000;
    /**
     * The frames the scenario runs after its start.
     */
    private static final int RUN_FRAMES = 40;
    /**
     * The frames the shadow test runs after its start.
     */
    private static final int SHADOW_FRAMES = 6;
    /**
     * The frames the test of the Unit script runs.
     */
    private static final int UNIT_FRAMES = 64;
    private static final @NotNull String SCRIPT = """
                                                  var sheet = game.map.resources.getSheet('art/tree.png', {frameWidth: 16, frameHeight: 32, anchorY: 28,
                                                                                                           shadow: 'art/tree-shadow.png'});
                                                  var tree = ImageType.create({sheet: sheet, name: 'Tree', animation: {frameSets: [0, 1], frameTime: 4}});
                                                  var treeSprite = SpriteType.create({image: tree, name: 'Tree'});
                                                  var bolt = ImageType.create({sheet: game.map.resources.getSheet('art/bolt.png', {frameWidth: 8, frameHeight: 8}),
                                                                               script: ImageScript.Projectile});
                                                  var boltFlingy = FlingyType.create({sprite: SpriteType.create({image: bolt}), topSpeed: 2560, acceleration: 1000});
                                                  var walkerSheet = game.map.resources.getSheet('art/walker.png', {frameWidth: 16, frameHeight: 16, directions: 17});
                                                  var walker;
                                                  var effect;
                                                  var bullet;
                                                  var target;
                                                  var released = false;
                                                  function error(fn) {
                                                      try {
                                                          fn();
                                                          return 'none';
                                                      } catch (e) {
                                                          return e.name;
                                                      }
                                                  }
                                                  log.info(tree.getId(), treeSprite.getId(), bolt.getId(), boltFlingy.getId(), tree.getName(), String(treeSprite),
                                                           ImageType.get(tree.getId()) === tree, ImageType.getCount(), SpriteType.getCount(), FlingyType.getCount(),
                                                           treeSprite.getImage() === tree, boltFlingy.getSprite().getImage() === bolt, boltFlingy.getTopSpeed(),
                                                           boltFlingy.getAcceleration(), tree.getGraphicTurns(), sheet.getWidth(), sheet.getHeight(), sheet.getFrameCount());
                                                  function onStart() {
                                                      var human = game.players.getHumans()[0];
                                                      var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                      var position = commandCenter.getPosition();
                                                      walker = ImageType.create({sheet: walkerSheet, script: ImageType.get(MARINE_IMAGE), frameSets: FRAME_SETS});
                                                      var walkerSprite = SpriteType.create({image: walker, selectionCircle: 2, healthBarWidth: 32});
                                                      UnitType.TerranMarine.setFlingyType(FlingyType.create({sprite: walkerSprite, base: UnitType.TerranMarine.getFlingyType()}));
                                                      var marine = game.units.create(UnitType.TerranMarine, human, Position.of(position.x, position.y + 96));
                                                      effect = game.createEffect(treeSprite, Position.of(position.x + 96, position.y));
                                                      log.info('effect ' + effect.getId(), effect.getType() === treeSprite, effect.getMainImage().getType() === tree,
                                                               marine.getSprite().getType() === walkerSprite, marine.getSprite().getMainImage().getType() === walker,
                                                               walker.getIscript() === ImageType.get(MARINE_IMAGE).getIscript(), UnitType.TerranMarine.getFlingyType().getSprite() === walkerSprite);
                                                      target = game.units.findOne({type: UnitType.TerranSCV, owner: human});
                                                      bullet = game.createBullet(WeaponType.get(FLYING_WEAPON), marine, marine.getPosition(),
                                                                                 {target: target, flingy: boltFlingy, direction: 64});
                                                      log.info(bullet.getFlingyType() === boltFlingy, bullet.getDirection(), bullet.getSprite().getMainImage().getType() === bolt);
                                                      log.info(error(function () { effect.playAnimation(Animation.Init); }), error(function () { marine.getSprite().playAnimation(Animation.Death); }),
                                                               error(function () { bullet.getSprite().playAnimation(Animation.Death); }));
                                                  }
                                                  function onFrame(frame) {
                                                      if (bullet !== undefined && bullet.exists() && bullet.getState() === BulletState.Move) {
                                                          bullet.detonate();
                                                          log.info('detonated ' + bullet.getState().getName() + ' ' + (bullet.getTarget() === target));
                                                      } else if (bullet !== undefined && !bullet.exists() && !released) {
                                                          released = true;
                                                          log.info('released');
                                                          log.info(error(function () { ImageType.create({sheet: sheet}); }));
                                                          effect.playAnimation(Animation.Death);
                                                          log.info('death ' + effect.exists());
                                                      }
                                                  }
                                                  function onLocalFrame() {
                                                      if (effect !== undefined && effect.exists()) {
                                                          effect.getMainImage().play({frameSets: [1, 0], frameTime: 2, graphics: {sheet: sheet}, loop: true});
                                                      }
                                                      display.images.replace(ImageType.get(MARINE_IMAGE), {sheet: walkerSheet});
                                                      log.info(error(function () { SpriteType.create({image: tree}); }));
                                                  }
                                                  """;

    private static @Nullable GameData gameData;
    private static @Nullable ScriptResources resources;

    @BeforeAll
    static void loadGameData() throws IOException {
        GameData installedData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath());

        new ScriptContentExtension().extendInstalledData(installedData);

        Map<String, byte[]> files = new LinkedHashMap<>();

        files.put("art/tree.png", createPng(TREE_WIDTH * 2, TREE_HEIGHT, OPAQUE_GREEN));
        files.put("art/tree-shadow.png", createPng(TREE_WIDTH * 2, TREE_HEIGHT, TRANSLUCENT_BLACK));
        files.put("art/bolt.png", createPng(BOLT_SIZE, BOLT_SIZE, OPAQUE_GREEN));
        files.put("art/walker.png", createPng(MARINE_FRAME_SIZE * TURNING_DIRECTIONS, MARINE_FRAME_SIZE * 2, OPAQUE_GREEN));

        gameData  = installedData;
        resources = ScriptMapScenario.createResources(files);
    }

    @AfterAll
    static void closeGameData() {
        GameData installedData = gameData;

        gameData  = null;
        resources = null;

        if (installedData != null) {
            IgnoreException.execute(
                    installedData::close
            );
        }
    }

    /**
     * Creates a PNG of one color over its lower three quarters, transparent above.
     */
    private static byte @NotNull [] createPng(int width, int height, int color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = height / 4; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color);
            }
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        ImageIO.write(image, "png", output);

        return output.toByteArray();
    }

    private static @NotNull GameData requireGameData() {
        Assertions.assertNotNull(gameData);

        return gameData;
    }

    /**
     * Starts the scenario's script on a melee game: the Marine's image and its frame sets of the walker sheet, one per frame set of the Marine.
     */
    private static @NotNull LiveScriptGameScenario start() throws IOException {
        GameData data = requireGameData();
        int marineImage = data.getSpriteTypes().getImage(data.getFlingyTypes().getSprite(data.getUnitTypes().getFlingyType(ScrUnitIds.TERRAN_MARINE)));
        int frameSets = data.getGrpFile(marineImage).getFrameCount() / TURNING_DIRECTIONS;
        List<String> sets = new ArrayList<>();

        for (int set = 0; set < frameSets; set++) {
            sets.add(String.valueOf(set % 2));
        }

        String source = "var MARINE_IMAGE = " + marineImage + ";\nvar FLYING_WEAPON = " + FLYING_WEAPON + ";\nvar FRAME_SETS = [" +
                        String.join(", ", sets) + "];\n" + SCRIPT;

        Assertions.assertNotNull(resources);

        return LiveScriptGameScenario.startMelee(data, source, resources);
    }

    @Test
    void registered_types_take_reserved_entries_and_draw_their_sheets() throws IOException {
        LiveScriptGameScenario scenario = start();

        for (int frame = 0; frame < RUN_FRAMES; frame++) {
            scenario.stepFrame();
        }

        scenario.runLocalFrame();

        List<String> messages = scenario.getInfoMessages();
        ScriptContentExtension.Entries entries = ScriptContentExtension.findEntries(requireGameData().getAddedGraphics());

        Assertions.assertNotNull(entries);
        Assertions.assertFalse(messages.isEmpty(), scenario.getLines()::toString);

        int firstImage = entries.getFirstImage();
        int firstSprite = entries.getFirstSprite();
        int firstFlingy = entries.getFirstFlingy();

        Assertions.assertEquals(firstImage + " " + firstSprite + " " + (firstImage + 1) + " " + firstFlingy + " Tree Tree true " + (firstImage + 2) +
                                " " + (firstSprite + 2) + " " + (firstFlingy + 1) + " true true 2560 1000 0 16 56 2", messages.get(0),
                                scenario.getLines()::toString);

        String effect = messages.get(1);

        Assertions.assertTrue(effect.endsWith("true true true true true true"), effect);

        SpritePool sprites = scenario.getGame().getSpriteSystem().requireSpritePool();
        int effectId = Integer.parseInt(effect.split(" ")[1]);
        GrpFile frames = scenario.getGame().requireGameData().getGrpFile(firstImage);

        Assertions.assertEquals(List.of("true 64 true", "RangeError TypeError TypeError", "detonated Dying true", "released", "TypeError",
                                        "death true"),
                                messages.subList(2, Math.min(messages.size(), 8)), scenario.getLines()::toString);
        Assertions.assertTrue(!sprites.isAllocated(effectId) || sprites.getSprite(effectId).getSpriteTypeId() != firstSprite,
                              "the Death of Still ended the effect");
        Assertions.assertEquals(TREE_WIDTH, frames.getWidth(), "the game reads the canvas of the sheet");
        Assertions.assertEquals(TREE_ANCHOR_Y * 2, frames.getHeight());
        Assertions.assertEquals(2, frames.getFrameCount());
        Assertions.assertEquals("TypeError", messages.getLast(), "local code registers no type");
    }

    @Test
    void registered_types_give_the_same_game_state_in_every_game() throws IOException {
        List<Long> first = new ArrayList<>();
        List<Long> second = new ArrayList<>();

        for (List<Long> hashes : List.of(first, second)) {
            LiveScriptGameScenario scenario = start();

            for (int frame = 0; frame < RUN_FRAMES; frame++) {
                scenario.stepFrame();

                long before = scenario.getGame().computeStateHash();

                scenario.runLocalFrame();

                Assertions.assertEquals(before, scenario.getGame().computeStateHash(), "local code leaves the game state");

                hashes.add(before);
            }
        }

        Assertions.assertEquals(first, second);
    }

    @Test
    void every_reserved_type_taken_makes_create_throw_a_range_error() throws IOException {
        Assertions.assertNotNull(resources);

        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(requireGameData(), """
                                                                                               var sheet = game.map.resources.getSheet('art/bolt.png', {frameWidth: 8, frameHeight: 8});
                                                                                               var images = [];
                                                                                               var error = 'none';
                                                                                               try {
                                                                                                   for (var index = 0; index <= 256; index++) {
                                                                                                       images.push(ImageType.create({sheet: sheet}));
                                                                                                   }
                                                                                               } catch (e) {
                                                                                                   error = e.name;
                                                                                               }
                                                                                               log.info(images.length, error, images[255].getName(), ImageType.getCount() === images[255].getId() + 1);
                                                                                               """, resources);

        Assertions.assertEquals(List.of("256 RangeError Image" + (ScrAddedGraphics.FIRST_IMAGE_ID + 255) + " true"), scenario.getInfoMessages(),
                                scenario.getLines()::toString);
    }

    @Test
    void unit_script_walks_through_its_frame_sets_and_needs_a_turning_sheet() throws IOException {
        Assertions.assertNotNull(resources);

        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(requireGameData(), """
                                                                                               var walkerSheet = game.map.resources.getSheet('art/walker.png', {frameWidth: 16, frameHeight: 16,
                                                                                                                                                                directions: 17});
                                                                                               var boltSheet = game.map.resources.getSheet('art/bolt.png', {frameWidth: 8, frameHeight: 8});
                                                                                               var sets = [];
                                                                                               for (var index = 0; index < 15; index++) {
                                                                                                   sets.push(index % 2);
                                                                                               }
                                                                                               var walker = ImageType.create({sheet: walkerSheet, script: ImageScript.Unit, frameSets: sets});
                                                                                               var walkerSprite = SpriteType.create({image: walker, selectionCircle: 2, healthBarWidth: 32});
                                                                                               UnitType.TerranSCV.setFlingyType(FlingyType.create({sprite: walkerSprite,
                                                                                                                                                   base: UnitType.TerranSCV.getFlingyType()}));
                                                                                               var errors = [];
                                                                                               [function () { ImageType.create({sheet: boltSheet, script: ImageScript.Unit}); },
                                                                                                function () { ImageType.create({sheet: walkerSheet, script: ImageScript.Unit}); }].forEach(function (fn) {
                                                                                                   try {
                                                                                                       fn();
                                                                                                       errors.push('none');
                                                                                                   } catch (e) {
                                                                                                       errors.push(e.name);
                                                                                                   }
                                                                                               });
                                                                                               var scv;
                                                                                               var seen = {};
                                                                                               function onStart() {
                                                                                                   var human = game.players.getHumans()[0];
                                                                                                   var position = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human}).getPosition();
                                                                                                   scv = game.units.create(UnitType.TerranSCV, human, Position.of(position.x, position.y + 96));
                                                                                                   scv.issueOrder(Order.Move, Position.of(position.x + 320, position.y + 96));
                                                                                                   log.info(errors.join(' '), walker.getClickable(), walker.getFullIscript());
                                                                                               }
                                                                                               function onFrame(frame) {
                                                                                                   if (scv !== undefined && scv.exists()) {
                                                                                                       seen[Math.floor(scv.getSprite().getMainImage().getFrame() / 17)] = true;
                                                                                                   }
                                                                                                   if (frame === 60) {
                                                                                                       log.info(Object.keys(seen).sort(function (a, b) { return a - b; }).join(' '));
                                                                                                   }
                                                                                               }
                                                                                               """, resources);

        for (int frame = 0; frame < UNIT_FRAMES; frame++) {
            scenario.stepFrame();
        }

        Assertions.assertEquals(List.of("RangeError RangeError 1 1", "0 1 2 3 4 5 6 7 8"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void an_effect_of_a_sheet_with_a_shadow_draws_the_shadow_below_its_main_image() throws IOException {
        Assertions.assertNotNull(resources);

        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(requireGameData(), """
                                                                                               var sheet = game.map.resources.getSheet('art/tree.png', {frameWidth: 16, frameHeight: 32,
                                                                                                                                                        anchorY: 28, shadow: 'art/tree-shadow.png'});
                                                                                               var tree = ImageType.create({sheet: sheet, animation: {frameSets: [0, 1], frameTime: 4}});
                                                                                               var treeSprite = SpriteType.create({image: tree});
                                                                                               function onStart() {
                                                                                                   var position = game.units.findOne({type: UnitType.TerranCommandCenter}).getPosition();
                                                                                                   log.info(game.createEffect(treeSprite, Position.of(position.x + 96, position.y)).getId());
                                                                                               }
                                                                                               """, resources);

        for (int frame = 0; frame < SHADOW_FRAMES; frame++) {
            scenario.stepFrame();
        }

        Sprite sprite = scenario.getGame().getSpriteSystem().requireSpritePool().getSprite(Integer.parseInt(scenario.getInfoMessages().getFirst()));
        ImageReplacements replacements = scenario.getRuntime().getImageReplacements();
        ClassicImageGraphics graphics = new ClassicImageGraphics(scenario.getGame().getSpriteSystem()::getImagePool, () -> replacements);
        SpriteImage main = sprite.getMainImage();

        Assertions.assertNotNull(main);

        List<SpriteImage> shadows = sprite.getImages().stream().filter(image -> image.getModifier() == SpriteImage.MODIFIER_SHADOW).toList();

        Assertions.assertEquals(1, shadows.size());

        SpriteImage shadow = shadows.getFirst();
        ImageGraphics mainGraphics = replacements.findGraphics(main);

        Assertions.assertNotNull(mainGraphics);
        Assertions.assertNotNull(mainGraphics.getShadow());
        Assertions.assertEquals(0, shadow.getFlags() & SpriteImage.FLAG_HIDDEN);
        Assertions.assertSame(mainGraphics.getShadow(), replacements.findGraphics(shadow));
        Assertions.assertSame(mainGraphics.getShadow().getGrp(), graphics.findImageSheet(shadow));
        Assertions.assertEquals(graphics.findImageFrame(main), graphics.findImageFrame(shadow));
        Assertions.assertTrue(sprite.getImages().indexOf(shadow) > sprite.getImages().indexOf(main), "the shadow is drawn below the main image");
    }
}
