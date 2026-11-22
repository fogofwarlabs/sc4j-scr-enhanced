package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayModCommand;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrModCommandSubmitter;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.screnhanced.script.input.ScriptLocalInput;
import org.fogofwarlabs.sc4j.screnhanced.script.sync.ScriptCommands;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the script commands of {@code core.command} and the channels and per-player values of {@code sync} over them between two peers of one
 * game: the delivery to every peer at the frame the command runs, the order, the split of a large value, the commands that wait for room, the
 * values that can be submitted, the creation of channels in shared code, the commands of other shapes, and the local hooks that read and
 * submit but cannot change the game.
 */
final class ScriptCommandCoreTest {
    private static final int MAP_TILE_SIZE = 4;
    private static final int KEY_Q = 0x51;
    private static final int KEY_W = 0x57;
    private static final int UNLIMITED_ROOM = Integer.MAX_VALUE;
    /**
     * The tags of encoded values: null, a 32-bit integer and an object, and a tag no value has.
     */
    private static final byte NULL_TAG = 0;
    private static final byte INTEGER_TAG = 3;
    private static final byte OBJECT_TAG = 7;
    private static final byte BROKEN_TAG = 0x7F;

    /**
     * Records the submitted commands and refuses them once its room is used up.
     */
    private static final class RecordingSubmitter implements ScrModCommandSubmitter {
        private final @NotNull List<byte[]> commands;

        private int room;

        private RecordingSubmitter() {
            this.commands = new ArrayList<>();

            this.room = UNLIMITED_ROOM;
        }

        @Override
        public boolean submitModCommand(byte @NotNull [] data) {
            if (this.room == 0) {
                return false;
            }

            this.room--;
            this.commands.add(data.clone());

            return true;
        }

        private @NotNull List<byte[]> take() {
            List<byte[]> taken = new ArrayList<>(this.commands);

            this.commands.clear();

            return taken;
        }
    }

    /**
     * One peer of the game: its own game, script runtime and submitter, with the slot of its player.
     */
    private static final class Peer {
        private final int slot;
        private final @NotNull Game game;
        private final @NotNull ScriptGameScenario scenario;
        private final @NotNull RecordingSubmitter submitter;

        private Peer(int slot, @NotNull String mainSource) throws IOException {
            this.slot      = slot;
            this.game      = createGame();
            this.scenario  = new ScriptGameScenario(this.game, mainSource);
            this.submitter = new RecordingSubmitter();

            this.scenario.getRuntime().start();
            this.scenario.getRuntime().submitCommands(this.submitter);
        }

        private void runFrame(int frame) {
            this.game.storeCurrentFrame(frame);
            this.scenario.getRuntime().runFrame(frame);
            this.scenario.getRuntime().submitCommands(this.submitter);
        }

        private boolean callLocalInput(@NotNull ScriptLocalInput input) {
            boolean consumed = this.scenario.getRuntime().callLocalInput(input);

            this.scenario.getRuntime().submitCommands(this.submitter);

            return consumed;
        }
    }

    /**
     * One command of a player in the command stream.
     */
    private static final class Command {
        private final int slot;
        private final byte @NotNull [] data;

        private Command(int slot, byte @NotNull [] data) {
            this.slot = slot;
            this.data = data;
        }
    }

