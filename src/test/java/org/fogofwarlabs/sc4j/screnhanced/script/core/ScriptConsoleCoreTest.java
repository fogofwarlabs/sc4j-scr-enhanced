package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsoleArt;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsolePlacement;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalDisplay;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ClassicConsoleGraphics;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ConsoleSettings;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageFileScenario;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptMapScenario;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Tests {@code core.console} and its upper layer ({@code console}, {@code ConsoleControl}, {@code ConsoleImage}, {@code ConsoleAnchor},
 * {@code ConsoleChild}) on a game without game data: the settings they set in shared and in local code, those of the dialogs' controls, anchors
 * and the minimap size included, the resource icons of each resource type, the refusals, the classic console that shows them, the drawn
 * rectangles local code reads and the control names.
 */
final class ScriptConsoleCoreTest {
    private static final int MAP_TILE_SIZE = 4;
    private static final int GAS_ICONS = 3;
    /**
     * The two control identifiers the control test script sets.
     */
    private static final int CONTROL = 12;
    private static final int OTHER_CONTROL = 13;
    private static final int LARGE_MINIMAP = 192;
    /**
     * The minimap dialog's rectangle the fake client gives: x, y, width, height.
     */
    private static final float @NotNull [] MINIMAP_RECTANGLE = {6, 348.5f, 180, 132};
    /**
     * Defines {@code error(fn)}, which returns the name of the error {@code fn} throws, or {@code "none"}.
     */
    private static final @NotNull String ERROR_FUNCTION = """
                                                          function error(fn) {
                                                              try {
                                                                  fn();
                                                                  return 'none';
                                                              } catch (e) {
                                                                  return e.name;
                                                              }
                                                          }
                                                          """;

