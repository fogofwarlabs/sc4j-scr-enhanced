package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGameExtension;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gives every game its classic terrain pictures ({@link ClassicTerrainGraphics}) over the installed tileset of its map, which also draw the terrain
 * pictures a simulation plugin of the game replaces ({@link TerrainReplacementSource}).
 */
public final class ClassicTerrainExtension implements ScrGameExtension {
    /**
     * The folder of the installed tileset files.
     */
    private static final @NotNull String TILESET_FOLDER = "tileset/";

    /**
     * Finds the terrain pictures a simulation plugin of a game replaces: those of its first plugin that is a {@link TerrainReplacementSource}.
     *
     * @param game the game
     * @return the replaced terrain pictures, or {@code null} if no simulation plugin of the game replaces any
     */
    private static @Nullable TerrainReplacements findTerrainReplacements(@NotNull Game game) {
        ScrSimulationPlugins plugins = game.getSimulationPlugins();

        if (plugins == null) {
            return null;
        }

        for (ScrSimulationPlugin plugin : plugins.getPlugins()) {
            if (plugin instanceof TerrainReplacementSource source) {
                return source.getTerrainReplacements();
            }
        }

        return null;
    }


    /**
     * Creates the extension's plugin of a game, once, when the game's SC:R system attaches; the plugin lives as long as the game, so every new
     * game, live, replay or loaded save, starts with a new plugin.
     *
     * @param game the game, attached and not yet initialized
     * @return the created {@link ClassicTerrainGraphics} instance
     */
    @Override
    public @NotNull ScrGamePlugin createPlugin(@NotNull Game game) {
        return new ClassicTerrainGraphics(extension -> {
            GameData data = game.getGameData();

            if (data == null) {
                throw Exceptions.newIOException("The game has no game data.");
            }

            return data.readInstalledFile(TILESET_FOLDER + TerrainTileset.getTilesetName(game.getMap().getTileset()) + extension);
        }, () -> findTerrainReplacements(game));
    }
}
