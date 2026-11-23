package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonAction;
import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonCondition;
import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonSetTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.HostButtonSets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Defines {@code core.buttons}: the command card's button set table, sets {@code 0} to {@value HostButtonSets#SET_COUNT} minus one, each an
 * array of buttons in table order and a connected set. The sets of the unit types are their type ids; the command card fills each position
 * with the first button of the shown set whose condition does not hide it.
 * <p>
 * The table is presentation state that the simulation never reads, so every function works anywhere: a change in shared code changes the
 * card of every peer, a change in local code only the local card. The first change copies the installed table into the game's table
 * ({@link HostButtonSets}); several sets may share one array, as the sets of a unit and its hero do, and a change of a button or of the array
 * changes every set that shares it ({@code getArray(set)} names the array by the first set that holds it). An EUD write of the table rebuilds
 * every set from the classic table, so it replaces the changes made before it.
 * <p>
 * Sets: {@code getSetCount()}, {@code getButtonCount(set)}, {@code getConnectedSet(set)} and {@code setConnectedSet(set, value)} (a WORD,
 * {@code 0xFFFF} for none), {@code getArray(set)}, {@code share(set, fromSet)} (the set takes the array of {@code fromSet}),
 * {@code unshare(set)} (the set takes a copy of its array), {@code restore(set)} (the installed array and connected set) and
 * {@code restoreAll()} (the installed table, also after EUD writes).
 * <p>
 * Buttons by set and index: {@code getPosition} and {@code setPosition} ({@code 1} to {@code 9}), {@code getIcon} and {@code setIcon} (the
 * {@code cmdicons} frame, a WORD), {@code getCondition} and {@code setCondition} (a condition id, the order of {@code getConditionName}),
 * {@code getConditionValue} and {@code setConditionValue} (a BYTE), {@code getAction} and {@code setAction} (an action id, the order of
 * {@code getActionName}), {@code getActionValue} and {@code setActionValue} (a WORD), {@code getEnabledString}, {@code setEnabledString},
 * {@code getDisabledString} and {@code setDisabledString} (one-based {@code stat_txt} ids, {@code 0} for none), {@code getEnabledText},
 * {@code getDisabledText} and {@code setText(set, index, enabled, disabled)} (texts shown instead of the strings, {@code null} for the
 * string: the enabled text in the {@code stat_txt} button string format, its hotkey character, its button kind character and the text, and the
 * plain disabled text; both may hold line breaks and the text color control characters), {@code insert(set, index, position, icon,
 * condition, conditionValue, action, actionValue, enabledString, disabledString)} (before the button at the index, or at the end for the
 * button count; the table order is the order the card tries the buttons in) and {@code remove(set, index)}. A button whose function is
 * unknown has the condition or action {@code -1}.
 * <p>
 * The conditions and actions: {@code getConditionCount()}, {@code getConditionName(id)}, {@code getActionCount()} and
 * {@code getActionName(id)}, in the order of the script API's own lists ({@link Condition}, {@link Action}); the last condition,
 * {@code Script}, gives the state that {@code core.local.setButtonState} sets for its condition value, and the last actions, {@code Script}
 * and {@code ScriptTarget}, send no command: the press, or the target of a targeting mode, reaches the local input hooks only.
 */
final class ScriptButtonCore {
    /**
     * The largest WORD value.
     */
    private static final int WORD_MAXIMUM = 0xFFFF;
    /**
     * The largest BYTE value.
     */
    private static final int BYTE_MAXIMUM = 0xFF;
    /**
     * The first and last command card positions.
     */
    private static final int FIRST_POSITION = 1;
    private static final int LAST_POSITION = 9;
    /**
     * The id of an unknown condition or action function.
     */
    private static final int UNKNOWN_FUNCTION = -1;

    private static final HostButtonSets.@NotNull Button @NotNull [] NO_BUTTONS = new HostButtonSets.Button[0];
    private static final @NotNull Condition @NotNull [] CONDITIONS = Condition.values();
    private static final @NotNull Action @NotNull [] ACTIONS = Action.values();

