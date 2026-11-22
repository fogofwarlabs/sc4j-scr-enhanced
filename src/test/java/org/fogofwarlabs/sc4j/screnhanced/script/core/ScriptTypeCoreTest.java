package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.List;

/**
 * Tests the {@code ImageType} names that {@code core.imageType} builds from the installed GRP paths and drawing functions, the {@code SpriteType}
 * and {@code FlingyType} names from their images, and the image types whose HD anims the image types draw after the installed {@code images.rel}.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScriptTypeCoreTest {
    private static final int MAP_TILE_SIZE = 4;
    private static final int MAP_TILESET = 0;

    @Test
    void image_type_names_are_unique_identifiers_from_the_grp_paths(@NotNull InstallationTestResources resources) throws IOException {
        GameMap map = new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, MAP_TILESET, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]);
        ScriptGameScenario scenario = new ScriptGameScenario(Game.create(resources.getGameData(), map, new LcgRandom(0)), """
                                                                                                                          var names = {};
                                                                                                                          var unique = 0;
                                                                                                                          var valid = 0;
                                                                                                                          for (var id = 0; id < ImageType.getCount(); id++) {
                                                                                                                              var name = ImageType.get(id).getName();
                                                                                                                              if (names[name.toLowerCase()] === undefined) {
                                                                                                                                  names[name.toLowerCase()] = id;
                                                                                                                                  unique++;
                                                                                                                              }
                                                                                                                              if (/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name) && ImageType[name] === ImageType.get(id) && core.imageType.getName(id) === name) {
                                                                                                                                  valid++;
                                                                                                                              }
                                                                                                                          }
                                                                                                                          log.info(ImageType.getCount(), unique, valid);
                                                                                                                          log.info([239, 130, 131, 133, 344, 345, 920, 921].map(function (id) { return ImageType.get(id).getName(); }).join(' '));
                                                                                                                          """);

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("999 999 999",
                                        "UnitTerranMarine UnitProtossArbiter UnitProtossArbiterShadow UnitProtossArbiterWarpFlash " +
                                        "UnitNeutralGeyser UnitNeutralGeyser_2 UnitZergZLurker UnitZergZlurker_2"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void sprite_and_flingy_type_names_are_unique_identifiers_from_their_images(@NotNull InstallationTestResources resources) throws IOException {
        GameMap map = new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, MAP_TILESET, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]);
        ScriptGameScenario scenario = new ScriptGameScenario(Game.create(resources.getGameData(), map, new LcgRandom(0)), """
                                                                                                                          function count(table) {
                                                                                                                              var names = {};
                                                                                                                              var valid = 0;
                                                                                                                              for (var id = 0; id < table.getCount(); id++) {
                                                                                                                                  var name = table.get(id).getName();
                                                                                                                                  if (names[name.toLowerCase()] === undefined && /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name) && table[name] === table.get(id)) {
                                                                                                                                      names[name.toLowerCase()] = id;
                                                                                                                                      valid++;
                                                                                                                                  }
                                                                                                                              }
                                                                                                                              return table.getCount() + ' ' + valid;
                                                                                                                          }
                                                                                                                          var flingy = UnitType.TerranMarine.getFlingyType();
                                                                                                                          log.info(count(SpriteType), count(FlingyType));
                                                                                                                          log.info(flingy.getName(), flingy.getSprite().getName(), core.spriteType.getName(flingy.getSprite().getId()));
                                                                                                                          """);

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("517 517 209 209", "UnitTerranMarine UnitTerranMarine UnitTerranMarine"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }

    @Test
    void an_image_type_draws_the_hd_anim_that_the_installed_image_relations_name(@NotNull InstallationTestResources resources) throws IOException {
        GameMap map = new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, MAP_TILESET, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]);
        ScriptGameScenario scenario = new ScriptGameScenario(Game.create(resources.getGameData(), map, new LcgRandom(0)), """
                                                                                                                          var shared = 0;
                                                                                                                          for (var id = 0; id < ImageType.getCount(); id++) {
                                                                                                                              var animImageType = ImageType.get(id).getAnimImageType();
                                                                                                                              if (animImageType.getId() !== core.imageType.getAnimImageType(id) || animImageType.getAnimImageType() !== animImageType) {
                                                                                                                                  throw new Error('image type ' + id);
                                                                                                                              }
                                                                                                                              if (animImageType !== ImageType.get(id)) {
                                                                                                                                  shared++;
                                                                                                                              }
                                                                                                                          }
                                                                                                                          log.info(shared > 0, [239, 275, 172].map(function (id) { return ImageType.get(id).getAnimImageType().getName(); }).join(' '));
                                                                                                                          """);

        scenario.getRuntime().start();

        Assertions.assertEquals(List.of("true UnitTerranMarine UnitTerranControl UnitProtossGateway"),
                                scenario.getInfoMessages(), scenario.getLines()::toString);
    }
}
