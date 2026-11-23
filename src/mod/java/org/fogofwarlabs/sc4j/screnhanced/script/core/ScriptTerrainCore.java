package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitGroupMovement;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.screnhanced.graphics.TerrainReplacements;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.*;

import java.io.IOException;

/**
 * Defines {@code core.terrain}: the tiles of the map by tile coordinates ({@code 32} pixels), as MTXM values ({@code group << 4 | index}, the
 * CV5 group and the tile's index in it).
 * <p>
 * {@code getTile(tileX, tileY)} reads the tile the map has after the changes queued so far, the tile below creep for a tile with creep;
 * {@code getTileFlags(tileX, tileY)} reads the tile's flag DWORD as the game holds it now (the sight bits of {@code core.vision}, walkability,
 * creep, elevation and occupation, {@link MapTileFlags}); {@code hasCreep(tileX, tileY)} tests the creep flag of those flags, which the rules
 * of the game read (placement, larvae, spells) and the creep routines of {@code core.creep} set and clear;
 * {@code setTile(tileX, tileY, value)} queues a change, which the game applies at the end of the script's next frame start, before the
 * triggers, as on a map that had the tile from the start ({@link ScriptTerrainChanges}); {@code fillTiles(left, top, width, height, value)},
 * {@code copyTiles(sourceLeft, sourceTop, width, height, targetLeft, targetTop)} and {@code setTiles(left, top, width, height, values)} queue the
 * changes of a rectangle of tiles in one call, {@code values} an array of values or of objects with a value {@code id}, row by row; a rectangle
 * that leaves the map throws a {@code RangeError}, and nothing is queued when a value names no tile. {@code getTileGroupCount()} is the
 * number of CV5 groups of the map's tileset and {@code isValidTile(value)} tells whether a value names a tile of it. The terrain a value gives
 * a tile:
 * {@code isTileWalkable(value)} (more than twelve of its sixteen mini-tiles are walkable), {@code isTilePartiallyWalkable(value)} (some but
 * not all are), {@code isTileBuildable(value)}, {@code hasTileCreep(value)}, {@code getTileElevation(value)} ({@code 0} low, {@code 1}
 * middle and {@code 2} high ground) and {@code isTileBlockingSight(value)} (a tile that blocks the sight of ground units at every
 * elevation). A coordinate outside the map or a value that names no tile of the tileset throws a {@code RangeError}.
 * <p>
 * The ground of the pathing regions, in map pixels: {@code findWalkablePosition(x, y, fromX, fromY, unitType)} is the position a move command
 * to {@code x, y} gives one ground unit of the type at {@code fromX, fromY}, as {@code [x, y]}: the position itself where the type stands, else
 * walkable ground nearby on the unit's side ({@link UnitGroupMovement#calculateUnitPosition}); {@code isReachable(fromX, fromY, x, y)} tells
 * whether both positions lie in one connected area of the ground, as the routines test it (an unwalkable position lies in an area of its own
 * blocked region). Both read the regions the game has now and throw a {@code TypeError} when the game has none; a position outside the map
 * throws a {@code RangeError}.
 * <p>
 * {@code replaceTileImage(value, imageId)} replaces the picture of the megatile a value shows, for every tile that shows it, with an image
 * ({@code core.resources.getImage}) as wide and high as a multiple of {@code 32} pixels: the HD and HD2 graphics draw the image stretched over
 * the tile with its alpha over the installed tile, the classic graphics its pixels quantized to the tileset palette where they are opaque
 * ({@link TerrainReplacements}). It is called in shared code at the module top level or in {@code onStart} ({@link ScriptGame#isTypeRegistration()});
 * the replacement is presentation and changes no game state.
 */
final class ScriptTerrainCore {
    /**
     * The elevations of {@code getTileElevation}.
     */
    private static final int LOW_GROUND = 0;
    private static final int MIDDLE_GROUND = TerrainTileset.MIDDLE_GROUND_HEIGHT;
    private static final int HIGH_GROUND = TerrainTileset.HIGH_GROUND_HEIGHT;

    /**
     * The mask of the horizontal WORD of a packed position, and the shift of its vertical WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    private static final int VERTICAL_SHIFT = Short.SIZE;

    /**
     * The name of the property that holds the value of a tile object.
     */
    private static final @NotNull String TILE_ID_PROPERTY = "id";

    private final @NotNull ScriptGame scriptGame;
    private final @NotNull ScriptGraphicsCore graphicsCore;

    /**
     * Creates a {@link ScriptTerrainCore} instance.
     *
     * @param scriptGame   the game binding
     * @param graphicsCore the resources, which read image arguments and convert them to the tileset palette
     */
    ScriptTerrainCore(@NotNull ScriptGame scriptGame, @NotNull ScriptGraphicsCore graphicsCore) {
        this.scriptGame   = scriptGame;
        this.graphicsCore = graphicsCore;
    }

