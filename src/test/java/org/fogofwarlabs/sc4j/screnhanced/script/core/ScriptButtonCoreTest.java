package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonAction;
import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonCondition;
import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonSetTable;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrButtonInput;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrModCommandSubmitter;
import org.fogofwarlabs.sc4j.scr.game.presentation.HostButtonSets;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Tests the command card of the script API: the button set table of {@code core.buttons} and its upper objects, the local states of the
 * Script condition, the button inputs and their hooks, and the script buttons of {@code display.commandCard.createButton} between two peers.
 */
final class ScriptButtonCoreTest {
    private static final int MAP_TILE_SIZE = 4;
    /**
     * The Marine's button set, whose array the hero Marine's set shares.
     */
    private static final int MARINE_SET = 0;
    private static final int SCRIPT_POSITION = 7;
    private static final int SCRIPT_ICON = 236;
    private static final int TARGET_X = 96;
    private static final int TARGET_Y = 160;
    /**
     * The ids of the script API's Script condition and Script and ScriptTarget actions, which map authors use.
     */
    private static final int SCRIPT_CONDITION_ID = 63;
    private static final int SCRIPT_ACTION_ID = 60;
    private static final int SCRIPT_TARGET_ACTION_ID = 61;

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
     * One peer of the game: its own game and script runtime, with the slot of its player.
     */
    private static final class Peer {
        private final int slot;
        private final @NotNull Game game;
        private final @NotNull ScriptGameScenario scenario;
        private final @NotNull RecordingSubmitter submitter;

