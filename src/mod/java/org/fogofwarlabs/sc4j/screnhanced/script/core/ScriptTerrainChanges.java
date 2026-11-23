package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.path.PathSystem;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Changes the tiles of a running game's map for {@code core.terrain} ({@link ScriptTerrainCore}), so that the game continues as on a map that
 * had the new tiles from the start, as far as the game's routines allow.
 * <p>
 * {@link #setTile} queues a change and {@link #getTile} reads the tile the map has after the queued changes; {@link #fillTiles},
 * {@link #copyTiles} and {@link #setTiles} queue the changes of a rectangle of tiles. The script's simulation plugin
 * applies the queued changes ({@link #applyChanges}) at the end of its frame start, before the triggers, the last change of a tile winning, so
 * every peer and every replay changes the same tiles at the same point of the same frame. Applying the changes:
 * <ul>
 * <li>replaces each changed tile ({@link GameMap#replaceTerrain}) with the terrain flags the map loader gives it (the tileset's walkability,
 * heights, sight blocking, buildability and creep bits, the bottom border rule), keeping the per-player visibility, the building occupancy and
 * the creep; a tile below creep keeps the creep and retains the new tile below it; the graphics cache of a tile without creep takes the new
 * tile's megatile;</li>
 * <li>builds the terrain contours and regions of the whole map again from the terrain without creep, through the loader's
 * {@link TerrainContours#create}, keeps the footprint controls of the previous contours, and makes every unit that follows a path search a
 * new one;</li>
 * <li>moves each ground unit that the terrain under it no longer carries to the nearest free walkable position, searched in growing rectangles
 * around it over the whole map ({@link UnitRelocation#displace(Unit, int, int, int, int)}), in the order of the unit list, and restarts the
 * current order of a moved completed unit whose order can be interrupted (the order's first state), so a unit that was moving keeps moving to its
 * order's target; the queued orders stay.</li>
 * </ul>
 * The computer players and the resource areas keep the regions the game started with ({@link PathSystem#getStartTerrainContours}).
 */
final class ScriptTerrainChanges {
    /**
     * The value of a tile without a queued change.
     */
    private static final int NO_CHANGE = -1;

    private final @NotNull Game game;

    private int @Nullable [] pendingTiles;
    private int pendingCount;

    /**
     * Creates a {@link ScriptTerrainChanges} instance without queued changes.
     *
     * @param game the game whose map changes
     */
    ScriptTerrainChanges(@NotNull Game game) {
        this.game = game;

        this.pendingTiles = null;
        this.pendingCount = 0;
    }

    /**
     * Reads a tile of the map after the queued changes: the queued tile, else the terrain of the map, the tile retained below creep first.
     *
     * @param tileX the horizontal coordinate in tiles
     * @param tileY the vertical coordinate in tiles
     * @return the tile WORD, the CV5 group shifted left by {@code 4} plus the tile's index in the group
     * @throws IndexOutOfBoundsException if the coordinates are outside the map
     */
    int getTile(int tileX, int tileY) {
        int tileIndex = this.toTileIndex(tileX, tileY);
        int[] pending = this.pendingTiles;

        if (pending != null && pending[tileIndex] != NO_CHANGE) {
            return pending[tileIndex];
        }

        return this.game.getMap().getTerrainTileIdentifier(tileIndex);
    }

    /**
     * Queues a change of a tile of the map, which the next frame start applies.
     *
     * @param tileX          the horizontal coordinate in tiles
     * @param tileY          the vertical coordinate in tiles
     * @param tileIdentifier the new tile WORD ({@link #isValidTile})
     * @throws IndexOutOfBoundsException if the coordinates are outside the map
     * @throws IllegalArgumentException  if the tile WORD names no tile of the map's tileset
     * @throws IllegalStateException     if the map has no expanded terrain flags or overlays, or the tileset cannot be loaded
     */
    void setTile(int tileX, int tileY, int tileIdentifier) {
        int tileIndex = this.toTileIndex(tileX, tileY);
        GameMap map = this.game.getMap();

        if (!map.hasTileFlags() || !map.hasOverlayTiles()) {
            throw Exceptions.newIllegalStateException("The map tiles of a game without terrain flags do not change.");
        }

        if (!this.isValidTile(tileIdentifier)) {
            throw Exceptions.newIllegalArgumentException("The tile {0} is not a tile of the tileset of the map.", tileIdentifier);
        }

        int[] pending = this.pendingTiles;

        if (pending == null) {
            pending = new int[map.getTileCount()];
            Arrays.fill(pending, NO_CHANGE);
            this.pendingTiles = pending;
        }

        if (pending[tileIndex] == NO_CHANGE) {
            this.pendingCount++;
        }

        pending[tileIndex] = tileIdentifier;
    }

    /**
     * Queues a change of every tile of a rectangle of the map to one tile WORD, which the next frame start applies.
     *
     * @param left           the left column in tiles
     * @param top            the top row in tiles
     * @param width          the width in tiles
     * @param height         the height in tiles
     * @param tileIdentifier the new tile WORD ({@link #isValidTile})
     * @throws IndexOutOfBoundsException if the rectangle leaves the map
     * @throws IllegalArgumentException  if the tile WORD names no tile of the map's tileset
     * @throws IllegalStateException     if the map has no expanded terrain flags or overlays, or the tileset cannot be loaded
     */
    void fillTiles(int left, int top, int width, int height, int tileIdentifier) {
        this.checkRectangle(left, top, width, height);

        if (!this.isValidTile(tileIdentifier)) {
            throw Exceptions.newIllegalArgumentException("The tile {0} is not a tile of the tileset of the map.", tileIdentifier);
        }

        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                this.setTile(left + column, top + row, tileIdentifier);
            }
        }
    }

    /**
     * Queues the changes that copy the tiles of a rectangle of the map, as {@link #getTile} reads them, onto the rectangle of the same size at
     * another place; the rectangles may overlap.
     *
     * @param sourceLeft the left column of the copied rectangle in tiles
     * @param sourceTop  the top row of the copied rectangle in tiles
     * @param width      the width in tiles
     * @param height     the height in tiles
     * @param targetLeft the left column of the changed rectangle in tiles
     * @param targetTop  the top row of the changed rectangle in tiles
     * @throws IndexOutOfBoundsException if a rectangle leaves the map
     * @throws IllegalStateException     if the map has no expanded terrain flags or overlays, or the tileset cannot be loaded
     */
    void copyTiles(int sourceLeft, int sourceTop, int width, int height, int targetLeft, int targetTop) {
        this.checkRectangle(sourceLeft, sourceTop, width, height);
        this.checkRectangle(targetLeft, targetTop, width, height);

        int[] tiles = new int[width * height];

        for (int index = 0; index < tiles.length; index++) {
            tiles[index] = this.getTile(sourceLeft + index % width, sourceTop + index / width);
        }

        this.setTiles(targetLeft, targetTop, width, height, tiles);
    }

    /**
     * Queues a change of every tile of a rectangle of the map, which the next frame start applies; nothing is queued when a tile WORD names no
     * tile of the map's tileset.
     *
     * @param left            the left column in tiles
     * @param top             the top row in tiles
     * @param width           the width in tiles
     * @param height          the height in tiles
     * @param tileIdentifiers the new tile WORDs of the rectangle, row by row from the top left tile
     * @throws IndexOutOfBoundsException if the rectangle leaves the map
     * @throws IllegalArgumentException  if the number of tile WORDs is not the number of tiles of the rectangle or a tile WORD names no tile of the tileset
     * @throws IllegalStateException     if the map has no expanded terrain flags or overlays, or the tileset cannot be loaded
     */
    void setTiles(int left, int top, int width, int height, int @NotNull [] tileIdentifiers) {
        this.checkRectangle(left, top, width, height);

        if (tileIdentifiers.length != width * height) {
            throw Exceptions.newIllegalArgumentException("A rectangle of {0}x{1} tiles takes {2} tiles, not {3}.", width, height, width * height,
                                                         tileIdentifiers.length);
        }

        for (int tileIdentifier : tileIdentifiers) {
            if (!this.isValidTile(tileIdentifier)) {
                throw Exceptions.newIllegalArgumentException("The tile {0} is not a tile of the tileset of the map.", tileIdentifier);
            }
        }

        for (int index = 0; index < tileIdentifiers.length; index++) {
            this.setTile(left + index % width, top + index / width, tileIdentifiers[index]);
        }
    }

    /**
     * @return {@code true} if a change is queued, {@code false} otherwise
     */
    boolean hasPendingChanges() {
        return this.pendingCount > 0;
    }

    /**
     * Checks whether a tile WORD names a tile of the map's tileset ({@link TerrainTileset#hasTile}).
     *
     * @param tileIdentifier the tile WORD
     * @return {@code true} if the tile WORD names a tile of the map's tileset, {@code false} otherwise
     * @throws IllegalStateException if the tileset cannot be loaded
     */
    boolean isValidTile(int tileIdentifier) {
        return this.requireTileset().hasTile(tileIdentifier);
    }

    /**
     * Returns the terrain flags the map loader gives a tile WORD ({@link TerrainTileset#expandTileFlags}), before the bottom border rule.
     *
     * @param tileIdentifier the tile WORD
     * @return the terrain flags ({@link MapTileFlags})
     * @throws IllegalArgumentException if the tile WORD names no tile of the map's tileset
     * @throws IllegalStateException    if the tileset cannot be loaded
     */
    int getTerrainFlags(int tileIdentifier) {
        TerrainTileset terrain = this.requireTileset();

        if (!terrain.hasTile(tileIdentifier)) {
            throw Exceptions.newIllegalArgumentException("The tile {0} is not a tile of the tileset of the map.", tileIdentifier);
        }

        return terrain.expandTileFlags(new int[]{tileIdentifier})[0];
    }

    /**
     * Applies the queued changes: the tiles, then the contours and regions of the whole map, the paths and the ground units on terrain that no
     * longer carries them. Nothing happens without a queued change.
     *
     * @throws IllegalStateException         if the tileset cannot be loaded
     * @throws UnsupportedOperationException if the contours of the changed map cannot be built
     */
    void applyChanges() {
        int[] pending = this.pendingTiles;

        if (pending == null || this.pendingCount == 0) {
            return;
        }

        GameMap map = this.game.getMap();
        TerrainTileset terrain = this.requireTileset();
        CreepField creep = this.game.getCreepField();
        int[] tile = new int[1];

        for (int tileIndex = 0; tileIndex < pending.length; tileIndex++) {
            int tileIdentifier = pending[tileIndex];

            if (tileIdentifier == NO_CHANGE) {
                continue;
            }

            pending[tileIndex] = NO_CHANGE;
            tile[0]            = map.getTerrainTileIdentifier(tileIndex);

            int previousFlags = map.applyTerrainBorder(tileIndex, terrain.expandTileFlags(tile)[0]);

            tile[0] = tileIdentifier;

            int flags = map.applyTerrainBorder(tileIndex, terrain.expandTileFlags(tile)[0]);
            boolean underCreep = map.getOverlayTileIdentifier(tileIndex) != 0;

            map.replaceTerrain(tileIndex, tileIdentifier, previousFlags, flags);

            if (creep != null && !underCreep) {
                int graphicsCache = terrain.getMegatileIdentifier(tileIdentifier) | tileIdentifier & CreepField.GRAPHICS_REFRESH_FLAG;

                creep.updateGraphicsCache(tileIndex, graphicsCache);
            }
        }

        this.pendingCount = 0;

        PathSystem paths = this.game.getPathSystem();
        TerrainContours previous = paths.getTerrainContours();

        if (previous == null) {
            return;
        }

        TerrainContours contours = TerrainContours.create(this.createLoadedMap(map, terrain), terrain);

        contours.copyFootprintControls(previous);
        paths.replaceTerrainContours(contours);
        this.updateUnits();
    }

    /**
     * Creates the map the loader builds contours from: the map's terrain without creep, its expanded terrain flags with the bottom border rule and
     * empty overlays.
     */
    private @NotNull GameMap createLoadedMap(@NotNull GameMap map, @NotNull TerrainTileset terrain) {
        int[] tileIdentifiers = new int[map.getTileCount()];

        for (int tileIndex = 0; tileIndex < tileIdentifiers.length; tileIndex++) {
            tileIdentifiers[tileIndex] = map.getTerrainTileIdentifier(tileIndex);
        }

        GameMap loaded = new GameMap(map.getWidth(), map.getHeight(), map.getTileset(), tileIdentifiers, terrain.expandTileFlags(tileIdentifiers));

        loaded.initializeTerrainBorders();
        loaded.initializeOverlayTiles();

        return loaded;
    }

    /**
     * Makes every unit that follows a path search a new one, then moves the ground units that the terrain no longer carries, found first, in the
     * order of the unit list, to the nearest free walkable position on the map, and restarts the interruptible current order of a moved completed
     * unit.
     */
    private void updateUnits() {
        UnitPool pool = this.game.getUnitSystem().getUnitPool();

        if (pool == null) {
            return;
        }

        PathPool paths = this.game.getPathSystem().getPathPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (paths != null && unit.getPathIndex() >= 0) {
                paths.setFlags(unit.getPathIndex(), paths.getFlags(unit.getPathIndex()) | PathPool.FLAG_NEW_MOVE_TARGET);
            }
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        List<Unit> displaced = new ArrayList<>();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (this.isDisplaced(unit, units)) {
                displaced.add(unit);
            }
        }

        GameMap map = this.game.getMap();
        int right = map.getWidth() * TerrainTileset.TILE_SIZE - 1;
        int bottom = map.getHeight() * TerrainTileset.TILE_SIZE - 1;

        for (Unit unit : displaced) {
            if (this.game.getUnitRelocation().displace(unit, 0, 0, right, bottom) &&
                (unit.getStatus() & (UnitStatusFlags.COMPLETED | UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE)) == UnitStatusFlags.COMPLETED) {
                unit.setPrimaryOrderState(0);
            }
        }
    }

    /**
     * Checks whether the terrain under a unit no longer carries it: a shown, living, non-building ground unit that is not a subunit, whose
     * placement the terrain refuses.
     */
    private boolean isDisplaced(@NotNull Unit unit, @NotNull ScrUnitTypeView units) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null || (sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0 || (unit.getStatus() & UnitStatusFlags.FLYING) != 0 ||
            unit.getPrimaryOrder() == ScrOrderIds.DIE || UnitQueries.isGroundedBuilding(unit) ||
            (units.getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            return false;
        }

        return !this.game.getUnitPositioning().isTerrainPlacementWalkable(unit.getUnitId(), sprite.getX(), sprite.getY());
    }

    /**
     * Checks that a rectangle of tiles lies inside the map.
     *
     * @throws IndexOutOfBoundsException if the rectangle leaves the map or has a negative size
     */
    private void checkRectangle(int left, int top, int width, int height) {
        GameMap map = this.game.getMap();

        if (left < 0 || top < 0 || width < 0 || height < 0 || left > map.getWidth() - width || top > map.getHeight() - height) {
            throw Exceptions.newIndexOutOfBoundsException("The {0}x{1} tiles at {2}, {3} leave the map.", width, height, left, top);
        }
    }

    private int toTileIndex(int tileX, int tileY) {
        GameMap map = this.game.getMap();

        if (tileX < 0 || tileX >= map.getWidth() || tileY < 0 || tileY >= map.getHeight()) {
            throw Exceptions.newIndexOutOfBoundsException("The tile {0}, {1} is outside the map.", tileX, tileY);
        }

        return tileY * map.getWidth() + tileX;
    }

    private @NotNull TerrainTileset requireTileset() {
        try {
            return this.game.requireGameData().getTerrainTileset(this.game.getMap().getTileset());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "The tileset of the map cannot be loaded.");
        }
    }
}
