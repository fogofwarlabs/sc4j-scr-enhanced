package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationDecision;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationEvent;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SpriteCreatedEvent;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.UnitDiedEvent;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceLink;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.screnhanced.graphics.*;
import org.fogofwarlabs.sc4j.screnhanced.script.inspector.ScriptInspector;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the script package of one game's scenario in the game's simulation.
 * <p>
 * The plugin creates the {@link ScriptRuntime} with the game API, the map's resources and a seed from the game's setup seed, the same on every
 * peer and in every replay, and starts it at the first frame start. The runtime's log output also goes to the client's DevTools console
 * ({@link ScriptInspector}).
 * <p>
 * The events of the game go into the runtime's queue with their frame, the events from before the first frame (the units the map and the
 * game start create) as well, and the runtime delivers them at the next frame start, before the triggers. A frame start whose frame number
 * did not advance runs no script while the Pause command of the game menu holds the game, when the triggers do not run either, and runs it
 * with the same frame number while Pause Game holds the game, when the triggers run. Decisions go to the runtime at once. The mod commands of
 * the players go to the runtime, which joins the script commands into values and delivers them at the next frame start. After each frame start
 * the script commands the script submitted go to the local player's outgoing turn ({@link ScriptRuntime#submitCommands}); the game's
 * {@link ScriptInputPlugin} sends those of the local code at once.
 * <p>
 * The classic image graphics of the game draw the image graphics the script replaces ({@link #getImageReplacements()}), and the classic terrain
 * graphics the terrain pictures it replaces ({@link #getTerrainReplacements()}); every frame start first forgets the image replacements of units
 * whose slot was freed. The classic console of the game merges the console settings of the script ({@link #getConsoleSettings()}). The
 * adjustments of the simulation answer with the stats the script gives single units ({@link ScriptRuntime#getUnitStats()}). A game the plugin
 * runs cannot be saved: a saved game would lose the script's state.
 */
public final class ScriptSimulationPlugin implements ScrSimulationPlugin, ImageReplacementSource, TerrainReplacementSource, ConsoleSettingsSource {
    private final @NotNull Game game;
    private final @NotNull ScriptRuntime runtime;

    private boolean started;
    private int lastFrame;

    /**
     * Creates a {@link ScriptSimulationPlugin} instance whose script has not started.
     *
     * @param game          the game
     * @param scriptPackage the script package of the game's scenario
     * @param resources     the resources of the game's map
     */
    public ScriptSimulationPlugin(@NotNull Game game, @NotNull ScriptPackage scriptPackage, @NotNull ScriptResources resources) {
        this.game    = game;
        this.runtime = ScriptRuntime.create(scriptPackage, game, resources, Integer.toUnsignedLong(game.getSetup().getSeed()),
                                            ScriptRuntime.INSTRUCTION_BUDGET, ScriptInspector.getShared().createLogOutput(game));

        this.started   = false;
        this.lastFrame = 0;
    }

    /**
     * @return the script runtime of the game's script package
     */
    public @NotNull ScriptRuntime getRuntime() {
        return this.runtime;
    }

    /**
     * @return the image graphics the script replaces, the same instance for the whole game
     */
    @Override
    public @NotNull ImageReplacements getImageReplacements() {
        return this.runtime.getImageReplacements();
    }

    /**
     * @return the terrain pictures the plugin replaces, the same instance for the whole game
     */
    @Override
    public @NotNull TerrainReplacements getTerrainReplacements() {
        return this.runtime.getTerrainReplacements();
    }

    /**
     * @return the console settings of the plugin, the same instance for the whole game
     */
    @Override
    public @NotNull ConsoleSettings getConsoleSettings() {
        return this.runtime.getConsoleSettings();
    }


    /**
     * Called once per frame when the frame's trigger step runs, before the triggers, with the random generator enabled as for the triggers; the
     * trigger step also runs while the game is paused.
     * <p>
     * Every call forgets the image replacements of units whose slot was freed; the first call starts the script; the first call, every call
     * whose frame number advanced and every call while Pause Game holds the game, when the triggers run without a frame, run the script's frame;
     * every call then runs the console code of the client's DevTools console ({@link ScriptInspector#onFrameStarted}), hands the waiting script
     * commands to the game's mod command submitter, which drops them in a replay or for an observer, plays the sounds the script's local code
     * played since the previous call, and last applies the map tile changes the script queued, before the triggers.
     */
    @Override
    public void onFrameStarted() {
        int frame = this.game.getCurrentFrame();
        boolean first = !this.started;
        TriggerContext triggers = this.game.getTriggerContext();
        boolean triggerPaused = triggers != null && triggers.getPhaseGate() != 0 && triggers.getTriggersEnabledWhileGated() != 0;

        this.runtime.getImageReplacements().removeFreedUnits();

        if (first) {
            this.started = true;

            this.runtime.start();
        }

        if (first || frame != this.lastFrame || triggerPaused) {
            this.lastFrame = frame;

            this.runtime.runFrame(frame);
        }

        ScriptInspector.getShared().onFrameStarted(this.game, this.runtime);
        this.runtime.submitCommands(this.game.getInterfaceLink().getModCommandSubmitter());
        this.runtime.playLocalSounds();
        this.runtime.applyTerrainChanges();
    }

    /**
     * Called when something happens in the simulation, from inside the routine where it happens.
     * <p>
     * A unit's death and a sprite an iscript created go to the image replacements, which give the remains their graphics
     * ({@link ImageReplacements#onUnitDied}, {@link ImageReplacements#onSpriteCreated}). The event goes into the runtime's queue with the current
     * frame.
     *
     * @param event the event; its kind is its class
     */
    @Override
    public void onEvent(@NotNull SimulationEvent event) {
        if (event instanceof UnitDiedEvent died) {
            this.runtime.getImageReplacements().onUnitDied(died.getUnit().getUnit());
        } else if (event instanceof SpriteCreatedEvent created) {
            this.runtime.getImageReplacements().onSpriteCreated(created.getSprite(), created.getSourceSprite());
        }

        this.runtime.queueEvent(event, this.game.getCurrentFrame());
    }

    /**
     * Called at a decision point of a simulation routine, which continues with the values the plugins leave in the decision; the plugins decide
     * in extension order, each on the values the previous one left.
     * <p>
     * The script's decision hooks decide with the game read-only.
     *
     * @param decision the decision; its kind is its class
     */
    @Override
    public void decide(@NotNull SimulationDecision decision) {
        this.runtime.decide(decision);
    }

    /**
     * Adjusts the maximum range of a unit's weapon after the native bonuses (the Bunker, the range upgrades and the heroes), wherever the
     * simulation reads the range: the attack range tests, the target searches and the spell ranges of weapons.
     * <p>
     * The unit's weapon range bonus is added.
     *
     * @param unit   the attacking unit or turret
     * @param weapon the weapons.dat entry
     * @param range  the range in pixels
     * @return the range in pixels, compared unsigned
     */
    @Override
    public int adjustWeaponRange(@NotNull Unit unit, int weapon, int range) {
        return this.runtime.getUnitStats().adjustWeaponRange(unit, range);
    }

    /**
     * Adjusts a movement speed of a unit after its speed modifiers (the speed upgrades, Stim Packs and Ensnare): the {@code flingy.dat} top speed
     * when the unit's movement fields are recalculated, and the speed each iscript {@code move} instruction gives a unit that its iscript moves,
     * which also makes the top speed sampled from its Walking animation.
     * <p>
     * The unit's speed percentage applies.
     *
     * @param unit  the unit
     * @param speed the speed in {@code 1/256} pixels per frame
     * @return the speed in {@code 1/256} pixels per frame
     */
    @Override
    public int adjustMovementSpeed(@NotNull Unit unit, int speed) {
        return this.runtime.getUnitStats().adjustSpeed(unit, speed);
    }

    /**
     * Adjusts the acceleration of a unit after its speed modifiers, when the unit's movement fields are recalculated.
     * <p>
     * The unit's speed percentage applies.
     *
     * @param unit         the unit
     * @param acceleration the {@code flingy.dat} acceleration after the speed modifiers
     * @return the acceleration, a WORD
     */
    @Override
    public int adjustAcceleration(@NotNull Unit unit, int acceleration) {
        return this.runtime.getUnitStats().adjustSpeed(unit, acceleration);
    }

    /**
     * Adjusts the maximum hit points of a unit, wherever the game reads the maximum of a unit: the hit point limit, completion, repair,
     * construction, the percentages of the triggers and the display.
     * <p>
     * The unit's own maximum hit points replace those of its type.
     *
     * @param unit             the unit
     * @param maximumHitPoints the {@code units.dat} hit points of the unit's type in {@code 1/256} points
     * @return the maximum hit points in {@code 1/256} points
     */
    @Override
    public int adjustMaximumHitPoints(@NotNull Unit unit, int maximumHitPoints) {
        return this.runtime.getUnitStats().adjustMaximumHitPoints(unit, maximumHitPoints);
    }

    /**
     * Called when a mod command of a player runs, at its place in the command stream of the frame. The player is the record the command's issuer
     * resolved to, the issuing player's own record also in a team game, where the other commands of every member act for the team's leader. A
     * live game queues the mod commands of the local player through the game's mod command submitter
     * ({@link InterfaceLink#getModCommandSubmitter()}).
     * <p>
     * The data goes to the runtime with the slot of the player ({@link ScriptRuntime#queueModCommand}); the data of a player without a slot is
     * ignored.
     *
     * @param player the issuing player's record
     * @param data   the command data, which every plugin of the game receives; the plugin does not change it
     */
    @Override
    public void onModCommand(@NotNull Player player, byte @NotNull [] data) {
        int slot = this.findSlot(player);

        if (slot >= 0) {
            this.runtime.queueModCommand(slot, data);
        }
    }

    /**
     * Tells whether the game can be saved while this plugin runs it: never, since a saved game would lose the script's state.
     *
     * @return {@code false}
     */
    @Override
    public boolean allowsSave() {
        return false;
    }

    /**
     * Finds the slot of a player record in the game's player table.
     *
     * @return the slot id, or {@code -1} if no slot holds the record
     */
    private int findSlot(@NotNull Player player) {
        PlayerTable table = this.game.getPlayerTable();

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            if (table.getPlayer(slot) == player) {
                return slot;
            }
        }

        return -1;
    }
}
