package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationExtension;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameSetup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * Runs the script package of a map's script trailer ({@link ScriptStorageUtils}) in the games of SC:R Enhanced: a game whose map has a script
 * trailer gets a {@link ScriptSimulationPlugin}, any other game none.
 * <p>
 * A live game started from a map file reads the trailer from the map file and stores it as the replay section
 * {@value ScriptStorageUtils#REPLAY_SECTION}, with the resources as references by name, size and SHA-256 digest. A replay played back reads the
 * manifest and the scripts from that section and the resources from a map file that holds every referenced resource: the first one found in
 * the map folders of the install ({@link ScriptRunner#getMapFolders}). A trailer or section that cannot be read, or a replay whose resources
 * no map holds, fails the game with the reason.
 */
public final class ScriptSimulationExtension implements ScrSimulationExtension {
    private static final @NotNull Logger LOGGER = Logger.of(ScriptSimulationExtension.class);
    private static final @NotNull String MAP_DESCRIPTION = "the game's map";

    /**
     * The trailer of a map file and the file state it was read at.
     */
    private static final class LoadedMap {
        private final @NotNull Path file;
        private final long size;
        private final @NotNull FileTime modified;
        private final @NotNull ScriptTrailer trailer;

        private LoadedMap(@NotNull Path file, long size, @NotNull FileTime modified, @NotNull ScriptTrailer trailer) {
            this.file     = file;
            this.size     = size;
            this.modified = modified;
            this.trailer  = trailer;
        }
    }


    /**
     * The map file whose resources a replay read last, kept for the next game of the same replay.
     */
    private @Nullable LoadedMap lastMap;

    /**
     * Creates a {@link ScriptSimulationExtension} instance that has read no map file.
     */
    public ScriptSimulationExtension() {
        this.lastMap = null;
    }

    /**
     * Creates the extension's simulation plugin of a game, once, when the game's SC:R system attaches; the plugin lives as long as the game, so
     * every new game, live, replay or loaded save, starts with a new plugin.
     * <p>
     * Only a game whose map has a script trailer gets a plugin, which gets the package and the map's resources: a live game from the map's
     * archive, a replay from its replay section and a map file of the map folders.
     *
     * @param game the game, attached and not yet initialized
     * @return the created {@link ScriptSimulationPlugin} instance, or {@code null} if the game's map has no script trailer
     * @throws IllegalStateException if the trailer or the replay section cannot be read, or no map file holds the resources of a replay
     */
    @Override
    public @Nullable ScriptSimulationPlugin createPlugin(@NotNull Game game) {
        GameSetup setup = game.getSetup();
        MpqArchive archive = setup.getMapArchive();

        try {
            if (archive != null) {
                ScriptTrailer trailer = ScriptStorageUtils.readTrailer(archive.getArchiveData(), MAP_DESCRIPTION);

                if (trailer == null) {
                    return null;
                }

                game.setReplaySection(ScriptStorageUtils.REPLAY_SECTION, ScriptStorageUtils.createReplaySection(trailer));

                return new ScriptSimulationPlugin(game, trailer.getScripts(), trailer.getResources());
            }

            byte[] section = setup.readReplaySection(ScriptStorageUtils.REPLAY_SECTION);

            if (section == null) {
                return null;
            }

            ScriptTrailer recorded = ScriptStorageUtils.readReplaySection(section);

            return new ScriptSimulationPlugin(game, recorded.getScripts(), this.findMapResources(recorded.getResources()));
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "The map script of the game cannot be loaded: {0}", exception.getMessage());
        }
    }

    /**
     * Finds the resources a replay references in the map folders of the install.
     *
     * @param references the referenced resources
     * @return the resources of the references with the stored bytes of the first map file that holds each of them
     * @throws IOException if no map file holds every referenced resource, or the found file cannot be read again
     */
    private @NotNull ScriptResources findMapResources(@NotNull ScriptResources references) throws IOException {
        if (references.isEmpty()) {
            return ScriptResources.EMPTY;
        }

        Path install = ScriptRunner.findInstall();
        List<Path> folders = install == null ? List.of() : ScriptRunner.getMapFolders(install);
        Path mapFile = new MapFolders(folders, null).findFile(file -> {
            ScriptResources index = ScriptStorageUtils.readResourceIndex(file);

            return index != null && index.select(references.getEntries()) != null;
        });

        if (mapFile == null) {
            throw Exceptions.newIOException("The replay's script uses {0} resources of its map, and no map in {1} holds them; put the map the game " +
                                            "was played on into one of these folders.",
                                            references.getEntries().size(), folders);
        }

        ScriptResources resources = this.readMap(mapFile).getResources().select(references.getEntries());

        if (resources == null) {
            throw Exceptions.newIOException("The map {0} changed while the replay's script read its resources.", mapFile);
        }

        LOGGER.info("SCRIPT REPLAY RESOURCES: {0} resources from {1}", references.getEntries().size(), mapFile);

        return resources;
    }

    /**
     * Reads the trailer of a map file, or takes the one read last when the file did not change since.
     *
     * @param mapFile the map file
     * @return the trailer
     * @throws IOException if the file cannot be read or has no valid trailer
     */
    private synchronized @NotNull ScriptTrailer readMap(@NotNull Path mapFile) throws IOException {
        long size = Files.size(mapFile);
        FileTime modified = Files.getLastModifiedTime(mapFile);
        LoadedMap loaded = this.lastMap;

        if (loaded != null && loaded.file.equals(mapFile) && loaded.size == size && loaded.modified.equals(modified)) {
            return loaded.trailer;
        }

        ScriptTrailer trailer = ScriptStorageUtils.readTrailer(Files.readAllBytes(mapFile), mapFile.toString());

        if (trailer == null) {
            throw Exceptions.newIOException("The map {0} has no script trailer.", mapFile);
        }

        this.lastMap = new LoadedMap(mapFile, size, modified, trailer);

        return trailer;
    }
}
