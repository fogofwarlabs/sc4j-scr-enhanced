package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.jetbrains.annotations.NotNull;

/**
 * A simulation plugin of a game that replaces terrain pictures ({@link TerrainReplacements}); the classic terrain graphics of the game find it
 * among the game's simulation plugins and draw its replacements ({@link ClassicTerrainGraphics}).
 */
public interface TerrainReplacementSource {
    /**
     * @return the terrain pictures the plugin replaces, the same instance for the whole game
     */
    @NotNull TerrainReplacements getTerrainReplacements();
}
