package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.List;

/**
 * Tests {@code core.terrain} and the {@code tileset}, {@code terrain} and {@code Tile} objects of the prelude in a live Use Map Settings game:
 * reading tiles and their terrain, changing them through both layers, filling and copying the tiles of locations, the changes reaching the map
 * at the next frame, and the errors of positions outside the map and tiles outside the tileset.
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

        Assertions.assertTrue(scenario.getGame().getMapTileChanges().hasPendingChanges());

        scenario.stepFrame();

        Assertions.assertFalse(scenario.getGame().getMapTileChanges().hasPendingChanges());

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
                                                                                                              }
                                                                                                              """);

        Assertions.assertEquals(List.of("RangeError RangeError RangeError RangeError RangeError RangeError TypeError RangeError false true"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertFalse(scenario.getGame().getMapTileChanges().hasPendingChanges());
    }
}
