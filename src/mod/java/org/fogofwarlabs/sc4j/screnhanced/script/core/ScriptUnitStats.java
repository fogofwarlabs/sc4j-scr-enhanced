package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * The stats a game's script gives single units ({@code core.unit}): a weapon range bonus added to the maximum range of the unit's weapons, a speed
 * percentage of its movement speeds and acceleration, and its maximum hit points. The simulation reads them through the adjustments of the
 * script's simulation plugin, so they answer without allocating.
 * <p>
 * A unit's stats last while its unit pool slot holds the same unit (the slot is allocated and keeps its generation); a slot that holds another
 * unit has the native stats. The stats are written in shared code only, so every peer and every replay holds the same stats; a game with a
 * script cannot be saved, so the stats are not saved.
 */
public final class ScriptUnitStats {
    /**
     * The speed percentage of a unit without one: the native speeds.
     */
    public static final int NATIVE_SPEED_PERCENT = 100;
    /**
     * The maximum hit points of a unit without its own: those of its type.
     */
    public static final int TYPE_MAXIMUM_HIT_POINTS = 0;

    /**
     * The generation of a slot without stats.
     */
    private static final int NO_GENERATION = -1;

    private final @NotNull Supplier<@Nullable UnitPool> pools;

    /* The stats by slot index, empty until a unit gets a stat. */
    private int @NotNull [] generations;
    private int @NotNull [] weaponRangeBonuses;
    private int @NotNull [] speedPercents;
    private int @NotNull [] maximumHitPoints;

    /**
     * Creates a {@link ScriptUnitStats} instance in which every unit has the native stats.
     *
     * @param pools the game's unit pool, {@code null} while the game has none
     */
    public ScriptUnitStats(@NotNull Supplier<@Nullable UnitPool> pools) {
        this.pools = pools;

        this.generations        = new int[0];
        this.weaponRangeBonuses = new int[0];
        this.speedPercents      = new int[0];
        this.maximumHitPoints   = new int[0];
    }

    /**
     * @param unit the unit
     * @return the range in pixels added to the maximum range of the unit's weapons, {@code 0} without a bonus
     */
    public int getWeaponRangeBonus(@NotNull Unit unit) {
        int index = this.findIndex(unit);

        return index < 0 ? 0 : this.weaponRangeBonuses[index];
    }

    /**
     * Sets the range in pixels added to the maximum range of the unit's weapons.
     *
     * @param unit  a live unit
     * @param bonus the bonus in pixels, negative to shorten the range
     */
    public void setWeaponRangeBonus(@NotNull Unit unit, int bonus) {
        int index = this.claimIndex(unit);

        this.weaponRangeBonuses[index] = bonus;
    }

    /**
     * @param unit the unit
     * @return the percentage of the unit's movement speeds and acceleration, {@link #NATIVE_SPEED_PERCENT} for the native ones
     */
    public int getSpeedPercent(@NotNull Unit unit) {
        int index = this.findIndex(unit);

        return index < 0 ? NATIVE_SPEED_PERCENT : this.speedPercents[index];
    }

    /**
     * Sets the percentage of the unit's movement speeds and acceleration.
     *
     * @param unit    a live unit
     * @param percent the percentage, at least {@code 0}
     */
    public void setSpeedPercent(@NotNull Unit unit, int percent) {
        int index = this.claimIndex(unit);

        this.speedPercents[index] = percent;
    }

    /**
     * @param unit the unit
     * @return the unit's own maximum hit points with eight fractional bits, {@link #TYPE_MAXIMUM_HIT_POINTS} for those of its type
     */
    public int getMaximumHitPoints(@NotNull Unit unit) {
        int index = this.findIndex(unit);

        return index < 0 ? TYPE_MAXIMUM_HIT_POINTS : this.maximumHitPoints[index];
    }

    /**
     * Sets the unit's own maximum hit points.
     *
     * @param unit             a live unit
     * @param maximumHitPoints the maximum hit points with eight fractional bits, at least {@code 1}, or {@link #TYPE_MAXIMUM_HIT_POINTS} for
     *                         those of its type
     */
    public void setMaximumHitPoints(@NotNull Unit unit, int maximumHitPoints) {
        int index = this.claimIndex(unit);

        this.maximumHitPoints[index] = maximumHitPoints;
    }


    /**
     * Adds the unit's weapon range bonus to the maximum range of one of its weapons.
     *
     * @param unit  the attacking unit or turret
     * @param range the range in pixels
     * @return the range in pixels
     */
    public int adjustWeaponRange(@NotNull Unit unit, int range) {
        int index = this.findIndex(unit);

        return index < 0 ? range : range + this.weaponRangeBonuses[index];
    }

    /**
     * Applies the unit's speed percentage to a movement speed or acceleration.
     *
     * @param unit  the unit
     * @param value the speed in {@code 1/256} pixels per frame, or the acceleration
     * @return the value times the percentage, rounded toward zero
     */
    public int adjustSpeed(@NotNull Unit unit, int value) {
        int index = this.findIndex(unit);

        if (index < 0 || this.speedPercents[index] == NATIVE_SPEED_PERCENT) {
            return value;
        }

        return (int) ((long) value * this.speedPercents[index] / NATIVE_SPEED_PERCENT);
    }

    /**
     * Replaces the maximum hit points of the unit's type with the unit's own.
     *
     * @param unit             the unit
     * @param maximumHitPoints the maximum hit points of the unit's type with eight fractional bits
     * @return the unit's maximum hit points with eight fractional bits
     */
    public int adjustMaximumHitPoints(@NotNull Unit unit, int maximumHitPoints) {
        int index = this.findIndex(unit);

        return index < 0 || this.maximumHitPoints[index] == TYPE_MAXIMUM_HIT_POINTS ? maximumHitPoints : this.maximumHitPoints[index];
    }

    /**
     * Finds the slot index of a unit that has stats.
     *
     * @return the slot index, or {@code -1} if no stat was set or the slot holds another unit
     */
    private int findIndex(@NotNull Unit unit) {
        int index = unit.getIndex();

        if (index >= this.generations.length || this.generations[index] == NO_GENERATION) {
            return -1;
        }

        UnitPool pool = this.pools.get();

        return pool != null && pool.isAllocated(index) && pool.getGeneration(index) == this.generations[index] ? index : -1;
    }

    /**
     * Finds the slot index of a live unit for a stat write: a slot that holds another unit than the one its stats were set for starts again from
     * the native stats.
     *
     * @return the slot index
     */
    private int claimIndex(@NotNull Unit unit) {
        UnitPool pool = this.pools.get();

        if (pool == null) {
            throw Exceptions.newIllegalStateException("The game has no unit pool.");
        }

        int index = unit.getIndex();

        if (this.generations.length == 0) {
            int length = pool.getCapacity() + 1;

            this.generations        = new int[length];
            this.weaponRangeBonuses = new int[length];
            this.speedPercents      = new int[length];
            this.maximumHitPoints   = new int[length];

            Arrays.fill(this.generations, NO_GENERATION);
        }

        int generation = pool.getGeneration(index);

        if (this.generations[index] != generation) {
            this.generations[index]        = generation;
            this.weaponRangeBonuses[index] = 0;
            this.speedPercents[index]      = NATIVE_SPEED_PERCENT;
            this.maximumHitPoints[index]   = TYPE_MAXIMUM_HIT_POINTS;
        }

        return index;
    }
}
