package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrInputModifiers;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrKeyInput;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrModCommandSubmitter;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrMouseInput;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptLogOutput;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests how {@link ScriptInputPlugin} hands the local keys, mouse buttons, chat lines and played frames to the local hooks: the event values,
 * the consumption, the script commands the hooks send, the local information and the local screen move of {@code onLocalFrame}, a replay,
 * a game without a submitter or a script, and the refusal of the local information in shared code.
 */
final class ScriptInputPluginTest {
    private static final int KEY_Q = 0x51;
    private static final int KEY_W = 0x57;
    private static final int MAP_TILE_SIZE = 4;
    private static final int UNIT_CAPACITY = 16;
    private static final long SEED = 1;
    private static final @NotNull String MANIFEST = "{\"name\": \"Test\", \"main\": \"main.js\", \"api\": \"1\"}";
    /**
     * Logs every local event and consumes the presses of Q, the right button and the chat lines that start with {@code !}, which it also
     * sends on a channel.
     */
    private static final @NotNull String SCRIPT = """
                                                  var channel = sync.createChannel(function () {});
                                                  function describe(event) {
                                                      return event.getMousePosition() + ' ' + event.isShift() + ' ' + event.isControl() + ' ' + event.isAlt();
                                                  }
                                                  function onLocalKeyDown(event) {
                                                      log.info('key down ' + event.getKey().getName() + ' ' + describe(event));
                                                      if (event.getKey() === Key.Q) {
                                                          event.consume();
                                                      }
                                                  }
                                                  function onLocalKeyUp(event) {
                                                      log.info('key up ' + event.getKey().getName() + ' ' + describe(event));
                                                      event.consume();
                                                  }
                                                  function onLocalMouseDown(event) {
                                                      log.info('mouse down ' + event.getButton().getName() + ' ' + describe(event));
                                                      if (event.getButton() === MouseButton.Right) {
                                                          event.consume();
                                                      }
                                                  }
                                                  function onLocalChat(event) {
                                                      log.info('chat ' + event.getText() + ' ' + event.isConsumed());
                                                      if (event.getText().charAt(0) === '!') {
                                                          event.consume();
                                                          channel.send(event.getText());
                                                      }
                                                  }
                                                  """;

    /**
     * Logs the local information on every played frame; at frame 2 it moves the local screen and sends the frame on a channel.
     */
    private static final @NotNull String FRAME_SCRIPT = """
                                                        var channel = sync.createChannel(function () {});
                                                        function onLocalFrame(frame) {
                                                            log.info('frame ' + frame + ' ' + local.getPlayer() + ' ' + local.isReplay() + ' ' + local.isChatOpen() + ' ' +
                                                                     local.getScreenPosition() + ' ' + local.getMousePosition() + ' ' + local.getSelection().length);
                                                            if (frame === 2) {
                                                                log.info('moved ' + local.setScreenPosition(Position.of(96, 5000)) + ' ' + local.getScreenPosition());
                                                                channel.send(frame);
                                                            }
                                                        }
                                                        """;
    /**
     * Reads the local information in shared code (the top level, onStart, onFrame and core) and in onLocalFrame.
     */
    private static final @NotNull String SHARED_SCRIPT = """
                                                         var results = [];
                                                         function attempt(name, read) {
                                                             try {
                                                                 read();
                                                                 results.push(name + ' read');
                                                             } catch (error) {
                                                                 results.push(name + ' ' + error.name);
                                                             }
                                                         }
                                                         attempt('top', function () { return local.getPlayer(); });
                                                         function onStart() {
                                                             attempt('start', function () { return local.isReplay(); });
                                                             attempt('core', function () { return core.local.getScreenX(); });
                                                         }
                                                         function onFrame(frame) {
                                                             attempt('frame', function () { return local.getSelection(); });
                                                             attempt('screen', function () { return local.setScreenPosition(Position.of(0, 0)); });
                                                             log.info(results.join(', '));
                                                         }
                                                         function onLocalFrame(frame) {
                                                             attempt('local frame', function () { return local.getMousePosition(); });
                                                             log.info(results.join(', '));
                                                         }
                                                         """;

