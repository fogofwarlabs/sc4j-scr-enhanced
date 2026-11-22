package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.PlayerLeftEvent;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.PlayerResultEvent;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

/**
 * Tests the frame's events of {@code core.events}: the records of the events delivered at a frame start, which reading does not take, the
 * event objects of the hooks over the same records, and the kinds and their names.
 */
final class ScriptEventCoreTest {
    private static final int MAP_TILE_SIZE = 4;
    private static final int UNIT_CAPACITY = 16;
    private static final int VICTORY = 3;
    private static final int LEFT_REASON = 6;

    /**
     * Creates a game without game data and with an empty unit pool.
     */
    private static @NotNull Game createGame() {
        Game game = Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, 0, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]), new LcgRandom(0));

        game.getUnitSystem().initializeUnitPool(UNIT_CAPACITY);

        return game;
    }

    @Test
    void get_frame_events_should_return_the_records_of_the_frame_without_taking_them() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = new ScriptGameScenario(game, """
                                                                   var hooked = [];
                                                                   function onPlayerResult(event) {
                                                                       hooked.push(event.getRecord() === core.events.getFrameEvents()[0] && event.isVictory());
                                                                   }
                                                                   function onFrame(frame) {
                                                                       var events = core.events.getFrameEvents();
                                                                       log.info(frame + ' ' + JSON.stringify(events));
                                                                       log.info(events === core.events.getFrameEvents(), events[0] === core.events.getFrameEvents()[0],
                                                                                events.length > 0 && Object.isFrozen(events[0]), hooked.join());
                                                                   }
                                                                   """);

        scenario.getRuntime().start();
        scenario.getRuntime().queueEvent(new PlayerResultEvent(2, VICTORY), 3);
        scenario.getRuntime().queueEvent(new PlayerLeftEvent(1, LEFT_REASON), 4);
        scenario.getRuntime().runFrame(5);
        scenario.getRuntime().runFrame(6);

        Assertions.assertEquals(List.of("5 [{\"kind\":16,\"frame\":3,\"player\":2,\"result\":3},{\"kind\":15,\"frame\":4,\"player\":1,\"reason\":6}]",
                                        "false true true true",
                                        "6 []",
                                        "false true false true"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void kinds_are_named_by_id_and_match_the_event_kind_table() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), """
                                                                           var names = [];
                                                                           for (var kind = 0; kind < core.events.getKindCount(); kind++) {
                                                                               names.push(core.events.getKindName(kind) + (EventKind.get(kind).getName() === core.events.getKindName(kind) ? '' : '!'));
                                                                           }
                                                                           log.info(names.join(' '));
                                                                           try {
                                                                               core.events.getKindName(core.events.getKindCount());
                                                                           } catch (error) {
                                                                               log.info(error.name);
                                                                           }
                                                                           """);

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("UnitCreated UnitCompleted UnitDied UnitDamaged OrderIssued TargetAcquired UnitTrained ResearchCompleted " +
                                        "UpgradeCompleted SpellCast OwnerChanged UnitLoaded UnitUnloaded BulletCreated BulletHit PlayerLeft " +
                                        "PlayerResult", "RangeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }
}
