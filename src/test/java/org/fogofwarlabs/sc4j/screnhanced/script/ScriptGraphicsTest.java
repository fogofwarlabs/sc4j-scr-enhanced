package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageAnimation;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ClassicImageGraphics;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageFileScenario;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageGrp;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tests the graphics a map script replaces in a live melee game: the Marine image of every Marine and that of one Marine, drawn by the game's
 * classic image graphics, and the end of the one Marine's graphics when the Marine is removed.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptGraphicsTest {
    /**
     * The script: once the Command Centers exist it creates two Marines of the human player toward the map center, replaces the Marine image
     * with the GRP and the HD anim, gives the second Marine its own HD2 anim, prints the Marine image type and the Marines' ids, and removes the
     * second Marine three frames later.
     */
    private static final @NotNull String SCRIPT = """
                                                  var marine, other, spawnFrame;
                                                  function onFrame(frame) {
                                                      if (spawnFrame === undefined && game.units.findOne({type: UnitType.TerranCommandCenter}) !== null) {
                                                          spawnFrame = frame;
                                                          var human = game.players.getHumans()[0];
                                                          var commandCenter = game.units.findOne({type: UnitType.TerranCommandCenter, owner: human});
                                                          var directionX = commandCenter.getX() < game.map.getWidth() / 2 ? 1 : -1;
                                                          var directionY = commandCenter.getY() < game.map.getHeight() / 2 ? 1 : -1;
                                                          var x = commandCenter.getX() + 160 * directionX;
                                                          var y = commandCenter.getY() + 160 * directionY;
                                                          marine = game.units.create(UnitType.TerranMarine, human, Position.of(x, y));
                                                          other = game.units.create(UnitType.TerranMarine, human, Position.of(x + 48 * directionX, y));
                                                          display.images.replace(marine.getImageType(), {sd: game.map.resources.getGrp('units/marine.grp'), hd: game.map.resources.getAnim('units/marine.anim')});
                                                          display.images.replaceUnit(other, {hd2: game.map.resources.getAnim('units/marine-hd2.anim')});
                                                          game.print('ES replaced ' + marine.getImageType().getId() + ' ' + marine.getId() + ' ' + other.getId());
                                                      } else if (frame === spawnFrame + 3) {
                                                          other.remove();
                                                          game.print('ES removed');
                                                      }
                                                  }
                                                  """;
    private static final int SPAWN_FRAMES = 200;
    private static final int REMOVAL_FRAMES = 10;

    /**
     * @return the resources of the script: a GRP, an HD anim and an HD2 anim
     */
    static @NotNull Map<String, byte[]> createResources() {
        return Map.of("units/marine.grp", ImageFileScenario.createGrp(0),
                      "units/marine.anim", ImageFileScenario.createAnim(ImageFileScenario.HD_SCALE),
                      "units/marine-hd2.anim", ImageFileScenario.createAnim(ImageFileScenario.HD2_SCALE));
    }

    private static @NotNull ClassicImageGraphics findImageGraphics(@NotNull Game game) {
        for (ScrGamePlugin plugin : game.getPlugins()) {
            if (plugin instanceof ClassicImageGraphics graphics) {
                return graphics;
            }
        }

        return Assertions.fail("The game has no classic image graphics.");
    }

    private static @NotNull SpriteImage findMainImage(@NotNull Game game, int unitId) {
        Unit unit = game.getUnitSystem().requireUnitPool().getUnit(unitId);
        Sprite sprite = unit.getSpriteOrNull();

        Assertions.assertNotNull(sprite);

        SpriteImage image = sprite.getMainImage();

        Assertions.assertNotNull(image);

        return image;
    }

    @Test
    void a_script_replaces_the_marine_image_of_every_marine_and_of_one_marine(@NotNull InstallationTestResources resources,
                                                                              @TempDir @NotNull Path folder) throws IOException {
        ScriptMapGameScenario scenario = ScriptMapGameScenario.startMelee(resources.getGameData(), folder, SCRIPT, createResources());

        scenario.stepUntilPrinted("replaced", SPAWN_FRAMES);

        List<String> prints = scenario.getPrints();

        Assertions.assertEquals(1, prints.size(), prints::toString);

        String[] values = prints.getFirst().split(" ");
        int imageType = Integer.parseInt(values[1]);
        Game game = scenario.getGame();
        ClassicImageGraphics graphics = findImageGraphics(game);
        SpriteImage marineImage = findMainImage(game, Integer.parseInt(values[2]));
        SpriteImage otherImage = findMainImage(game, Integer.parseInt(values[3]));
        ScrImageSheet marineSheet = graphics.findImageSheet(marineImage);
        ScrImageAnimation marineAnimation = graphics.findImageAnimation(marineImage);
        ScrImageAnimation otherAnimation = graphics.findImageAnimation(otherImage);

        Assertions.assertEquals(imageType, marineImage.getImageTypeId());
        Assertions.assertInstanceOf(ImageGrp.class, marineSheet);
        Assertions.assertEquals(ImageFileScenario.GRP_FRAME_COUNT, marineSheet.getFrameCount());
        Assertions.assertNotNull(marineAnimation);
        Assertions.assertEquals(1, ImageFileScenario.awaitSheet(marineAnimation, ImageFileScenario.HD_SCALE).getUnitsPerTexel());
        Assertions.assertNull(graphics.findImageSheet(otherImage), "the Marine's own graphics without a GRP keep the installed GRP");
        Assertions.assertNotNull(otherAnimation);
        Assertions.assertEquals(2, ImageFileScenario.awaitSheet(otherAnimation, ImageFileScenario.HD_SCALE).getUnitsPerTexel());

        ScriptSimulationPlugin plugin = ScriptMapGameScenario.findPlugin(game);

        Assertions.assertNotNull(plugin);
        Assertions.assertEquals(1, plugin.getImageReplacements().getUnitReplacementCount());

        scenario.stepUntilPrinted("removed", REMOVAL_FRAMES);
        scenario.stepFrames(REMOVAL_FRAMES);

        Assertions.assertEquals(List.of(prints.getFirst(), "removed"), scenario.getPrints());
        Assertions.assertEquals(0, plugin.getImageReplacements().getUnitReplacementCount(), "the removed Marine's graphics are gone");
        Assertions.assertSame(marineSheet, graphics.findImageSheet(marineImage));
    }
}
