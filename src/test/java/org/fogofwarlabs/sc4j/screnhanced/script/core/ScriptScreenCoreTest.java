package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrScreenOverlay;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ClassicScreenGraphics;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageFileScenario;
import org.fogofwarlabs.sc4j.screnhanced.script.*;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests the drawing of map scripts ({@code onRender}, {@code screen}, {@code core.screen}, {@code core.world}, {@code core.ground},
 * {@code core.minimap}) on a game without game data: the draw list of each space in call order, the refusal outside {@code onRender}, the
 * dropped list of a call over its budget, the local screen and its scale, the colour, font and alignment values, the images, the classic screen
 * pixels and the call without a submitter, as in a replay.
 */
final class ScriptScreenCoreTest {
    private static final int MAP_TILE_SIZE = 4;
    private static final int WIDE_WIDTH = 853;
    private static final float HD_SCALE = 2.25f;
    private static final @NotNull String MANIFEST = "{\"name\": \"Test\", \"main\": \"main.js\", \"api\": \"1\"}";
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
        Map<String, byte[]> files = new LinkedHashMap<>();

        files.put("ui/skull.png", ImageFileScenario.createPng());
        files.put("ui/skull.dds", ImageFileScenario.createDds());
        files.put("ui/text.txt", "text".getBytes(StandardCharsets.US_ASCII));