    /**
     * Records the submitted commands.
     */
    private static final class RecordingSubmitter implements ScrModCommandSubmitter {
        private final @NotNull List<byte[]> commands;

        private RecordingSubmitter() {
            this.commands = new ArrayList<>();
        }

        @Override
        public boolean submitModCommand(byte @NotNull [] data) {
            this.commands.add(data.clone());

            return true;
        }
    }

    /**
     * Creates a game without game data and with an empty unit pool.
     */
    private static @NotNull Game createGame() {
        Game game = Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, 0, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]), new LcgRandom(0));

        game.getUnitSystem().initializeUnitPool(UNIT_CAPACITY);

        return game;
    }

    /**
     * Creates a started runtime of the input script over a game without game data; its info messages go to a list.
     */
    private static @NotNull ScriptRuntime startRuntime(@NotNull List<String> messages) throws IOException {
        return startRuntime(messages, createGame(), SCRIPT);
    }

    /**
     * Creates a started runtime of a script over a game; its info messages go to a list.
     */
    private static @NotNull ScriptRuntime startRuntime(@NotNull List<String> messages, @NotNull Game game, @NotNull String script) throws IOException {
        ScriptPackage scriptPackage = ScriptPackage.create(ScriptManifest.parse(MANIFEST.getBytes(StandardCharsets.UTF_8)),
                                                           Map.of("main.js", script.getBytes(StandardCharsets.UTF_8)), Map.of());
        ScriptLogOutput logOutput = (level, moduleName, message) -> messages.add(level == LogLevel.INFO ? message : level + " " + message);
        ScriptRuntime runtime = ScriptRuntime.create(scriptPackage, game, ScriptResources.EMPTY, SEED, ScriptRuntime.INSTRUCTION_BUDGET, logOutput);

        runtime.start();

        return runtime;
    }

    @Test
    void local_hooks_receive_the_keys_buttons_and_chat_lines_and_consume_when_asked() throws IOException {
        List<String> messages = new ArrayList<>();
        ScriptRuntime runtime = startRuntime(messages);
        RecordingSubmitter submitter = new RecordingSubmitter();
        LocalView view = new LocalView();
        ScriptInputPlugin plugin = new ScriptInputPlugin(() -> runtime, () -> submitter, view);

        Assertions.assertFalse(plugin.onKeyInput(new ScrKeyInput(KEY_W, true, ScrInputModifiers.SHIFT)), "a key the hook does not consume");

        view.setView(640, 320, 100, 50);

        Assertions.assertTrue(plugin.onKeyInput(new ScrKeyInput(KEY_Q, true, ScrInputModifiers.CONTROL | ScrInputModifiers.ALT)));
        Assertions.assertTrue(plugin.onKeyInput(new ScrKeyInput(KEY_Q, false, 0)), "a consumed release is reported as consumed");
        Assertions.assertFalse(plugin.onMouseInput(new ScrMouseInput(ScrMouseInput.Button.LEFT, true, 0, 10, 20)));
        Assertions.assertTrue(plugin.onMouseInput(new ScrMouseInput(ScrMouseInput.Button.RIGHT, true, ScrInputModifiers.SHIFT, 300, 200)));
        Assertions.assertFalse(plugin.onMouseInput(new ScrMouseInput(ScrMouseInput.Button.RIGHT, false, 0, 300, 200)), "no up hook");
        Assertions.assertFalse(plugin.onChatInput("hello"));
        Assertions.assertTrue(plugin.onChatInput("!ready"));
        Assertions.assertEquals(List.of("key down W null true false false",
                                        "key down Q (740, 370) false true true",
                                        "key up Q (740, 370) false false false",
                                        "mouse down Left (10, 20) false false false",
                                        "mouse down Right (300, 200) true false false",
                                        "chat hello false",
                                        "chat !ready false"), messages);
        Assertions.assertEquals(1, submitter.commands.size(), "the hook's send is submitted at once");
        Assertions.assertFalse(runtime.isReadOnly(), "the game is writable again after a local hook");
    }

    @Test
    void a_game_without_a_submitter_or_a_script_calls_no_local_hook() throws IOException {
        List<String> messages = new ArrayList<>();
        ScriptRuntime runtime = startRuntime(messages);
        RecordingSubmitter submitter = new RecordingSubmitter();
        ScriptInputPlugin replay = new ScriptInputPlugin(() -> runtime, () -> null, new LocalView());
        ScriptInputPlugin withoutScript = new ScriptInputPlugin(() -> null, () -> submitter, new LocalView());

        Assertions.assertFalse(replay.onKeyInput(new ScrKeyInput(KEY_Q, true, 0)));
        Assertions.assertFalse(replay.onMouseInput(new ScrMouseInput(ScrMouseInput.Button.RIGHT, true, 0, 1, 1)));
        Assertions.assertFalse(replay.onChatInput("!ready"));
        Assertions.assertFalse(withoutScript.onKeyInput(new ScrKeyInput(KEY_Q, true, 0)));
        Assertions.assertFalse(withoutScript.onChatInput("!ready"));
        Assertions.assertEquals(List.of(), messages);
        Assertions.assertEquals(List.of(), submitter.commands);
    }

    @Test
    void on_local_frame_reads_the_local_information_and_moves_only_the_local_screen() throws IOException {
        List<String> messages = new ArrayList<>();
        Game game = createGame();
        ScriptRuntime runtime = startRuntime(messages, game, FRAME_SCRIPT);
        RecordingSubmitter submitter = new RecordingSubmitter();
        LocalView view = game.getInterfaceLink().getLocalView();
        ScriptInputPlugin plugin = new ScriptInputPlugin(() -> runtime, () -> submitter, view);

        plugin.onFramePlayed(1);

        view.setView(640, 320, 100, 50);
        view.setScreenGeometry(640, 480, 2000, 1000);
        view.takeWritten();
        game.getInterfaceLink().getLocalInterface().setChatMode(1);

        long stateHash = game.computeStateHash();

        plugin.onFramePlayed(2);

        Assertions.assertEquals(List.of("frame 1 null false false null null 0",
                                        "frame 2 null false true (640, 320) (740, 370) 0",
                                        "moved true (96, 1000)"), messages);
        Assertions.assertEquals(96, view.getScreenX(), "the local screen moved, clamped to the map");
        Assertions.assertEquals(1000, view.getScreenY());
        Assertions.assertTrue(view.takeWritten(), "the client applies the move");
        Assertions.assertEquals(stateHash, game.computeStateHash(), "the game state did not change");
        Assertions.assertEquals(1, submitter.commands.size(), "the hook's send is submitted at once");
        Assertions.assertFalse(runtime.isReadOnly(), "the game is writable again after a local hook");
    }

    @Test
    void a_replay_calls_on_local_frame_without_a_local_player_and_drops_its_sends() throws IOException {
        List<String> messages = new ArrayList<>();
        Game game = createGame();
        ScriptRuntime runtime = startRuntime(messages, game, FRAME_SCRIPT);
        RecordingSubmitter submitter = new RecordingSubmitter();
        ScriptInputPlugin replay = new ScriptInputPlugin(() -> runtime, () -> null, game.getInterfaceLink().getLocalView());

        game.setReplayPlayback(true);
        replay.onFramePlayed(2);
        runtime.submitCommands(submitter);

        Assertions.assertEquals(List.of("frame 2 null true false null null 0", "moved false null"), messages);
        Assertions.assertFalse(replay.onKeyInput(new ScrKeyInput(KEY_Q, true, 0)), "a replay calls no input hook");
        Assertions.assertEquals(List.of(), submitter.commands, "a replay drops the sends of onLocalFrame");
    }

    @Test
    void shared_code_cannot_read_the_local_information() throws IOException {
        List<String> messages = new ArrayList<>();
        Game game = createGame();
        ScriptRuntime runtime = startRuntime(messages, game, SHARED_SCRIPT);
        ScriptInputPlugin plugin = new ScriptInputPlugin(() -> runtime, () -> null, game.getInterfaceLink().getLocalView());

        runtime.runFrame(1);
        plugin.onFramePlayed(1);

        Assertions.assertEquals(List.of("top TypeError, start TypeError, core TypeError, frame TypeError, screen TypeError",
                                        "top TypeError, start TypeError, core TypeError, frame TypeError, screen TypeError, local frame read"),
                                messages);
    }
}
