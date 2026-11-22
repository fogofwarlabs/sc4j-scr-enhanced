package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageFileScenario;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageGraphics;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageReplacements;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptMapScenario;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests {@code core.resources}, {@code core.graphics} and their upper layer ({@code resources}, {@code graphics}, {@code ImageType}) on a game
 * without game data: resource handles, their checks and errors, the image type replacements they set, also while the game is read-only and
 * in local code.
 */
final class ScriptGraphicsCoreTest {
    private static final int MAP_TILE_SIZE = 4;
    private static final int MAP_TILESET = 0;
    private static final @NotNull String GRP_NAME = "units/marine.grp";
    private static final @NotNull String HD_ANIM_NAME = "units/marine.anim";
    private static final @NotNull String HD2_ANIM_NAME = "units/marine-hd2.anim";
    private static final int REPLACED_IMAGE_TYPE = 239;
    private static final int OTHER_IMAGE_TYPE = 240;
    /**
     * Defines {@code error(fn)}, which returns the name of the error {@code fn} throws, or {@code "none"}.
     */
    private static final @NotNull String ERROR_FUNCTION = """
                                                          function error(fn) {
                                                              try {
                                                                  fn();
                                                                  return 'none';
                                                              } catch (e) {
                                                                  return e.name;
                                                              }
                                                          }
                                                          """;

    private static @NotNull Game createGame() {
        return Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, MAP_TILESET, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]), new LcgRandom(0));
    }

    /**
     * Creates the resources of a GRP, an HD anim and an HD2 anim.
     */
    private static @NotNull ScriptResources createResources() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();

        files.put(GRP_NAME, ImageFileScenario.createGrp(0));
        files.put(HD_ANIM_NAME, ImageFileScenario.createAnim(ImageFileScenario.HD_SCALE));
        files.put(HD2_ANIM_NAME, ImageFileScenario.createAnim(ImageFileScenario.HD2_SCALE));

        return ScriptMapScenario.createResources(files);
    }

    @Test
    void resource_handles_are_checked_once_and_kept_per_name() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), ERROR_FUNCTION + """
                                                                                            var grp = game.map.resources.getGrp('units/marine.grp');
                                                                                            log.info(grp === game.map.resources.getGrp('units/marine.grp'), grp.getName(), String(grp), game.map.resources.has('units/marine.grp'),
                                                                                                     game.map.resources.has('units/none.grp'), grp.getId() === game.map.resources.getAnim('units/marine.anim').getId());
                                                                                            log.info(error(function () { game.map.resources.getGrp('units/none.grp'); }), error(function () { game.map.resources.getGrp('units/marine.anim'); }),
                                                                                                     error(function () { game.map.resources.getAnim('units/marine.grp'); }), error(function () { core.resources.getGrp(1); }));
                                                                                            """, createResources());

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("true units/marine.grp units/marine.grp true false false", "Error Error Error TypeError"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void text_resources_read_as_utf8_and_parse_as_json() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();

        files.put("data/heroes.json", "﻿{\"name\": \"Zeratul\", \"skills\": [1, 2]}".getBytes(StandardCharsets.UTF_8));
        files.put("data/notes.txt", "한 줄\nline".getBytes(StandardCharsets.UTF_8));
        files.put("data/broken.txt", new byte[]{(byte) 0xC3, (byte) 0x28});

        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), ERROR_FUNCTION + """
                                                                                            var resources = game.map.resources;
                                                                                            var heroes = resources.readJson('data/heroes.json');
                                                                                            heroes.skills.push(3);
                                                                                            log.info(heroes.name, resources.readJson('data/heroes.json').skills.length, resources.readText('data/notes.txt').length);
                                                                                            log.info(error(function () { resources.readText('data/none.txt'); }), error(function () { resources.readText('data/broken.txt'); }),
                                                                                                     error(function () { resources.readJson('data/notes.txt'); }));
                                                                                            function onLocalFrame() {
                                                                                                log.info(resources.readText('data/notes.txt').split('\\n')[1]);
                                                                                            }
                                                                                            """, ScriptMapScenario.createResources(files));

        scenario.getRuntime().start();
        scenario.getRuntime().callLocalFrame(0);

        Assertions.assertEquals(List.of("Zeratul 2 8", "Error Error SyntaxError", "line"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void replace_image_sets_the_graphics_of_an_image_type() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), ERROR_FUNCTION + """
                                                                                            var grp = game.map.resources.getGrp('units/marine.grp');
                                                                                            var hd = game.map.resources.getAnim('units/marine.anim');
                                                                                            var hd2 = game.map.resources.getAnim('units/marine-hd2.anim');
                                                                                            display.images.replace(ImageType.get(239), {sd: grp, hd: hd, hd2: hd2});
                                                                                            display.images.replace(ImageType.get(240), {hd2: hd2});
                                                                                            display.images.restore(ImageType.get(240));
                                                                                            log.info(error(function () { display.images.replace(ImageType.get(240), {hd: hd2}); }),
                                                                                                     error(function () { display.images.replace(ImageType.get(240), {}); }),
                                                                                                     error(function () { display.images.replace(ImageType.get(240), {sd: hd}); }),
                                                                                                     error(function () { display.images.replace(239, {sd: grp}); }),
                                                                                                     error(function () { core.graphics.replaceImage(240, 99, -1, -1); }));
                                                                                            log.info(ImageType.getCount(), ImageType.get(211) === ImageType.Image211, ImageType.Image239 === ImageType.get(239),
                                                                                                     ImageType.get(239).getName());
                                                                                            """, createResources());

        scenario.getRuntime().start();

        ImageReplacements replacements = scenario.getRuntime().getImageReplacements();
        ImageGraphics replaced = replacements.findImageGraphics(REPLACED_IMAGE_TYPE);

        Assertions.assertEquals(List.of("RangeError RangeError TypeError TypeError RangeError", "999 true true Image239"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertNotNull(replaced);
        Assertions.assertNotNull(replaced.getGrp());
        Assertions.assertEquals(GRP_NAME, replaced.getGrp().getName());
        Assertions.assertNotNull(replaced.getHdAnim());
        Assertions.assertNotNull(replaced.getHd2Anim());
        Assertions.assertEquals(HD2_ANIM_NAME, replaced.getHd2Anim().getName());
        Assertions.assertNull(replacements.findImageGraphics(OTHER_IMAGE_TYPE));
    }

    @Test
    void graphics_changes_work_while_the_game_is_read_only_and_in_local_code() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), ERROR_FUNCTION + """
                                                                                            function onFrame() {
                                                                                                var grp = game.map.resources.getGrp('units/marine.grp');
                                                                                                log.info(error(function () { display.images.replace(ImageType.get(239), {sd: grp}); }), grp.getName());
                                                                                            }
                                                                                            function onLocalFrame() {
                                                                                                log.info(error(function () { display.images.restore(ImageType.get(239)); }));
                                                                                            }
                                                                                            """, createResources());

        scenario.getRuntime().start();
        scenario.getRuntime().setReadOnly(true);
        scenario.getRuntime().runFrame(0);

        Assertions.assertNotNull(scenario.getRuntime().getImageReplacements().findImageGraphics(REPLACED_IMAGE_TYPE));

        scenario.getRuntime().callLocalFrame(1);

        Assertions.assertEquals(List.of("none units/marine.grp", "none"), scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertNull(scenario.getRuntime().getImageReplacements().findImageGraphics(REPLACED_IMAGE_TYPE));
    }
}
