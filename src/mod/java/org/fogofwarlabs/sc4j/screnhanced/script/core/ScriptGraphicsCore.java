package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.audio.WaveData;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicDatRegion;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.screnhanced.graphics.*;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Defines {@code core.resources} and {@code core.graphics}: the GRPs, anims, images and sounds of the map's resources, and the graphics they
 * replace.
 * <p>
 * {@code core.resources}: {@code has(name)}, {@code getGrp(name)}, {@code getAnim(name)}, {@code getImage(name)} and {@code getSound(name)}, and
 * {@code getImageWidth(id)} and {@code getImageHeight(id)} (the pixel size of an image handle). A resource name is relative to the resource folder
 * with {@code /} separators. {@code getGrp}, {@code getAnim}, {@code getImage} and {@code getSound} return the id of a resource handle, the same
 * id for the same name, after checking the resource once: a GRP header with a readable frame table whose frames decode, a single-entry anim of
 * asset scale {@code 4} (HD) or {@code 2} (HD2) with a canvas and a {@code diffuse} layer, a PNG or DDS picture that decodes
 * ({@link PictureFileUtils}), or a sound file that decodes ({@link WaveData#decode}: a RIFF WAVE of 8-bit, 16-bit or 24-bit PCM, mono or stereo,
 * or an Ogg Vorbis file), which {@code core.sound.playResourceAt} plays ({@link ScriptSoundCore}).
 * A missing or invalid resource throws an {@code Error}.
 * <p>
 * {@code getSheet(name, frameWidth, frameHeight, frameCount, directions, scale, anchorX, anchorY, teamColor, shadow, sd)} reads a PNG or DDS
 * picture as a sheet of frames ({@link PictureSheet}, {@link SheetLayout}): {@code frameCount}, {@code anchorX} and {@code anchorY} are
 * {@code -1} for every whole frame and the frame center, {@code teamColor} and {@code shadow} the names of pictures of the same size or
 * {@code null}, {@code sd} a GRP handle of SD frames of the sheet's frame count and canvas or {@code -1}. It returns a sheet handle, the same for
 * the same name and arguments; a layout that does not fit throws a {@code RangeError}. Graphics made of a sheet take one byte per texel and
 * layer of each distinct sheet frame they show, frames no graphics shows nothing; a call whose graphics would take the anims of a game past
 * {@value #ANIMATION_BUDGET_MEBIBYTES} MiB throws a {@code RangeError}. {@code getSheetFrameCount(id)}, {@code getSheetDirections(id)},
 * {@code getSheetWidth(id)} and {@code getSheetHeight(id)} read a sheet handle: its frames, the frames of its frame sets and its canvas in map
 * pixels. {@code getSheetFrames(sheet, frameSets)} returns the handle of a sheet's graphics in the order of frame sets, the sheet's frame set for
 * each frame set an iscript plays (the same handle for the same sheet and frame sets), which every graphics argument takes in place of a sheet
 * handle. {@code readText(name)} returns any resource as text: its bytes decoded as UTF-8, a leading byte order mark left out; bytes that are no
 * UTF-8 throw an {@code Error}. The resources are read from the map's script trailer, inflated and checked, when they are first asked for; an
 * image is decoded once and drawn from the same picture afterwards, a text decoded once.
 * <p>
 * {@code core.graphics}: {@code replaceImage(imageType, grp, hdAnim, hd2Anim)} replaces the graphics of every image of an image type,
 * {@code replaceUnitImage(unit, imageType, grp, hdAnim, hd2Anim)} those of one live unit's images of the type, while the unit lives;
 * {@code restoreImage(imageType)} and {@code restoreUnitImage(unit, imageType)} take a replacement back. A handle argument is {@code -1} for
 * none; at least one is given, the HD anim has asset scale {@code 4} and the HD2 anim {@code 2}. The replacements change only what is drawn
 * ({@link ImageReplacements}): the classic graphics draw the GRP, else the installed GRP; HD draws the HD anim, else the HD2 anim; HD2 draws the
 * HD2 anim, else the HD anim at half size; graphics without an anim draw the GRP in HD too. A unit's replacement wins over that of the image
 * type, and a replacement wins over the GRP and anim pointers that EUD writes give the image. A sheet handle in place of the GRP, with both
 * anims {@code -1}, replaces with the sheet's frames: its GRP quantized to the map's tileset palette and an anim built when first drawn.
 * <p>
 * {@code replaceImageShadow(imageType, grp, hdAnim, hd2Anim)} and {@code replaceImageRemains(imageType, grp, hdAnim, hd2Anim)} give graphics to
 * the shadow and the remains of the sprites whose main image is of the type, {@code replaceUnitShadow(unit, grp, hdAnim, hd2Anim)} and
 * {@code replaceUnitRemains(unit, grp, hdAnim, hd2Anim)} to those of one live unit, winning over those of the image type; all three handles
 * {@code -1} draw nothing. {@code restoreImageShadow(imageType)}, {@code restoreImageRemains(imageType)}, {@code restoreUnitShadow(unit)} and
 * {@code restoreUnitRemains(unit)} take them back ({@link ImageReplacements}).
 * <p>
 * One image of {@code core.image}, while its slot holds it: {@code replaceImageGraphics(image, grp, hdAnim, hd2Anim)} and
 * {@code restoreImageGraphics(image)} (its graphics win over those of its unit and image type); {@code setImageFrame(image, frame)} (a drawn
 * frame from {@code 0} to {@value #MAXIMUM_FRAME}) and {@code setImageDrawFunction(image, drawFunction)} (from {@code 0} to
 * {@code 17}) draw that frame or draw function until the image's own drawn frame or draw function changes, as an EUD write of the image's
 * entry does; {@code -1} gives back the image's own. {@code holdImageFrame(image, frame)} draws the frame while the image's iscript steps its
 * own frame, until {@code -1} releases it, another frame is set or the image is gone. A frame or draw function a script set wins over an EUD
 * write. {@code playImage(image, frameSets, frameTime, loop, grp, hdAnim, hd2Anim)} shows frame sets one after another, each for
 * {@code frameTime} game frames from the call, of the given graphics or of the image's own when all three are {@code -1}; the image's direction
 * is added to the frame sets of turning graphics. {@code stopPlayingImage(image)} ends a play; a frame set or held frame ends it as well. The
 * {@code core.graphics} functions change no game state and work anywhere: shared code replaces on every peer, local code on the local machine
 * only.
 */
final class ScriptGraphicsCore {
    private static final @NotNull String ERROR_NAME = "Error";
    /**
     * The integer layout arguments of {@code getSheet} after the name: frame width, frame height, frame count, directions, scale, anchor x and
     * anchor y.
     */
    private static final int SHEET_LAYOUT_ARGUMENTS = 7;
    private static final int SHEET_SCALE = 4;
    private static final int SHEET_ANCHOR_X = 5;
    private static final int SHEET_ANCHOR_Y = 6;
    private static final int SHEET_TEAM_COLOR_ARGUMENT = 8;
    private static final int SHEET_SHADOW_ARGUMENT = 9;
    private static final int SHEET_SD_ARGUMENT = 10;
    /**
     * The largest drawn frame of an image, a WORD.
     */
    private static final int MAXIMUM_FRAME = 0xFFFF;
    /**
     * The byte order mark a text resource may start with, which {@code readText} leaves out.
     */
    private static final char BYTE_ORDER_MARK = '﻿';
    /**
     * The block-compressed bytes of the sheet anims of a game, in mebibytes.
     */
    private static final int ANIMATION_BUDGET_MEBIBYTES = 256;
    private static final int MEBIBYTE_SHIFT = 20;
    private static final long ANIMATION_BUDGET = (long) ANIMATION_BUDGET_MEBIBYTES << MEBIBYTE_SHIFT;

    /**
     * Reads one resource.
     */
    @FunctionalInterface
    private interface ResourceReader {
        @NotNull Object read() throws IOException;
    }


    /**
     * Reads an argument that is a string or {@code null}.
     *
     * @throws EcmaError if the argument is neither
     */
    private static @Nullable String readOptionalString(@NotNull Object @NotNull [] arguments, int index) {
        return index < arguments.length && arguments[index] == null ? null : ScriptCoreUtils.readString(arguments, index);
    }

    /**
     * Expands the frame sets a script plays into the sheet frame of each frame of the graphics.
     *
     * @param layout    the layout of the sheet
     * @param frameSets the sheet's frame set for each frame set the script plays, or {@code null} for the sheet's own order
     * @return the sheet frame of each frame
     * @throws EcmaError if there is no frame set or a frame set is outside the sheet
     */
    static int @NotNull [] expandFrameSets(@NotNull SheetLayout layout, int @Nullable [] frameSets) {
        int directions = layout.getDirections();
        int setCount = layout.getFrameCount() / directions;
        int[] sets = frameSets;

        if (sets == null) {
            sets = new int[setCount];

            for (int set = 0; set < setCount; set++) {
                sets[set] = set;
            }
        }

        if (sets.length == 0) {
            throw ScriptRuntime.rangeError("The frame sets are empty.");
        }

        int[] frames = new int[sets.length * directions];

        for (int index = 0; index < sets.length; index++) {
            ScriptCoreUtils.requireId("frame set", sets[index], setCount);

            for (int direction = 0; direction < directions; direction++) {
                frames[index * directions + direction] = sets[index] * directions + direction;
            }
        }

        return frames;
    }

    /**
     * Reads a value from {@code 0} to a maximum, or {@code -1} for none.
     *
     * @throws EcmaError if the argument is not an integer or neither {@code -1} nor from {@code 0} to the maximum
     */
    private static int readOptionalValue(@NotNull Object @NotNull [] arguments, int index, @NotNull String kind, int maximum) {
        int value = ScriptCoreUtils.readInteger(arguments, index);

        return value == ScriptCoreUtils.NO_ID ? ScriptCoreUtils.NO_ID : ScriptCoreUtils.requireId(kind, value, maximum + 1);
    }


    private final @NotNull ScriptGame scriptGame;
    private final @NotNull ScriptUnitCore unitCore;
    private final @NotNull ScriptSpriteCore spriteCore;
    private final @NotNull ScriptResources resources;

    /**
     * The resource handles by id.
     */
    private final @NotNull List<Object> handles;
    private final @NotNull Map<String, Integer> grpIds;
    private final @NotNull Map<String, Integer> animIds;
    private final @NotNull Map<String, Integer> imageIds;
    private final @NotNull Map<String, Integer> soundIds;
    private final @NotNull Map<String, Integer> sheetIds;
    /**
     * The handles of the graphics of sheets in the order of frame sets ({@code getSheetFrames}), by sheet handle and frames.
     */
    private final @NotNull Map<String, Integer> sheetFrameIds;
    /**
     * The decoded text resources by name.
     */
    private final @NotNull Map<String, String> texts;

    /**
     * The graphics of each sheet with its own frame order, made on first use.
     */
    private final @NotNull Map<PictureSheet, ImageGraphics> sheetGraphics;
    /**
     * The bytes the anims of each graphics of sheet frames take ({@link PictureSheetConverter#countAnimBytes}).
     */
    private final @NotNull Map<ImageGraphics, Long> sheetGraphicsBytes;
    /**
     * The graphics of sheet frames shared code was given and the bytes their anims take, the same on every peer.
     */
    private final @NotNull Set<ImageGraphics> sharedSheetGraphics;
    private long sharedSheetBytes;
    /**
     * The graphics of sheet frames shared or local code was given and the bytes their anims take.
     */
    private final @NotNull Set<ImageGraphics> countedSheetGraphics;
    private long sheetBytes;
    private @Nullable PictureSheetConverter converter;

    /**
     * Creates a {@link ScriptGraphicsCore} instance without resource handles.
     *
     * @param scriptGame the game binding
     * @param unitCore   the unit functions, which read unit arguments
     * @param spriteCore the sprite and image functions, which read image arguments
     * @param resources  the resources of the map
     */
    ScriptGraphicsCore(@NotNull ScriptGame scriptGame, @NotNull ScriptUnitCore unitCore, @NotNull ScriptSpriteCore spriteCore,
                       @NotNull ScriptResources resources) {
        this.scriptGame = scriptGame;
        this.unitCore   = unitCore;
        this.spriteCore = spriteCore;
        this.resources  = resources;

        this.handles       = new ArrayList<>();
        this.grpIds        = new HashMap<>();
        this.animIds       = new HashMap<>();
        this.imageIds      = new HashMap<>();
        this.soundIds      = new HashMap<>();
        this.sheetIds      = new HashMap<>();
        this.sheetFrameIds = new HashMap<>();
        this.texts         = new HashMap<>();

        this.sheetGraphics        = new HashMap<>();
        this.sheetGraphicsBytes   = new HashMap<>();
        this.sharedSheetGraphics  = new HashSet<>();
        this.sharedSheetBytes     = 0;
        this.countedSheetGraphics = new HashSet<>();
        this.sheetBytes           = 0;
        this.converter            = null;
    }

    /**
     * Defines the functions of {@code core.resources}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void defineResources(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "has", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);

            return this.resources.findEntry(name) != null;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getGrp", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);

            return (double) this.findHandle(this.grpIds, name, "GRP", () -> this.readGrp(name));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getAnim", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);

            return (double) this.findHandle(this.animIds, name, "anim", () -> this.readAnim(name));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getImage", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);

            return (double) this.findHandle(this.imageIds, name, "image", () -> PictureFileUtils.read(this.resources.read(this.requireEntry(name)),
                                                                                                      name));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getSound", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);

            return (double) this.findHandle(this.soundIds, name, "sound", () -> this.readSound(name));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "readText", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);
            String text = this.texts.get(name);

            if (text != null) {
                return text;
            }

            try {
                byte[] content = this.resources.read(this.requireEntry(name));
                CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                                               .onUnmappableCharacter(CodingErrorAction.REPORT);

                text = decoder.decode(ByteBuffer.wrap(content)).toString();
            } catch (CharacterCodingException exception) {
                throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The resource {0} is no UTF-8 text.", name));
            } catch (IOException exception) {
                String message = Exceptions.format("The resource {0} cannot be read: {1}", name, exception.getMessage());

                throw ScriptRuntime.constructError(ERROR_NAME, message);
            }

            if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
                text = text.substring(1);
            }

            this.texts.put(name, text);

            return text;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getSheet", 11, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);
            int[] layout = new int[SHEET_LAYOUT_ARGUMENTS];

            for (int index = 0; index < layout.length; index++) {
                layout[index] = ScriptCoreUtils.readInteger(arguments, index + 1);
            }

            String teamColor = readOptionalString(arguments, SHEET_TEAM_COLOR_ARGUMENT);
            String shadow = readOptionalString(arguments, SHEET_SHADOW_ARGUMENT);
            ImageGrp sd = this.readHandle(arguments, SHEET_SD_ARGUMENT, ImageGrp.class, "GRP");
            String key = name + '|' + Arrays.toString(layout) + '|' + teamColor + '|' + shadow + '|' + (sd == null ? null : sd.getName());

            return (double) this.findHandle(this.sheetIds, key, "sheet", () -> this.readSheet(name, layout, teamColor, shadow, sd));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getSheetFrames", 2, (context, scope, thisObject, arguments) -> {
            int sheetId = ScriptCoreUtils.readInteger(arguments, 0);
            PictureSheet sheet = this.readSheet(arguments, 0);
            int[] frames = expandFrameSets(sheet.getLayout(), ScriptCoreUtils.readIntegers(arguments, 1));
            String key = sheetId + "|" + Arrays.toString(frames);
            Integer id = this.sheetFrameIds.get(key);

            if (id == null) {
                id = this.handles.size();
                this.handles.add(this.createSheetGraphics(sheet, frames, this.createGrp(sheet, frames)));
                this.sheetFrameIds.put(key, id);
            } else {
                this.countSheetGraphics(sheet, (ImageGraphics) this.handles.get(id));
            }

            return (double) id;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getSheetFrameCount", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readSheet(arguments, 0).getLayout().getFrameCount());
        ScriptCoreUtils.defineFunction(global, namespace, "getSheetDirections", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readSheet(arguments, 0).getLayout().getDirections());
        ScriptCoreUtils.defineFunction(global, namespace, "getSheetWidth", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readSheet(arguments, 0).getLayout().getCanvasWidth());
        ScriptCoreUtils.defineFunction(global, namespace, "getSheetHeight", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readSheet(arguments, 0).getLayout().getCanvasHeight());
        ScriptCoreUtils.defineFunction(global, namespace, "getImageWidth", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readPicture(arguments, 0).getWidth());
        ScriptCoreUtils.defineFunction(global, namespace, "getImageHeight", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readPicture(arguments, 0).getHeight());
    }

    /**
     * Reads an image handle argument ({@code core.resources.getImage}).
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the picture of the image
     * @throws EcmaError if the argument is not an integer or names no image handle
     */
    @NotNull Picture readPicture(@NotNull Object @NotNull [] arguments, int index) {
        Picture picture = this.readOptionalPicture(arguments, index);

        if (picture == null) {
            throw ScriptRuntime.rangeError("An image handle is needed, not -1.");
        }

        return picture;
    }

    /**
     * Reads an image handle argument ({@code core.resources.getImage}) or {@code -1} for none.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the picture of the image, or {@code null} for {@code -1}
     * @throws EcmaError if the argument is not an integer or names no image handle
     */
    @Nullable Picture readOptionalPicture(@NotNull Object @NotNull [] arguments, int index) {
        return this.readHandle(arguments, index, Picture.class, "image");
    }

    /**
     * Reads a sheet handle argument ({@code core.resources.getSheet}).
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the sheet
     * @throws EcmaError if the argument is not an integer or names no sheet handle
     */
    @NotNull PictureSheet readSheet(@NotNull Object @NotNull [] arguments, int index) {
        PictureSheet sheet = this.readHandle(arguments, index, PictureSheet.class, "sheet");

        if (sheet == null) {
            throw ScriptRuntime.rangeError("A sheet handle is needed, not -1.");
        }

        return sheet;
    }

    /**
     * Returns the converter of sheets to the GRPs of the map's tileset palette, made on first use.
     *
     * @return the converter
     * @throws EcmaError if the game has no game data or the palette cannot be read
     */
    @NotNull PictureSheetConverter getConverter() {
        PictureSheetConverter current = this.converter;

        if (current == null) {
            Game game = this.scriptGame.getGame();
            GameData gameData = game.getGameData();

            if (gameData == null) {
                throw ScriptRuntime.typeError("The game has no game data to convert a sheet with.");
            }

            try {
                current = new PictureSheetConverter(gameData.loadTerrainPalette(game.getMap().getTileset()));
            } catch (IOException exception) {
                throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The tileset palette cannot be read: {0}", exception.getMessage()));
            }

            this.converter = current;
        }

        return current;
    }

    /**
     * Creates the graphics of frames of a sheet: its GRP ({@link PictureSheetConverter#createGrp}) and the anims built when first drawn.
     *
     * @param sheet  the sheet
     * @param frames the sheet frame of each frame of the graphics
     * @param grp    the GRP bytes of the frames
     * @return the graphics
     * @throws EcmaError if the GRP of the shadow picture cannot be made or a GRP cannot be read
     */
    @NotNull ImageGraphics createSheetGraphics(@NotNull PictureSheet sheet, int @NotNull [] frames, byte @NotNull [] grp) {
        ImageGraphics graphics;

        try {
            byte[] shadowGrp = sheet.hasShadow() ? this.getConverter().createShadowGrp(sheet, frames) : null;

            graphics = this.getReplacements().createSheetGraphics(sheet, frames, grp, shadowGrp);
        } catch (IOException | IllegalArgumentException exception) {
            throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The sheet {0} cannot be converted: {1}", sheet.getName(),
                                                                             exception.getMessage()));
        }

        this.sheetGraphicsBytes.put(graphics, PictureSheetConverter.countAnimBytes(sheet.getLayout(), frames, sheet.hasTeamColor(),
                                                                                   sheet.hasShadow()));
        this.countSheetGraphics(sheet, graphics);

        return graphics;
    }

    /**
     * Creates the GRP of frames of a sheet ({@link PictureSheetConverter#createGrp}).
     *
     * @param sheet  the sheet
     * @param frames the sheet frame of each GRP frame
     * @return the GRP bytes
     * @throws EcmaError if the SD frames of the sheet cannot be decoded
     */
    byte @NotNull [] createGrp(@NotNull PictureSheet sheet, int @NotNull [] frames) {
        try {
            return this.getConverter().createGrp(sheet, frames);
        } catch (IOException exception) {
            throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The sheet {0} cannot be converted: {1}", sheet.getName(),
                                                                             exception.getMessage()));
        }
    }

    /**
     * Reads a sound handle argument ({@code core.resources.getSound}).
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the sound file of the handle
     * @throws EcmaError if the argument is not an integer or names no sound handle
     */
    byte @NotNull [] readSoundFile(@NotNull Object @NotNull [] arguments, int index) {
        byte[] file = this.readHandle(arguments, index, byte[].class, "sound");

        if (file == null) {
            throw ScriptRuntime.rangeError("A sound handle is needed, not -1.");
        }

        return file;
    }

    /**
     * Defines the functions of {@code core.graphics}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void defineGraphics(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "replaceImage", 4, (context, scope, thisObject, arguments) -> {
            int imageType = this.readImageType(arguments, 0);
            ImageGraphics graphics = this.readGraphics(arguments, 1);

            this.getReplacements().replaceImage(imageType, graphics);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreImage", 1, (context, scope, thisObject, arguments) -> {
            this.getReplacements().restoreImage(this.readImageType(arguments, 0));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "replaceUnitImage", 5, (context, scope, thisObject, arguments) -> {
            Unit unit = this.unitCore.readUnit(arguments, 0);
            int imageType = this.readImageType(arguments, 1);
            ImageGraphics graphics = this.readGraphics(arguments, 2);

            this.getReplacements().replaceUnitImage(unit, imageType, graphics);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreUnitImage", 2, (context, scope, thisObject, arguments) -> {
            Unit unit = this.unitCore.readUnit(arguments, 0);

            this.getReplacements().restoreUnitImage(unit, this.readImageType(arguments, 1));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "replaceImageShadow", 4, (context, scope, thisObject, arguments) -> {
            int imageType = this.readImageType(arguments, 0);

            this.getReplacements().setTypeShadow(imageType, this.readPartGraphics(arguments, 1));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreImageShadow", 1, (context, scope, thisObject, arguments) -> {
            this.getReplacements().setTypeShadow(this.readImageType(arguments, 0), null);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "replaceImageRemains", 4, (context, scope, thisObject, arguments) -> {
            int imageType = this.readImageType(arguments, 0);

            this.getReplacements().setTypeRemains(imageType, this.readPartGraphics(arguments, 1));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreImageRemains", 1, (context, scope, thisObject, arguments) -> {
            this.getReplacements().setTypeRemains(this.readImageType(arguments, 0), null);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "replaceUnitShadow", 4, (context, scope, thisObject, arguments) -> {
            Unit unit = this.unitCore.readUnit(arguments, 0);

            this.getReplacements().setUnitShadow(unit, this.readPartGraphics(arguments, 1));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreUnitShadow", 1, (context, scope, thisObject, arguments) -> {
            this.getReplacements().setUnitShadow(this.unitCore.readUnit(arguments, 0), null);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "replaceUnitRemains", 4, (context, scope, thisObject, arguments) -> {
            Unit unit = this.unitCore.readUnit(arguments, 0);

            this.getReplacements().setUnitRemains(unit, this.readPartGraphics(arguments, 1));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreUnitRemains", 1, (context, scope, thisObject, arguments) -> {
            this.getReplacements().setUnitRemains(this.unitCore.readUnit(arguments, 0), null);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "replaceImageGraphics", 4, (context, scope, thisObject, arguments) -> {
            SpriteImage image = this.spriteCore.readImage(arguments, 0);
            ImageGraphics graphics = this.readGraphics(arguments, 1);

            this.getReplacements().replaceImageGraphics(image, graphics);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreImageGraphics", 1, (context, scope, thisObject, arguments) -> {
            this.getReplacements().restoreImageGraphics(this.spriteCore.readImage(arguments, 0));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setImageFrame", 2, (context, scope, thisObject, arguments) -> {
            SpriteImage image = this.spriteCore.readImage(arguments, 0);
            int frame = readOptionalValue(arguments, 1, "frame", MAXIMUM_FRAME);

            this.getReplacements().setImageFrame(image, frame);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "holdImageFrame", 2, (context, scope, thisObject, arguments) -> {
            SpriteImage image = this.spriteCore.readImage(arguments, 0);
            int frame = readOptionalValue(arguments, 1, "frame", MAXIMUM_FRAME);

            this.getReplacements().holdImageFrame(image, frame);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "playImage", 7, (context, scope, thisObject, arguments) -> {
            SpriteImage image = this.spriteCore.readImage(arguments, 0);
            int[] frameSets = ScriptCoreUtils.readIntegers(arguments, 1);
            int frameTime = ScriptCoreUtils.readInteger(arguments, 2);
            boolean loop = ScriptCoreUtils.readBoolean(arguments, 3);
            boolean own = ScriptCoreUtils.readInteger(arguments, 4) == ScriptCoreUtils.NO_ID &&
                          ScriptCoreUtils.readInteger(arguments, 5) == ScriptCoreUtils.NO_ID &&
                          ScriptCoreUtils.readInteger(arguments, 6) == ScriptCoreUtils.NO_ID;
            ImageGraphics graphics = own ? null : this.readGraphics(arguments, 4);

            try {
                this.getReplacements().playImage(image, frameSets, frameTime, loop, graphics);
            } catch (IllegalArgumentException exception) {
                throw ScriptRuntime.rangeError(exception.getMessage());
            }

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "stopPlayingImage", 1, (context, scope, thisObject, arguments) -> {
            this.getReplacements().stopPlayingImage(this.spriteCore.readImage(arguments, 0));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setImageDrawFunction", 2, (context, scope, thisObject, arguments) -> {
            SpriteImage image = this.spriteCore.readImage(arguments, 0);
            int drawFunction = readOptionalValue(arguments, 1, "draw function", ClassicDatRegion.DRAW_FUNCTION_LIMIT - 1);

            this.getReplacements().setImageDrawFunction(image, drawFunction);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Reads an image type argument.
     *
     * @throws EcmaError if the argument is not an integer or not an image type, the registered ones included
     */
    private int readImageType(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("image type", ScriptCoreUtils.readInteger(arguments, index), this.scriptGame.getContentCore().getImageTypeCount());
    }

    private @NotNull ImageReplacements getReplacements() {
        return this.scriptGame.getImageReplacements();
    }


    /**
     * Reads the three handle arguments of graphics: a GRP, an HD anim and an HD2 anim, each {@code -1} for none; a sheet handle or a handle of
     * sheet frames ({@code getSheetFrames}) in place of the GRP, with both anims {@code -1}.
     *
     * @throws EcmaError if an argument is not an integer or names no handle of its kind, all three are {@code -1}, the HD anim is an HD2 anim or the HD2 anim an HD anim
     */
    private @NotNull ImageGraphics readGraphics(@NotNull Object @NotNull [] arguments, int index) {
        int first = ScriptCoreUtils.readInteger(arguments, index);
        Object handle = first >= 0 && first < this.handles.size() ? this.handles.get(first) : null;

        if (handle instanceof PictureSheet || handle instanceof ImageGraphics) {
            if (ScriptCoreUtils.readInteger(arguments, index + 1) != ScriptCoreUtils.NO_ID ||
                ScriptCoreUtils.readInteger(arguments, index + 2) != ScriptCoreUtils.NO_ID) {
                throw ScriptRuntime.rangeError("A sheet is given without anims.");
            }

            return handle instanceof ImageGraphics graphics ? graphics : this.findSheetGraphics((PictureSheet) handle);
        }

        ImageGrp grp = this.readHandle(arguments, index, ImageGrp.class, "GRP");
        ImageAnim hdAnim = this.readHandle(arguments, index + 1, ImageAnim.class, "anim");
        ImageAnim hd2Anim = this.readHandle(arguments, index + 2, ImageAnim.class, "anim");

        try {
            return ImageGraphics.of(grp, hdAnim, hd2Anim);
        } catch (IllegalArgumentException exception) {
            throw ScriptRuntime.rangeError(exception.getMessage());
        }
    }

    /**
     * Reads the three handle arguments of the graphics of a shadow or of remains ({@link #readGraphics}); all three {@code -1} draw nothing.
     *
     * @throws EcmaError if an argument is not an integer or names no handle of its kind, the HD anim is an HD2 anim or the HD2 anim an HD anim
     */
    private @NotNull ImageGraphics readPartGraphics(@NotNull Object @NotNull [] arguments, int index) {
        if (ScriptCoreUtils.readInteger(arguments, index) == ScriptCoreUtils.NO_ID &&
            ScriptCoreUtils.readInteger(arguments, index + 1) == ScriptCoreUtils.NO_ID &&
            ScriptCoreUtils.readInteger(arguments, index + 2) == ScriptCoreUtils.NO_ID) {
            return ImageGraphics.HIDDEN;
        }

        return this.readGraphics(arguments, index);
    }

    /**
     * Finds the graphics of a sheet in its own frame order, made on first use.
     */
    private @NotNull ImageGraphics findSheetGraphics(@NotNull PictureSheet sheet) {
        ImageGraphics graphics = this.sheetGraphics.get(sheet);

        if (graphics == null) {
            int[] frames = new int[sheet.getLayout().getFrameCount()];

            for (int frame = 0; frame < frames.length; frame++) {
                frames[frame] = frame;
            }

            graphics = this.createSheetGraphics(sheet, frames, this.createGrp(sheet, frames));
            this.sheetGraphics.put(sheet, graphics);
        } else {
            this.countSheetGraphics(sheet, graphics);
        }

        return graphics;
    }

    /**
     * Counts the anim bytes of graphics of sheet frames a call gives, once per graphics, by the distinct sheet frames they show, so the frames of a
     * sheet no graphics shows take nothing: shared code counts the graphics shared code was given, so its budget check is the same on every
     * peer; local code counts all graphics.
     *
     * @throws EcmaError if the anims would exceed the budget
     */
    private void countSheetGraphics(@NotNull PictureSheet sheet, @NotNull ImageGraphics graphics) {
        long bytes = this.sheetGraphicsBytes.get(graphics);
        boolean local = this.scriptGame.isLocal();

        if (local ? !this.countedSheetGraphics.contains(graphics) && this.sheetBytes + bytes > ANIMATION_BUDGET
                  : !this.sharedSheetGraphics.contains(graphics) && this.sharedSheetBytes + bytes > ANIMATION_BUDGET) {
            throw ScriptRuntime.rangeError(Exceptions.format("The sheet {0} would take the anims of the map past {1} MiB.", sheet.getName(),
                                                             ANIMATION_BUDGET_MEBIBYTES));
        }

        if (!local && this.sharedSheetGraphics.add(graphics)) {
            this.sharedSheetBytes += bytes;
        }

        if (this.countedSheetGraphics.add(graphics)) {
            this.sheetBytes += bytes;
        }
    }

    /**
     * Reads a picture as a sheet after checking its layout.
     *
     * @throws IOException if a picture cannot be read
     * @throws EcmaError   if the layout does not fit the picture
     */
    private @NotNull PictureSheet readSheet(@NotNull String name, int @NotNull [] layout, @Nullable String teamColor, @Nullable String shadow,
                                            @Nullable ImageGrp sd) throws IOException {
        Picture picture = PictureFileUtils.read(this.resources.read(this.requireEntry(name)), name);
        Picture teamPicture = teamColor == null ? null : PictureFileUtils.read(this.resources.read(this.requireEntry(teamColor)), teamColor);
        Picture shadowPicture = shadow == null ? null : PictureFileUtils.read(this.resources.read(this.requireEntry(shadow)), shadow);
        GrpFile sdFrames = sd == null ? null : GrpFile.parse(this.resources.read(this.requireEntry(sd.getName())));
        PictureSheet sheet;

        try {
            SheetLayout sheetLayout = SheetLayout.create(picture.getWidth(), picture.getHeight(), layout[0], layout[1], layout[2], layout[3],
                                                         layout[SHEET_SCALE], layout[SHEET_ANCHOR_X], layout[SHEET_ANCHOR_Y]);

            sheet = PictureSheet.create(name, picture, sheetLayout, teamPicture, shadowPicture, sdFrames);
        } catch (IllegalArgumentException exception) {
            throw ScriptRuntime.rangeError(Exceptions.format("The sheet {0} does not fit: {1}", name, exception.getMessage()));
        }

        return sheet;
    }

    /**
     * Reads a handle argument of a kind.
     *
     * @return the handle's resource, or {@code null} for {@code -1}
     * @throws EcmaError if the argument is not an integer or names no handle of the kind
     */
    private <T> @Nullable T readHandle(@NotNull Object @NotNull [] arguments, int index, @NotNull Class<T> kind, @NotNull String kindName) {
        int id = ScriptCoreUtils.readInteger(arguments, index);

        if (id == ScriptCoreUtils.NO_ID) {
            return null;
        }

        Object handle = id >= 0 && id < this.handles.size() ? this.handles.get(id) : null;

        if (!kind.isInstance(handle)) {
            throw ScriptRuntime.rangeError(Exceptions.format("The resource handle {0} is no {1}.", id, kindName));
        }

        return kind.cast(handle);
    }

    /**
     * Finds the handle of a resource of a kind, reading the resource on its first use.
     *
     * @param ids      the handle ids of the kind by resource name
     * @param name     the resource name
     * @param kindName the kind for error messages
     * @param read     reads the resource
     * @return the handle id
     * @throws EcmaError if the resource is missing or cannot be read as the kind
     */
    private int findHandle(@NotNull Map<String, Integer> ids, @NotNull String name, @NotNull String kindName, @NotNull ResourceReader read) {
        Integer id = ids.get(name);

        if (id != null) {
            return id;
        }

        Object handle;

        try {
            handle = read.read();
        } catch (IOException exception) {
            throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The resource {0} is no valid {1}: {2}", name, kindName,
                                                                             exception.getMessage()));
        }

        int newId = this.handles.size();

        this.handles.add(handle);
        ids.put(name, newId);

        return newId;
    }

    private @NotNull ImageGrp readGrp(@NotNull String name) throws IOException {
        byte[] content = this.resources.read(this.requireEntry(name));

        return this.getReplacements().readGrp(content, 0, content.length, name);
    }

    private @NotNull ImageAnim readAnim(@NotNull String name) throws IOException {
        byte[] content = this.resources.read(this.requireEntry(name));

        return this.getReplacements().readAnim(content, 0, content.length, name);
    }

    /**
     * Reads a sound file and checks that it decodes.
     *
     * @param name the resource name
     * @return the sound file
     * @throws IOException if the file is no sound file that decodes
     */
    private byte @NotNull [] readSound(@NotNull String name) throws IOException {
        byte[] content = this.resources.read(this.requireEntry(name));

        WaveData.decode(content);

        return content;
    }

    /**
     * Finds a resource of the map.
     *
     * @throws EcmaError if the map has no resource of the name
     */
    private @NotNull ScriptResources.Entry requireEntry(@NotNull String name) {
        ScriptResources.Entry entry = this.resources.findEntry(name);

        if (entry == null) {
            throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The map has no resource {0}.", name));
        }

        return entry;
    }
}
