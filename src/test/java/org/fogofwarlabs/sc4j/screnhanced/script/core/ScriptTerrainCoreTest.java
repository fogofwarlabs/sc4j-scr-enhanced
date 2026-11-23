package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.screnhanced.graphics.TerrainReplacements;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptMapScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests {@code core.terrain} and the {@code tileset}, {@code terrain} and {@code Tile} objects of the prelude in a live Use Map Settings game:
 * reading tiles and their terrain, changing them through both layers, filling and copying the tiles of locations and of rectangles, setting a
 * large rectangle in one call, the changes reaching the map at the end of the frame start, the errors of positions outside the map and tiles
 * outside the tileset, and the replacement of a tile picture at the registration.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptTerrainCoreTest {
    private static final int FIRST_TILE_MESSAGE = 0;
    /**
     * The tiles the script changes, as {tileX, tileY}: two set tiles, two filled tiles and two copied tiles.
     */
    private static final int @NotNull [] @NotNull [] SET_TILES = {{1, 1}, {2, 1}};
    private static final int @NotNull [] @NotNull [] FILLED_TILES = {{2, 2}, {3, 2}, {10, 10}, {11, 10}};
    /**
     * The side of the rectangle that one {@code setTiles} call writes, and the side of the replacing tile pictures.
     */
    private static final int LARGE_SIDE = 96;
    private static final int PICTURE_SIDE = 64;
    private static final int OPAQUE_GRAY = 0xFF808080;

    /**
     * Creates a PNG file of one color.
     */
    private static byte @NotNull [] createPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, OPAQUE_GRAY);
            }
        }

        ImageIO.write(image, "png", output);

        return output.toByteArray();
    }

    @Test
    void terrain_reads_and_changes_tiles_in_both_layers(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startUseMapSettings(resources.getGameData(), """
                                                                                                              function findTile(accept) {
                                                                                                                  for (var tileY = 0; tileY < game.map.getTileHeight(); tileY++) {
                                                                                                                      for (var tileX = 0; tileX < game.map.getTileWidth(); tileX++) {
                                                                                                                          var tile = game.map.terrain.getTile(tileX, tileY);
                                                                                                                          if (accept(tile)) {
                                                                                                                              return tile;
                                                                                                                          }
                                                                                                                      }
                                                                                                                  }
                                                                                                                  return null;
                                                                                                              }
                                                                                                              function onStart() {
                                                                                                                  var blocked = findTile(function (tile) { return !tile.isWalkable() && !tile.isPartiallyWalkable() && !tile.hasCreep(); });
                                                                                                                  var open = findTile(function (tile) { return tile.isWalkable() && tile.isBuildable() && !tile.isPartiallyWalkable(); });
                                                                                                                  log.info(blocked.getId(), open.getId());
                                                                                                                  log.info(blocked !== open, blocked === game.map.tileset.getTile(blocked.getGroup(), blocked.getIndex()),
                                                                                                                           blocked.getId() === (blocked.getGroup() << 4 | blocked.getIndex()), String(open) === 'Tile ' + open.getGroup() + ':' + open.getIndex(),
                                                                                                                           core.terrain.isTileWalkable(open.getId()), core.terrain.isTileBuildable(open.getId()), open.getElevation() >= 0,
                                                                                                                           game.map.tileset.getId() === game.map.tileset.getId(), game.map.tileset.getGroupCount() === core.terrain.getTileGroupCount());
                                                                                                                  core.terrain.setTile(1, 1, blocked.getId());
                                                                                                                  game.map.terrain.setTile(2, 1, blocked);
                                                                                                                  log.info(game.map.terrain.getTile(1, 1) === blocked, core.terrain.getTile(2, 1) === blocked.getId());
                                                                                                                  var source = game.map.locations.get(3);
                                                                                                                  var target = game.map.locations.get(4);
                                                                                                                  source.setBounds(64, 64, 128, 96);
                                                                                                                  target.setBounds(320, 320, 384, 352);
                                                                                                                  game.map.terrain.fillTiles(source, open);
                                                                                                                  game.map.terrain.copyTiles(source, target);
                                                                                                                  log.info(game.map.terrain.getTile(10, 10) === open, game.map.terrain.getTile(11, 10) === open, game.map.terrain.getTile(12, 10) === open);
                                                                                                              }
                                                                                                              """);

        List<String> messages = scenario.getInfoMessages();

        Assertions.assertEquals(4, messages.size(), scenario.getLines()::toString);
        Assertions.assertEquals("true true true true true true true true true", messages.get(1));
        Assertions.assertEquals("true true", messages.get(2));
        Assertions.assertTrue(messages.get(3).startsWith("true true "), messages.get(3));

        String[] ids = messages.get(FIRST_TILE_MESSAGE).split(" ");
        int blocked = Integer.parseInt(ids[0]);
        int open = Integer.parseInt(ids[1]);
        GameMap map = scenario.getGame().getMap();

        for (int[] tile : SET_TILES) {
            Assertions.assertEquals(blocked, map.getTerrainTileIdentifier(tile[1] * map.getWidth() + tile[0]));
            Assertions.assertEquals(0, map.getTileFlags(tile[0], tile[1]) & MapTileFlags.WALKABLE);
        }

        for (int[] tile : FILLED_TILES) {
            Assertions.assertEquals(open, map.getTerrainTileIdentifier(tile[1] * map.getWidth() + tile[0]));
            Assertions.assertTrue(scenario.getGame().getTerrain().isBuildable(tile[0], tile[1]));
        }
    }

    @Test
    void terrain_refuses_positions_outside_the_map_and_tiles_outside_the_tileset(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startUseMapSettings(resources.getGameData(), """
                                                                                                              function errorOf(body) {
                                                                                                                  try {
                                                                                                                      body();
                                                                                                                      return 'none';
                                                                                                                  } catch (error) {
                                                                                                                      return error.name;
                                                                                                                  }
                                                                                                              }
                                                                                                              function onStart() {
                                                                                                                  var value = core.terrain.getTile(0, 0);
                                                                                                                  var next = core.terrain.getTile(1, 0);
                                                                                                                  var missing = core.terrain.getTileGroupCount() * 16;
                                                                                                                  var small = game.map.locations.get(3);
                                                                                                                  var large = game.map.locations.get(4);
                                                                                                                  small.setBounds(0, 0, 32, 32);
                                                                                                                  large.setBounds(0, 0, 64, 32);
                                                                                                                  log.info(errorOf(function () { core.terrain.setTile(-1, 0, value); }),
                                                                                                                           errorOf(function () { core.terrain.getTile(0, game.map.getTileHeight()); }),
                                                                                                                           errorOf(function () { core.terrain.setTile(0, 0, missing); }),
                                                                                                                           errorOf(function () { core.terrain.isTileWalkable(missing); }),
                                                                                                                           errorOf(function () { game.map.tileset.getTile(0, 16); }),
                                                                                                                           errorOf(function () { game.map.tileset.getTile(core.terrain.getTileGroupCount(), 0); }),
                                                                                                                           errorOf(function () { game.map.terrain.setTile(0, 0, value); }),
                                                                                                                           errorOf(function () { game.map.terrain.copyTiles(small, large); }),
                                                                                                                           core.terrain.isValidTile(-1), core.terrain.isValidTile(value));
                                                                                                                  log.info(value, next);
                                                                                                              }
                                                                                                              """);

        List<String> messages = scenario.getInfoMessages();

        Assertions.assertEquals(2, messages.size(), scenario.getLines()::toString);
        Assertions.assertEquals("RangeError RangeError RangeError RangeError RangeError RangeError TypeError RangeError false true", messages.getFirst());

        scenario.stepFrame();

        String[] tiles = messages.get(1).split(" ");

        Assertions.assertEquals(Integer.parseInt(tiles[0]), scenario.getGame().getMap().getTerrainTileIdentifier(0), "a refused change queues nothing");
        Assertions.assertEquals(Integer.parseInt(tiles[1]), scenario.getGame().getMap().getTerrainTileIdentifier(1));
    }

    @Test
    void terrain_changes_rectangles_of_tiles_in_one_call(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startUseMapSettings(resources.getGameData(), """
                                                                                                              function errorOf(body) {
                                                                                                                  try {
                                                                                                                      body();
                                                                                                                      return 'none';
                                                                                                                  } catch (error) {
                                                                                                                      return error.name;
                                                                                                                  }
                                                                                                              }
                                                                                                              function onStart() {
                                                                                                                  var terrain = game.map.terrain;
                                                                                                                  var first = terrain.getTile(0, 0);
                                                                                                                  var second = terrain.getTile(1, 0);
                                                                                                                  var width = Math.min(96, game.map.getTileWidth());
                                                                                                                  var height = Math.min(96, game.map.getTileHeight());
                                                                                                                  var tiles = [];
                                                                                                                  for (var index = 0; index < width * height; index++) {
                                                                                                                      tiles.push(index % 2 === 0 ? first : second.getId());
                                                                                                                  }
                                                                                                                  terrain.setTiles(0, 0, width, height, tiles);
                                                                                                                  terrain.fillTiles(0, 0, 2, 1, second);
                                                                                                                  terrain.copyTiles(0, 0, 2, 1, 4, 3);
                                                                                                                  log.info(width, height, first.getId(), second.getId());
                                                                                                                  log.info(terrain.getTile(0, 0) === second, terrain.getTile(2, 0) === first, terrain.getTile(4, 3) === second,
                                                                                                                           terrain.getTile(5, 3) === second, terrain.getTile(6, 3) === (((3 * width + 6) % 2 === 0) ? first : second));
                                                                                                                  log.info(errorOf(function () { terrain.setTiles(0, 0, 2, 1, [first]); }),
                                                                                                                           errorOf(function () { terrain.setTiles(0, 0, 1, 1, ['x']); }),
                                                                                                                           errorOf(function () { terrain.fillTiles(-1, 0, 1, 1, first); }),
                                                                                                                           errorOf(function () { terrain.copyTiles(0, 0, 1, 1, game.map.getTileWidth(), 0); }),
                                                                                                                           errorOf(function () { core.terrain.setTiles(0, 0, 1, 1, [core.terrain.getTileGroupCount() * 16]); }));
                                                                                                              }
                                                                                                              """);

        List<String> messages = scenario.getInfoMessages();

        Assertions.assertEquals(3, messages.size(), scenario.getLines()::toString);
        Assertions.assertEquals("true true true true true", messages.get(1));
        Assertions.assertEquals("RangeError TypeError RangeError RangeError RangeError", messages.get(2));

        String[] values = messages.getFirst().split(" ");
        int width = Integer.parseInt(values[0]);
        int height = Integer.parseInt(values[1]);
        int first = Integer.parseInt(values[2]);
        int second = Integer.parseInt(values[3]);
        GameMap map = scenario.getGame().getMap();

        Assertions.assertTrue(width == LARGE_SIDE || width == map.getWidth());
        Assertions.assertEquals(second, map.getTerrainTileIdentifier(0));
        Assertions.assertEquals(first, map.getTerrainTileIdentifier(2));
        int lastTile = (height - 1) * map.getWidth() + width - 1;

        Assertions.assertEquals((width * height - 1) % 2 == 0 ? first : second, map.getTerrainTileIdentifier(lastTile));
    }

    @Test
    void replace_tile_image_replaces_the_picture_of_the_megatile_at_the_registration(@NotNull InstallationTestResources resources) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();

        files.put("tiles/wall.png", createPng(PICTURE_SIDE, PICTURE_SIDE));
        files.put("tiles/small.png", createPng(PICTURE_SIDE + 1, PICTURE_SIDE));

        LiveScriptGameScenario scenario = LiveScriptGameScenario.startMelee(resources.getGameData(), """
                                                                                                     var wall = game.map.resources.getImage('tiles/wall.png');
                                                                                                     var small = game.map.resources.getImage('tiles/small.png');
                                                                                                     var done = false;
                                                                                                     function errorOf(body) {
                                                                                                         try {
                                                                                                             body();
                                                                                                             return 'none';
                                                                                                         } catch (error) {
                                                                                                             return error.name;
                                                                                                         }
                                                                                                     }
                                                                                                     var registered = errorOf(function () { game.map.terrain.replaceTileImage(game.map.terrain.getTile(0, 0), wall); });
                                                                                                     function onStart() {
                                                                                                         log.info(registered, errorOf(function () { game.map.terrain.replaceTileImage(game.map.terrain.getTile(0, 0), small); }));
                                                                                                     }
                                                                                                     function onFrame() {
                                                                                                         if (!done) {
                                                                                                             done = true;
                                                                                                             log.info(errorOf(function () { game.map.terrain.replaceTileImage(game.map.terrain.getTile(0, 0), wall); }));
                                                                                                         }
                                                                                                     }
                                                                                                     """, ScriptMapScenario.createResources(files));

        scenario.stepFrame();

        Game game = scenario.getGame();
        TerrainTileset terrain = game.requireGameData().getTerrainTileset(game.getMap().getTileset());
        int megatile = terrain.getMegatileIdentifier(game.getMap().getTerrainTileIdentifier(0));
        TerrainReplacements replacements = scenario.getRuntime().getTerrainReplacements();

        Assertions.assertEquals(List.of("none RangeError", "TypeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertNotNull(replacements.findPicture(megatile));
        Assertions.assertEquals(PICTURE_SIDE, replacements.findPicture(megatile).getWidth());
        Assertions.assertNotNull(replacements.findPixels(megatile));
        Assertions.assertEquals(1, replacements.findCoverage(megatile)[0], "an opaque picture covers the megatile");
    }
}