    /**
     * Defines the functions of {@code core.terrain}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getTile", 2, (context, scope, thisObject, arguments) -> {
            int tileX = this.readTileX(arguments, 0);
            int tileY = this.readTileY(arguments, 1);

            return (double) this.getChanges().getTile(tileX, tileY);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getTileFlags", 2, (context, scope, thisObject, arguments) -> {
            int tileX = this.readTileX(arguments, 0);
            int tileY = this.readTileY(arguments, 1);
            GameMap map = this.scriptGame.getGame().getMap();

            if (!map.hasTileFlags()) {
                throw ScriptRuntime.typeError("The map has no tile flags.");
            }

            return (double) Integer.toUnsignedLong(map.getTileFlags(tileX, tileY));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "hasCreep", 2, (context, scope, thisObject, arguments) -> {
            int tileX = this.readTileX(arguments, 0);
            int tileY = this.readTileY(arguments, 1);
            GameMap map = this.scriptGame.getGame().getMap();

            if (!map.hasTileFlags()) {
                throw ScriptRuntime.typeError("The map has no tile flags.");
            }

            return (map.getTileFlags(tileX, tileY) & MapTileFlags.HAS_CREEP) != 0;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setTile", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int tileX = this.readTileX(arguments, 0);
            int tileY = this.readTileY(arguments, 1);
            int value = this.readTile(arguments, 2);

            this.getChanges().setTile(tileX, tileY, value);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "fillTiles", 5, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int value = this.readTile(arguments, 4);

            try {
                this.getChanges().fillTiles(ScriptCoreUtils.readInteger(arguments, 0), ScriptCoreUtils.readInteger(arguments, 1),
                                            ScriptCoreUtils.readInteger(arguments, 2), ScriptCoreUtils.readInteger(arguments, 3), value);
            } catch (IndexOutOfBoundsException exception) {
                throw ScriptRuntime.rangeError(exception.getMessage());
            }

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "copyTiles", 6, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            try {
                this.getChanges().copyTiles(ScriptCoreUtils.readInteger(arguments, 0), ScriptCoreUtils.readInteger(arguments, 1),
                                            ScriptCoreUtils.readInteger(arguments, 2), ScriptCoreUtils.readInteger(arguments, 3),
                                            ScriptCoreUtils.readInteger(arguments, 4), ScriptCoreUtils.readInteger(arguments, 5));
            } catch (IndexOutOfBoundsException exception) {
                throw ScriptRuntime.rangeError(exception.getMessage());
            }

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setTiles", 5, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int[] values = readTileValues(arguments, 4);

            try {
                this.getChanges().setTiles(ScriptCoreUtils.readInteger(arguments, 0), ScriptCoreUtils.readInteger(arguments, 1),
                                           ScriptCoreUtils.readInteger(arguments, 2), ScriptCoreUtils.readInteger(arguments, 3), values);
            } catch (IndexOutOfBoundsException | IllegalArgumentException exception) {
                throw ScriptRuntime.rangeError(exception.getMessage());
            }

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "replaceTileImage", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            if (!this.scriptGame.isTypeRegistration()) {
                throw ScriptRuntime.typeError("Tile images are replaced at the module top level or in onStart only.");
            }

            int value = this.readTile(arguments, 0);
            Picture picture = this.graphicsCore.readPicture(arguments, 1);
            byte[] coverage = new byte[TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE];
            byte[] pixels;

            try {
                pixels = this.graphicsCore.getConverter().quantizeTile(picture, coverage);
            } catch (IllegalArgumentException exception) {
                throw ScriptRuntime.rangeError(exception.getMessage());
            }

            this.scriptGame.getTerrainReplacements().replaceTile(this.requireTileset().getMegatileIdentifier(value), picture, pixels, coverage);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getTileGroupCount", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.requireTileset().getGroupCount());
        ScriptCoreUtils.defineFunction(global, namespace, "isValidTile", 1, (context, scope, thisObject, arguments) -> {
            int value = ScriptCoreUtils.readInteger(arguments, 0);

            return this.getChanges().isValidTile(value);
        });

        ScriptCoreUtils.defineFunction(global, namespace, "isTileWalkable", 1,
                                       (context, scope, thisObject, arguments) -> (this.readFlags(arguments) & MapTileFlags.WALKABLE) != 0);
        ScriptCoreUtils.defineFunction(global, namespace, "isTilePartiallyWalkable", 1,
                                       (context, scope, thisObject, arguments) -> (this.readFlags(arguments) & MapTileFlags.PARTIALLY_WALKABLE) != 0);
        ScriptCoreUtils.defineFunction(global, namespace, "isTileBuildable", 1,
                                       (context, scope, thisObject, arguments) -> (this.readFlags(arguments) & MapTileFlags.UNBUILDABLE) == 0);
        ScriptCoreUtils.defineFunction(global, namespace, "hasTileCreep", 1,
                                       (context, scope, thisObject, arguments) -> (this.readFlags(arguments) & MapTileFlags.HAS_CREEP) != 0);
        ScriptCoreUtils.defineFunction(global, namespace, "getTileElevation", 1, (context, scope, thisObject, arguments) -> {
            int flags = this.readFlags(arguments);

            if ((flags & MapTileFlags.HIGH) != 0) {
                return (double) HIGH_GROUND;
            }

            return (double) ((flags & MapTileFlags.MIDDLE) != 0 ? MIDDLE_GROUND : LOW_GROUND);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isTileBlockingSight", 1,
                                       (context, scope, thisObject, arguments) -> (this.readFlags(arguments) & MapTileFlags.VERY_HIGH) != 0);

        ScriptCoreUtils.defineFunction(global, namespace, "findWalkablePosition", 5, (context, scope, thisObject, arguments) -> {
            int x = this.readMapX(arguments, 0);
            int y = this.readMapY(arguments, 1);
            int fromX = this.readMapX(arguments, 2);
            int fromY = this.readMapY(arguments, 3);
            int unitType = ScriptCoreUtils.requireId("unit type", ScriptCoreUtils.readInteger(arguments, 4), ScrUnitIds.NONE);

            this.requireContours();

            int position = UnitGroupMovement.calculateUnitPosition(this.scriptGame.getGame(), unitType, fromX, fromY, x, y);

            return context.newArray(global, new Object[]{(double) (position & WORD_MASK), (double) (position >>> VERTICAL_SHIFT)});
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isReachable", 4, (context, scope, thisObject, arguments) -> {
            int fromX = this.readMapX(arguments, 0);
            int fromY = this.readMapY(arguments, 1);
            int x = this.readMapX(arguments, 2);
            int y = this.readMapY(arguments, 3);

            return this.requireContours().getRegions().sharesComponent(fromX, fromY, x, y);
        });
    }

    /**
     * Reads an array argument of tile values: numbers, or objects whose {@code id} is the value.
     *
     * @throws EcmaError if the argument is not an array or an element is neither an integer nor an object with an integer {@code id}
     */
    private static int @NotNull [] readTileValues(@NotNull Object @NotNull [] arguments, int index) {
        if (index >= arguments.length || !(arguments[index] instanceof NativeArray array)) {
            throw ScriptRuntime.typeError(Exceptions.format("Argument {0} is not an array.", index + 1));
        }

        int[] values = new int[(int) array.getLength()];

        for (int element = 0; element < values.length; element++) {
            Object value = array.get(element, array);

            if (value instanceof Scriptable tile) {
                value = ScriptableObject.getProperty(tile, TILE_ID_PROPERTY);
            }

            double number = value instanceof Number numeric ? numeric.doubleValue() : Double.NaN;

            if ((int) number != number) {
                throw ScriptRuntime.typeError(Exceptions.format("Element {0} of argument {1} is not a tile value.", element, index + 1));
            }

            values[element] = (int) number;
        }

        return values;
    }

