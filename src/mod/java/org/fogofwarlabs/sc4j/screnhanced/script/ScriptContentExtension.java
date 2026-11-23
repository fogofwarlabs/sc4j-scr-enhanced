package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.type.ImageType;
import org.fogofwarlabs.sc4j.engine.content.type.MotionProfile;
import org.fogofwarlabs.sc4j.engine.content.type.SpriteType;
import org.fogofwarlabs.sc4j.scr.content.*;
import org.fogofwarlabs.sc4j.scr.data.format.dat.FlingyDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SpritesDat;
import org.fogofwarlabs.sc4j.scr.data.format.image.ImageFileWriterUtils;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.*;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrModFiles;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrModGraphics;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrContentExtension;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Reserves the image, sprite and flingy entries that map scripts register types in ({@code ImageType.create} and the others) and adds the
 * generic scripts of those image types: {@value #IMAGE_COUNT} images, one shadow image, {@value #SPRITE_COUNT} sprites and {@value #FLINGY_COUNT}
 * flingy entries after the native entries ({@link ScrAddedGraphics}), the same in every session, so every game, replay and peer has the same
 * tables.
 * <p>
 * A reserved image starts as a copy of the Terran Marine's image with the {@code Still} script; a reserved sprite as a copy of the Marine's
 * sprite naming its reserved image, a reserved flingy entry as a copy of the Marine's flingy entry naming its reserved sprite. Each reserved
 * image and the shadow image read frames of their own path in the mod files: a one-pixel GRP until a game gives the path a file of its own
 * ({@link GameData#putGameFile}). No reserved image has an anim of its own, so the HD graphics load the base image's anim for all of them.
 * <p>
 * The scripts use native opcodes only: {@code Still} shows frame {@code 0} and waits until Death, which ends it a frame later;
 * {@code StillShadow} does the same after creating the shadow image below; {@code Shadow} follows the main image's frame each frame; and
 * {@code Projectile} has the shape of a native bullet script: Init shows frame {@code 0} and signals the bullet's order a frame later, Death
 * applies the weapon ({@code domissiledmg}) and ends a frame later. Their headers copy the header type of a native bullet script.
 */
public final class ScriptContentExtension implements ScrContentExtension {
    /**
     * The reserved image entries.
     */
    public static final int IMAGE_COUNT = 256;
    /**
     * The reserved sprite entries.
     */
    public static final int SPRITE_COUNT = 256;
    /**
     * The reserved flingy entries.
     */
    public static final int FLINGY_COUNT = 128;

    /**
     * The mod file folder of the frames of the reserved images.
     */
    private static final @NotNull String FRAMES_FOLDER = "org/fogofwarlabs/sc4j/screnhanced/images/";
    private static final @NotNull String FRAMES_EXTENSION = ".grp";
    private static final @NotNull String SHADOW_FRAMES_NAME = "shadow";
    private static final @NotNull String ENTRY_NAME_PREFIX = "SC:R Enhanced ";
    /**
     * The header type of a native bullet script: Init, Death and GndAttkInit.
     */
    private static final int PROJECTILE_HEADER_TYPE = 2;
    private static final int ANIMATION_INIT = 0;
    private static final int ANIMATION_DEATH = 1;
    /**
     * The wait of an idle loop, in frames.
     */
    private static final int IDLE_WAIT = 125;
    /**
     * The order signal a bullet's Init gives, which starts its move.
     */
    private static final int BULLET_ORDER_SIGNAL = 1;
    private static final @NotNull String LOOP_LABEL = "loop";

    /**
     * The entries of each installed data, by its added graphics, which its game copies share.
     */
    private static final @NotNull Map<ScrAddedGraphics, Entries> ENTRIES = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The entries and scripts this extension reserved in one installed data.
     */
    public static final class Entries {
        private final int firstImage;
        private final int shadowImage;
        private final int firstSprite;
        private final int firstFlingy;

        private final int stillScript;
        private final int stillShadowScript;
        private final int projectileScript;

        private Entries(int firstImage, int shadowImage, int firstSprite, int firstFlingy, int stillScript, int stillShadowScript,
                        int projectileScript) {
            this.firstImage  = firstImage;
            this.shadowImage = shadowImage;
            this.firstSprite = firstSprite;
            this.firstFlingy = firstFlingy;

            this.stillScript       = stillScript;
            this.stillShadowScript = stillShadowScript;
            this.projectileScript  = projectileScript;
        }

        /**
         * @return the id of the first reserved image; the {@value #IMAGE_COUNT} reserved images follow each other
         */
        public int getFirstImage() {
            return this.firstImage;
        }

        /**
         * @return the id of the shadow image of the {@code StillShadow} script
         */
        public int getShadowImage() {
            return this.shadowImage;
        }

        /**
         * @return the id of the first reserved sprite; the {@value #SPRITE_COUNT} reserved sprites follow each other
         */
        public int getFirstSprite() {
            return this.firstSprite;
        }

        /**
         * @return the id of the first reserved flingy entry; the {@value #FLINGY_COUNT} reserved flingy entries follow each other
         */
        public int getFirstFlingy() {
            return this.firstFlingy;
        }

        /**
         * @return the id of the {@code Still} script
         */
        public int getStillScript() {
            return this.stillScript;
        }

        /**
         * @return the id of the {@code StillShadow} script
         */
        public int getStillShadowScript() {
            return this.stillShadowScript;
        }

        /**
         * @return the id of the {@code Projectile} script
         */
        public int getProjectileScript() {
            return this.projectileScript;
        }

        /**
         * Returns the mod file path of the frames of a reserved image, which a game gives its frames under ({@link GameData#putGameFile}).
         *
         * @param imageId a reserved image id
         * @return the path
         * @throws IllegalArgumentException if the id names no reserved image
         */
        public @NotNull String getFramesPath(int imageId) {
            if (imageId < this.firstImage || imageId >= this.firstImage + IMAGE_COUNT) {
                throw Exceptions.newIllegalArgumentException("The image {0} is no reserved image.", imageId);
            }

            return FRAMES_FOLDER + (imageId - this.firstImage) + FRAMES_EXTENSION;
        }
    }


    /**
     * Finds the entries this extension reserved in the installed data of a game.
     *
     * @param graphics the added graphics of the installed data or of one of its game copies
     * @return the entries, or {@code null} if this extension reserved none there
     */
    public static @Nullable Entries findEntries(@NotNull ScrAddedGraphics graphics) {
        return ENTRIES.get(graphics);
    }

    /**
     * Creates the code of an idle loop: optional leading instructions, then a long wait in a loop.
     */
    private static @NotNull IscriptCode createLoop(@NotNull IscriptCode.Builder builder) {
        return builder.label(LOOP_LABEL).instruction(IscriptOpcodes.WAIT, IDLE_WAIT).jump(LOOP_LABEL).build();
    }

    /**
     * Creates a script on the header of a native bullet script: Init and Death of their own, and every other animation an idle loop.
     */
    private static @NotNull ScrAddedScript createScript(int baseScript, @NotNull IscriptFile iscript, @NotNull IscriptCode init,
                                                        @NotNull IscriptCode death, @NotNull IscriptCode other) {
        ScrAddedScript.Builder builder = new ScrAddedScript.Builder(baseScript, iscript);
        int count = builder.build().getAnimationCount();

        builder.setAnimation(ANIMATION_INIT, init).setAnimation(ANIMATION_DEATH, death);

        for (int animation = ANIMATION_DEATH + 1; animation < count; animation++) {
            builder.setAnimation(animation, other);
        }

        return builder.build();
    }

    /**
     * Finds the shadow image the installed script of an image creates in its Init animation ({@code imgul}).
     *
     * @throws IllegalStateException if the script creates none
     */
    private static int findShadowImage(@NotNull IscriptFile iscript, int script) {
        IscriptHeader header = iscript.findHeader(script);

        if (header != null) {
            for (IscriptHeader.Animation animation : header.getAnimations()) {
                if (animation.getTypeIndex() != ANIMATION_INIT) {
                    continue;
                }

                for (IscriptInstruction instruction : animation.getInstructions()) {
                    if (instruction.getOpcode() == IscriptOpcodes.IMGUL) {
                        return instruction.getOperands()[0];
                    }
                }
            }
        }

        throw Exceptions.newIllegalStateException("The script {0} creates no shadow image.", script);
    }

    /**
     * @return the id of the first native script with the header type of a bullet
     * @throws IllegalStateException if the iscript has none
     */
    private static int findProjectileScript(@NotNull IscriptFile iscript) {
        for (IscriptHeader header : iscript.getHeaders()) {
            if (header.getType() == PROJECTILE_HEADER_TYPE) {
                return header.getId();
            }
        }

        throw Exceptions.newIllegalStateException("The iscript has no script of header type {0}.", PROJECTILE_HEADER_TYPE);
    }


    /**
     * Creates a {@link ScriptContentExtension} instance, as the service loader does.
     */
    public ScriptContentExtension() {
    }

    /**
     * Reserves the entries and adds the scripts in the installed data, once per installed data.
     *
     * @param installed the installed data
     */
    @Override
    public void extendInstalledData(@NotNull GameData installed) {
        ScrAddedGraphics graphics = installed.getAddedGraphics();

        if (ENTRIES.containsKey(graphics)) {
            return;
        }

        IscriptFile iscript = installed.getIscript();
        int baseFlingy = installed.getUnitTypes().getFlingyType(ScrUnitIds.TERRAN_MARINE);
        int baseSprite = installed.getFlingyTypes().getSprite(baseFlingy);
        int baseImage = installed.getSpriteTypes().getImage(baseSprite);
        int baseShadow = findShadowImage(iscript, installed.getImageTypes().getIscript(baseImage));
        int baseScript = findProjectileScript(iscript);
        byte[] placeholder = ImageFileWriterUtils.encodeGrp(1, 1, List.of(new byte[]{1}));
        ScrModFiles files = path -> placeholder.clone();

        IscriptCode follow = new IscriptCode.Builder().label(LOOP_LABEL)
                                                      .instruction(IscriptOpcodes.FOLLOWMAINGRAPHIC)
                                                      .instruction(IscriptOpcodes.WAIT, 1)
                                                      .jump(LOOP_LABEL)
                                                      .build();
        IscriptCode death = new IscriptCode.Builder().instruction(IscriptOpcodes.WAIT, 1).instruction(IscriptOpcodes.END).build();
        IscriptCode idle = createLoop(new IscriptCode.Builder());
        IscriptCode still = createLoop(new IscriptCode.Builder().instruction(IscriptOpcodes.PLAYFRAM, 0));
        IscriptCode launch = createLoop(new IscriptCode.Builder().instruction(IscriptOpcodes.PLAYFRAM, 0)
                                                                 .instruction(IscriptOpcodes.WAIT, 1)
                                                                 .instruction(IscriptOpcodes.SIGORDER, BULLET_ORDER_SIGNAL));
        IscriptCode hit = new IscriptCode.Builder().instruction(IscriptOpcodes.DOMISSILEDMG)
                                                   .instruction(IscriptOpcodes.WAIT, 1)
                                                   .instruction(IscriptOpcodes.END)
                                                   .build();
        int shadowScript = graphics.addScript(createScript(baseScript, iscript, follow, death, follow));
        int stillScript = graphics.addScript(createScript(baseScript, iscript, still, death, idle));
        int projectileScript = graphics.addScript(createScript(baseScript, iscript, launch, hit, idle));
        int firstImage = -1;

        for (int index = 0; index < IMAGE_COUNT; index++) {
            int image = graphics.addImage(new ScrAddedImage.Builder(new ImageType(ENTRY_NAME_PREFIX + "image " + index), baseImage,
                                                                    installed.getImages())
                                                  .setValue(ImagesDat.ISCRIPT, stillScript)
                                                  .setGraphics(new ScrModGraphics.Builder(files, FRAMES_FOLDER + index + FRAMES_EXTENSION).build())
                                                  .build());

            firstImage = index == 0 ? image : firstImage;
        }

        int shadowImage = graphics.addImage(new ScrAddedImage.Builder(new ImageType(ENTRY_NAME_PREFIX + "shadow image"), baseShadow,
                                                                      installed.getImages())
                                                    .setValue(ImagesDat.ISCRIPT, shadowScript)
                                                    .setGraphics(new ScrModGraphics.Builder(files, FRAMES_FOLDER + SHADOW_FRAMES_NAME +
                                                                                                   FRAMES_EXTENSION).build())
                                                    .build());
        IscriptCode stillShadow = createLoop(new IscriptCode.Builder().instruction(IscriptOpcodes.IMGUL, shadowImage, 0, 0)
                                                                      .instruction(IscriptOpcodes.PLAYFRAM, 0));
        int stillShadowScript = graphics.addScript(createScript(baseScript, iscript, stillShadow, death, idle));
        int firstSprite = -1;

        for (int index = 0; index < SPRITE_COUNT; index++) {
            int sprite = graphics.addSprite(new ScrAddedSprite.Builder(new SpriteType(ENTRY_NAME_PREFIX + "sprite " + index), baseSprite,
                                                                       installed.getSprites())
                                                    .setValue(SpritesDat.IMAGE, firstImage + index)
                                                    .build());

            firstSprite = index == 0 ? sprite : firstSprite;
        }

        int firstFlingy = -1;

        for (int index = 0; index < FLINGY_COUNT; index++) {
            int flingy = graphics.addFlingy(new ScrAddedFlingy.Builder(new MotionProfile(ENTRY_NAME_PREFIX + "flingy " + index), baseFlingy,
                                                                       installed.getFlingy())
                                                    .setValue(FlingyDat.SPRITE, firstSprite + index)
                                                    .build());

            firstFlingy = index == 0 ? flingy : firstFlingy;
        }

        ENTRIES.put(graphics, new Entries(firstImage, shadowImage, firstSprite, firstFlingy, stillScript, stillShadowScript, projectileScript));
    }
}
