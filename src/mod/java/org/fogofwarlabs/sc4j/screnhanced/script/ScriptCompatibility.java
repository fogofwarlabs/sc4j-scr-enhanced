package org.fogofwarlabs.sc4j.screnhanced.script;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The compatibility mark of a map with a script package: whether the map needs SC:R Enhanced to play correctly or plays the same in regular
 * SC:R, which does not run its scripts. The mark decides the extension of the packed map ({@link ScriptTool}).
 */
public enum ScriptCompatibility {
    /**
     * The scripts change the game: the map needs SC:R Enhanced and is written with {@link #ENHANCED_MAP_EXTENSION}, which regular SC:R does
     * not list.
     */
    ENHANCED_ONLY("enhanced-only"),
    /**
     * The scripts only add graphics and conveniences: the map plays the same in regular SC:R and keeps {@code .scm} or {@code .scx}.
     */
    COMPATIBLE("compatible"),
    /**
     * No mark is given: the packing scans the scripts and picks {@link #ENHANCED_ONLY} or {@link #COMPATIBLE}
     * ({@link ScriptCompatibilityScan}).
     */
    AUTO("auto");

    /**
     * The file name extension of a map that needs SC:R Enhanced, with the dot: the same MPQ format as {@code .scm} and {@code .scx}.
     */
    public static final @NotNull String ENHANCED_MAP_EXTENSION = ".sce";

    /**
     * Finds the mark of a name.
     *
     * @param name the name, such as {@code enhanced-only}
     * @return the mark with the name, or {@code null} if no mark has it
     */
    public static @Nullable ScriptCompatibility findByName(@NotNull String name) {
        for (ScriptCompatibility compatibility : values()) {
            if (compatibility.name.equals(name)) {
                return compatibility;
            }
        }

        return null;
    }


    private final @NotNull String name;

    ScriptCompatibility(@NotNull String name) {
        this.name = name;
    }

    /**
     * @return the name of the mark in a manifest and on the command line, such as {@code enhanced-only}
     */
    public @NotNull String getName() {
        return this.name;
    }
}