    /**
     * The conditions of the script API: the id is the ordinal, the name the Pascal case constant name. New conditions go last.
     */
    enum Condition {
        ALWAYS(ButtonCondition.ALWAYS),
        REPLAY_SLOW_DOWN(ButtonCondition.REPLAY_SLOW_DOWN),
        REPLAY_SPEED_UP(ButtonCondition.REPLAY_SPEED_UP),
        REPLAY_PLAY_PAUSE(ButtonCondition.REPLAY_PLAY_PAUSE),
        UNIT_REQUIREMENT(ButtonCondition.UNIT_REQUIREMENT),
        HANGAR_UNIT_REQUIREMENT(ButtonCondition.HANGAR_UNIT_REQUIREMENT),
        ANY_CAN_MOVE(ButtonCondition.ANY_CAN_MOVE),
        ANY_HAS_RIGHT_CLICK(ButtonCondition.ANY_HAS_RIGHT_CLICK),
        ANY_CAN_ATTACK(ButtonCondition.ANY_CAN_ATTACK),
        ANY_ENABLED_CAN_ATTACK(ButtonCondition.ANY_ENABLED_CAN_ATTACK),
        TRAINING_UNIT(ButtonCondition.TRAINING_UNIT),
        BUILDING_ADDON(ButtonCondition.BUILDING_ADDON),
        IS_HATCHERY(ButtonCondition.IS_HATCHERY),
        RALLY_ORDER(ButtonCondition.RALLY_ORDER),
        HATCHERY_RALLY_ORDER(ButtonCondition.HATCHERY_RALLY_ORDER),
        INCOMPLETE(ButtonCondition.INCOMPLETE),
        UPGRADING(ButtonCondition.UPGRADING),
        RESEARCHING(ButtonCondition.RESEARCHING),
        ANY_NOT_CARRYING(ButtonCondition.ANY_NOT_CARRYING),
        ANY_CARRYING(ButtonCondition.ANY_CARRYING),
        ANY_CAN_LOAD(ButtonCondition.ANY_CAN_LOAD),
        ANY_HAS_LOADED(ButtonCondition.ANY_HAS_LOADED),
        RESEARCH_REQUIREMENT(ButtonCondition.RESEARCH_REQUIREMENT),
        TECHNOLOGY_USE_REQUIREMENT(ButtonCondition.TECHNOLOGY_USE_REQUIREMENT),
        ANY_TECHNOLOGY_USE_REQUIREMENT(ButtonCondition.ANY_TECHNOLOGY_USE_REQUIREMENT),
        UPGRADE_REQUIREMENT(ButtonCondition.UPGRADE_REQUIREMENT),
        CAN_CLOAK(ButtonCondition.CAN_CLOAK),
        CAN_DECLOAK(ButtonCondition.CAN_DECLOAK),
        CAN_CLOAK_OWN_TECHNOLOGY(ButtonCondition.CAN_CLOAK_OWN_TECHNOLOGY),
        CAN_DECLOAK_OWN_TECHNOLOGY(ButtonCondition.CAN_DECLOAK_OWN_TECHNOLOGY),
        CAN_SIEGE(ButtonCondition.CAN_SIEGE),
        CAN_UNSIEGE(ButtonCondition.CAN_UNSIEGE),
        NONE_SIEGED(ButtonCondition.NONE_SIEGED),
        NUCLEAR_SILO_READY(ButtonCondition.NUCLEAR_SILO_READY),
        NOT_DISABLED(ButtonCondition.NOT_DISABLED),
        NOT_GROUNDED(ButtonCondition.NOT_GROUNDED),
        CAN_LAND(ButtonCondition.CAN_LAND),
        CAN_LIFT_OFF(ButtonCondition.CAN_LIFT_OFF),
        CAN_BURROW(ButtonCondition.CAN_BURROW),
        CAN_UNBURROW(ButtonCondition.CAN_UNBURROW),
        LURKER_ASPECT(ButtonCondition.LURKER_ASPECT),
        NONE_BURROWED(ButtonCondition.NONE_BURROWED),
        NONE_BURROWED_CAN_ATTACK(ButtonCondition.NONE_BURROWED_CAN_ATTACK),
        NONE_BURROWED_NOT_CARRYING(ButtonCondition.NONE_BURROWED_NOT_CARRYING),
        NONE_BURROWED_CARRYING(ButtonCondition.NONE_BURROWED_CARRYING),
        SAME_BURROW_STATE(ButtonCondition.SAME_BURROW_STATE),
        NO_NYDUS_EXIT(ButtonCondition.NO_NYDUS_EXIT),
        ZERG_BASIC_STRUCTURES(ButtonCondition.ZERG_BASIC_STRUCTURES),
        ZERG_ADVANCED_STRUCTURES(ButtonCondition.ZERG_ADVANCED_STRUCTURES),
        CARRIER_HAS_INTERCEPTORS(ButtonCondition.CARRIER_HAS_INTERCEPTORS),
        REAVER_HAS_SCARABS(ButtonCondition.REAVER_HAS_SCARABS),
        TECHNOLOGY_USE_DISABLED(ButtonCondition.TECHNOLOGY_USE_DISABLED),
        ARCHON_WARP(ButtonCondition.ARCHON_WARP),
        DARK_ARCHON_MELD(ButtonCondition.DARK_ARCHON_MELD),
        PROTOSS_BASIC_STRUCTURES(ButtonCondition.PROTOSS_BASIC_STRUCTURES),
        PROTOSS_ADVANCED_STRUCTURES(ButtonCondition.PROTOSS_ADVANCED_STRUCTURES),
        ALL_CONSTRUCTING(ButtonCondition.ALL_CONSTRUCTING),
        ANY_NOT_CONSTRUCTING(ButtonCondition.ANY_NOT_CONSTRUCTING),
        SCV_CAN_GATHER(ButtonCondition.SCV_CAN_GATHER),
        SCV_CAN_RETURN_CARGO(ButtonCondition.SCV_CAN_RETURN_CARGO),
        TERRAN_BASIC_STRUCTURES(ButtonCondition.TERRAN_BASIC_STRUCTURES),
        TERRAN_ADVANCED_STRUCTURES(ButtonCondition.TERRAN_ADVANCED_STRUCTURES),
        SINGLE_UNIT_REQUIREMENT(ButtonCondition.SINGLE_UNIT_REQUIREMENT),
        SCRIPT(ButtonCondition.PLUGIN);