        return ScriptMapScenario.createResources(files);
    }

    /**
     * Creates a runtime of a main script, whose log lines go to a list.
     */
    private static @NotNull ScriptRuntime createRuntime(@NotNull Game game, @NotNull String source, @NotNull List<String> lines) throws IOException {
        ScriptPackage scriptPackage = ScriptPackage.create(ScriptManifest.parse(MANIFEST.getBytes(StandardCharsets.UTF_8)),
                                                           Map.of("main.js", source.getBytes(StandardCharsets.UTF_8)), Map.of());

        return ScriptRuntime.create(scriptPackage, game, ScriptResources.EMPTY, ScriptGameScenario.SEED, ScriptRuntime.INSTRUCTION_BUDGET,
                                    (level, module, message) -> lines.add(level == LogLevel.INFO ? message : level.getTitle() + ' ' + message));
    }

    @Test
    void render_should_draw_each_space_in_call_order() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), """
                                                                           var image = game.map.resources.getImage('ui/skull.png');
                                                                           function onRender(screen) {
                                                                               screen.fillRectangle(1.5, 2.25, 10, 20, Color.fromRgba(1, 2, 3, 0.2));
                                                                               screen.world.drawCircle(100.5, 200, 16, {color: Color.Red, thickness: 2});
                                                                               screen.drawText('\\x04Hi\\nthere', 320, 10, {font: Font.Large, align: Align.Center});
                                                                               screen.minimap.fillCircle(64, 64, 8, Color.Green);
                                                                               screen.drawImage(image, 5, 6, {opacity: 0.5});
                                                                               screen.world.drawLine(0, 0, 32, 32.5);
                                                                               screen.drawPoint(3, 4, Color.Black);
                                                                               screen.minimap.drawRectangle(0, 0, 32, 32);
                                                                               screen.drawRectangle(1, 1, 2, 2, {thickness: 0.5, color: Color.Blue});
                                                                               screen.world.drawImage(image, 1, 2, {width: 64, height: 32});
                                                                               screen.ground.fillRectangle(32, 64, 96, 32, Color.Blue);
                                                                           }
                                                                           """, createResources());
        ScriptRuntime runtime = scenario.getRuntime();

        runtime.start();

        Assertions.assertNull(runtime.findDrawList(), "nothing drawn before the first render call");

        runtime.callRender();

        ScrDrawList list = runtime.findDrawList();

        Assertions.assertEquals(List.of("fillRectangle 1.5 2.25 10.0 20.0 33010203",
                                        "drawText \u0004Hi\nthere 320.0 10.0 LARGE CENTER ffffffff",
                                        "drawPicture 2x1 5.0 6.0 2.0 1.0 0.5",
                                        "drawPoint 3.0 4.0 ff000000",
                                        "drawRectangle 1.0 1.0 2.0 2.0 0.5 ff0000ff"),
                                DrawListCheck.describe(list, ScrDrawList.Space.SCREEN), scenario.getLines()::toString);
        Assertions.assertEquals(List.of("drawCircle 100.5 200.0 16.0 2.0 ffff0000",
                                        "drawLine 0.0 0.0 32.0 32.5 1.0 ffffffff",
                                        "drawPicture 2x1 1.0 2.0 64.0 32.0 1.0"),
                                DrawListCheck.describe(list, ScrDrawList.Space.WORLD));
        Assertions.assertEquals(List.of("fillCircle 64.0 64.0 8.0 ff00ff00", "drawRectangle 0.0 0.0 32.0 32.0 1.0 ffffffff"),
                                DrawListCheck.describe(list, ScrDrawList.Space.MINIMAP));
        Assertions.assertEquals(List.of("fillRectangle 32.0 64.0 96.0 32.0 ff0000ff"), DrawListCheck.describe(list, ScrDrawList.Space.GROUND));

        runtime.callRender();

        Assertions.assertEquals(5, runtime.findDrawList().getCount(ScrDrawList.Space.SCREEN), "a new call replaces the list");
    }

    @Test
    void draw_image_should_draw_a_source_rectangle_inside_the_image() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), ERROR_FUNCTION + """
                                                                                            var image = game.map.resources.getImage('ui/skull.png');
                                                                                            function onRender(screen) {
                                                                                                screen.drawImage(image, 7, 8, {source: {x: 1, y: 0, width: 1, height: 1}});
                                                                                                screen.world.drawImage(image, 0, 0, {source: {x: 0, y: 0, width: 1, height: 1}, width: 16, height: 16});
                                                                                                screen.minimap.drawImage(image, 1, 1, {source: {x: 0, y: 0, width: 2, height: 1}, opacity: 0.25});
                                                                                                log.info(error(function () { screen.drawImage(image, 0, 0, {source: {x: 1, y: 0, width: 2, height: 1}}); }),
                                                                                                         error(function () { screen.drawImage(image, 0, 0, {source: {x: -1, y: 0, width: 1, height: 1}}); }),
                                                                                                         error(function () { core.screen.drawImageRegion(-1, 0, 0, 1, 1, 0, 0, 1, 1, 1); }));
                                                                                            }
                                                                                            """, createResources());
        ScriptRuntime runtime = scenario.getRuntime();

        runtime.start();
        runtime.callRender();

        ScrDrawList list = runtime.findDrawList();

        Assertions.assertEquals(List.of("RangeError RangeError RangeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertEquals(List.of("drawPicture 2x1 7.0 8.0 1.0 1.0 1.0 from 1.0 0.0 1.0 1.0"), DrawListCheck.describe(list, ScrDrawList.Space.SCREEN));
        Assertions.assertEquals(List.of("drawPicture 2x1 0.0 0.0 16.0 16.0 1.0 from 0.0 0.0 1.0 1.0"), DrawListCheck.describe(list, ScrDrawList.Space.WORLD));
        Assertions.assertEquals(List.of("drawPicture 2x1 1.0 1.0 2.0 1.0 0.25"), DrawListCheck.describe(list, ScrDrawList.Space.MINIMAP));
    }

    @Test
    void drawing_throws_outside_on_render() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), ERROR_FUNCTION + """
                                                                                            var saved = null;
                                                                                            var results = [error(function () { core.screen.fillRectangle(0, 0, 1, 1, 0xFFFFFFFF); })];
                                                                                            function onStart() {
                                                                                                results.push(error(function () { core.world.drawPoint(0, 0, 0xFFFFFFFF); }));
                                                                                            }
                                                                                            function onFrame(frame) {
                                                                                                results.push(error(function () { core.minimap.drawLine(0, 0, 1, 1, 0xFFFFFFFF, 1); }));
                                                                                            }
                                                                                            function onLocalFrame(frame) {
                                                                                                results.push(error(function () { saved.drawPoint(0, 0); }));
                                                                                                log.info(results.join(' '));
                                                                                            }
                                                                                            function onRender(screen) {
                                                                                                saved = screen;
                                                                                                results.push(error(function () { screen.drawPoint(0, 0, -1); }), error(function () { screen.drawText('x', 0, 0, {font: 1}); }),
                                                                                                             error(function () { core.screen.drawText(0, 0, 'x', 9, 0xFFFFFFFF, 0); }),
                                                                                                             error(function () { screen.fillRectangle(0, 0, -1, 1); }), error(function () { screen.drawLine(0, 0, NaN, 1); }));
                                                                                            }
                                                                                            """);
        ScriptRuntime runtime = scenario.getRuntime();

        runtime.start();
        runtime.runFrame(1);
        runtime.callRender();
        runtime.callLocalFrame(1);

        Assertions.assertEquals(List.of("TypeError TypeError TypeError RangeError TypeError RangeError RangeError TypeError TypeError"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void a_render_call_over_its_budget_draws_nothing() throws IOException {
        List<String> lines = new ArrayList<>();
        ScriptRuntime runtime = createRuntime(createGame(), """
                                                            var mode = 'draw';
                                                            function onLocalFrame(frame) {
                                                                mode = frame === 2 ? 'loop' : frame === 3 ? 'throw' : 'draw';
                                                            }
                                                            function onRender(screen) {
                                                                screen.fillRectangle(0, 0, 1, 1);
                                                                if (mode === 'loop') {
                                                                    while (true) {
                                                                    }
                                                                }
                                                                if (mode === 'throw') {
                                                                    throw new Error('stop');
                                                                }
                                                                screen.fillRectangle(1, 1, 1, 1);
                                                            }
                                                            """, lines);

        runtime.start();
        runtime.callLocalFrame(1);
        runtime.callRender();

        Assertions.assertEquals(2, runtime.findDrawList().getCount(ScrDrawList.Space.SCREEN));

        runtime.callLocalFrame(2);
        runtime.callRender();

        Assertions.assertNull(runtime.findDrawList(), "the list of a call over its budget is dropped");
        Assertions.assertFalse(runtime.isStopped(), "a local call never stops the script");

        runtime.callLocalFrame(3);
        runtime.callRender();

        Assertions.assertEquals(1, runtime.findDrawList().getCount(ScrDrawList.Space.SCREEN), "a call that throws keeps what it drew");

        runtime.callLocalFrame(4);
        runtime.callRender();

        Assertions.assertEquals(2, runtime.findDrawList().getCount(ScrDrawList.Space.SCREEN));
        Assertions.assertTrue(lines.stream().anyMatch(line -> line.contains("onRender ran more than the budget of instructions")), lines::toString);
    }

    @Test
    void screen_reports_the_local_display_and_snaps_to_its_pixels() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = new ScriptGameScenario(game, ERROR_FUNCTION + """
                                                                                    function onFrame(frame) {
                                                                                        log.info(error(function () { core.screen.getWidth(); }), error(function () { core.screen.measureText('x', 0); }));
                                                                                    }
                                                                                    function onRender(screen) {
                                                                                        log.info(screen.getWidth(), screen.getHeight(), screen.getScale(), screen.getGraphics() === Graphics.HD, screen.snap(1.3),
                                                                                                 screen.snap(4), screen.measureText('abc', Font.Large), screen.world.measureText('ab'));
                                                                                    }
                                                                                    """);
        ScriptRuntime runtime = scenario.getRuntime();

        runtime.start();
        runtime.callRender();
        game.getInterfaceLink().getLocalDisplay().setScreen(WIDE_WIDTH, 480, HD_SCALE, AssetResolver.AssetSet.HD);
        game.getInterfaceLink().getLocalDisplay().setTextMeasure((text, font) -> text.length() * (font.ordinal() + 1));
        runtime.callRender();
        runtime.runFrame(1);

        Assertions.assertEquals(List.of("640 480 1 false 1 4 0 0", "853 480 2.25 true 1.3333333333333333 4 9 4", "TypeError TypeError"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void local_reports_the_pointer_and_the_interface_size_in_interface_units() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = new ScriptGameScenario(game, ERROR_FUNCTION + """
                                                                                    function onFrame(frame) {
                                                                                        log.info(error(function () { local.getPointerPosition(); }), error(function () { local.getInterfaceWidth(); }));
                                                                                    }
                                                                                    function onLocalFrame() {
                                                                                        var pointer = local.getPointerPosition();
                                                                                        log.info(pointer === null ? 'unknown' : pointer.x + ' ' + pointer.y + ' ' + Object.isFrozen(pointer),
                                                                                                 local.getInterfaceWidth(), local.getInterfaceHeight());
                                                                                    }
                                                                                    """);
        ScriptRuntime runtime = scenario.getRuntime();

        runtime.start();
        runtime.callLocalFrame(0);
        game.getInterfaceLink().getLocalDisplay().setScreen(WIDE_WIDTH, 480, HD_SCALE, AssetResolver.AssetSet.HD);
        game.getInterfaceLink().getLocalDisplay().setPointer(812.5f, 470.25f);
        runtime.callLocalFrame(0);
        runtime.runFrame(1);

        Assertions.assertEquals(List.of("unknown 640 480", "812.5 470.25 true 853 480", "TypeError TypeError"), scenario.getInfoMessages(),
                                scenario.getLines()::toString);
    }

    @Test
    void colors_fonts_alignments_and_console_constants_have_their_values() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), ERROR_FUNCTION + """
                                                                                            log.info(Color.White === 0xFFFFFFFF, Color.fromRgba(1, 2, 3, 0.2) === 0x33010203, Color.fromRgb(255, 0, 0) === Color.Red,
                                                                                                     Color.Transparent, error(function () { Color.fromRgb(256, 0, 0); }), error(function () { Color.fromRgb(0.5, 0, 0); }),
                                                                                                     error(function () { Color.fromPalette(1); }));
                                                                                            log.info(Font.Small.getId(), Font.Huge.getId(), Font.getCount(), Align.Left.getId(), Align.Right.getId(), Graphics.SD.getId(),
                                                                                                     Graphics.HD2.getId(), ConsoleControl.CommandCard.getId(), ConsoleControl.TextBox.getId(), ConsoleImage.Console.getId(),
                                                                                                     ResourceType.Supply.getId());
                                                                                            """);

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("true true true 0 RangeError RangeError TypeError", "0 3 4 0 2 0 2 0 6 0 3"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void images_decode_once_and_know_their_size() throws IOException {
        ScriptGameScenario scenario = new ScriptGameScenario(createGame(), ERROR_FUNCTION + """
                                                                                            var png = game.map.resources.getImage('ui/skull.png');
                                                                                            var dds = game.map.resources.getImage('ui/skull.dds');
                                                                                            log.info(png === game.map.resources.getImage('ui/skull.png'), png.getWidth(), png.getHeight(), dds.getWidth(), dds.getHeight(), png.getName());
                                                                                            log.info(error(function () { game.map.resources.getImage('ui/none.png'); }), error(function () { game.map.resources.getImage('ui/text.txt'); }),
                                                                                                     error(function () { core.resources.getImageWidth(-1); }));
                                                                                            """, createResources());

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("true 2 1 4 4 ui/skull.png", "Error Error RangeError"), scenario.getInfoMessages(),
                                scenario.getLines()::toString);
    }

    @Test
    void pixel_writes_land_in_the_classic_screen() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = new ScriptGameScenario(game, ERROR_FUNCTION + """
                                                                                    function onStart() {
                                                                                        core.screen.setPixel(1, 2, 5);
                                                                                        core.screen.fillPixels(638, 479, 4, 4, 6);
                                                                                        log.info(error(function () { core.screen.setPixel(0, 0, 256); }));
                                                                                    }
                                                                                    function onLocalFrame(frame) {
                                                                                        core.screen.setPixel(3, 2, 7);
                                                                                    }
                                                                                    """);
        ClassicScreenGraphics screen = null;

        for (ScrGamePlugin plugin : game.getPlugins()) {
            if (plugin instanceof ClassicScreenGraphics graphics) {
                screen = graphics;
            }
        }

        Assertions.assertNotNull(screen, "the game has the classic screen of the mod");

        scenario.getRuntime().start();
        scenario.getRuntime().runFrame(0);
        scenario.getRuntime().callLocalFrame(1);

        ScrScreenOverlay overlay = screen.findScreenOverlay();

        Assertions.assertNotNull(overlay);
        Assertions.assertEquals(List.of("RangeError"), scenario.getInfoMessages(), scenario.getLines()::toString);
        Assertions.assertEquals(4, overlay.getShownCount());
        Assertions.assertEquals(5, overlay.getPixel(2 * 640 + 1));
        Assertions.assertEquals(7, overlay.getPixel(2 * 640 + 3));
        Assertions.assertEquals(6, overlay.getPixel(479 * 640 + 639));
    }

    @Test
    void render_runs_without_a_submitter_and_the_plugin_hands_out_its_list() throws IOException {
        Game game = createGame();
        ScriptGameScenario scenario = new ScriptGameScenario(game, """
                                                                   function onRender(screen) {
                                                                       screen.world.drawPoint(local.isReplay() ? 1 : 0, 0, Color.White);
                                                                   }
                                                                   """);
        ScriptRuntime runtime = scenario.getRuntime();
        ScriptInputPlugin plugin = new ScriptInputPlugin(() -> runtime, () -> null, game.getInterfaceLink().getLocalView());

        Assertions.assertNull(plugin.findDrawList());

        runtime.start();
        plugin.onFramePlayed(1);

        Assertions.assertEquals(List.of("drawPoint 0.0 0.0 ffffffff"), DrawListCheck.describe(plugin.findDrawList(), ScrDrawList.Space.WORLD),
                                scenario.getLines()::toString);
    }
}