        private Peer(int slot, @NotNull String mainSource) throws IOException {
            this.slot      = slot;
            this.game      = Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, 0, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]),
                                         new LcgRandom(0));
            this.scenario  = new ScriptGameScenario(this.game, mainSource);
            this.submitter = new RecordingSubmitter();

            this.scenario.getRuntime().start();
        }

        private void runFrame(int frame) {
            this.game.storeCurrentFrame(frame);
            this.scenario.getRuntime().runFrame(frame);
        }

        private boolean press(@NotNull ScrButtonInput input) {
            boolean consumed = this.scenario.getRuntime().callLocalInput(this.scenario.getRuntime().createButtonInput(input));

            this.scenario.getRuntime().submitCommands(this.submitter);

            return consumed;
        }

        private @NotNull List<String> getMessages() {
            return this.scenario.getInfoMessages();
        }
    }

    /**
     * Creates the press of the script button with an id at its index of the Marine's set.
     */
    private static @NotNull ScrButtonInput createPress(int index, int id, boolean target) {
        ButtonAction action = target ? ButtonAction.PLUGIN_TARGET : ButtonAction.PLUGIN;

        return ScrButtonInput.ofPress(MARINE_SET, index, SCRIPT_POSITION, SCRIPT_ICON, ButtonCondition.PLUGIN.getNativeAddress(), id,
                                      action.getNativeAddress(), id, false, true);
    }

    /**
     * Runs the commands a peer submitted on every peer at a frame, as the command stream does, then the frame.
     */
    private static void runCommands(int frame, @NotNull Peer sender, @NotNull Peer @NotNull ... peers) {
        List<byte[]> commands = new ArrayList<>(sender.submitter.commands);

        sender.submitter.commands.clear();

        for (Peer peer : peers) {
            for (byte[] data : commands) {
                peer.scenario.getRuntime().queueModCommand(sender.slot, data);
            }

            peer.runFrame(frame);
        }
    }

    @Test
    void the_table_starts_installed_and_a_change_reaches_every_set_that_shares_the_array() throws IOException {
        Peer peer = new Peer(0, """
                                var marine = display.commandCard.getButtonSet(UnitType.TerranMarine);
                                var shared = -1;
                                for (var set = 1; set < core.buttons.getSetCount() && shared < 0; set++) {
                                    if (core.buttons.getArray(set) === 0) {
                                        shared = set;
                                    }
                                }
                                var hero = display.commandCard.getButtonSet(shared);
                                log.info(marine.getId(), marine.getButtonCount(), hero.getButtonCount(), hero.getArray() === marine,
                                         marine.getButton(0).getAction() === ButtonAction.Move, marine.getButton(0).getCondition() === ButtonCondition.Always,
                                         marine.getButton(0).getEnabledString(), marine.getButton(0).getText(), marine.getButton(99));
                                log.info(core.buttons.getConditionName(core.buttons.getConditionCount() - 1), ButtonCondition.Script.getId(),
                                         core.buttons.getActionName(core.buttons.getActionCount() - 2), ButtonAction.ScriptTarget.getName());
                                var added = marine.insertButton(0, {position: 9, icon: 12, condition: ButtonCondition.Always, action: ButtonAction.Stop,
                                                                    text: 'Halt\\nnow', hotkey: 'H', kind: ButtonKind.Research, disabledText: '\\x06No'});
                                log.info(marine.getButtonCount(), hero.getButtonCount(), added.getText() === 'Halt\\nnow', added.getHotkey(),
                                         added.getKind() === ButtonKind.Research, JSON.stringify(core.buttons.getEnabledText(0, 0)),
                                         JSON.stringify(added.getDisabledText()));
                                added.setText('Stop it', {hotkey: null});
                                added.setIcon(13);
                                log.info(JSON.stringify(core.buttons.getEnabledText(0, 0)), core.buttons.getIcon(shared, 0), added.getKind().getName());
                                hero.unshare();
                                marine.removeButton(0);
                                log.info(marine.getButtonCount(), hero.getButtonCount(), hero.getArray() === hero, core.buttons.getIcon(shared, 0));
                                hero.share(marine);
                                log.info(hero.getButtonCount(), hero.getArray() === marine);
                                hero.restore();
                                log.info(hero.getButtonCount(), hero.getArray() === hero);
                                core.buttons.restoreAll();
                                log.info(hero.getArray() === marine, marine.getButtonCount());
                                function attempt(name, call) {
                                    try {
                                        call();
                                        log.info(name + ' ok');
                                    } catch (error) {
                                        log.info(name + ' ' + error.name);
                                    }
                                }
                                attempt('set', function () { core.buttons.getButtonCount(250); });
                                attempt('position', function () { core.buttons.setPosition(0, 0, 10); });
                                attempt('index', function () { core.buttons.remove(0, 99); });
                                attempt('condition', function () { core.buttons.setCondition(0, 0, core.buttons.getConditionCount()); });
                                attempt('value', function () { core.buttons.setConditionValue(0, 0, 256); });
                                attempt('integer', function () { core.buttons.setIcon(0, 0, 'a'); });
                                attempt('text', function () { core.buttons.setText(0, 0, 5, null); });
                                function onLocalFrame() {
                                    attempt('local', function () { core.buttons.setIcon(0, 0, 99); });
                                    log.info(core.buttons.getIcon(0, 0));
                                }
                                """);
        int count = ButtonSetTable.getButtons(MARINE_SET).length;

        peer.scenario.getRuntime().callLocalFrame(0);

        Assertions.assertEquals(List.of("0 " + count + " " + count + " true true true 664 null null",
                                        "Script " + SCRIPT_CONDITION_ID + " Script ScriptTarget",
                                        (count + 1) + " " + (count + 1) + " true H true \"H\\u0004Halt\\nnow\" \"\\u0006No\"",
                                        "\"\\u0000\\u0004Stop it\" 13 Research",
                                        count + " " + (count + 1) + " true 13",
                                        count + " true",
                                        count + " true",
                                        "true " + count,
                                        "set RangeError", "position RangeError", "index RangeError", "condition RangeError", "value RangeError",
                                        "integer TypeError", "text TypeError",
                                        "local ok", "99"),
                                peer.getMessages(), peer.scenario.getLines()::toString);
    }

    @Test
    void the_script_api_names_every_condition_and_action_once_with_fixed_ids() {
        Set<ButtonCondition> conditions = EnumSet.noneOf(ButtonCondition.class);
        Set<ButtonAction> actions = EnumSet.noneOf(ButtonAction.class);

        for (ScriptButtonCore.Condition condition : ScriptButtonCore.Condition.values()) {
            Assertions.assertTrue(conditions.add(condition.getFunction()), condition::name);
            Assertions.assertEquals(condition.ordinal(), ScriptButtonCore.findConditionId(condition.getFunction().getNativeAddress()));
        }

        for (ScriptButtonCore.Action action : ScriptButtonCore.Action.values()) {
            Assertions.assertTrue(actions.add(action.getFunction()), action::name);
            Assertions.assertEquals(action.ordinal(), ScriptButtonCore.findActionId(action.getFunction().getNativeAddress()));
        }

        Assertions.assertEquals(EnumSet.allOf(ButtonCondition.class), conditions);
        Assertions.assertEquals(EnumSet.allOf(ButtonAction.class), actions);
        Assertions.assertEquals(ButtonCondition.PLUGIN, ScriptButtonCore.Condition.values()[SCRIPT_CONDITION_ID].getFunction());
        Assertions.assertEquals(ButtonAction.PLUGIN, ScriptButtonCore.Action.values()[SCRIPT_ACTION_ID].getFunction());
        Assertions.assertEquals(ButtonAction.PLUGIN_TARGET, ScriptButtonCore.Action.values()[SCRIPT_TARGET_ACTION_ID].getFunction());
        Assertions.assertEquals(-1, ScriptButtonCore.findConditionId(0L));
    }

    @Test
    void an_eud_commit_replaces_the_changes_and_later_changes_apply_to_the_committed_table() throws IOException {
        Peer peer = new Peer(0, """
                                var marine = display.commandCard.getButtonSet(0);
                                marine.insertButton(0, {position: 9, icon: 12, condition: ButtonCondition.Always, action: ButtonAction.Stop});
                                log.info(marine.getButtonCount(), marine.getButton(0).getIcon());
                                function onFrame() {
                                    log.info(marine.getButtonCount(), marine.getButton(0).getIcon());
                                    marine.getButton(0).setIcon(77);
                                    log.info(marine.getButton(0).getIcon());
                                }
                                """);
        HostButtonSets host = peer.game.getGameContext().getHostButtonSets();
        HostButtonSets.Button[][] committed = ButtonSetTable.createHostButtons();
        int count = committed[MARINE_SET].length;

        // A commit of an EUD table write rebuilds every set.
        for (int set = 0; set < HostButtonSets.SET_COUNT; set++) {
            host.replace(set, committed[set].length, committed[set], ButtonSetTable.getConnectedSet(set));
        }

        peer.runFrame(1);

        Assertions.assertEquals(List.of((count + 1) + " 12", count + " 228", "77"), peer.getMessages(), peer.scenario.getLines()::toString);
        Assertions.assertEquals(77, host.getButtons(MARINE_SET)[0].getIcon());
    }

    @Test
    void the_script_condition_states_are_local_and_start_hidden() throws IOException {
        Peer peer = new Peer(0, """
                                try {
                                    core.local.setButtonState(0, 1);
                                } catch (error) {
                                    log.info('shared ' + error.name);
                                }
                                function onLocalFrame() {
                                    log.info(core.local.getButtonState(5), local.getButtonState(5) === ButtonState.Hidden, ButtonState.get(0).getName());
                                    local.setButtonState(5, ButtonState.Disabled);
                                    core.local.setButtonState(6, 1);
                                    log.info(core.local.getButtonState(5), core.local.getButtonState(6), local.getDisplayedButtonSet());
                                    try {
                                        core.local.setButtonState(0, 2);
                                    } catch (error) {
                                        log.info('state ' + error.name);
                                    }
                                    local.changeButtons(UnitType.TerranMarine);
                                }
                                """);

        peer.scenario.getRuntime().callLocalFrame(0);

        Assertions.assertEquals(List.of("shared TypeError", "0 true Hidden", "-1 1 null", "state RangeError"), peer.getMessages(),
                                peer.scenario.getLines()::toString);
        Assertions.assertEquals(-1, peer.game.getInterfaceLink().getButtonStates().getState(5));
        Assertions.assertEquals(MARINE_SET, peer.game.getInterfaceLink().getButtonStates().takeRequestedButtonSet());
    }

    @Test
    void a_script_button_press_reaches_on_pressed_on_every_peer_through_its_channel() throws IOException {
        String script = """
                        var blink = display.commandCard.createButton({
                            unitType: UnitType.TerranMarine, position: 7, icon: 236, text: 'Blink', hotkey: 'B', disabledText: 'Needs energy',
                            target: true,
                            onLocalPressed: function (event) {
                                log.info('local ' + event.getAction().getName() + (event.getRecord().kind === InputKind.ButtonTargeted.id
                                                                                   ? ' ' + event.getTargetPosition() + ' ' + event.getTarget()
                                                                                   : ' press'));
                            },
                            onPressed: function (player, units, target) {
                                log.info('pressed ' + game.getFrame() + ' ' + player.getId() + ' ' + units.length + ' ' + target.position + ' ' + target.unit);
                            }
                        });
                        var plain = display.commandCard.createButton({buttonSet: 0, position: 8, icon: 237, text: 'Ping', hotkey: 'P',
                            onPressed: function (player, units, target) {
                                log.info('plain ' + player.getId() + ' ' + target);
                            }
                        });
                        var index = blink.getButton().getIndex();
                        log.info(blink.getId(), plain.getId(), index, plain.getButton().getIndex(), blink.getButton().getPosition(),
                                 blink.getButton().getCondition().getName(), blink.getButton().getConditionValue(),
                                 blink.getButton().getAction().getName(), plain.getButton().getActionValue(),
                                 JSON.stringify(core.buttons.getEnabledText(0, index)), blink.getButton().getDisabledText());
                        function onLocalButtonPressed(event) {
                            log.info('hook pressed ' + event.getButtonSet().getId() + ' ' + event.getButton().getIndex() + ' ' + event.getAction().getName() +
                                     ' ' + event.getConditionValue() + ' ' + event.isHotkey() + ' ' + event.isQueued() + ' ' + event.getRecord().kind);
                        }
                        function onLocalButtonTargeted(event) {
                            if (event.getTargetPosition().getX() === 0) {
                                event.consume();
                            }
                        }
                        function onLocalFrame() {
                            log.info('states ' + core.local.getButtonState(0) + ' ' + core.local.getButtonState(1) + ' ' + core.local.getButtonState(2));
                            try {
                                display.commandCard.createButton({buttonSet: 0, position: 1, icon: 1});
                            } catch (error) {
                                log.info('local ' + error.name);
                            }
                        }
                        """;
        Peer first = new Peer(0, script);
        Peer second = new Peer(1, script);
        int index = ButtonSetTable.getButtons(MARINE_SET).length;

        first.runFrame(1);
        second.runFrame(1);
        first.scenario.getRuntime().callLocalFrame(1);

        ScrButtonInput press = createPress(index, 0, true);

        Assertions.assertFalse(first.press(press), "a target button's press enters the targeting mode");
        Assertions.assertTrue(first.press(press.createTargeted(0, TARGET_Y, null, false)), "a hook consumed the target");
        Assertions.assertTrue(first.submitter.commands.isEmpty(), "a consumed target sends nothing");
        Assertions.assertFalse(first.press(press.createTargeted(TARGET_X, TARGET_Y, null, false)));
        Assertions.assertFalse(first.press(createPress(index + 1, 1, false)));

        runCommands(2, first, first, second);

        String created = "0 1 " + index + " " + (index + 1) + " 7 Script 0 ScriptTarget 1 \"B\\u0000Blink\" Needs energy";
        List<String> pressed = List.of("pressed 2 0 0 (" + TARGET_X + ", " + TARGET_Y + ") null", "plain 0 null");

        Assertions.assertEquals(List.of(created, "states 1 1 0", "local TypeError",
                                        "hook pressed 0 " + index + " ScriptTarget 0 true false 5", "local ScriptTarget press",
                                        "local ScriptTarget (" + TARGET_X + ", " + TARGET_Y + ") null",
                                        "hook pressed 0 " + (index + 1) + " Script 1 true false 5",
                                        pressed.get(0), pressed.get(1)),
                                first.getMessages(), first.scenario.getLines()::toString);

        List<String> remote = new ArrayList<>(List.of(created));

        remote.addAll(pressed);

        Assertions.assertEquals(remote, second.getMessages(), second.scenario.getLines()::toString);
    }

    @Test
    void consuming_the_press_of_a_script_target_button_keeps_it_from_the_game() throws IOException {
        Peer peer = new Peer(0, """
                                display.commandCard.createButton({
                                    unitType: UnitType.TerranMarine, position: 7, icon: 236, target: true,
                                    onLocalPressed: function (event) {
                                        log.info('local ' + event.getRecord().kind);
                                        event.consume();
                                    },
                                    onPressed: function () {
                                        log.info('pressed');
                                    }
                                });
                                """);
        int index = ButtonSetTable.getButtons(MARINE_SET).length;
        ScrButtonInput press = createPress(index, 0, true);

        peer.runFrame(1);

        Assertions.assertTrue(peer.press(press), "onLocalPressed consumed the press");
        Assertions.assertTrue(peer.press(press.createTargeted(TARGET_X, TARGET_Y, null, false)), "onLocalPressed consumed the target");
        Assertions.assertTrue(peer.submitter.commands.isEmpty());
        Assertions.assertEquals(List.of("local 5", "local 6"), peer.getMessages(), peer.scenario.getLines()::toString);
    }
}
