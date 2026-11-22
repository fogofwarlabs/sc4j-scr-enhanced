package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.scr.game.extension.simulation.PlayerLeftEvent;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationUnit;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.WeaponDamageDecision;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitHandle;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptPackage;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptProtector;
import org.fogofwarlabs.sc4j.screnhanced.script.input.ScriptLocalInput;
import org.fogofwarlabs.sc4j.screnhanced.script.sync.ScriptCommands;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Tests the entry points of the prelude that {@link ScriptRuntime} calls: the order of the hooks of a frame, the decision records that the
 * decision hooks hand on, the local inputs and their consumption, and the refusal of the lower functions in the wrong code; and that the prelude
 * files, as a body of real scripts, protect to the same programs ({@link ScriptProtector}).
 */
final class ScriptPreludeTest {
    private static final int KEY_Q = 0x51;
    private static final int KEY_W = 0x57;
    private static final int UNIT_CAPACITY = 16;
    private static final int TARGET_SLOT = 1;
    private static final int MARINE = 0;
    private static final int DAMAGE = 2560;
    /**
     * The encoded channel envelope {@code {c: 0, v: "x"}}.
     */
    private static final byte @NotNull [] ENVELOPE = {7, 2, 1, 'c', 3, 0, 0, 0, 0, 1, 'v', 5, 1, 'x'};

    private static @NotNull ScriptRuntimeScenario start(@NotNull Game game, @NotNull String @NotNull ... namesAndSources) throws IOException {
        ScriptPackage scriptPackage = ScriptRuntimeScenario.createPackage(namesAndSources);
        ScriptRuntimeScenario scenario = new ScriptRuntimeScenario(scriptPackage, game, ScriptRuntimeScenario.SEED, ScriptRuntime.INSTRUCTION_BUDGET);

        scenario.getRuntime().start();

        return scenario;
    }