    private static @NotNull Game createGame() {
        return Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, 0, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]), new LcgRandom(0));
    }

    private static @NotNull ScriptResources createResources() throws IOException {
        return ScriptMapScenario.createResources(Map.of("ui/console.png", ImageFileScenario.createPng(), "ui/gas.dds", ImageFileScenario.createDds()));
    }

    @Test
    void console_settings_follow_shared_and_local_code() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), ERROR_FUNCTION + """
                                                                                            var art = game.map.resources.getImage('ui/console.png');
                                                                                            var gas = game.map.resources.getImage('ui/gas.dds');
                                                                                            var minimap = display.console.getControl(ConsoleControl.Minimap);
                                                                                            minimap.setPosition(10.4, 20.6);
                                                                                            display.console.getControl(ConsoleControl.CommandCard).setVisible(false);
                                                                                            display.console.replaceImage(ConsoleImage.Console, art);
                                                                                            display.console.replaceImage(ConsoleImage.ConsoleRight, art);
                                                                                            display.console.replaceImage(ConsoleImage.ConsoleTime, gas);
                                                                                            display.console.setResourceIcon(ResourceType.Gas, gas);
                                                                                            log.info(minimap === display.console.getControl(ConsoleControl.Minimap), minimap.getControl() === ConsoleControl.Minimap,
                                                                                                     display.console.getControl(ConsoleControl.CommandCard).isVisible(), minimap.isVisible(), display.console.isVisible());
                                                                                            log.info(error(function () { display.console.setResourceIcon(ResourceType.MineralsAndGas, gas); }),
                                                                                                     error(function () { display.console.getControl(3); }), error(function () { core.console.setDialogVisible(7, true); }),
                                                                                                     error(function () { core.console.replaceImage(7, -1); }), error(function () { core.console.setResourceIcon(7, -1); }));
                                                                                            log.info(ConsoleImage.getCount(), ConsoleImage.Console.getId(), ConsoleImage.ConsoleLeft.getId(), ConsoleImage.ConsoleCenter.getId(),
                                                                                                     ConsoleImage.ConsoleRight.getId(), ConsoleImage.ConsoleTime.getId(), ConsoleImage.ConsoleApm.getId(),
                                                                                                     ConsoleImage.Conover.getId());
                                                                                            function onLocalFrame(frame) {
                                                                                                display.console.setVisible(false);
                                                                                                display.console.setResourceIcon(ResourceType.Minerals, gas);
                                                                                                display.console.replaceImage(ConsoleImage.Console, null);
                                                                                            }
                                                                                            """, createResources());

        scenario.getRuntime().start();

        ConsoleSettings settings = scenario.getRuntime().getConsoleSettings();

        Assertions.assertEquals(List.of("true true false true true", "TypeError TypeError RangeError RangeError RangeError", "7 0 1 2 3 4 5 6"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertTrue(settings.isDialogPositionSet(ScrConsolePlacement.Dialog.MINIMAP));
        Assertions.assertEquals(10, settings.getDialogLeft(ScrConsolePlacement.Dialog.MINIMAP));
        Assertions.assertEquals(21, settings.getDialogTop(ScrConsolePlacement.Dialog.MINIMAP));
        Assertions.assertFalse(settings.isDialogVisible(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertNotNull(settings.getArt(ScrConsoleArt.CONSOLE));
        Assertions.assertSame(settings.getArt(ScrConsoleArt.CONSOLE), settings.getArt(ScrConsoleArt.CONSOLE_RIGHT));
        Assertions.assertNotNull(settings.getArt(ScrConsoleArt.CONSOLE_TIME));
        Assertions.assertNull(settings.getArt(ScrConsoleArt.CONSOLE_LEFT));
        Assertions.assertNull(settings.getResourceIcon(0));

        for (int icon = 1; icon <= GAS_ICONS; icon++) {
            Assertions.assertNotNull(settings.getResourceIcon(icon), "every race's gas icon");
        }

        Assertions.assertNull(settings.getResourceIcon(GAS_ICONS + 1));

        ClassicConsoleGraphics graphics = new ClassicConsoleGraphics(() -> settings);
        ScrConsolePlacement placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertTrue(placement.isDialogHidden(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertEquals(10, placement.getDialogLeft(ScrConsolePlacement.Dialog.MINIMAP));
        Assertions.assertSame(settings.getArt(ScrConsoleArt.CONSOLE), graphics.findConsoleArt(ScrConsoleArt.CONSOLE));
        Assertions.assertFalse(graphics.isConsoleHidden());

        scenario.getRuntime().callLocalFrame(1);

        Assertions.assertTrue(graphics.isConsoleHidden(), "local code sets the local console");
        Assertions.assertNotNull(graphics.findResourceIconPicture(0));
        Assertions.assertNull(graphics.findConsoleArt(ScrConsoleArt.CONSOLE));
        Assertions.assertNotNull(graphics.findConsoleArt(ScrConsoleArt.CONSOLE_RIGHT), "the other files stay");
    }

    @Test
    void console_control_settings_follow_shared_and_local_code_until_restored() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), ERROR_FUNCTION + """
                                                                                            var card = display.console.getControl(ConsoleControl.CommandCard);
                                                                                            card.setChildPosition(12, 30.4, -40.6);
                                                                                            card.setChildVisible(13, false);
                                                                                            display.console.getControl(ConsoleControl.Minimap).setChildVisible(12, false);
                                                                                            log.info(error(function () { core.console.setControlVisible(0, 32768, false); }),
                                                                                                     error(function () { core.console.restoreControl(7, 1); }), error(function () { card.restoreChild(-32769); }));
                                                                                            function onLocalFrame(frame) {
                                                                                                card.restoreChild(13);
                                                                                                display.console.getControl(ConsoleControl.Minimap).restoreChild(12);
                                                                                            }
                                                                                            """);

        scenario.getRuntime().start();

        ConsoleSettings settings = scenario.getRuntime().getConsoleSettings();
        ClassicConsoleGraphics graphics = new ClassicConsoleGraphics(() -> settings);
        ScrConsolePlacement placement = graphics.findConsolePlacement();

        Assertions.assertEquals(List.of("RangeError RangeError RangeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertNotNull(placement);
        Assertions.assertEquals(30, placement.getControlLeft(ScrConsolePlacement.Dialog.COMMAND_CARD, CONTROL));
        Assertions.assertEquals(-41, placement.getControlTop(ScrConsolePlacement.Dialog.COMMAND_CARD, CONTROL));
        Assertions.assertTrue(placement.isControlHidden(ScrConsolePlacement.Dialog.COMMAND_CARD, OTHER_CONTROL));
        Assertions.assertTrue(placement.isControlHidden(ScrConsolePlacement.Dialog.MINIMAP, CONTROL));

        scenario.getRuntime().callLocalFrame(1);
        placement = graphics.findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertFalse(placement.isControlHidden(ScrConsolePlacement.Dialog.COMMAND_CARD, OTHER_CONTROL), "local code restores the control");
        Assertions.assertEquals(0, placement.getPlacedControls(ScrConsolePlacement.Dialog.MINIMAP).length);
        Assertions.assertArrayEquals(new int[]{CONTROL}, placement.getPlacedControls(ScrConsolePlacement.Dialog.COMMAND_CARD));
    }

    @Test
    void anchors_and_the_minimap_size_reach_the_placement_and_local_code_reads_the_drawn_rectangles() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = new ScriptGameScenario(game, ERROR_FUNCTION + """
                                                                                    var minimap = display.console.getControl(ConsoleControl.Minimap);
                                                                                    minimap.setAnchor(ConsoleAnchor.Right);
                                                                                    display.console.setMinimapSize(192);
                                                                                    log.info(display.console.getMinimapSize(), ConsoleAnchor.Right.getId(), error(function () { display.console.setMinimapSize(0); }),
                                                                                             error(function () { display.console.setMinimapSize(481); }), error(function () { core.console.setDialogAnchor(4, 3); }),
                                                                                             error(function () { minimap.setAnchor(2); }), error(function () { minimap.getRectangle(); }),
                                                                                             error(function () { display.console.getMinimapRectangle(); }));
                                                                                    log.info(ConsoleChild.MinimapArea, ConsoleChild.MinimapDiplomacy, ConsoleChild.CommandCardButton9, ConsoleChild.SelectionQueue1,
                                                                                             ConsoleChild.SelectionGroup12, ConsoleChild.ResourcesCounter6, ConsoleChild.TextBoxEdit, Object.isFrozen(ConsoleChild));
                                                                                    function onLocalFrame(frame) {
                                                                                        var rectangle = minimap.getRectangle();
                                                                                        var area = minimap.getChildRectangle(1);
                                                                                        var minimapArea = display.console.getMinimapRectangle();
                                                                                        log.info(rectangle === null ? 'none' : rectangle.x + ' ' + rectangle.y + ' ' + rectangle.width + ' ' + rectangle.height + ' ' +
                                                                                                                               Object.isFrozen(rectangle),
                                                                                                 area === null ? 'none' : area.x + ' ' + area.width, minimap.getChildRectangle(2),
                                                                                                 minimapArea === null ? 'none' : minimapArea.y + ' ' + minimapArea.width);
                                                                                    }
                                                                                    """);

        scenario.getRuntime().start();

        ConsoleSettings settings = scenario.getRuntime().getConsoleSettings();
        ScrConsolePlacement placement = new ClassicConsoleGraphics(() -> settings).findConsolePlacement();

        Assertions.assertNotNull(placement);
        Assertions.assertEquals(ScrConsolePlacement.Anchor.RIGHT, placement.getDialogAnchor(ScrConsolePlacement.Dialog.MINIMAP));
        Assertions.assertEquals(ScrConsolePlacement.Anchor.CENTER, placement.getDialogAnchor(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(LARGE_MINIMAP, placement.getMinimapSize());

        scenario.getRuntime().callLocalFrame(1);
        game.getInterfaceLink().getLocalDisplay().setConsoleRectangles(new LocalDisplay.ConsoleRectangles() {
            @Override
            public boolean findDialogRectangle(ScrConsolePlacement.@NotNull Dialog dialog, float @NotNull [] rectangle) {
                System.arraycopy(MINIMAP_RECTANGLE, 0, rectangle, 0, rectangle.length);

                return dialog == ScrConsolePlacement.Dialog.MINIMAP;
            }

            @Override
            public boolean findControlRectangle(ScrConsolePlacement.@NotNull Dialog dialog, int control, float @NotNull [] rectangle) {
                System.arraycopy(MINIMAP_RECTANGLE, 0, rectangle, 0, rectangle.length);
                rectangle[2] = LARGE_MINIMAP;

                return control == 1;
            }
        });
        scenario.getRuntime().callLocalFrame(2);

        Assertions.assertEquals(List.of("192 2 RangeError RangeError RangeError TypeError TypeError TypeError", "1 4 9 2 44 6 7 true",
                                        "none none null none", "6 348.5 180 132 true 6 192 null 348.5 192"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }
}
