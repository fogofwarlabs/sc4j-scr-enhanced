package org.fogofwarlabs.sc4j.screnhanced.net;

import org.fogofwarlabs.sc4j.beaconlink.client.BeaconLinkClient;
import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.DatagramEndpoint;
import org.fogofwarlabs.sc4j.engine.net.UdpDatagramEndpoint;
import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.net.beaconlink.BeaconLinkProvider;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanDiscovery;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.lan.LanGameOptions;
import org.fogofwarlabs.sc4j.scr.net.lan.LanLobby;
import org.fogofwarlabs.sc4j.scr.net.lan.LanMapTransfer;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Checks lobbies of SC:R Enhanced in a Beacon.Link room on 127.0.0.1 whose datagrams are lost and arrive in another order: three joiners join
 * one after another while the map downloads through the engine's transfer, the second leaves during the download and joins again, and every
 * joiner ends with the map, every lobby shows every player and the host sees every download at {@code 100} percent.
 */
final class LinkLobbyLossTest {
    private static final @NotNull String GAME_NAME = "s4jlossyroom";
    private static final @NotNull String MAP_NAME = "lossy map.scx";
    private static final int MAP_SIZE = 4_000_000;
    private static final long MAP_SEED = 17L;
    private static final long LOSS_SEED = 23L;
    private static final int CLIENTS = 4;
    private static final int REJOINING_CLIENT = 2;
    private static final int LOSS_PERCENT = 5;
    private static final int HOLD_PERCENT = 5;
    private static final int COMPLETE_PERCENT = 100;
    private static final int PERCENT = 100;

    /**
     * Loses and reorders the datagrams a client sends once losses are on: a held datagram goes out after the next one.
     */
    private static final class LossyEndpoint implements DatagramEndpoint {
        private final @NotNull DatagramEndpoint endpoint;
        private final @NotNull Random random;
        private byte @Nullable [] held;
        private @Nullable InetSocketAddress heldTarget;
        private boolean lossy;

        private LossyEndpoint(@NotNull DatagramEndpoint endpoint, long seed) {
            this.endpoint   = endpoint;
            this.random     = new Random(seed);
            this.held       = null;
            this.heldTarget = null;
            this.lossy      = false;
        }

        @Override
        public @NotNull InetSocketAddress getLocalAddress() throws IOException {
            return this.endpoint.getLocalAddress();
        }

        @Override
        public void send(@NotNull ByteBuffer datagram, @NotNull InetSocketAddress target) throws IOException {
            int choice = this.random.nextInt(PERCENT);

            if (this.lossy && choice < LOSS_PERCENT) {
                datagram.position(datagram.limit());

                return;
            }

            if (this.lossy && choice < LOSS_PERCENT + HOLD_PERCENT && this.held == null) {
                byte[] bytes = new byte[datagram.remaining()];

                datagram.get(bytes);
                this.held       = bytes;
                this.heldTarget = target;

                return;
            }

            this.endpoint.send(datagram, target);

            byte[] bytes = this.held;
            InetSocketAddress heldAt = this.heldTarget;

            if (bytes != null && heldAt != null) {
                this.held       = null;
                this.heldTarget = null;
                this.endpoint.send(ByteBuffer.wrap(bytes), heldAt);
            }
        }

        @Override
        public @Nullable InetSocketAddress receive(@NotNull ByteBuffer buffer) throws IOException {
            return this.endpoint.receive(buffer);
        }

        @Override
        public void close() throws IOException {
            this.endpoint.close();
        }
    }