    @Test
    void frame_calls_start_timers_channels_events_and_frame_hooks_in_order() throws IOException {
        Game game = ScriptRuntimeScenario.createGame();

        game.getUnitSystem().initializeUnitPool(UNIT_CAPACITY);

        ScriptRuntimeScenario scenario = start(game, ScriptRuntimeScenario.MAIN_NAME, """
                                                                                      var channel = sync.createChannel(function (player, value) { log.info('channel ' + player.getId() + ' ' + value); });
                                                                                      game.every(1, function () { log.info('timer'); });
                                                                                      function onStart() { log.info('start'); }
                                                                                      function onPlayerLeft(event) {
                                                                                          log.info('left ' + event.getPlayer().getId() + ' ' + (event.getRecord().kind === EventKind.PlayerLeft.id));
                                                                                      }
                                                                                      function onFrame(frame) { log.info('frame ' + frame); }
                                                                                      function onLocalFrame(frame) { log.info('local frame ' + frame); }
                                                                                      function onRender(screen) { log.info('render ' + typeof screen.drawText); }
                                                                                      """);

        game.storeCurrentFrame(1);
        scenario.getRuntime().queueEvent(new PlayerLeftEvent(2, 0), 1);
        scenario.getRuntime().queueModCommand(1, ScriptCommands.split(ENVELOPE).getFirst());
        scenario.getRuntime().runFrame(1);
        scenario.getRuntime().callLocalFrame(1);
        scenario.getRuntime().callRender();

        Assertions.assertEquals(List.of("start", "timer", "channel 1 x", "left 2 true", "frame 1", "local frame 1", "render function"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void decision_hooks_hand_their_changes_on_through_the_record() throws IOException {
        Game game = ScriptRuntimeScenario.createGame();

        game.getUnitSystem().initializeUnitPool(UNIT_CAPACITY);

        ScriptRuntimeScenario scenario = start(game, ScriptRuntimeScenario.MAIN_NAME, """
                                                                                      require('./second.js');
                                                                                      require('./third.js');
                                                                                      function onBeforeDamage(decision) {
                                                                                          log.info('first', decision.getAmount(), game.isReadOnly(), decision.getRecord().kind === DecisionKind.Damage.id);
                                                                                          decision.setAmount(decision.getAmount() / 2);
                                                                                      }
                                                                                      """,
                                               "second.js", """
                                                            function onBeforeDamage(decision) {
                                                                log.info('second', decision.getAmount(), decision.getRecord().amount);
                                                                decision.getRecord().amount = 1;
                                                                decision.cancel();
                                                                throw new Error('fails');
                                                            }
                                                            """,
                                               "third.js", """
                                                           function onBeforeDamage(decision) {
                                                               log.info('third', decision.getAmount(), decision.isCancelled(), decision.getTarget().exists());
                                                               decision.getRecord().cancelled = true;
                                                           }
                                                           """);
        UnitPool pool = game.getUnitSystem().requireUnitPool();
        SimulationUnit target = new SimulationUnit(pool.getUnit(TARGET_SLOT), UnitHandle.encode(TARGET_SLOT, 0, pool.getCapacity()), MARINE, 0);
        WeaponDamageDecision decision = new WeaponDamageDecision(0, null, 1, target, DAMAGE);

        scenario.getRuntime().decide(decision);

        Assertions.assertEquals(List.of("first 10 true true", "second 5 1280", "third 5 false false"), scenario.getInfoMessages(),
                                scenario.getLines()::toString);
        Assertions.assertTrue(scenario.getLines().contains("ERROR second.js: onBeforeDamage failed: Error: fails (second.js:5)"),
                              scenario.getLines()::toString);
        Assertions.assertEquals(DAMAGE / 2, decision.getDamage());
        Assertions.assertTrue(decision.isCancelled());
        Assertions.assertFalse(scenario.getRuntime().isReadOnly());
    }

    @Test
    void define_hooks_receive_the_events_decisions_and_local_calls_of_function_hooks() throws IOException {
        Game game = ScriptRuntimeScenario.createGame();

        game.getUnitSystem().initializeUnitPool(UNIT_CAPACITY);

        ScriptRuntimeScenario scenario = start(game, ScriptRuntimeScenario.MAIN_NAME, """
                                                                                      require('./second.js');
                                                                                      defineHooks({
                                                                                          onStart() { log.info('start'); },
                                                                                          onPlayerLeft(event) { log.info('left ' + event.getPlayer().getId()); },
                                                                                          onFrame(frame) { log.info('frame ' + frame); },
                                                                                          onBeforeDamage(decision) {
                                                                                              log.info('first', decision.getAmount(), game.isReadOnly());
                                                                                              decision.setAmount(decision.getAmount() / 2);
                                                                                          }
                                                                                      });
                                                                                      defineHooks({
                                                                                          onLocalKeyDown(event) { event.consume(); },
                                                                                          onLocalFrame(frame) { log.info('local frame ' + frame); },
                                                                                          onRender(screen) { log.info('render ' + typeof screen.drawText); }
                                                                                      });
                                                                                      """,
                                               "second.js", """
                                                            function onPlayerLeft(event) { log.info('second left ' + event.getPlayer().getId()); }
                                                            defineHooks({
                                                                onBeforeDamage(decision) { log.info('second', decision.getAmount()); }
                                                            });
                                                            """);
        UnitPool pool = game.getUnitSystem().requireUnitPool();
        SimulationUnit target = new SimulationUnit(pool.getUnit(TARGET_SLOT), UnitHandle.encode(TARGET_SLOT, 0, pool.getCapacity()), MARINE, 0);
        WeaponDamageDecision decision = new WeaponDamageDecision(0, null, 1, target, DAMAGE);

        game.storeCurrentFrame(1);
        scenario.getRuntime().queueEvent(new PlayerLeftEvent(2, 0), 1);
        scenario.getRuntime().runFrame(1);
        scenario.getRuntime().decide(decision);

        Assertions.assertTrue(scenario.getRuntime().callLocalInput(ScriptLocalInput.ofKey(KEY_Q, true, 0, false, 0, 0)));

        scenario.getRuntime().callLocalFrame(1);
        scenario.getRuntime().callRender();

        Assertions.assertEquals(List.of("start", "left 2", "second left 2", "frame 1", "first 10 true", "second 5", "local frame 1",
                                        "render function"), scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertEquals(DAMAGE / 2, decision.getDamage());
    }

    @Test
    void local_input_returns_the_consumption_and_core_local_lists_the_inputs_of_the_frame() throws IOException {
        ScriptRuntimeScenario scenario = start(ScriptRuntimeScenario.createGame(), ScriptRuntimeScenario.MAIN_NAME, """
                                                                                                                    function onLocalKeyDown(event) {
                                                                                                                        if (event.getKey() === Key.Q) {
                                                                                                                            event.consume();
                                                                                                                        }
                                                                                                                    }
                                                                                                                    function onLocalChat(event) {
                                                                                                                        event.getRecord().consumed = event.getText() === 'hide';
                                                                                                                    }
                                                                                                                    function onLocalFrame(frame) {
                                                                                                                        log.info(frame + ' ' + JSON.stringify(core.local.getInputEvents()));
                                                                                                                    }
                                                                                                                    """);
        ScriptRuntime runtime = scenario.getRuntime();

        Assertions.assertTrue(runtime.callLocalInput(ScriptLocalInput.ofKey(KEY_Q, true, 0, false, 0, 0)));
        Assertions.assertFalse(runtime.callLocalInput(ScriptLocalInput.ofKey(KEY_W, true, 1, true, 32, 64)));
        Assertions.assertTrue(runtime.callLocalInput(ScriptLocalInput.ofChat("hide")));

        runtime.callLocalFrame(1);
        runtime.callLocalFrame(2);

        Assertions.assertEquals(List.of("1 [{\"kind\":0,\"key\":81,\"modifiers\":0,\"positioned\":false,\"x\":0,\"y\":0,\"consumed\":true}," +
                                        "{\"kind\":0,\"key\":87,\"modifiers\":1,\"positioned\":true,\"x\":32,\"y\":64,\"consumed\":false}," +
                                        "{\"kind\":4,\"text\":\"hide\",\"consumed\":true}]",
                                        "2 []"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void lower_functions_refuse_the_wrong_code_with_a_type_error_and_bad_values_with_a_range_error() throws IOException {
        ScriptRuntimeScenario scenario = start(ScriptRuntimeScenario.createGame(), ScriptRuntimeScenario.MAIN_NAME, """
                                                                                                                    function attempt(calls) {
                                                                                                                        return Object.keys(calls).map(function (name) {
                                                                                                                            try {
                                                                                                                                calls[name]();
                                                                                                                                return name + ' works';
                                                                                                                            } catch (error) {
                                                                                                                                return name + ' ' + error.name;
                                                                                                                            }
                                                                                                                        }).join(', ');
                                                                                                                    }
                                                                                                                    function onFrame() {
                                                                                                                        log.info(attempt({
                                                                                                                            local: function () { core.local.getPlayer(); },
                                                                                                                            screen: function () { core.screen.getWidth(); },
                                                                                                                            draw: function () { core.screen.drawPoint(0, 0, 0); },
                                                                                                                            events: function () { core.events.getFrameEvents(); },
                                                                                                                            executed: function () { core.command.getExecuted(); },
                                                                                                                            submit: function () { core.command.submit(1); },
                                                                                                                            read: function () { core.game.getFrame(); },
                                                                                                                            kind: function () { core.events.getKindName(99); }
                                                                                                                        }));
                                                                                                                    }
                                                                                                                    function onLocalFrame() {
                                                                                                                        log.info(attempt({
                                                                                                                            local: function () { core.local.isReplay(); },
                                                                                                                            events: function () { core.events.getFrameEvents(); },
                                                                                                                            executed: function () { core.command.getExecuted(); },
                                                                                                                            write: function () { core.player.setMinerals(0, 5); },
                                                                                                                            print: function () { core.game.print('local'); },
                                                                                                                            submit: function () { core.command.submit(1); },
                                                                                                                            read: function () { core.game.getFrame(); },
                                                                                                                            names: function () { core.events.getKindName(0); }
                                                                                                                        }));
                                                                                                                    }
                                                                                                                    """);

        scenario.getRuntime().runFrame(0);
        scenario.getRuntime().callLocalFrame(0);

        Assertions.assertEquals(List.of("local TypeError, screen TypeError, draw TypeError, events works, executed works, submit works, " +
                                        "read works, kind RangeError",
                                        "local works, events TypeError, executed TypeError, write TypeError, print TypeError, submit works, " +
                                        "read works, names works"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void prelude_files_protect_to_the_same_programs() throws IOException {
        for (String fileName : ScriptPrelude.FILE_NAMES) {
            String source;

            try (InputStream input = ScriptPrelude.class.getResourceAsStream(ScriptPrelude.RESOURCE_FOLDER + fileName)) {
                Assertions.assertNotNull(input, fileName);
                source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }

            // The protector refuses a protected text whose syntax tree differs from the source beyond the given names.
            ScriptProtector protector = ScriptProtector.protect(fileName, source);

            Assertions.assertTrue(protector.getText().length() < source.length() / 2, fileName);
            Assertions.assertFalse(protector.getRenames().isEmpty(), fileName);
        }
    }
}