        private final @NotNull ButtonCondition function;

        Condition(@NotNull ButtonCondition function) {
            this.function = function;
        }

        /**
         * @return the SC:R condition function
         */
        @NotNull ButtonCondition getFunction() {
            return this.function;
        }
    }

    /**
     * The actions of the script API: the id is the ordinal, the name the Pascal case constant name. New actions go last.
     */
    enum Action {
        MOVE(ButtonAction.MOVE),
        STOP(ButtonAction.STOP),
        ATTACK(ButtonAction.ATTACK),
        PATROL(ButtonAction.PATROL),
        HOLD_POSITION(ButtonAction.HOLD_POSITION),
        STIM_PACKS(ButtonAction.STIM_PACKS),
        CLOAK(ButtonAction.CLOAK),
        DECLOAK(ButtonAction.DECLOAK),
        USE_TECHNOLOGY(ButtonAction.USE_TECHNOLOGY),
        NUCLEAR_STRIKE(ButtonAction.NUCLEAR_STRIKE),
        SIEGE(ButtonAction.SIEGE),
        UNSIEGE(ButtonAction.UNSIEGE),
        REPAIR(ButtonAction.REPAIR),
        GATHER(ButtonAction.GATHER),
        RETURN_CARGO(ButtonAction.RETURN_CARGO),
        CHANGE_BUTTONS(ButtonAction.CHANGE_BUTTONS),
        LOAD(ButtonAction.LOAD),
        UNLOAD_ALL(ButtonAction.UNLOAD_ALL),
        HEAL(ButtonAction.HEAL),
        UNIT_MORPH(ButtonAction.UNIT_MORPH),
        CANCEL_MORPH(ButtonAction.CANCEL_MORPH),
        BURROW(ButtonAction.BURROW),
        UNBURROW(ButtonAction.UNBURROW),
        INFESTED_TERRAN_ATTACK(ButtonAction.INFESTED_TERRAN_ATTACK),
        DARK_ARCHON_MELD(ButtonAction.DARK_ARCHON_MELD),
        ARCHON_WARP(ButtonAction.ARCHON_WARP),
        CARRIER_MOVE(ButtonAction.CARRIER_MOVE),
        CARRIER_STOP(ButtonAction.CARRIER_STOP),
        CARRIER_ATTACK(ButtonAction.CARRIER_ATTACK),
        TRAIN_HANGAR_UNIT(ButtonAction.TRAIN_HANGAR_UNIT),
        CANCEL_LAST(ButtonAction.CANCEL_LAST),
        REAVER_STOP(ButtonAction.REAVER_STOP),
        REAVER_ATTACK(ButtonAction.REAVER_ATTACK),
        TRAIN(ButtonAction.TRAIN),
        SET_RALLY_POINT(ButtonAction.SET_RALLY_POINT),
        BUILD_ADDON(ButtonAction.BUILD_ADDON),
        LAND(ButtonAction.LAND),
        LIFT_OFF(ButtonAction.LIFT_OFF),
        CANCEL_ADDON(ButtonAction.CANCEL_ADDON),
        UPGRADE(ButtonAction.UPGRADE),
        RESEARCH(ButtonAction.RESEARCH),
        CANCEL_UPGRADE(ButtonAction.CANCEL_UPGRADE),
        CANCEL_RESEARCH(ButtonAction.CANCEL_RESEARCH),
        BUILDING_ATTACK(ButtonAction.BUILDING_ATTACK),
        SELECT_LARVA(ButtonAction.SELECT_LARVA),
        BUILDING_MORPH(ButtonAction.BUILDING_MORPH),
        PLACE_NYDUS_EXIT(ButtonAction.PLACE_NYDUS_EXIT),
        RECHARGE_SHIELDS(ButtonAction.RECHARGE_SHIELDS),
        PLACE_BEACON(ButtonAction.PLACE_BEACON),
        CANCEL_TARGETING(ButtonAction.CANCEL_TARGETING),
        CANCEL_PLACEMENT(ButtonAction.CANCEL_PLACEMENT),
        CANCEL_CONSTRUCTION(ButtonAction.CANCEL_CONSTRUCTION),
        CANCEL_INFESTATION(ButtonAction.CANCEL_INFESTATION),
        CANCEL_NUCLEAR_STRIKE(ButtonAction.CANCEL_NUCLEAR_STRIKE),
        ZERG_BUILD(ButtonAction.ZERG_BUILD),
        TERRAN_BUILD(ButtonAction.TERRAN_BUILD),
        PROTOSS_BUILD(ButtonAction.PROTOSS_BUILD),
        REPLAY_PLAY_PAUSE(ButtonAction.REPLAY_PLAY_PAUSE),
        REPLAY_SLOW_DOWN(ButtonAction.REPLAY_SLOW_DOWN),
        REPLAY_SPEED_UP(ButtonAction.REPLAY_SPEED_UP),
        SCRIPT(ButtonAction.PLUGIN),
        SCRIPT_TARGET(ButtonAction.PLUGIN_TARGET);