    private @NotNull ScriptTerrainChanges getChanges() {
        return this.scriptGame.getTerrainChanges();
    }

    private int readTileX(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("tile x", ScriptCoreUtils.readInteger(arguments, index), this.scriptGame.getGame().getMap().getWidth());
    }

    private int readTileY(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("tile y", ScriptCoreUtils.readInteger(arguments, index), this.scriptGame.getGame().getMap().getHeight());
    }

    private int readMapX(@NotNull Object @NotNull [] arguments, int index) {
        int width = this.scriptGame.getGame().getMap().getWidth() * TerrainTileset.TILE_SIZE;

        return ScriptCoreUtils.requireId("x", ScriptCoreUtils.readInteger(arguments, index), width);
    }

    private int readMapY(@NotNull Object @NotNull [] arguments, int index) {
        int height = this.scriptGame.getGame().getMap().getHeight() * TerrainTileset.TILE_SIZE;

        return ScriptCoreUtils.requireId("y", ScriptCoreUtils.readInteger(arguments, index), height);
    }

    /**
     * @return the terrain contours the game has now
     * @throws EcmaError if the game has no pathing regions
     */
    private @NotNull TerrainContours requireContours() {
        TerrainContours contours = this.scriptGame.getGame().getPathSystem().getTerrainContours();

        if (contours == null) {
            throw ScriptRuntime.typeError("The game has no pathing regions.");
        }

        return contours;
    }

    /**
     * Reads a tile value argument.
     *
     * @throws EcmaError if the argument is not an integer or names no tile of the map's tileset
     */
    private int readTile(@NotNull Object @NotNull [] arguments, int index) {
        int value = ScriptCoreUtils.readInteger(arguments, index);

        if (!this.getChanges().isValidTile(value)) {
            throw ScriptRuntime.rangeError(Exceptions.format("The tile {0} is not a tile of the tileset of the map.", value));
        }

        return value;
    }

    /**
     * Reads the terrain flags a tile value argument gives a tile.
     *
     * @throws EcmaError if the argument is not an integer or names no tile of the map's tileset
     */
    private int readFlags(@NotNull Object @NotNull [] arguments) {
        return this.getChanges().getTerrainFlags(this.readTile(arguments, 0));
    }

    private @NotNull TerrainTileset requireTileset() {
        Game game = this.scriptGame.getGame();

        try {
            return game.requireGameData().getTerrainTileset(game.getMap().getTileset());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "The tileset of the map cannot be loaded.");
        }
    }
}
