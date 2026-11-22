package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonAction;
import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonCondition;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrButtonInput;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tests a script button of {@code display.commandCard.createButton} in live melee games: the local state from {@code isEnabled} over the
 * local selection, and the press that reaches {@code onPressed} with the units the sender had selected when the command ran, filtered by
 * {@code isEnabled}, the same in the pressing game and in a game that only replays the command.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptButtonGameTest {
    private static final int HUMAN_SLOT = 0;
    private static final int BUTTON_POSITION = 9;
    private static final int BUTTON_ICON = 236;
    private static final @NotNull String SCRIPT = """
                                                  var go = display.commandCard.createButton({
                                                      unitType: UnitType.TerranSCV, position: 9, icon: 236, text: 'Go', hotkey: 'G',
                                                      isEnabled: function (unit) {
                                                          return unit.getId() % 2 === 0;
                                                      },
                                                      onPressed: function (player, units, target) {
                                                          log.info('pressed ' + player.getId() + ' ' + units.map(function (unit) { return unit.getId(); }).join(',') + ' ' + target);
                                                      }
                                                  });
                                                  var start;
                                                  var scvs;
                                                  function onStart() {
                                                      start = game.getFrame();
                                                      scvs = game.units.find({type: UnitType.TerranSCV}).map(function (unit) { return unit.getId(); });
                                                      scvs.sort(function (a, b) { return a - b; });
                                                      core.player.setSelection(0, scvs.slice(0, 2));
                                                      log.info('scvs ' + scvs.join(',') + ' ' + go.getButton().getIndex());
                                                  }
                                                  function onFrame(frame) {
                                                      if (frame === start + 1) {
                                                          core.player.setSelection(0, scvs);
                                                      }
                                                  }
                                                  function onLocalFrame() {
                                                      log.info('state ' + core.local.getButtonState(go.getId()));
                                                  }
                                                  """;

    private static int @NotNull [] readIds(@NotNull String text) {
        return Arrays.stream(text.split(",")).mapToInt(Integer::parseInt).toArray();
    }

    @Test
    void a_press_runs_on_pressed_with_the_selection_of_its_execution_filtered_by_is_enabled(@NotNull InstallationTestResources resources) throws IOException {
        LiveScriptGameScenario live = LiveScriptGameScenario.startMelee(resources.getGameData(), SCRIPT);
        LiveScriptGameScenario replay = LiveScriptGameScenario.startMelee(resources.getGameData(), SCRIPT);
        Assertions.assertFalse(live.getInfoMessages().isEmpty(), live.getLines()::toString);

        String[] start = live.getInfoMessages().getFirst().split(" ");
        int[] scvs = readIds(start[1]);
        int index = Integer.parseInt(start[2]);
        boolean enabledAtPress = scvs[0] % 2 == 0 || scvs[1] % 2 == 0;

        live.runLocalFrame();

        List<byte[]> commands = new ArrayList<>();
        ScrButtonInput press = ScrButtonInput.ofPress(ScrUnitIds.TERRAN_SCV, index, BUTTON_POSITION, BUTTON_ICON,
                                                      ButtonCondition.SCRIPT.getNativeAddress(), 0,
                                                      ButtonAction.SCRIPT.getNativeAddress(), 0, false, false);

        Assertions.assertFalse(live.getRuntime().callLocalInput(live.getRuntime().createButtonInput(press)));
        live.getRuntime().submitCommands(data -> commands.add(data.clone()));
        Assertions.assertFalse(commands.isEmpty(), "the press sends a command");

        // The selection changes to every SCV before the command runs.
        live.stepFrame();
        replay.stepFrame();

        for (byte[] data : commands) {
            live.runModCommand(HUMAN_SLOT, data);
            replay.runModCommand(HUMAN_SLOT, data);
        }

        live.stepFrame();
        replay.stepFrame();

        String passing = Arrays.stream(scvs).filter(id -> id % 2 == 0).mapToObj(Integer::toString).collect(Collectors.joining(","));
        String pressed = "pressed " + HUMAN_SLOT + " " + passing + " null";

        Assertions.assertEquals(4, scvs.length, live.getLines()::toString);
        Assertions.assertTrue(passing.length() > 0 && passing.split(",").length < scvs.length, "isEnabled passes some of the SCVs: " + passing);
        Assertions.assertNotEquals(passing, Arrays.stream(scvs).limit(2).filter(id -> id % 2 == 0).mapToObj(Integer::toString)
                                                  .collect(Collectors.joining(",")), "the selection of the press differs");
        Assertions.assertEquals(List.of(live.getInfoMessages().getFirst(), "state " + (enabledAtPress ? 1 : -1), pressed), live.getInfoMessages(),
                                live.getLines()::toString);
        Assertions.assertEquals(List.of(live.getInfoMessages().getFirst(), pressed), replay.getInfoMessages(), replay.getLines()::toString);
    }
}
