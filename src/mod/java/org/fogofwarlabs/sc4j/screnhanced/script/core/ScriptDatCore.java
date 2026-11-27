package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.table.ScrDatKind;
import org.fogofwarlabs.sc4j.scr.data.format.dat.*;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.*;

/**
 * Defines {@code core.dat}: one namespace per table of {@link ScriptDatTables} ({@code core.dat.units} and the others) with
 * {@code get<Column>(id)} for every column and {@code set<Column>(id, value)} for every writable column.
 * <p>
 * The values are the raw values of the game's own table, which EUD writes change too: unsigned columns read from {@code 0}, the signed ones
 * keep their sign. An id outside the entries a column holds reads the value the game reads there, or throws a {@code RangeError} where the game
 * reads no table. A write takes an integer of the column's type and, for a validated column, below its limit; the id must be one the column
 * holds. A write changes only the current game.
 * <p>
 * The {@code flingy}, {@code sprites} and {@code images} tables also hold the types the script registered ({@link ScriptContentCore}): every
 * column of a registered id reads and writes its entry, and a reference column whose limit is the native count of the flingy, sprite or image
 * types takes the registered ids as well.
 * <p>
 * The prelude makes the methods of the type objects from {@link ScriptDatTables#createPreludeTables}.
 */
final class ScriptDatCore {
    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptDatCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptDatCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the table namespaces of {@code core.dat}, each closed to new properties.
     *
     * @param context   the current context
     * @param namespace the {@code core.dat} object
     * @param global    the global scope
     */
    void define(@NotNull Context context, @NotNull Scriptable namespace, @NotNull Scriptable global) {
        for (ScriptDatTables.Table table : ScriptDatTables.getTables()) {
            ScriptableObject tableNamespace = ScriptCoreUtils.createNamespace(context, global, namespace, table.getName());

            for (ScriptDatTables.Column column : table.getColumns()) {
                ScriptCoreUtils.defineFunction(global, tableNamespace, "get" + column.getName(), 1, (callContext, scope, thisObject, arguments) -> {
                    int id = ScriptCoreUtils.requireId(table.getKind(), ScriptCoreUtils.readInteger(arguments, 0), this.getCount(table));

                    return (double) this.read(table, column, id);
                });

                if (column.isWritable()) {
                    ScriptCoreUtils.defineFunction(global, tableNamespace, "set" + column.getName(), 2, (callContext, scope, thisObject, arguments) -> {
                        this.scriptGame.checkWritable();

                        int id = ScriptCoreUtils.requireId(table.getKind(), ScriptCoreUtils.readInteger(arguments, 0), this.getCount(table));
                        long value = ScriptCoreUtils.readLong(arguments, 1);

                        this.write(table, column, id, value);

                        return Context.getUndefinedValue();
                    });
                }
            }

            tableNamespace.preventExtensions();
        }
    }

    /**
     * Reads a column of an entry: the element of the column, or outside the entries it holds the value the game reads there.
     *
     * @return the raw value, unsigned for an unsigned column
     * @throws EcmaError if the game reads no table for the entry
     */
    private long read(ScriptDatTables.@NotNull Table table, ScriptDatTables.@NotNull Column column, int id) {
        if (id >= table.getCount()) {
            int registered = this.scriptGame.getContentCore().readValue(requireKind(table), column.getFieldName(), id);

            return column.getType() == DatType.U32 ? Integer.toUnsignedLong(registered) : registered;
        }

        DatTable values = table.getTable(this.scriptGame.getGame().requireGameData());
        int element = id - column.getFirstId();
        int value;

        if (element >= 0 && element < column.getCount()) {
            value = values.get(column.getFieldName(), element);
        } else {
            try {
                value = values.readNativeElement(column.getFieldName(), element);
            } catch (UnsupportedOperationException | ArrayIndexOutOfBoundsException exception) {
                throw ScriptRuntime.rangeError(Exceptions.format("The {0} {1} has no {2} value.", table.getKind(), id, column.getName()));
            }
        }

        return column.getType() == DatType.U32 ? Integer.toUnsignedLong(value) : value;
    }

    /**
     * Writes a column of an entry the column holds.
     *
     * @throws EcmaError if the column does not hold the entry, or the value is outside the column's type or not below its limit
     */
    private void write(ScriptDatTables.@NotNull Table table, ScriptDatTables.@NotNull Column column, int id, long value) {
        int element = id - column.getFirstId();
        boolean registered = id >= table.getCount();

        if (!registered && (element < 0 || element >= column.getCount())) {
            throw ScriptRuntime.rangeError(Exceptions.format("{0} holds the {1}s {2} to {3}, not {4}.", column.getName(), table.getKind(),
                                                             column.getFirstId(), column.getFirstId() + column.getCount() - 1, id));
        }

        if (value < column.getMinimumValue() || value > column.getMaximumValue()) {
            throw ScriptRuntime.rangeError(Exceptions.format("The {0} value {1} is not from {2} to {3}.", column.getName(), value,
                                                             column.getMinimumValue(), column.getMaximumValue()));
        }

        int limit = this.getLimit(column);

        if (limit != ScriptDatTables.NO_LIMIT && value >= limit) {
            throw ScriptRuntime.rangeError(Exceptions.format("The {0} value {1} is not below {2}.", column.getName(), value, limit));
        }

        if (registered) {
            this.scriptGame.getContentCore().writeValue(requireKind(table), column.getFieldName(), id, (int) value);
        } else {
            table.getTable(this.scriptGame.getGame().requireGameData()).set(column.getFieldName(), element, (int) value);
        }
    }

    /**
     * @return the number of ids of a table: the native entries and, for the flingy, sprite and image tables, the registered types
     */
    private int getCount(ScriptDatTables.@NotNull Table table) {
        ScriptContentCore content = this.scriptGame.getContentCore();

        return switch (table.getType()) {
            case FLINGY_TYPE -> content.getFlingyTypeCount();
            case SPRITE_TYPE -> content.getSpriteTypeCount();
            case IMAGE_TYPE -> content.getImageTypeCount();
            default -> table.getCount();
        };
    }

    /**
     * @return the limit of a write: that of the column, or the count of the flingy, sprite or image types, registered ones included, for a
     *         reference column whose limit is the native count of its types
     */
    private int getLimit(ScriptDatTables.@NotNull Column column) {
        ScriptDatTables.Reference reference = column.getReference();
        ScriptContentCore content = this.scriptGame.getContentCore();

        if (reference == ScriptDatTables.Reference.FLINGY_TYPE && column.getLimit() == FlingyDat.FLINGY_COUNT) {
            return content.getFlingyTypeCount();
        }

        if (reference == ScriptDatTables.Reference.SPRITE_TYPE && column.getLimit() == SpritesDat.SPRITE_COUNT) {
            return content.getSpriteTypeCount();
        }

        if (reference == ScriptDatTables.Reference.IMAGE_TYPE && column.getLimit() == ImagesDat.IMAGE_COUNT) {
            return content.getImageTypeCount();
        }

        return column.getLimit();
    }

    /**
     * @return the DAT kind of a table that holds registered types
     * @throws IllegalStateException if the table holds none
     */
    private static @NotNull ScrDatKind requireKind(ScriptDatTables.@NotNull Table table) {
        return switch (table.getType()) {
            case FLINGY_TYPE -> ScrDatKind.FLINGY;
            case SPRITE_TYPE -> ScrDatKind.SPRITES;
            case IMAGE_TYPE -> ScrDatKind.IMAGES;
            default -> throw Exceptions.newIllegalStateException("The table {0} holds no registered types.", table.getName());
        };
    }
}