        private final @NotNull ButtonAction function;

        Action(@NotNull ButtonAction function) {
            this.function = function;
        }

        /**
         * @return the SC:R action function
         */
        @NotNull ButtonAction getFunction() {
            return this.function;
        }
    }

    /**
     * Reads a field of a button.
     */
    @FunctionalInterface
    private interface ButtonRead {
        int read(HostButtonSets.@NotNull Button button);
    }

    /**
     * Creates a button with one field changed.
     */
    @FunctionalInterface
    private interface ButtonWrite {
        HostButtonSets.@NotNull Button write(HostButtonSets.@NotNull Button button, int value);
    }

    /**
     * Finds the id of a condition function.
     *
     * @param address the function address, as the game's table holds it
     * @return the condition id, or {@code -1} for an unknown function
     */
    static int findConditionId(long address) {
        for (Condition condition : CONDITIONS) {
            if (condition.getFunction().getNativeAddress() == address) {
                return condition.ordinal();
            }
        }

        return UNKNOWN_FUNCTION;
    }

    /**
     * Finds the id of an action function.
     *
     * @param address the function address, as the game's table holds it
     * @return the action id, or {@code -1} for an unknown function
     */
    static int findActionId(long address) {
        for (Action action : ACTIONS) {
            if (action.getFunction().getNativeAddress() == address) {
                return action.ordinal();
            }
        }

        return UNKNOWN_FUNCTION;
    }

