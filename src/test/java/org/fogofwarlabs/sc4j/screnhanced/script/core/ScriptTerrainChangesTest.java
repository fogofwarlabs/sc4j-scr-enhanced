package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.*;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.LiveMapGameScenario;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Checks the map tile changes of a live melee game on an installed map: a queued change waits until it is applied, the changed tiles take the terrain flags,
 * the regions and the walkability of the new tiles, a ground unit on tiles that became unwalkable moves to walkable terrain, the computer players
 * keep the start regions, two runs with the same changes keep the same state, and invalid changes are refused.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptTerrainChangesTest {
    /**
     * The installed two-player map, relative to the installation, and the lobby of the live game.
     */
    private static final @NotNull String MAP_PATH = "Maps/(2)Bottleneck.scm";
    private static final @NotNull String PLAYER_NAME = "tester";
    private static final @NotNull String HUMAN_RACE = "terran";
    private static final @NotNull String COMPUTER_RACE = "zerg";
    private static final int HUMAN_SLOT = 0;
    private static final int GAME_SEED = 0x2F6A11;
    /**
     * The frames the game runs before the Marine is created, so that the start units are in place.
     */
    private static final int START_FRAMES = 8;
    /**
     * The distance of the Marine from the human's Command Center toward the map center, on each axis.
     */
    private static final int MARINE_DISTANCE = 160;
    /**
     * The tiles around the unit's tile on each side that become unwalkable.
     */
    private static final int BLOCK_RADIUS = 1;
    private static final int STEPPED_FRAMES = 48;
    private static final int HALF_TILE = TerrainTileset.TILE_SIZE / 2;
    private static final int MAXIMUM_TILE_IDENTIFIER = 0xFFFF;
    private static final int TILES_PER_GROUP = 16;

    /**
     * Finds a tile of the map that is unwalkable, not partially walkable and unbuildable.
     */
    private static int findUnwalkableTile(@NotNull Game game, @NotNull ScriptTerrainChanges changes) {
        GameMap map = game.getMap();

        for (int tileIndex = 0; tileIndex < map.getTileCount(); tileIndex++) {
            int tileIdentifier = map.getTerrainTileIdentifier(tileIndex);
            int flags = changes.getTerrainFlags(tileIdentifier);

            if ((flags & (MapTileFlags.WALKABLE | MapTileFlags.PARTIALLY_WALKABLE | MapTileFlags.HAS_CREEP)) == 0 &&
                (flags & MapTileFlags.UNBUILDABLE) != 0) {
                return tileIdentifier;
            }
        }

        return Assertions.fail("The map has no unwalkable tile.");
    }

    private static @NotNull Path getMap() {
        return ExternalInput.INSTALLATION.getPath().resolve(MAP_PATH);
    }

    private static @NotNull LiveGame createLive() throws IOException {
        return new LiveGame(MeleeGameSetup.load(getMap(), PLAYER_NAME, HUMAN_RACE, COMPUTER_RACE, HUMAN_SLOT, GameSpeed.FASTEST), GAME_SEED);
    }

    /**
     * Starts the live game and runs its first frames.
     */
    private static @NotNull ReplayRuntime start(@NotNull GameData gameData, @NotNull LiveGame live) throws IOException {
        ReplayRuntime runtime = LiveMapGameScenario.startLive(gameData, live, getMap());

        for (int frame = 0; frame < START_FRAMES; frame++) {
            LiveMapGameScenario.stepFrame(runtime, live);
        }

        return runtime;
    }

    /**
     * Creates a Marine of the human player a little toward the map center from the human's Command Center.
     */
    private static @NotNull Unit createMarine(@NotNull ReplayRuntime runtime) {
        Game game = runtime.getGame();
        UnitPool pool = game.getUnitSystem().getUnitPool();
        Sprite center = null;

        Assertions.assertNotNull(pool);

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0 && center == null; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (unit.getUnitId() == ScrUnitIds.TERRAN_COMMAND_CENTER && unit.getOwnerIndex() == HUMAN_SLOT) {
                center = unit.requireSprite();
            }
        }

        Assertions.assertNotNull(center, "the human has a Command Center");

        int directionX = center.getX() < game.getMap().getWidth() * TerrainTileset.TILE_SIZE / 2 ? 1 : -1;
        int directionY = center.getY() < game.getMap().getHeight() * TerrainTileset.TILE_SIZE / 2 ? 1 : -1;
        int positionX = center.getX() + MARINE_DISTANCE * directionX;
        int positionY = center.getY() + MARINE_DISTANCE * directionY;
        Unit[] created = new Unit[1];

        RunningGameScenario.runInCommandDispatch(runtime, () -> created[0] = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_MARINE,
                                                                                                            positionX, positionY, HUMAN_SLOT));

        return created[0];
    }

    /**
     * Applies the queued changes inside a command dispatch of the game, as the script's frame start does.
     */
    private static void apply(@NotNull ReplayRuntime runtime, @NotNull ScriptTerrainChanges changes) {
        RunningGameScenario.runInCommandDispatch(runtime, () -> changes.applyChanges(runtime.getGame().requireFrame().getInstructionLimit()));
    }

    /**
     * Blocks the tiles around a unit's tile with a tile.
     */
    private static void blockAround(@NotNull ScriptTerrainChanges changes, @NotNull Unit unit, int tileIdentifier) {
        Sprite sprite = unit.requireSprite();
        int tileX = sprite.getX() / TerrainTileset.TILE_SIZE;
        int tileY = sprite.getY() / TerrainTileset.TILE_SIZE;

        for (int row = tileY - BLOCK_RADIUS; row <= tileY + BLOCK_RADIUS; row++) {
            for (int column = tileX - BLOCK_RADIUS; column <= tileX + BLOCK_RADIUS; column++) {
                changes.setTile(column, row, tileIdentifier);
            }
        }
    }

    /**
     * Runs the game with a Marine whose surrounding tiles become unwalkable, then some frames more.
     *
     * @return the state hash at the end
     */
    private static long runBlockedMarine(@NotNull GameData gameData) throws IOException {
        LiveGame live = createLive();
        ReplayRuntime runtime = start(gameData, live);
        Game game = runtime.getGame();
        ScriptTerrainChanges changes = new ScriptTerrainChanges(game);
        Unit marine = createMarine(runtime);

        blockAround(changes, marine, findUnwalkableTile(game, changes));
        apply(runtime, changes);

        for (int frame = 0; frame < STEPPED_FRAMES; frame++) {
            LiveMapGameScenario.stepFrame(runtime, live);
        }

        return game.computeStateHash();
    }


    @Test
    void apply_changes_should_give_the_tiles_their_terrain_and_move_the_unit_off_them(@NotNull InstallationTestResources resources) throws IOException {
        ReplayRuntime runtime = start(resources.getGameData(), createLive());
        Game game = runtime.getGame();
        GameMap map = game.getMap();
        ScriptTerrainChanges changes = new ScriptTerrainChanges(game);
        Unit marine = createMarine(runtime);
        Sprite sprite = marine.requireSprite();
        int tileX = sprite.getX() / TerrainTileset.TILE_SIZE;
        int tileY = sprite.getY() / TerrainTileset.TILE_SIZE;
        int tileIndex = tileY * map.getWidth() + tileX;
        int centerX = tileX * TerrainTileset.TILE_SIZE + HALF_TILE;
        int centerY = tileY * TerrainTileset.TILE_SIZE + HALF_TILE;
        int previousTile = map.getTerrainTileIdentifier(tileIndex);
        int unwalkableTile = findUnwalkableTile(game, changes);
        TerrainContours startContours = game.getPathSystem().requireTerrainContours();

        Assertions.assertNotEquals(previousTile, unwalkableTile);
        Assertions.assertNotEquals(TerrainRegions.BLOCKED_TERRAIN,
                                   startContours.getRegions().getTerrainType(TerrainRegionLookupUtils.lookup(startContours.getRegions(),
                                                                                                             centerX, centerY)));

        blockAround(changes, marine, unwalkableTile);

        Assertions.assertTrue(changes.hasPendingChanges());
        Assertions.assertEquals(unwalkableTile, changes.getTile(tileX, tileY));
        Assertions.assertEquals(previousTile, map.getTerrainTileIdentifier(tileIndex), "the change waits until it is applied");

        apply(runtime, changes);

        TerrainContours contours = game.getPathSystem().requireTerrainContours();

        Assertions.assertFalse(changes.hasPendingChanges());
        Assertions.assertEquals(unwalkableTile, map.getTerrainTileIdentifier(tileIndex));
        Assertions.assertEquals(MapTileFlags.UNWALKABLE, map.getTileFlags(tileIndex) & (MapTileFlags.WALKABLE | MapTileFlags.UNWALKABLE));
        Assertions.assertFalse(game.getTerrain().isBuildable(tileX, tileY));
        Assertions.assertFalse(game.getTerrain().isWalkable(tileX * TerrainTileset.MINI_TILES_PER_ROW, tileY * TerrainTileset.MINI_TILES_PER_ROW));
        Assertions.assertNotSame(startContours, contours);
        Assertions.assertSame(startContours, game.getPathSystem().getStartTerrainContours(), "the computer players keep the start regions");
        Assertions.assertEquals(TerrainRegions.BLOCKED_TERRAIN,
                                contours.getRegions().getTerrainType(TerrainRegionLookupUtils.lookup(contours.getRegions(), centerX, centerY)));

        int movedTileX = sprite.getX() / TerrainTileset.TILE_SIZE;
        int movedTileY = sprite.getY() / TerrainTileset.TILE_SIZE;

        Assertions.assertTrue(Math.abs(movedTileX - tileX) > BLOCK_RADIUS || Math.abs(movedTileY - tileY) > BLOCK_RADIUS,
                              "the marine left the unwalkable tiles");
        Assertions.assertTrue(game.getUnitPositioning().isTerrainPlacementWalkable(marine.getUnitId(), sprite.getX(), sprite.getY()));
    }

    @Test
    void the_same_tile_changes_give_the_same_state_in_two_runs(@NotNull InstallationTestResources resources) throws IOException {
        long first = runBlockedMarine(resources.getGameData());
        long second = runBlockedMarine(resources.getGameData());

        Assertions.assertEquals(first, second);
    }

    @Test
    void set_tile_should_refuse_positions_outside_the_map_and_tiles_outside_the_tileset(@NotNull InstallationTestResources resources) throws IOException {
        ReplayRuntime runtime = start(resources.getGameData(), createLive());
        Game game = runtime.getGame();
        ScriptTerrainChanges changes = new ScriptTerrainChanges(game);
        int width = game.getMap().getWidth();
        int height = game.getMap().getHeight();
        int tile = game.getMap().getTerrainTileIdentifier(0);
        int firstMissingGroupTile = game.requireGameData().getTerrainTileset(game.getMap().getTileset()).getGroupCount() * TILES_PER_GROUP;

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> changes.setTile(-1, 0, tile));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> changes.setTile(width, 0, tile));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> changes.getTile(0, height));
        Assertions.assertThrows(IllegalArgumentException.class, () -> changes.setTile(0, 0, MAXIMUM_TILE_IDENTIFIER + 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> changes.setTile(0, 0, firstMissingGroupTile));
        Assertions.assertFalse(changes.isValidTile(-1));
        Assertions.assertTrue(changes.isValidTile(tile));
        Assertions.assertFalse(changes.hasPendingChanges());
    }
}
