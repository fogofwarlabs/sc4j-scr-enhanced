package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrImages;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImagePool;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpritePool;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The image graphics a game's simulation replaces ({@link ImageGraphics}): per image type, for every image of the type, and per unit and image
 * type, for the images of that type that belong to one unit. An image belongs to a unit when it is an image of the unit's sprite or of the
 * sprite of the unit's subunit; it is resolved when the image is drawn, so the images a unit gets later, as when it sieges, burrows or shows an
 * overlay, draw the unit's graphics too. A unit's graphics win over those of the image type.
 * <p>
 * A unit's graphics last while its unit pool slot holds the same unit (the slot is allocated and keeps its generation), its death animation
 * included; {@link #removeFreedUnits()} forgets those of freed slots. The replacements change only what is drawn, never the simulation.
 * <p>
 * One image of the image pool takes graphics, a frame and a draw function of its own while its slot holds the same image (the slot keeps its
 * generation, {@link SpriteImagePool#getGeneration(int)}). Its graphics win over those of its unit and of its image type. Its frame and its
 * draw function last until the image's own drawn frame, or its own draw function, changes: the next change of the image's own state replaces
 * them, as for an EUD write of the image's entry ({@link ClassicImageGraphics}). A held frame ({@link #holdImageFrame}) stays through those
 * changes until it is released.
 * <p>
 * The GRPs and anims are read from byte ranges that are not copied ({@link #readGrp}, {@link #readAnim}); the parsed anims of the game are kept
 * within {@code 256} MiB, as those of the classic image graphics.
 * <p>
 * One image may play frame sets ({@link #playImage}): from the game frame of the call, each frame set is drawn for a number of game frames, of
 * the image's own graphics or of other graphics, then the image draws its own frame and graphics again, or the frame sets start over for a
 * looping play. The image's direction is added to each frame set of graphics of {@value SheetLayout#TURNING_DIRECTIONS} directions. An image type
 * may loop frame sets the same way for every image of the type that draws its own frame ({@link #setTypeAnimation}). A shadow image of a sprite
 * whose main image draws graphics made from a sheet ({@link ImageGraphics#isSheet()}) draws the sheet's shadow graphics, else the main image's
 * graphics, at the frame the main image draws.
 * <p>
 * A unit, and an image type, may also give graphics to its shadow and its remains, {@link ImageGraphics#HIDDEN} to draw nothing. The shadow is
 * every image of a sprite that draws with the shadow draw function while the sprite's main image is another one: a unit's shadow graphics apply
 * in the sprites of the unit, an image type's in the sprites whose main image is of the type, the unit's winning. The remains are what a unit
 * shows after it died ({@link #onUnitDied}): the images its sprite gets from then on, as the images of its death animation, and the sprites those
 * images create with their iscript ({@link #onSpriteCreated}), and the sprites these create in turn; the unit's remains graphics apply, else
 * those of the image type of its sprite's main image when it died. A sprite of remains keeps its graphics while its sprite pool slot holds it.
 */
public final class ImageReplacements {
    /**
     * The value of no frame and no draw function.
     */
    private static final int NONE = -1;
    /**
     * The frames of a frame set of an image that turns.
     */
    private static final int TURNING_DIRECTIONS = SheetLayout.TURNING_DIRECTIONS;
    /**
     * The image generation of a sprite of remains whose every image draws the remains graphics.
     */
    private static final long EVERY_IMAGE = -1;

    /**
     * Frame sets drawn one after another, each for a number of game frames from a start frame.
     */
    private static final class Playback {
        private final int @NotNull [] frameSets;
        private final int frameTime;
        private final int startFrame;
        private final boolean loop;
        private final @Nullable ImageGraphics graphics;

        private Playback(int @NotNull [] frameSets, int frameTime, int startFrame, boolean loop, @Nullable ImageGraphics graphics) {
            this.frameSets  = frameSets;
            this.frameTime  = frameTime;
            this.startFrame = startFrame;
            this.loop       = loop;
            this.graphics   = graphics;
        }

        /**
         * Finds the frame set drawn at a game frame.
         *
         * @param frame the game frame
         * @return the frame set, or {@code -1} once a play that does not loop has ended
         */
        private int findFrameSet(int frame) {
            int index = Math.max(0, frame - this.startFrame) / this.frameTime;

            if (index >= this.frameSets.length) {
                if (!this.loop) {
                    return NONE;
                }

                index %= this.frameSets.length;
            }

            return this.frameSets[index];
        }
    }

    /**
     * The graphics of one unit's images of one image type.
     */
    private static final class UnitReplacement {
        private final @NotNull Unit unit;
        private final int generation;
        private final int imageTypeId;

        private @NotNull ImageGraphics graphics;

        private UnitReplacement(@NotNull Unit unit, int generation, int imageTypeId, @NotNull ImageGraphics graphics) {
            this.unit        = unit;
            this.generation  = generation;
            this.imageTypeId = imageTypeId;

            this.graphics = graphics;
        }
    }

    /**
     * The graphics of one unit's shadow and remains.
     */
    private static final class UnitParts {
        private final @NotNull Unit unit;
        private final int generation;

        /**
         * The shadow graphics, {@code null} for the installed ones.
         */
        private @Nullable ImageGraphics shadow;
        /**
         * The remains graphics, {@code null} for the installed ones.
         */
        private @Nullable ImageGraphics remains;

        private UnitParts(@NotNull Unit unit, int generation) {
            this.unit       = unit;
            this.generation = generation;

            this.shadow  = null;
            this.remains = null;
        }
    }

    /**
     * A sprite whose images draw the remains graphics of a unit.
     */
    private static final class RemainsSprite {
        private final long generation;
        /**
         * The image pool generation when the unit died: the images of a later generation draw the remains graphics; {@link #EVERY_IMAGE} for a
         * sprite an image of the remains created, whose every image does.
         */
        private final long imageGeneration;
        private final @NotNull ImageGraphics graphics;

        private RemainsSprite(long generation, long imageGeneration, @NotNull ImageGraphics graphics) {
            this.generation      = generation;
            this.imageGeneration = imageGeneration;
            this.graphics        = graphics;
        }
    }

    /**
     * The graphics, frame and draw function of one image of the image pool.
     */
    private static final class ImageOverride {
        private final @NotNull SpriteImagePool pool;
        private final long generation;

        private @Nullable ImageGraphics graphics;
        /**
         * The frame drawn instead of the image's own, {@code -1} for none.
         */
        private int frame;
        /**
         * The image's own drawn frame when the frame was set.
         */
        private int ownFrame;
        /**
         * Whether the frame stays when the image's own drawn frame changes.
         */
        private boolean frameHeld;
        /**
         * The draw function drawn with instead of the image's own, {@code -1} for none.
         */
        private int drawFunction;
        /**
         * The image's own draw function when the draw function was set.
         */
        private int ownDrawFunction;
        /**
         * The frame sets the image plays, {@code null} for none.
         */
        private @Nullable Playback playback;

        private ImageOverride(@NotNull SpriteImagePool pool, long generation) {
            this.pool       = pool;
            this.generation = generation;

            this.graphics        = null;
            this.frame           = NONE;
            this.ownFrame        = 0;
            this.frameHeld       = false;
            this.drawFunction    = NONE;
            this.ownDrawFunction = 0;
            this.playback        = null;
        }

        /**
         * @return {@code true} if the override has no graphics, frame, draw function and play, {@code false} otherwise
         */
        private boolean isEmpty() {
            return this.graphics == null && this.frame == NONE && this.drawFunction == NONE && this.playback == null;
        }
    }


    /**
     * Checks whether a replacement's unit is still in its slot.
     *
     * @param pool        the game's unit pool
     * @param replacement the replacement
     * @return {@code true} if the slot is allocated and keeps the generation of the replacement's unit, {@code false} otherwise
     */
    private static boolean isHeld(@NotNull UnitPool pool, @NotNull UnitReplacement replacement) {
        int index = replacement.unit.getIndex();

        return pool.isAllocated(index) && pool.getGeneration(index) == replacement.generation;
    }

    /**
     * Checks whether the shadow and remains graphics of a unit belong to the unit still in its slot.
     *
     * @param pool  the game's unit pool
     * @param parts the graphics
     * @return {@code true} if the slot is allocated and keeps the generation of the unit, {@code false} otherwise
     */
    private static boolean isHeld(@NotNull UnitPool pool, @NotNull UnitParts parts) {
        int index = parts.unit.getIndex();

        return pool.isAllocated(index) && pool.getGeneration(index) == parts.generation;
    }

    /**
     * Checks whether a sprite of remains is still in its slot.
     *
     * @param pool    the game's sprite pool
     * @param slot    the sprite pool slot
     * @param remains the sprite of remains
     * @return {@code true} if the slot is allocated and keeps the generation of the sprite, {@code false} otherwise
     */
    private static boolean isHeld(@NotNull SpritePool pool, int slot, @NotNull RemainsSprite remains) {
        return slot < pool.getCapacity() && pool.isAllocated(slot) && pool.getGeneration(slot) == remains.generation;
    }

    private static @NotNull Playback createPlayback(int @NotNull [] frameSets, int frameTime, int startFrame, boolean loop,
                                                    @Nullable ImageGraphics graphics) {
        if (frameSets.length == 0) {
            throw Exceptions.newIllegalArgumentException("A play needs a frame set.");
        }

        if (frameTime < 1) {
            throw Exceptions.newIllegalArgumentException("The frame time {0} is below 1.", frameTime);
        }

        for (int frameSet : frameSets) {
            if (frameSet < 0) {
                throw Exceptions.newIllegalArgumentException("The frame set {0} is negative.", frameSet);
            }
        }

        return new Playback(frameSets.clone(), frameTime, startFrame, loop, graphics);
    }

    /**
     * Checks whether a sprite is the sprite of a unit or of its subunit.
     */
    private static boolean isUnitSprite(@NotNull Unit unit, @NotNull Sprite sprite) {
        if (unit.getSpriteOrNull() == sprite) {
            return true;
        }

        Unit subunit = unit.getSubunit();

        return subunit != null && subunit.getSpriteOrNull() == sprite;
    }


    private final @NotNull Supplier<@Nullable UnitPool> units;
    private final @NotNull Supplier<@Nullable SpritePool> sprites;
    private final @NotNull IntSupplier frames;
    private final @NotNull IntPredicate turning;
    private final EmbeddedAnim.@NotNull Cache animations;

    /**
     * The graphics per image type, {@code null} for the installed graphics.
     */
    private final @Nullable ImageGraphics @NotNull [] typeGraphics;
    /**
     * The frame sets every image of an image type loops, by image type; {@code null} for none.
     */
    private final @Nullable Playback @NotNull [] typeAnimations;
    /**
     * The shadow graphics per image type of the main image, {@code null} for the installed ones.
     */
    private final @Nullable ImageGraphics @NotNull [] typeShadows;
    /**
     * The remains graphics per image type of the main image, {@code null} for the installed ones.
     */
    private final @Nullable ImageGraphics @NotNull [] typeRemains;
    private int typeRemainsCount;
    private final @NotNull List<UnitReplacement> unitReplacements;
    private final @NotNull List<UnitParts> unitParts;
    /**
     * The sprites of remains by sprite pool slot.
     */
    private final @NotNull Map<Integer, RemainsSprite> remainsSprites;
    /**
     * The overrides of single images by image pool slot.
     */
    private final @NotNull Map<Integer, ImageOverride> imageOverrides;

    /**
     * Creates an {@link ImageReplacements} instance without replacements over the native image types, whose plays stay at their first frame set.
     *
     * @param units the game's unit pool, {@code null} before the game has one
     */
    public ImageReplacements(@NotNull Supplier<@Nullable UnitPool> units) {
        this(units, () -> null, () -> 0, ScrImages.COUNT, imageTypeId -> false);
    }

    /**
     * Creates an {@link ImageReplacements} instance without replacements.
     *
     * @param units          the game's unit pool, {@code null} before the game has one
     * @param sprites        the game's sprite pool, which holds the sprites of remains; {@code null} before the game has one
     * @param frames         the game's current frame, which times the plays
     * @param imageTypeCount the number of image types that take graphics, the native ones first
     * @param turning        tells whether an image type has Graphic Turns, which makes graphics of unknown frame sets turn
     */
    public ImageReplacements(@NotNull Supplier<@Nullable UnitPool> units, @NotNull Supplier<@Nullable SpritePool> sprites,
                             @NotNull IntSupplier frames, int imageTypeCount, @NotNull IntPredicate turning) {
        this.units      = units;
        this.sprites    = sprites;
        this.frames     = frames;
        this.turning    = turning;
        this.animations = new EmbeddedAnim.Cache(ClassicImageGraphics.ANIMATION_BUDGET);

        this.typeGraphics     = new ImageGraphics[imageTypeCount];
        this.typeAnimations   = new Playback[imageTypeCount];
        this.typeShadows      = new ImageGraphics[imageTypeCount];
        this.typeRemains      = new ImageGraphics[imageTypeCount];
        this.typeRemainsCount = 0;
        this.unitReplacements = new ArrayList<>();
        this.unitParts        = new ArrayList<>();
        this.remainsSprites   = new HashMap<>();
        this.imageOverrides   = new HashMap<>();
    }

    /**
     * Reads a GRP file from a byte range ({@link ImageGrp#read}).
     *
     * @param data   the bytes, not copied and not changed afterwards
     * @param offset the first byte of the GRP file
     * @param length the size of the GRP file
     * @param name   the name of the GRP for messages
     * @return the read {@link ImageGrp} instance
     * @throws IOException               if the bytes are no GRP header with a readable frame table or a frame cannot be decoded
     * @throws IndexOutOfBoundsException if the range lies outside the bytes
     */
    public @NotNull ImageGrp readGrp(byte @NotNull [] data, int offset, int length, @NotNull String name) throws IOException {
        return ImageGrp.read(data, offset, length, name);
    }

    /**
     * Reads an anim from a byte range after checking that it is a single-entry anim of asset scale {@code 4} or {@code 2} with a canvas and a
     * block-compressed {@code diffuse} layer whose textures parse; the texture sheet drawn is parsed again when the anim is first drawn.
     *
     * @param data   the bytes, not copied and not changed afterwards
     * @param offset the first byte of the anim
     * @param length the size of the anim
     * @param name   the name of the anim for messages
     * @return the read {@link ImageAnim} instance
     * @throws IOException               if the bytes are no such anim
     * @throws IndexOutOfBoundsException if the range lies outside the bytes
     */
    public @NotNull ImageAnim readAnim(byte @NotNull [] data, int offset, int length, @NotNull String name) throws IOException {
        int scale;

        try {
            scale = EmbeddedAnim.check(Arrays.copyOfRange(data, offset, Math.addExact(offset, length)));
        } catch (IOException | RuntimeException exception) {
            throw Exceptions.newIOException(exception, "The anim {0} is no single-entry HD or HD2 anim with frames on a canvas and a diffuse layer.",
                                            name);
        }

        return new ImageAnim(name, scale, new EmbeddedAnim(EmbeddedMemory.of(data, offset, length), 0, this.animations));
    }

    /**
     * Creates the graphics of frames of a sheet ({@link ImageGraphics#ofSheet}): the GRP made already, and an anim built on a worker thread when
     * it is first drawn ({@link PictureSheetConverter#createAnim}), whose parsed sheet is kept within the anim budget of the game.
     *
     * @param sheet     the sheet
     * @param frames    the sheet frame of each frame of the graphics
     * @param grp       the GRP bytes of the frames ({@link PictureSheetConverter#createGrp})
     * @param shadowGrp the GRP bytes of the frames of the shadow picture, or {@code null} for a sheet without
     * @return the created graphics
     * @throws IOException if a GRP cannot be read
     */
    public @NotNull ImageGraphics createSheetGraphics(@NotNull PictureSheet sheet, int @NotNull [] frames, byte @NotNull [] grp,
                                                      byte @Nullable [] shadowGrp) throws IOException {
        int scale = sheet.getLayout().getScale() == EmbeddedAnim.HD_SCALE ? EmbeddedAnim.HD_SCALE : EmbeddedAnim.HD2_SCALE;
        int[] copied = frames.clone();
        ImageGraphics shadow = null;

        if (shadowGrp != null) {
            ImageAnim shadowAnim = new ImageAnim(sheet.getName(), scale,
                                                 new EmbeddedAnim(() -> PictureSheetConverter.createAnim(sheet, copied, true), this.animations));

            shadow = ImageGraphics.ofSheet(ImageGrp.read(shadowGrp, 0, shadowGrp.length, sheet.getName()), shadowAnim,
                                           sheet.getLayout().getDirections(), null);
        }

        ImageAnim anim = new ImageAnim(sheet.getName(), scale,
                                       new EmbeddedAnim(() -> PictureSheetConverter.createAnim(sheet, copied, false), this.animations));

        return ImageGraphics.ofSheet(ImageGrp.read(grp, 0, grp.length, sheet.getName()), anim, sheet.getLayout().getDirections(), shadow);
    }

    private int requireImageTypeId(int imageTypeId) {
        if (imageTypeId < 0 || imageTypeId >= this.typeGraphics.length) {
            throw Exceptions.newIllegalArgumentException("The image type {0} is not from 0 to {1}.", imageTypeId, this.typeGraphics.length - 1);
        }

        return imageTypeId;
    }

    /**
     * Finds the graphics of an image type.
     *
     * @param imageTypeId the image type, an {@code images.dat} entry
     * @return the graphics, or {@code null} for the installed graphics
     * @throws IllegalArgumentException if the image type is outside {@code images.dat}
     */
    public @Nullable ImageGraphics findImageGraphics(int imageTypeId) {
        return this.typeGraphics[this.requireImageTypeId(imageTypeId)];
    }

    /**
     * Finds the graphics of one unit's images of an image type.
     *
     * @param unit        the unit
     * @param imageTypeId the image type, an {@code images.dat} entry
     * @return the graphics, or {@code null} if the unit's slot holds another unit or none, or the unit has no graphics of the image type
     * @throws IllegalArgumentException if the image type is outside {@code images.dat}
     */
    public @Nullable ImageGraphics findUnitImageGraphics(@NotNull Unit unit, int imageTypeId) {
        this.requireImageTypeId(imageTypeId);

        UnitReplacement replacement = this.findUnitReplacement(unit, imageTypeId);

        return replacement == null ? null : replacement.graphics;
    }

    /**
     * Finds the frame a sprite image draws instead of its own: the frame held for it ({@link #holdImageFrame}), or the frame set for it
     * ({@link #setImageFrame}) while its own drawn frame is the one it had then.
     *
     * @param image an image of a drawn sprite
     * @return the frame index, or {@code -1} for the image's own frame
     */
    public int findImageFrame(@NotNull SpriteImage image) {
        ImageOverride override = this.findImageOverride(image);

        if (override != null && override.playback != null) {
            int frame = this.findPlaybackFrame(image, override.playback);

            if (frame >= 0) {
                return frame;
            }
        }

        if (override != null && override.frame != NONE && (override.frameHeld || override.ownFrame == image.getDrawnFrameIndex())) {
            return override.frame;
        }

        int imageTypeId = image.getImageTypeId();
        Playback animation = imageTypeId >= 0 && imageTypeId < this.typeAnimations.length ? this.typeAnimations[imageTypeId] : null;

        if (animation != null) {
            return this.findPlaybackFrame(image, animation);
        }

        SpriteImage main = this.findShadowMain(image);

        return main == null ? NONE : this.findImageFrame(main);
    }

    /**
     * Finds the frame a play draws now: its frame set, plus the image's direction for graphics of turning frame sets.
     *
     * @return the frame, or {@code -1} once a play that does not loop has ended
     */
    private int findPlaybackFrame(@NotNull SpriteImage image, @NotNull Playback playback) {
        int frameSet = playback.findFrameSet(this.frames.getAsInt());

        if (frameSet < 0) {
            return NONE;
        }

        ImageGraphics graphics = playback.graphics != null ? playback.graphics : this.findOwnGraphics(image);
        int directions = graphics != null && graphics.getDirections() != 0 ? graphics.getDirections()
                                                                           : this.turning.test(image.getImageTypeId()) ? TURNING_DIRECTIONS : 1;

        return directions == TURNING_DIRECTIONS ? frameSet * TURNING_DIRECTIONS + image.getFrameIndexOffset() : frameSet;
    }

    /**
     * Finds the main image a shadow image follows: the main image of its sprite when the image draws with the shadow draw function and the main
     * image draws graphics made from a sheet.
     *
     * @return the main image, or {@code null} if the image follows none
     */
    private @Nullable SpriteImage findShadowMain(@NotNull SpriteImage image) {
        if (image.getModifier() != SpriteImage.MODIFIER_SHADOW) {
            return null;
        }

        Sprite sprite = image.getSprite();
        SpriteImage main = sprite == null ? null : sprite.getMainImage();

        if (main == null || main == image || main.getModifier() == SpriteImage.MODIFIER_SHADOW) {
            return null;
        }

        ImageGraphics graphics = this.findGraphics(main);

        return graphics != null && graphics.isSheet() ? main : null;
    }

    /**
     * Finds the draw function a sprite image draws with instead of its own: the draw function set for it ({@link #setImageDrawFunction}) while
     * its own draw function is the one it had then.
     *
     * @param image an image of a drawn sprite
     * @return the draw function, or {@code -1} for the image's own
     */
    public int findImageDrawFunction(@NotNull SpriteImage image) {
        ImageOverride override = this.findImageOverride(image);

        return override == null || override.drawFunction == NONE || override.ownDrawFunction != image.getModifier() ? NONE
                                                                                                                    : override.drawFunction;
    }

    /**
     * @return the number of units' image types with graphics, those of freed slots included until {@link #removeFreedUnits()}
     */
    public int getUnitReplacementCount() {
        return this.unitReplacements.size();
    }


    /**
     * Loops frame sets for every image of an image type that draws its own frame, timed from game frame {@code 0}.
     *
     * @param imageTypeId the image type
     * @param frameSets   the frame sets, drawn one after another
     * @param frameTime   the game frames each frame set is drawn, at least {@code 1}
     * @throws IllegalArgumentException if the image type is outside the image types, there is no frame set, a frame set is negative or the frame time is below {@code 1}
     */
    public void setTypeAnimation(int imageTypeId, int @NotNull [] frameSets, int frameTime) {
        this.typeAnimations[this.requireImageTypeId(imageTypeId)] = createPlayback(frameSets, frameTime, 0, true, null);
    }

    /**
     * Plays frame sets on one image while its slot holds it, from the current game frame; ends a frame set or held frame
     * ({@link #setImageFrame}, {@link #holdImageFrame}) and the play before.
     *
     * @param image     an allocated image of the image pool
     * @param frameSets the frame sets, drawn one after another
     * @param frameTime the game frames each frame set is drawn, at least {@code 1}
     * @param loop      whether the frame sets start over after the last one until {@link #stopPlayingImage}
     * @param graphics  the graphics drawn meanwhile, or {@code null} for the image's own
     * @throws IllegalArgumentException if the image's slot is not allocated, there is no frame set, a frame set is negative or the frame time is below {@code 1}
     */
    public void playImage(@NotNull SpriteImage image, int @NotNull [] frameSets, int frameTime, boolean loop, @Nullable ImageGraphics graphics) {
        Playback playback = createPlayback(frameSets, frameTime, this.frames.getAsInt(), loop, graphics);
        ImageOverride override = this.requireImageOverride(image);

        override.frame     = NONE;
        override.frameHeld = false;
        override.playback  = playback;
    }

    /**
     * Ends the play of one image at once: it draws its own frame and graphics again.
     *
     * @param image an image of the image pool
     */
    public void stopPlayingImage(@NotNull SpriteImage image) {
        ImageOverride override = this.findImageOverride(image);

        if (override != null) {
            override.playback = null;
            this.removeIfEmpty(image, override);
        }
    }

    /**
     * Replaces the graphics of every image of an image type.
     *
     * @param imageTypeId the image type, an {@code images.dat} entry
     * @param graphics    the graphics
     * @throws IllegalArgumentException if the image type is outside {@code images.dat}
     */
    public void replaceImage(int imageTypeId, @NotNull ImageGraphics graphics) {
        this.typeGraphics[this.requireImageTypeId(imageTypeId)] = graphics;
    }

    /**
     * Restores the installed graphics of the images of an image type, except for the units with graphics of their own.
     *
     * @param imageTypeId the image type, an {@code images.dat} entry
     * @throws IllegalArgumentException if the image type is outside {@code images.dat}
     */
    public void restoreImage(int imageTypeId) {
        this.typeGraphics[this.requireImageTypeId(imageTypeId)] = null;
    }

    /**
     * Replaces the graphics of a unit's images of an image type, for as long as the unit's slot holds the unit.
     *
     * @param unit        the unit, in an allocated slot of the game's unit pool
     * @param imageTypeId the image type, an {@code images.dat} entry
     * @param graphics    the graphics
     * @throws IllegalArgumentException if the image type is outside {@code images.dat} or the unit's slot is not allocated
     * @throws IllegalStateException    if the game has no unit pool
     */
    public void replaceUnitImage(@NotNull Unit unit, int imageTypeId, @NotNull ImageGraphics graphics) {
        this.requireImageTypeId(imageTypeId);

        UnitPool pool = this.requireUnitPool();

        if (!pool.isAllocated(unit.getIndex())) {
            throw Exceptions.newIllegalArgumentException("The unit slot {0} is not allocated.", unit.getIndex());
        }

        UnitReplacement replacement = this.findUnitReplacement(unit, imageTypeId);

        if (replacement != null) {
            replacement.graphics = graphics;
        } else {
            this.unitReplacements.add(new UnitReplacement(unit, pool.getGeneration(unit.getIndex()), imageTypeId, graphics));
        }
    }

    /**
     * Restores the graphics of a unit's images of an image type: those of the image type, else the installed graphics.
     *
     * @param unit        the unit
     * @param imageTypeId the image type, an {@code images.dat} entry
     * @throws IllegalArgumentException if the image type is outside {@code images.dat}
     */
    public void restoreUnitImage(@NotNull Unit unit, int imageTypeId) {
        this.requireImageTypeId(imageTypeId);

        UnitReplacement replacement = this.findUnitReplacement(unit, imageTypeId);

        if (replacement != null) {
            this.unitReplacements.remove(replacement);
        }
    }

    /**
     * Sets the graphics of the shadow of the sprites whose main image is of an image type.
     *
     * @param imageTypeId the image type of the main image, an {@code images.dat} entry
     * @param graphics    the graphics, {@link ImageGraphics#HIDDEN} for none, or {@code null} for the installed ones
     * @throws IllegalArgumentException if the image type is outside {@code images.dat}
     */
    public void setTypeShadow(int imageTypeId, @Nullable ImageGraphics graphics) {
        this.typeShadows[this.requireImageTypeId(imageTypeId)] = graphics;
    }

    /**
     * Sets the graphics of the remains of the units whose sprite's main image is of an image type when they die.
     *
     * @param imageTypeId the image type of the main image, an {@code images.dat} entry
     * @param graphics    the graphics, {@link ImageGraphics#HIDDEN} for none, or {@code null} for the installed ones
     * @throws IllegalArgumentException if the image type is outside {@code images.dat}
     */
    public void setTypeRemains(int imageTypeId, @Nullable ImageGraphics graphics) {
        int index = this.requireImageTypeId(imageTypeId);

        if (this.typeRemains[index] == null && graphics != null) {
            this.typeRemainsCount++;
        } else if (this.typeRemains[index] != null && graphics == null) {
            this.typeRemainsCount--;
        }

        this.typeRemains[index] = graphics;
    }

    /**
     * Sets the graphics of a unit's shadow, for as long as the unit's slot holds the unit.
     *
     * @param unit     the unit, in an allocated slot of the game's unit pool
     * @param graphics the graphics, {@link ImageGraphics#HIDDEN} for none, or {@code null} for those of the image type
     * @throws IllegalArgumentException if the unit's slot is not allocated
     * @throws IllegalStateException    if the game has no unit pool
     */
    public void setUnitShadow(@NotNull Unit unit, @Nullable ImageGraphics graphics) {
        this.placeUnitParts(unit).shadow = graphics;
    }

    /**
     * Sets the graphics of a unit's remains, which the unit leaves when it dies while its slot holds it.
     *
     * @param unit     the unit, in an allocated slot of the game's unit pool
     * @param graphics the graphics, {@link ImageGraphics#HIDDEN} for none, or {@code null} for those of the image type
     * @throws IllegalArgumentException if the unit's slot is not allocated
     * @throws IllegalStateException    if the game has no unit pool
     */
    public void setUnitRemains(@NotNull Unit unit, @Nullable ImageGraphics graphics) {
        this.placeUnitParts(unit).remains = graphics;
    }

    private @NotNull UnitParts placeUnitParts(@NotNull Unit unit) {
        UnitPool pool = this.requireUnitPool();

        if (!pool.isAllocated(unit.getIndex())) {
            throw Exceptions.newIllegalArgumentException("The unit slot {0} is not allocated.", unit.getIndex());
        }

        UnitParts parts = this.findUnitParts(pool, unit);

        if (parts == null) {
            parts = new UnitParts(unit, pool.getGeneration(unit.getIndex()));
            this.unitParts.add(parts);
        }

        return parts;
    }

    private @Nullable UnitParts findUnitParts(@NotNull UnitPool pool, @NotNull Unit unit) {
        for (UnitParts parts : this.unitParts) {
            if (parts.unit == unit && isHeld(pool, parts)) {
                return parts;
            }
        }

        return null;
    }

    /**
     * Hears that a unit died: when the unit, or the image type of its sprite's main image, has remains graphics, the images its sprite gets from
     * now on draw them.
     *
     * @param unit the dying unit, which still holds its sprite
     */
    public void onUnitDied(@NotNull Unit unit) {
        if (this.unitParts.isEmpty() && this.typeRemainsCount == 0) {
            return;
        }

        Sprite sprite = unit.getSpriteOrNull();
        SpriteImage main = sprite == null ? null : sprite.getMainImage();
        SpritePool spritePool = this.sprites.get();
        UnitPool unitPool = this.units.get();

        if (main == null || spritePool == null || unitPool == null) {
            return;
        }

        UnitParts parts = this.findUnitParts(unitPool, unit);
        ImageGraphics remains = parts == null ? null : parts.remains;
        int imageTypeId = main.getImageTypeId();

        if (remains == null && imageTypeId >= 0 && imageTypeId < this.typeRemains.length) {
            remains = this.typeRemains[imageTypeId];
        }

        if (remains != null) {
            int slot = sprite.getPoolIndex();

            this.remainsSprites.put(slot, new RemainsSprite(spritePool.getGeneration(slot), main.getPool().getGeneration(), remains));
        }
    }

    /**
     * Hears that an image's iscript created a sprite: a sprite created by a sprite of remains is one too, every image of it drawing the remains
     * graphics.
     *
     * @param sprite       the sprite pool slot of the created sprite
     * @param sourceSprite the sprite pool slot of the sprite whose image created it
     */
    public void onSpriteCreated(int sprite, int sourceSprite) {
        if (this.remainsSprites.isEmpty()) {
            return;
        }

        SpritePool pool = this.sprites.get();
        RemainsSprite source = this.remainsSprites.get(sourceSprite);

        if (pool != null && source != null && isHeld(pool, sourceSprite, source)) {
            this.remainsSprites.put(sprite, new RemainsSprite(pool.getGeneration(sprite), EVERY_IMAGE, source.graphics));
        }
    }

    /**
     * Replaces the graphics of one image while its slot holds it.
     *
     * @param image    an allocated image of the image pool
     * @param graphics the graphics
     */
    public void replaceImageGraphics(@NotNull SpriteImage image, @NotNull ImageGraphics graphics) {
        this.requireImageOverride(image).graphics = graphics;
    }

    /**
     * Restores the graphics of one image: those of its unit or image type, else the installed graphics.
     *
     * @param image an image of the image pool
     */
    public void restoreImageGraphics(@NotNull SpriteImage image) {
        ImageOverride override = this.findImageOverride(image);

        if (override != null) {
            override.graphics = null;
            this.removeIfEmpty(image, override);
        }
    }

    /**
     * Sets the frame one image draws until its own drawn frame changes.
     *
     * @param image an allocated image of the image pool
     * @param frame the frame index, or {@code -1} for the image's own frame
     */
    public void setImageFrame(@NotNull SpriteImage image, int frame) {
        this.placeImageFrame(image, frame, false);
    }

    /**
     * Holds the frame one image draws: it stays while the image's own drawn frame changes, as the image's iscript steps it, until another frame
     * is set or held for the image or the image is gone.
     *
     * @param image an allocated image of the image pool
     * @param frame the frame index, or {@code -1} for the image's own frame
     */
    public void holdImageFrame(@NotNull SpriteImage image, int frame) {
        this.placeImageFrame(image, frame, true);
    }

    private void placeImageFrame(@NotNull SpriteImage image, int frame, boolean held) {
        if (frame < 0) {
            ImageOverride override = this.findImageOverride(image);

            if (override != null) {
                override.frame     = NONE;
                override.frameHeld = false;
                override.playback  = null;
                this.removeIfEmpty(image, override);
            }

            return;
        }

        ImageOverride override = this.requireImageOverride(image);

        override.frame     = frame;
        override.ownFrame  = image.getDrawnFrameIndex();
        override.frameHeld = held;
        override.playback  = null;
    }

    /**
     * Sets the draw function one image draws with until its own draw function changes.
     *
     * @param image        an allocated image of the image pool
     * @param drawFunction the draw function, or {@code -1} for the image's own
     */
    public void setImageDrawFunction(@NotNull SpriteImage image, int drawFunction) {
        if (drawFunction < 0) {
            ImageOverride override = this.findImageOverride(image);

            if (override != null) {
                override.drawFunction = NONE;
                this.removeIfEmpty(image, override);
            }

            return;
        }

        ImageOverride override = this.requireImageOverride(image);

        override.drawFunction    = drawFunction;
        override.ownDrawFunction = image.getModifier();
    }

    /**
     * Forgets the graphics of the units whose slot was freed or holds another unit, the overrides of the images whose slot was freed or holds
     * another image, the sprites of remains whose slot was freed or holds another sprite, and the plays that ended.
     */
    public void removeFreedUnits() {
        if (!this.imageOverrides.isEmpty()) {
            int frame = this.frames.getAsInt();

            for (ImageOverride override : this.imageOverrides.values()) {
                if (override.playback != null && override.playback.findFrameSet(frame) < 0) {
                    override.playback = null;
                }
            }

            this.imageOverrides.entrySet().removeIf(entry -> !isHeld(entry.getValue(), entry.getKey()) || entry.getValue().isEmpty());
        }

        if (!this.remainsSprites.isEmpty()) {
            SpritePool spritePool = this.sprites.get();

            this.remainsSprites.entrySet().removeIf(entry -> spritePool == null || !isHeld(spritePool, entry.getKey(), entry.getValue()));
        }

        if (this.unitReplacements.isEmpty() && this.unitParts.isEmpty()) {
            return;
        }

        UnitPool pool = this.units.get();

        this.unitReplacements.removeIf(replacement -> pool == null || !isHeld(pool, replacement));
        this.unitParts.removeIf(parts -> pool == null || !isHeld(pool, parts));
    }

    /**
     * Finds the graphics a sprite image draws instead of its installed graphics: those of its play, those of the image, the remains graphics of
     * a sprite of remains, those of the unit it belongs to and its image type, else those of its image type; a shadow image draws the shadow
     * graphics of its sprite's unit or main image type, else, below a main image with graphics made from a sheet, their shadow graphics or the
     * main image's graphics.
     *
     * @param image an image of a drawn sprite
     * @return the graphics, {@link ImageGraphics#HIDDEN} for none, or {@code null} for the installed graphics
     */
    public @Nullable ImageGraphics findGraphics(@NotNull SpriteImage image) {
        ImageOverride override = this.findImageOverride(image);

        if (override != null && override.playback != null && override.playback.graphics != null &&
            override.playback.findFrameSet(this.frames.getAsInt()) >= 0) {
            return override.playback.graphics;
        }

        ImageGraphics graphics = this.findOwnGraphics(image);

        if (graphics != null) {
            return graphics;
        }

        ImageGraphics shadow = this.findShadowGraphics(image);

        if (shadow != null) {
            return shadow;
        }

        SpriteImage main = this.findShadowMain(image);

        if (main == null) {
            return null;
        }

        ImageGraphics mainGraphics = this.findGraphics(main);

        return mainGraphics == null || mainGraphics.getShadow() == null ? mainGraphics : mainGraphics.getShadow();
    }

    /**
     * Finds the shadow graphics a shadow image draws: those of the unit of its sprite, else those of the image type of its sprite's main image.
     *
     * @return the graphics, or {@code null} if the image is no shadow image below another main image or neither has shadow graphics
     */
    private @Nullable ImageGraphics findShadowGraphics(@NotNull SpriteImage image) {
        if (image.getModifier() != SpriteImage.MODIFIER_SHADOW) {
            return null;
        }

        Sprite sprite = image.getSprite();
        SpriteImage main = sprite == null ? null : sprite.getMainImage();

        if (main == null || main == image || main.getModifier() == SpriteImage.MODIFIER_SHADOW) {
            return null;
        }

        if (!this.unitParts.isEmpty()) {
            UnitPool pool = this.units.get();

            if (pool != null) {
                for (UnitParts parts : this.unitParts) {
                    if (parts.shadow != null && isHeld(pool, parts) && isUnitSprite(parts.unit, sprite)) {
                        return parts.shadow;
                    }
                }
            }
        }

        int imageTypeId = main.getImageTypeId();

        return imageTypeId >= 0 && imageTypeId < this.typeShadows.length ? this.typeShadows[imageTypeId] : null;
    }

    /**
     * Finds the graphics of an image without its play and the shadow rule: those of the image, the remains graphics of a sprite of remains, those
     * of the unit it belongs to and its image type, else those of its image type.
     *
     * @return the graphics, or {@code null} for the installed graphics
     */
    private @Nullable ImageGraphics findOwnGraphics(@NotNull SpriteImage image) {
        ImageOverride override = this.findImageOverride(image);

        if (override != null && override.graphics != null) {
            return override.graphics;
        }

        if (!this.remainsSprites.isEmpty()) {
            ImageGraphics remains = this.findRemainsGraphics(image);

            if (remains != null) {
                return remains;
            }
        }

        int imageTypeId = image.getImageTypeId();

        if (imageTypeId < 0 || imageTypeId >= this.typeGraphics.length) {
            return null;
        }

        if (!this.unitReplacements.isEmpty()) {
            Sprite sprite = image.getSprite();
            UnitPool pool = this.units.get();

            if (sprite != null && pool != null) {
                for (UnitReplacement replacement : this.unitReplacements) {
                    if (replacement.imageTypeId == imageTypeId && isHeld(pool, replacement) && isUnitSprite(replacement.unit, sprite)) {
                        return replacement.graphics;
                    }
                }
            }
        }

        return this.typeGraphics[imageTypeId];
    }

    /**
     * Finds the remains graphics of an image of a sprite of remains: an image of a sprite an image of the remains created, or an image the sprite
     * of a dead unit got after the unit died.
     *
     * @return the graphics, or {@code null} if the image is no image of remains
     */
    private @Nullable ImageGraphics findRemainsGraphics(@NotNull SpriteImage image) {
        Sprite sprite = image.getSprite();
        RemainsSprite remains = sprite == null ? null : this.remainsSprites.get(sprite.getPoolIndex());
        SpritePool pool = this.sprites.get();

        if (remains == null || pool == null || !isHeld(pool, sprite.getPoolIndex(), remains)) {
            return null;
        }

        return remains.imageGeneration == EVERY_IMAGE || image.getPool().getGeneration(image.getIndex()) > remains.imageGeneration
               ? remains.graphics
               : null;
    }

    private @Nullable UnitReplacement findUnitReplacement(@NotNull Unit unit, int imageTypeId) {
        UnitPool pool = this.units.get();

        if (pool == null) {
            return null;
        }

        for (UnitReplacement replacement : this.unitReplacements) {
            if (replacement.unit == unit && replacement.imageTypeId == imageTypeId && isHeld(pool, replacement)) {
                return replacement;
            }
        }

        return null;
    }

    /**
     * Checks whether an override's image is still in its slot.
     *
     * @param override the override
     * @param slot     the image pool slot of the override
     * @return {@code true} if the slot is allocated and keeps the generation of the override's image, {@code false} otherwise
     */
    private static boolean isHeld(@NotNull ImageOverride override, int slot) {
        return slot < override.pool.getCapacity() && override.pool.isAllocated(slot) && override.pool.getGeneration(slot) == override.generation;
    }

    /**
     * Finds the override of an image while its slot holds it.
     */
    private @Nullable ImageOverride findImageOverride(@NotNull SpriteImage image) {
        if (this.imageOverrides.isEmpty()) {
            return null;
        }

        int slot = image.getIndex();
        ImageOverride override = this.imageOverrides.get(slot);

        return override != null && override.pool == image.getPool() && isHeld(override, slot) ? override : null;
    }

    /**
     * Finds the override of an allocated image, creating it for the image in its slot.
     *
     * @param image an allocated image of the image pool
     * @return the override
     * @throws IllegalArgumentException if the image's slot is not allocated
     */
    private @NotNull ImageOverride requireImageOverride(@NotNull SpriteImage image) {
        SpriteImagePool pool = image.getPool();
        int slot = image.getIndex();

        if (!pool.isAllocated(slot)) {
            throw Exceptions.newIllegalArgumentException("The image slot {0} is not allocated.", slot);
        }

        ImageOverride override = this.findImageOverride(image);

        if (override == null) {
            override = new ImageOverride(pool, pool.getGeneration(slot));
            this.imageOverrides.put(slot, override);
        }

        return override;
    }

    private void removeIfEmpty(@NotNull SpriteImage image, @NotNull ImageOverride override) {
        if (override.isEmpty()) {
            this.imageOverrides.remove(image.getIndex());
        }
    }

    private @NotNull UnitPool requireUnitPool() {
        UnitPool pool = this.units.get();

        if (pool == null) {
            throw Exceptions.newIllegalStateException("The game has no unit pool.");
        }

        return pool;
    }
}
