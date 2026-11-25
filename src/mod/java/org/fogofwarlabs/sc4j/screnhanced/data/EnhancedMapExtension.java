package org.fogofwarlabs.sc4j.screnhanced.data;

import org.fogofwarlabs.sc4j.scr.data.extension.ScrDataExtension;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptCompatibility;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Lets SC:R Enhanced list, open, host and transfer the maps that need it, written with {@link ScriptCompatibility#ENHANCED_MAP_EXTENSION}, as
 * it does the {@code .scm} and {@code .scx} maps.
 */
public final class EnhancedMapExtension implements ScrDataExtension {
    /**
     * Returns the file name extensions of map archives besides the native {@code .scm} and {@code .scx}: a file with one of them is listed,
     * opened, hosted and transferred as a map, in the same MPQ format.
     *
     * @return {@link ScriptCompatibility#ENHANCED_MAP_EXTENSION}
     */
    @Override
    public @NotNull List<@NotNull String> getExtraMapFileExtensions() {
        return List.of(ScriptCompatibility.ENHANCED_MAP_EXTENSION);
    }
}
