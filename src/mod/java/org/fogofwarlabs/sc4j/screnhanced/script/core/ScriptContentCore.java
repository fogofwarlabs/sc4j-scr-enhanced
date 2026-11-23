package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.table.GameContent;
import org.fogofwarlabs.sc4j.scr.content.table.ScrDatBindingUtils;
import org.fogofwarlabs.sc4j.scr.content.table.ScrDatKind;
import org.fogofwarlabs.sc4j.scr.data.format.dat.FlingyDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SpritesDat;
import org.fogofwarlabs.sc4j.scr.data.type.DatColumn;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageGraphics;
import org.fogofwarlabs.sc4j.screnhanced.graphics.PictureSheet;
import org.fogofwarlabs.sc4j.screnhanced.graphics.SheetLayout;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptContentExtension;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Defines {@code create} of {@code core.imageType}, {@code core.spriteType} and {@code core.flingyType}: the types a script registers in the
 * entries {@link ScriptContentExtension} reserves, and their names and counts.
 * <p>
 * The types are registered in shared code at the module top level or in {@code onStart} ({@link ScriptGame#isTypeRegistration()}); each
 * {@code create} takes the next free reserved entry, so the same calls in the same order give the same ids on every peer and in every replay. A
 * registered type has the next id after the native types and the types registered before; {@code getCount()} and {@code getName(id)} count and
 * name it, and {@code core.dat} reads and writes its values.
 * <p>
 * {@code core.imageType.create(sheet, name, script, scriptImageType, frameSets, drawFunction, animationFrameSets, animationFrameTime)} registers an
 * image type that draws a sheet ({@code core.resources.getSheet}). Its iscript is a generic script ({@code script} {@value #STILL_SCRIPT} for
 * {@code Still}, {@value #PROJECTILE_SCRIPT} for {@code Projectile}, {@value #UNIT_SCRIPT} for {@code Unit}, which needs
 * {@link ScriptContentExtension#UNIT_FRAME_SETS} frame sets of {@value SheetLayout#TURNING_DIRECTIONS} directions and makes the image clickable)
 * or that of an image type ({@code scriptImageType}, {@code script} {@code -1}), whose iscript, draw function, color shift, Clickable, Use Full
 * Iscript, Draw If Cloaked and overlay files it copies.
 * {@code frameSets} ({@code null} for the sheet's own order) names for each frame set the script plays the frame set of the sheet that draws it;
 * the game's frames of the type, which the simulation reads, are the sheet's frames in that order, quantized in shared code from the map's
 * resource bytes and the layout ({@link org.fogofwarlabs.sc4j.screnhanced.graphics.PictureSheetConverter}). {@code drawFunction} is {@code -1}
 * for that of the script; {@code animationFrameSets} ({@code null} for none) loop for every image of the type, each for
 * {@code animationFrameTime} game frames, as presentation.
 * <p>
 * {@code core.spriteType.create(image, name, selectionCircle, selectionCircleOffset, healthBarWidth, visible)} and
 * {@code core.flingyType.create(sprite, name, base, topSpeed, acceleration, haltDistance, turnRate, movementControl)} write the reserved
 * {@code sprites.dat} and {@code flingy.dat} entries: a flingy type copies the motion values of {@code base} ({@code -1} for flingy type
 * {@code 0}) and takes the given ones, {@code -1} for the base's. A name {@code null} names the type after its kind and id. A call outside the
 * registration, in local code or when every reserved entry is taken throws.
 */
final class ScriptContentCore {
    /**
     * The generic script of an image type that shows frame {@code 0} until Death.
     */
    static final int STILL_SCRIPT = 0;
    /**
     * The generic script of a bullet's image type.
     */
    static final int PROJECTILE_SCRIPT = 1;
    /**
     * The generic script of a unit that turns, walks, attacks and dies, without sounds.
     */
    static final int UNIT_SCRIPT = 2;

    private static final @NotNull String IMAGE_NAME_PREFIX = "Image";
    private static final @NotNull String SPRITE_NAME_PREFIX = "Sprite";
    private static final @NotNull String FLINGY_NAME_PREFIX = "Flingy";
    /**
     * The {@code images.dat} fields an image type copies from the image type whose iscript it takes.
     */
    private static final @NotNull List<String> COPIED_IMAGE_FIELDS = List.of(ImagesDat.ISCRIPT, ImagesDat.DRAW_FUNCTION, ImagesDat.COLOR_SHIFT,
                                                                             ImagesDat.CLICKABLE, ImagesDat.FULL_ISCRIPT, ImagesDat.DRAW_IF_CLOAKED,
                                                                             ImagesDat.SHIELD_OVERLAY, ImagesDat.ATTACK_OVERLAY,
                                                                             ImagesDat.DAMAGE_OVERLAY, ImagesDat.SPECIAL_OVERLAY,
                                                                             ImagesDat.LANDING_DUST_OVERLAY, ImagesDat.LIFTOFF_DUST_OVERLAY);
    /**
     * The motion fields a flingy type copies from its base.
     */
    private static final @NotNull List<String> MOTION_FIELDS = List.of(FlingyDat.TOP_SPEED, FlingyDat.ACCELERATION, FlingyDat.HALT_DISTANCE,
                                                                       FlingyDat.TURN_RATE, FlingyDat.UNUSED, FlingyDat.MOVEMENT_CONTROL);
    private static final int BYTE_MAXIMUM = 0xFF;
    private static final int WORD_MAXIMUM = 0xFFFF;
    /**
     * The motion fields {@code core.flingyType.create} takes, in argument order, and their largest values.
     */
    private static final @NotNull List<String> GIVEN_MOTION_FIELDS = List.of(FlingyDat.TOP_SPEED, FlingyDat.ACCELERATION, FlingyDat.HALT_DISTANCE,
                                                                             FlingyDat.TURN_RATE, FlingyDat.MOVEMENT_CONTROL);
    private static final int @NotNull [] GIVEN_MOTION_MAXIMA = {Integer.MAX_VALUE, WORD_MAXIMUM, Integer.MAX_VALUE, BYTE_MAXIMUM, BYTE_MAXIMUM};
    /**
     * The argument of {@code core.flingyType.create} of the first motion field.
     */
    private static final int FIRST_MOTION_ARGUMENT = 3;
    /**
     * The largest selection circle size, the solid circles from {@code 0}.
     */
    private static final int MAXIMUM_SELECTION_CIRCLE = 9;
    private static final int TURNING = 1;

    /**
     * Names the entries between the native ones and the first reserved one, which other extensions added.
     */
    private static void fill(@NotNull List<String> names, int firstReserved, @NotNull String prefix) {
        while (names.size() < firstReserved) {
            names.add(prefix + names.size());
        }
    }

    private static @Nullable String readName(@NotNull Object @NotNull [] arguments, int index) {
        return index < arguments.length && arguments[index] == null ? null : ScriptCoreUtils.readString(arguments, index);
    }

    private static int @Nullable [] readOptionalIntegers(@NotNull Object @NotNull [] arguments, int index) {
        return index < arguments.length && arguments[index] == null ? null : ScriptCoreUtils.readIntegers(arguments, index);
    }


    private final @NotNull ScriptGame scriptGame;
    private final @NotNull ScriptGraphicsCore graphicsCore;
    private final @Nullable ScriptContentExtension.Entries entries;

    private final @NotNull List<String> imageNames;
    private final @NotNull List<String> spriteNames;
    private final @NotNull List<String> flingyNames;

    /**
     * The added columns of the game's tables by kind, bound on first use.
     */
    private final @NotNull Map<ScrDatKind, Function<String, @NotNull DatColumn>> addedColumns;

    private int imageCount;
    private int spriteCount;
    private int flingyCount;

    /**
     * Creates a {@link ScriptContentCore} instance without registered types.
     *
     * @param scriptGame   the game binding
     * @param graphicsCore the resources, which read sheet arguments
     * @param imageNames   the names of the native image types, which the registered ones join
     * @param spriteNames  the names of the native sprite types, which the registered ones join
     * @param flingyNames  the names of the native flingy types, which the registered ones join
     */
    ScriptContentCore(@NotNull ScriptGame scriptGame, @NotNull ScriptGraphicsCore graphicsCore, @NotNull List<String> imageNames,
                      @NotNull List<String> spriteNames, @NotNull List<String> flingyNames) {
        GameData gameData = scriptGame.getGame().getGameData();

        this.scriptGame   = scriptGame;
        this.graphicsCore = graphicsCore;
        this.entries      = gameData == null ? null : ScriptContentExtension.findEntries(gameData.getAddedGraphics());

        this.imageNames  = imageNames;
        this.spriteNames = spriteNames;
        this.flingyNames = flingyNames;

        this.addedColumns = new EnumMap<>(ScrDatKind.class);

        this.imageCount  = 0;
        this.spriteCount = 0;
        this.flingyCount = 0;

        if (this.entries != null) {
            fill(imageNames, this.entries.getFirstImage(), IMAGE_NAME_PREFIX);
            fill(spriteNames, this.entries.getFirstSprite(), SPRITE_NAME_PREFIX);
            fill(flingyNames, this.entries.getFirstFlingy(), FLINGY_NAME_PREFIX);
        }
    }

    /**
     * @return the number of image types: the native ones, those other extensions added before the reserved ones and the registered ones
     */
    int getImageTypeCount() {
        return this.imageNames.size();
    }

    /**
     * @return the number of sprite types, as {@link #getImageTypeCount()} counts them
     */
    int getSpriteTypeCount() {
        return this.spriteNames.size();
    }

    /**
     * @return the number of flingy types, as {@link #getImageTypeCount()} counts them
     */
    int getFlingyTypeCount() {
        return this.flingyNames.size();
    }

    /**
     * @return the number of image types that may take graphics: the native ones and every reserved one, registered or not
     */
    int getReservedImageTypeCount() {
        return this.entries == null ? this.imageNames.size() : this.entries.getFirstImage() + ScriptContentExtension.IMAGE_COUNT;
    }

    /**
     * Reads a DAT column of a registered entry.
     *
     * @param kind  the DAT table kind: images, sprites or flingy
     * @param field the field name
     * @param id    a registered id
     * @return the raw value
     */
    int readValue(@NotNull ScrDatKind kind, @NotNull String field, int id) {
        return this.findAddedColumns(kind).apply(field).get(id - kind.getTypeCount());
    }

    /**
     * Writes a DAT column of a registered entry.
     *
     * @param kind  the DAT table kind: images, sprites or flingy
     * @param field the field name
     * @param id    a registered id
     * @param value the raw value
     */
    void writeValue(@NotNull ScrDatKind kind, @NotNull String field, int id, int value) {
        this.findAddedColumns(kind).apply(field).set(id - kind.getTypeCount(), value);
    }

    /**
     * Defines {@code create} of the image, sprite and flingy type namespaces.
     *
     * @param imageTypes  the {@code core.imageType} namespace
     * @param spriteTypes the {@code core.spriteType} namespace
     * @param flingyTypes the {@code core.flingyType} namespace
     * @param global      the global scope
     */
    void define(@NotNull Scriptable imageTypes, @NotNull Scriptable spriteTypes, @NotNull Scriptable flingyTypes, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, imageTypes, "create", 8,
                                       (context, scope, thisObject, arguments) -> (double) this.createImageType(arguments));
        ScriptCoreUtils.defineFunction(global, spriteTypes, "create", 6,
                                       (context, scope, thisObject, arguments) -> (double) this.createSpriteType(arguments));
        ScriptCoreUtils.defineFunction(global, flingyTypes, "create", 8,
                                       (context, scope, thisObject, arguments) -> (double) this.createFlingyType(arguments));
    }

    private int createImageType(@NotNull Object @NotNull [] arguments) {
        ScriptContentExtension.Entries reserved = this.checkRegistration(this.imageCount, ScriptContentExtension.IMAGE_COUNT, "image");
        PictureSheet sheet = this.graphicsCore.readSheet(arguments, 0);
        String name = readName(arguments, 1);
        int script = ScriptCoreUtils.readInteger(arguments, 2);
        int scriptImageType = ScriptCoreUtils.readInteger(arguments, 3);
        int[] frameSets = readOptionalIntegers(arguments, 4);
        int drawFunction = ScriptCoreUtils.readInteger(arguments, 5);
        int[] animationFrameSets = readOptionalIntegers(arguments, 6);
        int animationFrameTime = ScriptCoreUtils.readInteger(arguments, 7);

        if (scriptImageType == ScriptCoreUtils.NO_ID) {
            ScriptCoreUtils.requireId("generic script", script, UNIT_SCRIPT + 1);
        } else if (script != ScriptCoreUtils.NO_ID) {
            throw ScriptRuntime.rangeError("An image type takes a generic script or the script of an image type, not both.");
        } else {
            ScriptCoreUtils.requireId("image type", scriptImageType, this.getImageTypeCount());
        }

        if (drawFunction != ScriptCoreUtils.NO_ID) {
            ScriptCoreUtils.requireId("draw function", drawFunction, SpriteImage.MODIFIER_WARP_FLASH + 1);
        }

        SheetLayout layout = sheet.getLayout();
        int[] frames = ScriptGraphicsCore.expandFrameSets(layout, frameSets);

        if (script == UNIT_SCRIPT && (layout.getDirections() != SheetLayout.TURNING_DIRECTIONS ||
                                      frames.length < ScriptContentExtension.UNIT_FRAME_SETS * SheetLayout.TURNING_DIRECTIONS)) {
            throw ScriptRuntime.rangeError(Exceptions.format("The Unit script plays {0} frame sets of {1} directions.",
                                                             ScriptContentExtension.UNIT_FRAME_SETS, SheetLayout.TURNING_DIRECTIONS));
        }
        int id = reserved.getFirstImage() + this.imageCount;
        GameData gameData = this.scriptGame.getGame().requireGameData();
        byte[] grp = this.graphicsCore.createGrp(sheet, frames);
        ImageGraphics graphics = this.graphicsCore.createSheetGraphics(sheet, frames, grp);

        if (animationFrameSets != null) {
            try {
                this.scriptGame.getImageReplacements().setTypeAnimation(id, animationFrameSets, animationFrameTime);
            } catch (IllegalArgumentException exception) {
                throw ScriptRuntime.rangeError(exception.getMessage());
            }
        }

        gameData.putGameFile(reserved.getFramesPath(id), grp);
        this.scriptGame.getImageReplacements().replaceImage(id, graphics);

        if (scriptImageType != ScriptCoreUtils.NO_ID) {
            for (String field : COPIED_IMAGE_FIELDS) {
                this.writeValue(ScrDatKind.IMAGES, field, id, this.readImageValue(gameData, field, scriptImageType));
            }
        } else {
            boolean still = script == STILL_SCRIPT;
            boolean unit = script == UNIT_SCRIPT;
            int iscript = switch (script) {
                case PROJECTILE_SCRIPT -> reserved.getProjectileScript();
                case UNIT_SCRIPT -> sheet.hasShadow() ? reserved.getUnitShadowScript() : reserved.getUnitScript();
                default -> sheet.hasShadow() ? reserved.getStillShadowScript() : reserved.getStillScript();
            };

            this.writeValue(ScrDatKind.IMAGES, ImagesDat.ISCRIPT, id, iscript);
            this.writeValue(ScrDatKind.IMAGES, ImagesDat.DRAW_FUNCTION, id, SpriteImage.MODIFIER_NORMAL);
            this.writeValue(ScrDatKind.IMAGES, ImagesDat.COLOR_SHIFT, id, 0);
            this.writeValue(ScrDatKind.IMAGES, ImagesDat.CLICKABLE, id, unit ? 1 : 0);
            this.writeValue(ScrDatKind.IMAGES, ImagesDat.FULL_ISCRIPT, id, still ? 0 : 1);
            this.writeValue(ScrDatKind.IMAGES, ImagesDat.DRAW_IF_CLOAKED, id, 0);
        }

        if (drawFunction != ScriptCoreUtils.NO_ID) {
            this.writeValue(ScrDatKind.IMAGES, ImagesDat.DRAW_FUNCTION, id, drawFunction);
        }

        this.writeValue(ScrDatKind.IMAGES, ImagesDat.GRAPHIC_TURNS, id, layout.getDirections() == SheetLayout.TURNING_DIRECTIONS ? TURNING : 0);
        this.imageNames.add(name == null ? IMAGE_NAME_PREFIX + id : name);
        this.imageCount++;

        return id;
    }

    private int createSpriteType(@NotNull Object @NotNull [] arguments) {
        ScriptContentExtension.Entries reserved = this.checkRegistration(this.spriteCount, ScriptContentExtension.SPRITE_COUNT, "sprite");
        int image = ScriptCoreUtils.requireId("image type", ScriptCoreUtils.readInteger(arguments, 0), this.getImageTypeCount());
        String name = readName(arguments, 1);
        int selectionCircle = ScriptCoreUtils.requireId("selection circle", ScriptCoreUtils.readInteger(arguments, 2), MAXIMUM_SELECTION_CIRCLE + 1);
        int selectionCircleOffset = ScriptCoreUtils.requireId("selection circle offset", ScriptCoreUtils.readInteger(arguments, 3), BYTE_MAXIMUM + 1);
        int healthBarWidth = ScriptCoreUtils.requireId("health bar width", ScriptCoreUtils.readInteger(arguments, 4), BYTE_MAXIMUM + 1);
        boolean visible = ScriptCoreUtils.readBoolean(arguments, 5);
        int id = reserved.getFirstSprite() + this.spriteCount;

        this.writeValue(ScrDatKind.SPRITES, SpritesDat.IMAGE, id, image);
        this.writeValue(ScrDatKind.SPRITES, SpritesDat.SELECTION_CIRCLE, id, selectionCircle);
        this.writeValue(ScrDatKind.SPRITES, SpritesDat.SELECTION_CIRCLE_OFFSET, id, selectionCircleOffset);
        this.writeValue(ScrDatKind.SPRITES, SpritesDat.HEALTH_BAR_WIDTH, id, healthBarWidth);
        this.writeValue(ScrDatKind.SPRITES, SpritesDat.VISIBLE, id, visible ? 1 : 0);
        this.spriteNames.add(name == null ? SPRITE_NAME_PREFIX + id : name);
        this.spriteCount++;

        return id;
    }

    private int createFlingyType(@NotNull Object @NotNull [] arguments) {
        ScriptContentExtension.Entries reserved = this.checkRegistration(this.flingyCount, ScriptContentExtension.FLINGY_COUNT, "flingy");
        int sprite = ScriptCoreUtils.requireId("sprite type", ScriptCoreUtils.readInteger(arguments, 0), this.getSpriteTypeCount());
        String name = readName(arguments, 1);
        int baseArgument = ScriptCoreUtils.readInteger(arguments, 2);
        int base = baseArgument == ScriptCoreUtils.NO_ID ? 0 : ScriptCoreUtils.requireId("flingy type", baseArgument, this.getFlingyTypeCount());
        int[] motion = new int[MOTION_FIELDS.size()];
        int id = reserved.getFirstFlingy() + this.flingyCount;
        GameData gameData = this.scriptGame.getGame().requireGameData();

        for (int index = 0; index < motion.length; index++) {
            String field = MOTION_FIELDS.get(index);

            motion[index] = base < FlingyDat.FLINGY_COUNT ? gameData.getFlingy().get(field, base) : this.readValue(ScrDatKind.FLINGY, field, base);
        }

        for (int index = 0; index < GIVEN_MOTION_FIELDS.size(); index++) {
            int value = ScriptCoreUtils.readInteger(arguments, index + FIRST_MOTION_ARGUMENT);
            int maximum = GIVEN_MOTION_MAXIMA[index];

            if (value == ScriptCoreUtils.NO_ID) {
                continue;
            }

            if (value < 0 || value > maximum) {
                throw ScriptRuntime.rangeError(Exceptions.format("The {0} {1} is not from 0 to {2}.", GIVEN_MOTION_FIELDS.get(index), value, maximum));
            }

            motion[MOTION_FIELDS.indexOf(GIVEN_MOTION_FIELDS.get(index))] = value;
        }

        for (int index = 0; index < motion.length; index++) {
            this.writeValue(ScrDatKind.FLINGY, MOTION_FIELDS.get(index), id, motion[index]);
        }

        this.writeValue(ScrDatKind.FLINGY, FlingyDat.SPRITE, id, sprite);
        this.flingyNames.add(name == null ? FLINGY_NAME_PREFIX + id : name);
        this.flingyCount++;

        return id;
    }

    /**
     * Checks that a type may be registered now and that a reserved entry is free.
     *
     * @return the reserved entries
     * @throws EcmaError if local code runs, the game is read-only or outside the registration, or every reserved entry of the kind is taken
     */
    private @NotNull ScriptContentExtension.Entries checkRegistration(int registered, int reservedCount, @NotNull String kind) {
        this.scriptGame.checkWritable();

        if (!this.scriptGame.isTypeRegistration()) {
            throw ScriptRuntime.typeError("Types are registered at the module top level or in onStart only.");
        }

        ScriptContentExtension.Entries reserved = this.entries;

        if (reserved == null) {
            throw ScriptRuntime.typeError("The game has no reserved entries for types.");
        }

        if (registered >= reservedCount) {
            throw ScriptRuntime.rangeError(Exceptions.format("Every reserved {0} type is taken: {1}.", kind, reservedCount));
        }

        return reserved;
    }

    private int readImageValue(@NotNull GameData gameData, @NotNull String field, int imageType) {
        return imageType < ImagesDat.IMAGE_COUNT ? gameData.getImages().get(field, imageType) : this.readValue(ScrDatKind.IMAGES, field, imageType);
    }

    private @NotNull Function<String, @NotNull DatColumn> findAddedColumns(@NotNull ScrDatKind kind) {
        return this.addedColumns.computeIfAbsent(kind, key -> {
            GameContent content = this.scriptGame.getGame().getEngineGame().getContent();
            Function<String, DatColumn> columns = ScrDatBindingUtils.bindAddedColumns(key, content);
            Map<String, DatColumn> bound = new HashMap<>();

            return field -> bound.computeIfAbsent(field, columns);
        });
    }
}