    private static @NotNull Game createGame() {
        return Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, 0, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]), new LcgRandom(0));
    }

    /**
     * Encodes the channel envelope {@code {c: channel, v: null}}: an object of two properties, the integer channel and the null value.
     */
    private static byte @NotNull [] createEnvelope(int channel) {
        return new byte[]{OBJECT_TAG, 2, 1, 'c', INTEGER_TAG, (byte) channel, 0, 0, 0, 1, 'v', NULL_TAG};
    }

    /**
     * Takes the commands each peer submitted, in peer order.
     */
    private static @NotNull List<Command> takeCommands(@NotNull Peer @NotNull ... peers) {
        List<Command> commands = new ArrayList<>();

        for (Peer peer : peers) {
            for (byte[] data : peer.submitter.take()) {
                commands.add(new Command(peer.slot, data));
            }
        }

        return commands;
    }

    /**
     * Runs the commands on every peer in their order at a frame, as the command stream does, then the frame.
     */
    private static void runCommands(int frame, @NotNull List<Command> commands, @NotNull Peer @NotNull ... peers) {
        for (Peer peer : peers) {
            for (Command command : commands) {
                peer.scenario.getRuntime().queueModCommand(command.slot, command.data);
            }

            peer.runFrame(frame);
        }
    }

    @Test
    void a_message_reaches_every_peer_at_the_frame_its_command_runs_in_command_order() throws IOException {
        String script = """
                        var channel = sync.createChannel(function (player, value) {
                            log.info(game.getFrame() + ' ' + player.getId() + ' ' + JSON.stringify(value));
                        });
                        function onFrame(frame) {
                            if (frame === 1) {
                                channel.send({frame: frame});
                            }
                        }
                        function onLocalChat(event) {
                            channel.send(event.getText());
                        }
                        """;
        Peer first = new Peer(0, script);
        Peer second = new Peer(1, script);

        first.runFrame(1);
        second.runFrame(1);
        first.callLocalInput(ScriptLocalInput.ofChat("hello"));

        List<Command> commands = takeCommands(first, second);

        Assertions.assertEquals(List.of(), first.scenario.getInfoMessages(), "nothing arrives before its command runs");

        runCommands(2, commands, first, second);

        List<String> expected = List.of("2 0 {\"frame\":1}", "2 0 \"hello\"", "2 1 {\"frame\":1}");

        Assertions.assertEquals(expected, first.scenario.getInfoMessages(), first.scenario.getLines()::toString);
        Assertions.assertEquals(expected, second.scenario.getInfoMessages(), second.scenario.getLines()::toString);
    }

    @Test
    void a_large_value_is_split_into_commands_and_joined_between_other_players_commands() throws IOException {
        String script = """
                        var channel = sync.createChannel(function (player, value) {
                            log.info(player.getId() + ' ' + (typeof value === 'string' ? value.length : value.text.length + ' ' + value.list.length));
                        });
                        function onLocalChat(event) {
                            channel.send(event.getText() === 'big' ? {text: 'x'.repeat(600), list: [1, 2, 3]} : event.getText());
                        }
                        """;
        Peer first = new Peer(0, script);
        Peer second = new Peer(1, script);

        first.callLocalInput(ScriptLocalInput.ofChat("big"));
        second.callLocalInput(ScriptLocalInput.ofChat("small"));

        List<Command> firstCommands = takeCommands(first);
        List<Command> commands = new ArrayList<>(firstCommands);

        Assertions.assertEquals(3, firstCommands.size());

        for (Command command : firstCommands) {
            Assertions.assertTrue(command.data.length <= ReplayModCommand.MAXIMUM_DATA_LENGTH);
        }

        commands.addAll(1, takeCommands(second));
        runCommands(1, commands, first, second);

        Assertions.assertEquals(List.of("1 5", "0 600 3"), first.scenario.getInfoMessages(), first.scenario.getLines()::toString);
        Assertions.assertEquals(List.of("1 5", "0 600 3"), second.scenario.getInfoMessages());
    }

    @Test
    void commands_wait_in_order_while_the_turn_has_no_room() throws IOException {
        Peer peer = new Peer(0, """
                                var channel = sync.createChannel(function (player, value) {
                                    log.info(value);
                                });
                                function onLocalChat(event) {
                                    channel.send(event.getText() + ' 1');
                                    channel.send(event.getText() + ' 2');
                                    channel.send(event.getText() + ' 3');
                                }
                                """);

        peer.submitter.room = 1;
        peer.callLocalInput(ScriptLocalInput.ofChat("value"));

        List<Command> commands = new ArrayList<>(takeCommands(peer));

        Assertions.assertEquals(1, commands.size());

        peer.submitter.room = 0;
        peer.runFrame(1);

        Assertions.assertEquals(List.of(), takeCommands(peer), "a turn without room takes nothing");

        peer.submitter.room = UNLIMITED_ROOM;
        peer.runFrame(2);
        commands.addAll(takeCommands(peer));
        runCommands(3, commands, peer);

        Assertions.assertEquals(List.of("value 1", "value 2", "value 3"), peer.scenario.getInfoMessages(), peer.scenario.getLines()::toString);

        peer.submitter.room = 0;
        peer.callLocalInput(ScriptLocalInput.ofChat("dropped"));
        peer.scenario.getRuntime().submitCommands(null);
        peer.submitter.room = UNLIMITED_ROOM;
        peer.runFrame(4);

        Assertions.assertEquals(List.of(), takeCommands(peer), "without a submitter, as in a replay, the commands are dropped");
    }

    @Test
    void send_takes_plain_values_and_refuses_anything_else() throws IOException {
        Peer peer = new Peer(0, """
                                var channel = sync.createChannel(function (player, value) {
                                    log.info(1 / value[0] === -Infinity, isNaN(value[1]), value[2] === -Infinity, value[3] === 2147483648, value[4] === 1.5,
                                             value[5] === 'é한😀', value[6]['0'] === 'zero' && value[6][0] === 'zero', value[7].length,
                                             Object.keys(value[8]).length, value[9], value[10], value[11] === null, JSON.stringify(value[12]));
                                });
                                function attempt(name, value) {
                                    try {
                                        channel.send(value);
                                        log.info(name + ' sent');
                                    } catch (error) {
                                        log.info(name + ' ' + error.name);
                                    }
                                }
                                function onStart() {
                                    var cycle = [];
                                    var deep = [];
                                    var nested = deep;
                                    cycle.push(cycle);
                                    for (var level = 0; level < 64; level++) {
                                        nested.push([]);
                                        nested = nested[0];
                                    }
                                    attempt('function', function () {});
                                    attempt('undefined', undefined);
                                    attempt('position', Position.of(1, 2));
                                    attempt('player', game.players.get(0));
                                    attempt('cycle', cycle);
                                    attempt('hole', [1, , 3]);
                                    attempt('deep', deep);
                                    attempt('regexp', /x/);
                                    channel.send([-0, NaN, -Infinity, 2147483648, 1.5, 'é한😀', {'0': 'zero'}, [], {}, true, false, null,
                                                  {a: [1, {b: 'c'}], d: null}]);
                                }
                                """);

        peer.runFrame(0);

        Assertions.assertEquals(List.of("function TypeError", "undefined TypeError", "position TypeError", "player TypeError", "cycle TypeError",
                                        "hole TypeError", "deep TypeError", "regexp TypeError"),
                                peer.scenario.getInfoMessages(), peer.scenario.getLines()::toString);

        runCommands(1, takeCommands(peer), peer);

        Assertions.assertEquals("true true true true true true true 0 0 true false true {\"a\":[1,{\"b\":\"c\"}],\"d\":null}",
                                peer.scenario.getInfoMessages().getLast(), peer.scenario.getLines()::toString);
    }

    @Test
    void channels_are_created_in_shared_code_in_creation_order() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = new ScriptGameScenario(game, """
                                                                   var first = sync.createChannel(function () {});
                                                                   var score = sync.createPlayerValue(0);
                                                                   function attempt(name, create) {
                                                                       try {
                                                                           log.info(name + ' ' + create().getId());
                                                                       } catch (error) {
                                                                           log.info(name + ' ' + error.name);
                                                                       }
                                                                   }
                                                                   log.info('top ' + first.getId());
                                                                   function onStart() {
                                                                       attempt('start', function () { return sync.createChannel(function () {}); });
                                                                       game.every(1, function () {
                                                                           attempt('timer', function () { return sync.createChannel(function () {}); });
                                                                       });
                                                                   }
                                                                   function onFrame(frame) {
                                                                       attempt('frame', function () { return sync.createChannel(function () {}); });
                                                                   }
                                                                   function onLocalFrame(frame) {
                                                                       attempt('local', function () { return sync.createChannel(function () {}); });
                                                                   }
                                                                   """);

        scenario.getRuntime().start();
        scenario.getRuntime().runFrame(0);
        game.storeCurrentFrame(1);
        scenario.getRuntime().runFrame(1);
        scenario.getRuntime().callLocalFrame(1);

        Assertions.assertEquals(List.of("top 0", "start 2", "frame 3", "timer 4", "frame 5", "local TypeError"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void core_command_values_reach_every_peer_without_a_channel() throws IOException {
        String script = """
                        function onLocalChat(event) {
                            core.command.submit({text: event.getText(), list: [1, 2]});
                        }
                        function onFrame(frame) {
                            var executed = core.command.getExecuted();
                            if (executed.length > 0) {
                                executed[0].value.text = 'changed';
                            }
                            log.info(frame + ' ' + JSON.stringify(core.command.getExecuted()));
                        }
                        """;
        Peer first = new Peer(0, script);
        Peer second = new Peer(1, script);

        first.callLocalInput(ScriptLocalInput.ofChat("hi"));
        second.callLocalInput(ScriptLocalInput.ofChat("yo"));
        runCommands(1, takeCommands(first, second), first, second);

        List<String> expected = List.of("1 [{\"player\":0,\"value\":{\"text\":\"hi\",\"list\":[1,2]},\"selection\":[],\"selectionGenerations\":[]}," +
                                        "{\"player\":1,\"value\":{\"text\":\"yo\",\"list\":[1,2]},\"selection\":[],\"selectionGenerations\":[]}]");

        Assertions.assertEquals(expected, first.scenario.getInfoMessages(), first.scenario.getLines()::toString);
        Assertions.assertEquals(expected, second.scenario.getInfoMessages(), second.scenario.getLines()::toString);
    }

    @Test
    void player_values_set_the_sending_players_slot_or_a_given_players_slot() throws IOException {
        String script = """
                        var score = sync.createPlayerValue('none');
                        function onLocalChat(event) {
                            var text = event.getText();
                            if (text.indexOf('for ') === 0) {
                                score.set(game.players.get(0), text.substring(4));
                            } else {
                                score.set(text);
                            }
                        }
                        function onFrame(frame) {
                            log.info(frame + ' ' + score.get(game.players.get(0)) + ' ' + score.get(game.players.get(1)) + ' ' + score.get(game.players.get(2)));
                        }
                        """;
        Peer first = new Peer(0, script);
        Peer second = new Peer(1, script);

        first.callLocalInput(ScriptLocalInput.ofChat("five"));
        second.callLocalInput(ScriptLocalInput.ofChat("for nine"));
        runCommands(1, takeCommands(first, second), first, second);
        second.callLocalInput(ScriptLocalInput.ofChat("three"));
        runCommands(2, takeCommands(first, second), first, second);

        List<String> expected = List.of("1 nine none none", "2 nine three none");

        Assertions.assertEquals(expected, first.scenario.getInfoMessages(), first.scenario.getLines()::toString);
        Assertions.assertEquals(expected, second.scenario.getInfoMessages());
    }

    @Test
    void local_hooks_read_and_send_but_cannot_change_the_game_or_the_shared_script_state() throws IOException {
        Peer peer = new Peer(0, """
                                var channel = sync.createChannel(function (player, value) {
                                    log.info('received ' + value);
                                });
                                var timer = game.every(100, function () {});
                                function attempt(name, action) {
                                    try {
                                        action();
                                        log.info(name + ' done');
                                    } catch (error) {
                                        log.info(name + ' ' + error.name);
                                    }
                                }
                                function onLocalKeyDown(event) {
                                    log.info('read only ' + game.isReadOnly() + ' ' + game.getFrame());
                                    attempt('switch', function () { game.switches.set(0, true); });
                                    attempt('timer', function () { game.every(1, function () {}); });
                                    attempt('random', function () { random.next(); });
                                    attempt('math random', function () { Math.random(); });
                                    attempt('require', function () { require('./other.js'); });
                                    attempt('cancel', function () { timer.cancel(); });
                                    attempt('channel', function () { sync.createChannel(function () {}); });
                                    attempt('events', function () { core.events.getFrameEvents(); });
                                    attempt('executed', function () { core.command.getExecuted(); });
                                    attempt('send', function () { channel.send('local'); });
                                    event.consume();
                                }
                                function onLocalKeyUp(event) {
                                    while (true) {
                                    }
                                }
                                function onFrame(frame) {
                                    log.info('frame ' + frame + ' ' + game.isReadOnly() + ' ' + (random.next() < 1));
                                }
                                """);

        Assertions.assertTrue(peer.callLocalInput(ScriptLocalInput.ofKey(KEY_Q, true, 0, false, 0, 0)));
        Assertions.assertFalse(peer.callLocalInput(ScriptLocalInput.ofKey(KEY_W, false, 0, false, 0, 0)), "a local hook that runs over its budget");
        Assertions.assertFalse(peer.scenario.getRuntime().isStopped(), "a local hook never stops the script");

        runCommands(1, takeCommands(peer), peer);

        Assertions.assertEquals(List.of("read only true 0", "switch TypeError", "timer TypeError", "random TypeError", "math random TypeError",
                                        "require TypeError", "cancel TypeError", "channel TypeError", "events TypeError", "executed TypeError",
                                        "send done", "received local", "frame 1 false true"),
                                peer.scenario.getInfoMessages(), peer.scenario.getLines()::toString);
        Assertions.assertTrue(peer.scenario.getLines().stream().anyMatch(line -> line.contains("onLocalKeyUp ran more than the budget")),
                              peer.scenario.getLines()::toString);
    }

    @Test
    void commands_of_other_shapes_reach_no_channel_and_a_broken_value_is_logged() throws IOException {
        Peer peer = new Peer(0, """
                                var channel = sync.createChannel(function (player, value) {
                                    log.info('received ' + value);
                                });
                                function onFrame(frame) {
                                    log.info(JSON.stringify(core.command.getExecuted()));
                                }
                                """);
        List<Command> commands = new ArrayList<>();

        commands.add(new Command(1, ScriptCommands.split(createEnvelope(5)).getFirst()));
        commands.add(new Command(1, ScriptCommands.split(new byte[]{BROKEN_TAG}).getFirst()));
        commands.add(new Command(1, ScriptCommands.split(createEnvelope(0)).getFirst()));
        commands.add(new Command(1, ScriptCommands.split(new byte[]{NULL_TAG}).getFirst()));
        runCommands(1, commands, peer);

        Assertions.assertEquals(List.of("received null", "[{\"player\":1,\"value\":{\"c\":5,\"v\":null},\"selection\":[],\"selectionGenerations\":[]}," +
                                                         "{\"player\":1,\"value\":{\"c\":0,\"v\":null},\"selection\":[],\"selectionGenerations\":[]}," +
                                                         "{\"player\":1,\"value\":null,\"selection\":[],\"selectionGenerations\":[]}]"),
                                peer.scenario.getInfoMessages(), peer.scenario.getLines()::toString);
        Assertions.assertEquals(3, peer.scenario.getLines().size(), peer.scenario.getLines()::toString);
        Assertions.assertTrue(peer.scenario.getLines().getFirst().startsWith("WARN main.js: A script command of player 1 whose value"),
                              peer.scenario.getLines()::toString);
        Assertions.assertFalse(peer.scenario.getRuntime().isStopped());
    }
}