    /**
     * Turns an upper snake case constant name into its Pascal case name, as {@code SCV_CAN_GATHER} into {@code ScvCanGather}.
     */
    private static @NotNull String toPascalCase(@NotNull String constantName) {
        StringBuilder name = new StringBuilder(constantName.length());

        for (String word : constantName.split("_")) {
            name.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }

        return name.toString();
    }

    private static HostButtonSets.@NotNull Button copy(HostButtonSets.@NotNull Button button, int position, int icon,
                                                       long condition, int conditionValue,
                                                       long action, int actionValue,
                                                       int enabledString, int disabledString) {
        return new HostButtonSets.Button(position, icon, condition, conditionValue, action, actionValue, enabledString, disabledString,
                                         button.getEnabledText(), button.getDisabledText());
    }

    private static int readSet(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("button set", ScriptCoreUtils.readInteger(arguments, index), HostButtonSets.SET_COUNT);
    }

    private static int readValue(@NotNull Object @NotNull [] arguments, int index, int maximum) {
        return readRange(arguments, index, "value", 0, maximum);
    }

    private static int readRange(@NotNull Object @NotNull [] arguments, int index, @NotNull String kind, int minimum, int maximum) {
        return requireRange(kind, ScriptCoreUtils.readInteger(arguments, index), minimum, maximum);
    }

    /**
     * Checks that a value lies in a range.
     *
     * @throws EcmaError if the value is outside the range
     */
    private static int requireRange(@NotNull String kind, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw ScriptRuntime.rangeError(Exceptions.format("The {0} {1} is not from {2} to {3}.", kind, value, minimum, maximum));
        }

