package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerLocation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.List;

/**
 * Tests {@code core.location} and the {@code Location} objects of the prelude in a live Use Map Settings game: finding a location by its map string,
 * reading its bounds, setting them in the trigger state and moving it by its center.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptLocationCoreTest {
    private static final int CHANGED_LOCATION = 3;

    @Test
    void locations_are_found_by_name_and_take_new_bounds(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startUseMapSettings(resources.getGameData(), """
                                                                                                              function onStart() {
                                                                                                                  var anywhere = game.map.locations.findByName('Anywhere');
                                                                                                                  log.info(anywhere.getId(), anywhere === game.map.locations.get(anywhere.getId()), anywhere.getName(), anywhere.getLeft(),
                                                                                                                           anywhere.getTop(), anywhere.getRight() === game.map.getWidth(), anywhere.getBottom() === game.map.getHeight(),
                                                                                                                           String(anywhere.getCenter()) === String(Position.of(game.map.getWidth() / 2, game.map.getHeight() / 2)));
                                                                                                                  log.info(game.map.locations.findByName('No such location'), core.location.getCount(), game.map.locations.get(255));
                                                                                                                  var changed = game.map.locations.get(3);
                                                                                                                  changed.setBounds(32, 64, 97, 129);
                                                                                                                  log.info(changed.getLeft(), changed.getTop(), changed.getRight(), changed.getBottom(), String(changed.getCenter()));
                                                                                                                  changed.setCenter(Position.of(100, 100));
                                                                                                                  log.info(changed.getLeft(), changed.getTop(), changed.getRight(), changed.getBottom());
                                                                                                              }
                                                                                                              """);

        Assertions.assertEquals(List.of("63 true Anywhere 0 0 true true true",
                                        "null 255 null",
                                        "32 64 97 129 (64, 96)",
                                        "68 68 133 133"), scenario.getInfoMessages(), scenario.getLines()::toString);

        TriggerContext triggers = scenario.getGame().getTriggerContext();

        Assertions.assertNotNull(triggers);

        TriggerLocation location = triggers.getLocation(CHANGED_LOCATION);

        Assertions.assertEquals(List.of(68, 68, 133, 133), List.of(location.getLeft(), location.getTop(), location.getRight(), location.getBottom()));
    }

    @Test
    void locations_take_elevation_flags(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario scenario = LiveScriptGameScenario.startUseMapSettings(resources.getGameData(), """
                                                                                                              var changed;
                                                                                                              function onStart() {
                                                                                                                  changed = game.map.locations.get(3);
                                                                                                                  log.info(core.location.getFlags(3), changed.hasFlag(LocationFlag.LowAir), LocationFlag.HighAir.getId());
                                                                                                                  changed.setFlag(LocationFlag.LowAir, true);
                                                                                                                  changed.setFlag(LocationFlag.HighGround, true);
                                                                                                                  log.info(core.location.getFlags(3), changed.hasFlag(LocationFlag.LowAir), changed.hasFlag(LocationFlag.MiddleGround));
                                                                                                                  changed.setFlag(LocationFlag.LowAir, false);
                                                                                                                  log.info(core.location.getFlags(3));
                                                                                                                  try {
                                                                                                                      core.location.setFlags(3, 0x10000);
                                                                                                                  } catch (error) {
                                                                                                                      log.info(error.name);
                                                                                                                  }
                                                                                                              }
                                                                                                              function onLocalFrame() {
                                                                                                                  try {
                                                                                                                      changed.setFlag(LocationFlag.LowGround, true);
                                                                                                                  } catch (error) {
                                                                                                                      log.info('local ' + error.name + ' ' + core.location.getFlags(3));
                                                                                                                  }
                                                                                                              }
                                                                                                              """);

        scenario.runLocalFrame();

        Assertions.assertEquals(List.of("0 false 32", "12 true false", "4", "RangeError", "local TypeError 4"), scenario.getInfoMessages(),
                                scenario.getLines()::toString);

        TriggerContext triggers = scenario.getGame().getTriggerContext();

        Assertions.assertNotNull(triggers);
        Assertions.assertEquals(4, triggers.getLocation(CHANGED_LOCATION).getElevationFlags());
    }
}
