package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrGameExtension;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gives every game the plugin that hands the local player's input to the local hooks of the game's script ({@link ScriptInputPlugin}); the
 * plugin finds the script among the game's simulation plugins ({@link ScriptSimulationPlugin}).
 */
public final class ScriptInputExtension implements ScrGameExtension {
    /**
     * Finds the runtime of a game's script: that of its {@link ScriptSimulationPlugin}.
     *
     * @param game the game
     * @return the runtime, or {@code null} if the game has no script
     */
    private static @Nullable ScriptRuntime findRuntime(@NotNull Game game) {
        ScrSimulationPlugins plugins = game.getSimulationPlugins();

        if (plugins == null) {
            return null;
        }

        for (ScrSimulationPlugin plugin : plugins.getPlugins()) {
            if (plugin instanceof ScriptSimulationPlugin scriptPlugin) {
                return scriptPlugin.getRuntime();
            }
        }

        return null;
    }


    /**
     * Creates the extension's plugin of a game, once, when the game's SC:R system attaches; the plugin lives as long as the game, so every new
     * game, live, replay or loaded save, starts with a new plugin.
     *
     * @param game the game, attached and not yet initialized
     * @return the created {@link ScriptInputPlugin} instance
     */
    @Override
    public @NotNull ScriptInputPlugin createPlugin(@NotNull Game game) {
        return new ScriptInputPlugin(() -> findRuntime(game), () -> game.getInterfaceLink().getModCommandSubmitter(),
                                     game.getInterfaceLink().getLocalView());
    }
}