        return value;
    }

    /**
     * Reads a text argument: a string, or {@code null} or {@code undefined} for none.
     *
     * @throws EcmaError if the argument is neither
     */
    private static @Nullable String readText(@NotNull Object @NotNull [] arguments, int index) {
        if (index >= arguments.length || arguments[index] == null || arguments[index] == Context.getUndefinedValue()) {
            return null;
        }

        return ScriptCoreUtils.readString(arguments, index);
    }


    private final @NotNull ScriptGame scriptGame;

    private HostButtonSets.@NotNull Button @Nullable [] @Nullable [] installed;

    /**
     * Creates a {@link ScriptButtonCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptButtonCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;

        this.installed = null;
    }

    /**
     * Defines the functions of {@code core.buttons}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        this.defineSetFunctions(namespace, global);
        this.defineButtonFunctions(namespace, global);

        ScriptCoreUtils.defineFunction(global, namespace, "getConditionCount", 0,
                                       (context, scope, thisObject, arguments) -> (double) CONDITIONS.length);
        ScriptCoreUtils.defineFunction(global, namespace, "getConditionName", 1, (context, scope, thisObject, arguments) -> {
            int id = ScriptCoreUtils.requireId("condition", ScriptCoreUtils.readInteger(arguments, 0), CONDITIONS.length);

            return toPascalCase(CONDITIONS[id].name());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getActionCount", 0,
                                       (context, scope, thisObject, arguments) -> (double) ACTIONS.length);
        ScriptCoreUtils.defineFunction(global, namespace, "getActionName", 1, (context, scope, thisObject, arguments) -> {
            int id = ScriptCoreUtils.requireId("action", ScriptCoreUtils.readInteger(arguments, 0), ACTIONS.length);

            return toPascalCase(ACTIONS[id].name());
        });
    }

    /**
     * Defines the functions of {@code core.buttons} over whole sets.
     */
    private void defineSetFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getSetCount", 0,
                                       (context, scope, thisObject, arguments) -> (double) HostButtonSets.SET_COUNT);
        ScriptCoreUtils.defineFunction(global, namespace, "getButtonCount", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.getArray(readSet(arguments, 0)).length);
        ScriptCoreUtils.defineFunction(global, namespace, "getConnectedSet", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.getConnectedSet(readSet(arguments, 0)));
        ScriptCoreUtils.defineFunction(global, namespace, "setConnectedSet", 2, (context, scope, thisObject, arguments) -> {
            int set = readSet(arguments, 0);
            int connected = readValue(arguments, 1, WORD_MAXIMUM);
            HostButtonSets host = this.requireChangedTable();

            host.replace(set, host.getCount(set), host.getButtons(set), connected);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getArray", 1, (context, scope, thisObject, arguments) -> {
            HostButtonSets.Button[] array = this.getArray(readSet(arguments, 0));

            for (int set = 0; set < HostButtonSets.SET_COUNT; set++) {
                if (this.getArray(set) == array) {
                    return (double) set;
                }
            }

            throw Exceptions.newIllegalStateException("No set holds the array of a set.");
        });
        ScriptCoreUtils.defineFunction(global, namespace, "share", 2, (context, scope, thisObject, arguments) -> {
            int set = readSet(arguments, 0);
            int fromSet = readSet(arguments, 1);
            HostButtonSets host = this.requireChangedTable();
            HostButtonSets.Button[] array = this.getArray(fromSet);

            host.replace(set, array.length, array, host.getConnectedSet(set));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "unshare", 1, (context, scope, thisObject, arguments) -> {
            int set = readSet(arguments, 0);
            HostButtonSets host = this.requireChangedTable();
            HostButtonSets.Button[] array = this.getArray(set);

            host.replace(set, array.length, array.clone(), host.getConnectedSet(set));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restore", 1, (context, scope, thisObject, arguments) -> {
            int set = readSet(arguments, 0);
            HostButtonSets host = this.getHost();

            if (host.isReplaced()) {
                HostButtonSets.Button[] array = this.getInstalled()[set];

                host.replace(set, array.length, array, ButtonSetTable.getConnectedSet(set));
            }

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreAll", 0, (context, scope, thisObject, arguments) -> {
            this.getHost().restoreDefaults();

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the functions of {@code core.buttons} over single buttons.
     */
    private void defineButtonFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        this.defineField(namespace, global, "Position", HostButtonSets.Button::getPosition, (button, value) -> {
            requireRange("position", value, FIRST_POSITION, LAST_POSITION);

            return copy(button, value, button.getIcon(), button.getCondition(), button.getConditionValue(), button.getAction(),
                        button.getActionValue(), button.getEnabledString(), button.getDisabledString());
        });
        this.defineField(namespace, global, "Icon", HostButtonSets.Button::getIcon, (button, value) -> {
            requireRange("icon", value, 0, WORD_MAXIMUM);

            return copy(button, button.getPosition(), value, button.getCondition(), button.getConditionValue(), button.getAction(),
                        button.getActionValue(), button.getEnabledString(), button.getDisabledString());
        });
        this.defineField(namespace, global, "Condition", button -> findConditionId(button.getCondition()), (button, value) -> {
            long condition = CONDITIONS[ScriptCoreUtils.requireId("condition", value, CONDITIONS.length)].getFunction().getNativeAddress();

            return copy(button, button.getPosition(), button.getIcon(), condition, button.getConditionValue(), button.getAction(),
                        button.getActionValue(), button.getEnabledString(), button.getDisabledString());
        });
        this.defineField(namespace, global, "ConditionValue", HostButtonSets.Button::getConditionValue, (button, value) -> {
            requireRange("condition value", value, 0, BYTE_MAXIMUM);

            return copy(button, button.getPosition(), button.getIcon(), button.getCondition(), value, button.getAction(),
                        button.getActionValue(), button.getEnabledString(), button.getDisabledString());
        });
        this.defineField(namespace, global, "Action", button -> findActionId(button.getAction()), (button, value) -> {
            long action = ACTIONS[ScriptCoreUtils.requireId("action", value, ACTIONS.length)].getFunction().getNativeAddress();

            return copy(button, button.getPosition(), button.getIcon(), button.getCondition(), button.getConditionValue(), action,
                        button.getActionValue(), button.getEnabledString(), button.getDisabledString());
        });
        this.defineField(namespace, global, "ActionValue", HostButtonSets.Button::getActionValue, (button, value) -> {
            requireRange("action value", value, 0, WORD_MAXIMUM);

            return copy(button, button.getPosition(), button.getIcon(), button.getCondition(), button.getConditionValue(), button.getAction(),
                        value, button.getEnabledString(), button.getDisabledString());
        });
        this.defineField(namespace, global, "EnabledString", HostButtonSets.Button::getEnabledString, (button, value) -> {
            requireRange("string", value, 0, WORD_MAXIMUM);

            return copy(button, button.getPosition(), button.getIcon(), button.getCondition(), button.getConditionValue(), button.getAction(),
                        button.getActionValue(), value, button.getDisabledString());
        });
        this.defineField(namespace, global, "DisabledString", HostButtonSets.Button::getDisabledString, (button, value) -> {
            requireRange("string", value, 0, WORD_MAXIMUM);

            return copy(button, button.getPosition(), button.getIcon(), button.getCondition(), button.getConditionValue(), button.getAction(),
                        button.getActionValue(), button.getEnabledString(), value);
        });

        ScriptCoreUtils.defineFunction(global, namespace, "getEnabledText", 2,
                                       (context, scope, thisObject, arguments) -> this.readButton(arguments).getEnabledText());
        ScriptCoreUtils.defineFunction(global, namespace, "getDisabledText", 2,
                                       (context, scope, thisObject, arguments) -> this.readButton(arguments).getDisabledText());
        ScriptCoreUtils.defineFunction(global, namespace, "setText", 4, (context, scope, thisObject, arguments) -> {
            String enabled = readText(arguments, 2);
            String disabled = readText(arguments, 3);

            this.changeButton(arguments, button -> new HostButtonSets.Button(button.getPosition(), button.getIcon(),
                                                                             button.getCondition(), button.getConditionValue(),
                                                                             button.getAction(), button.getActionValue(),
                                                                             button.getEnabledString(), button.getDisabledString(),
                                                                             enabled, disabled));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "insert", 10, (context, scope, thisObject, arguments) -> {
            int set = readSet(arguments, 0);
            HostButtonSets.Button[] array = this.getArray(set);
            int index = ScriptCoreUtils.requireId("button index", ScriptCoreUtils.readInteger(arguments, 1), array.length + 1);
            HostButtonSets.Button button = new HostButtonSets.Button(
                    readRange(arguments, 2, "position", FIRST_POSITION, LAST_POSITION), readValue(arguments, 3, WORD_MAXIMUM),
                    CONDITIONS[ScriptCoreUtils.requireId("condition", ScriptCoreUtils.readInteger(arguments, 4), CONDITIONS.length)]
                            .getFunction().getNativeAddress(),
                    readValue(arguments, 5, BYTE_MAXIMUM),
                    ACTIONS[ScriptCoreUtils.requireId("action", ScriptCoreUtils.readInteger(arguments, 6), ACTIONS.length)]
                            .getFunction().getNativeAddress(),
                    readValue(arguments, 7, WORD_MAXIMUM),
                    readValue(arguments, 8, WORD_MAXIMUM), readValue(arguments, 9, WORD_MAXIMUM)
            );
            HostButtonSets.Button[] changed = new HostButtonSets.Button[array.length + 1];

            System.arraycopy(array, 0, changed, 0, index);
            changed[index] = button;
            System.arraycopy(array, index, changed, index + 1, array.length - index);
            this.replaceArray(array, changed);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "remove", 2, (context, scope, thisObject, arguments) -> {
            int set = readSet(arguments, 0);
            HostButtonSets.Button[] array = this.getArray(set);
            int index = ScriptCoreUtils.requireId("button index", ScriptCoreUtils.readInteger(arguments, 1), array.length);
            HostButtonSets.Button[] changed = new HostButtonSets.Button[array.length - 1];

            System.arraycopy(array, 0, changed, 0, index);
            System.arraycopy(array, index + 1, changed, index, array.length - index - 1);
            this.replaceArray(array, changed);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the read {@code get<name>(set, index)} and the write {@code set<name>(set, index, value)} of a button field.
     */
    private void defineField(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name,
                             @NotNull ButtonRead read, @NotNull ButtonWrite write) {
        ScriptCoreUtils.defineFunction(global, namespace, "get" + name, 2,
                                       (context, scope, thisObject, arguments) -> (double) read.read(this.readButton(arguments)));
        ScriptCoreUtils.defineFunction(global, namespace, "set" + name, 3, (context, scope, thisObject, arguments) -> {
            int value = ScriptCoreUtils.readInteger(arguments, 2);

            this.changeButton(arguments, button -> write.write(button, value));

            return Context.getUndefinedValue();
        });
    }

    /**
     * Reads the button of the arguments {@code set} and {@code index}.
     */
    private HostButtonSets.@NotNull Button readButton(@NotNull Object @NotNull [] arguments) {
        HostButtonSets.Button[] array = this.getArray(readSet(arguments, 0));

        return array[ScriptCoreUtils.requireId("button index", ScriptCoreUtils.readInteger(arguments, 1), array.length)];
    }

    /**
     * Replaces the button of the arguments {@code set} and {@code index} in a new array, which every set that shares the array takes.
     */
    private void changeButton(@NotNull Object @NotNull [] arguments, @NotNull UnaryOperator<HostButtonSets.Button> change) {
        HostButtonSets.Button[] array = this.getArray(readSet(arguments, 0));
        int index = ScriptCoreUtils.requireId("button index", ScriptCoreUtils.readInteger(arguments, 1), array.length);
        HostButtonSets.Button changedButton = change.apply(array[index]);
        HostButtonSets.Button[] changed = array.clone();

        changed[index] = changedButton;
        this.replaceArray(array, changed);
    }

    /**
     * Makes every set that holds an array hold another; arrays never change in place, so readers that keep converted arrays stay valid.
     */
    private void replaceArray(HostButtonSets.@NotNull Button @NotNull [] array, HostButtonSets.@NotNull Button @NotNull [] changed) {
        HostButtonSets host = this.requireChangedTable();

        for (int set = 0; set < HostButtonSets.SET_COUNT; set++) {
            if (this.getArray(set) == array) {
                host.replace(set, changed.length, changed, host.getConnectedSet(set));
            }
        }
    }

    /**
     * Returns the game's table, holding the installed table first when nothing replaced it yet.
     */
    private @NotNull HostButtonSets requireChangedTable() {
        HostButtonSets host = this.getHost();

        if (!host.isReplaced()) {
            HostButtonSets.Button[][] arrays = this.getInstalled();

            for (int set = 0; set < HostButtonSets.SET_COUNT; set++) {
                host.replace(set, arrays[set].length, arrays[set], ButtonSetTable.getConnectedSet(set));
            }
        }

        return host;
    }

    /**
     * @return the array of a set in the game's table, or in the installed table while nothing replaced it
     */
    private HostButtonSets.@NotNull Button @NotNull [] getArray(int set) {
        HostButtonSets host = this.getHost();

        if (!host.isReplaced()) {
            return this.getInstalled()[set];
        }

        HostButtonSets.Button[] array = host.getButtons(set);

        return array == null ? NO_BUTTONS : array;
    }

    private int getConnectedSet(int set) {
        HostButtonSets host = this.getHost();

        return host.isReplaced() ? host.getConnectedSet(set) : ButtonSetTable.getConnectedSet(set);
    }

    /**
     * @return the installed table's arrays, converted once; they never change
     */
    private HostButtonSets.@NotNull Button @NotNull [] @NotNull [] getInstalled() {
        HostButtonSets.Button[][] arrays = this.installed;

        if (arrays == null) {
            arrays         = ButtonSetTable.createHostButtons();
            this.installed = arrays;
        }

        return arrays;
    }

    private @NotNull HostButtonSets getHost() {
        return this.scriptGame.getGame().getGameContext().getHostButtonSets();
    }
}