    private static boolean isSettled(@NotNull List<@NotNull LanClient> clients) {
        LanLobby host = clients.getFirst().getLobby();

        if (host == null || !host.isMapTransferComplete()) {
            return false;
        }

        for (LanClient client : clients) {
            LanLobby lobby = client.getLobby();

            if (lobby == null || (client != clients.getFirst() && lobby.getMapFile() == null)) {
                return false;
            }

            for (LanClient other : clients) {
                ProviderSession session = other.getSession();
                int player = session == null ? -1 : session.getLocalIdentifier();

                if (lobby.getPlayerSlot(player) < 0 || (lobby == host && lobby.getDownloadPercent(player) != COMPLETE_PERCENT)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static void join(@NotNull LinkMapExtensionTest.Driver driver, @NotNull LanClient joiner) throws Exception {
        LanDiscovery.Game[] listed = new LanDiscovery.Game[1];

        driver.runUntil(() -> {
            for (LanDiscovery.Game game : joiner.getGames()) {
                if (game.getInformation().getName().equals(GAME_NAME)) {
                    listed[0] = game;
                }
            }

            return listed[0] != null;
        }, () -> {
        }, "the joiner lists the room");
        joiner.join(listed[0]);
        driver.runUntil(() -> {
                            LanLobby lobby = joiner.getLobby();

                            return joiner.getState() == LanClient.State.LOBBY && lobby != null && lobby.getState() >= LanLobby.STATE_LOBBY;
                        }, () -> Assertions.assertNotEquals(LanClient.State.FAILED, joiner.getState(), () -> "the join failed: " + joiner.getJoinFailure()),
                        "the joiner reaches the lobby");
    }

    @Test
    void joiners_download_the_map_and_see_every_player_in_a_lossy_room(@TempDir @NotNull Path folder) throws Exception {
        byte[] contents = new byte[MAP_SIZE];

        new Random(MAP_SEED).nextBytes(contents);

        Path hostMaps = Files.createDirectories(folder.resolve("host"));
        Path mapPath = Files.write(hostMaps.resolve(MAP_NAME), contents);
        List<LanClient> clients = new ArrayList<>();
        List<LossyEndpoint> endpoints = new ArrayList<>();

        try (ClientScenario link = new ClientScenario()) {
            ModIdentity identity = ClientScenario.createIdentity("1.0.0");
            PeerSocket node = link.openNode(ClientScenario.createNodeConfiguration());
            LinkMapExtensionTest.Driver driver = new LinkMapExtensionTest.Driver(link, clients);

            try {
                for (int index = 0; index < CLIENTS; index++) {
                    LossyEndpoint endpoint = new LossyEndpoint(UdpDatagramEndpoint.open(new InetSocketAddress(ClientScenario.LOOPBACK, 0)),
                                                               LOSS_SEED + index);
                    BeaconLinkClient session = link.openClient(identity, List.of(ClientScenario.listed(node)), endpoint);
                    Path maps = index == 0 ? hostMaps : Files.createDirectories(folder.resolve("joiner" + index));

                    link.await(() -> session.getObservedAddress() != null, "the session registers");
                    endpoint.lossy = true;
                    endpoints.add(endpoint);
                    clients.add(LanClient.open("player" + index, new BeaconLinkProvider(session),
                                               MapFolders.of(List.of(maps), LanMapTransfer.DOWNLOAD_FOLDER)));
                }

                clients.getFirst().host(GAME_NAME, LanClient.HostedMap.read(mapPath, LinkMapExtensionTest.createScenario(CLIENTS), MAP_NAME),
                                        LanGameOptions.MELEE, GameSpeed.FASTEST);

                for (int index = 1; index < CLIENTS; index++) {
                    join(driver, clients.get(index));
                }

                LanClient rejoining = clients.get(REJOINING_CLIENT);

                rejoining.leave();
                join(driver, rejoining);
                driver.runUntil(() -> isSettled(clients), () -> {
                }, "every lobby settles");

                for (int index = 1; index < CLIENTS; index++) {
                    LanLobby lobby = LinkMapExtensionTest.requireLobby(clients.get(index));
                    Path downloaded = lobby.getMapFile();

                    Assertions.assertNotNull(downloaded);
                    Assertions.assertArrayEquals(contents, Files.readAllBytes(downloaded));
                }
            } finally {
                for (LanClient client : clients) {
                    client.close();
                }
            }
        }
    }
}
