package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageAnimation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The graphics an image draws instead of its installed graphics ({@link ImageReplacements}): a GRP for the classic graphics, an HD anim and an HD2
 * anim, at least one of them. The image's frame index selects the frame in each, as for the GRP and the anims of EUD GRP and EUD ANIM.
 * <p>
 * The HD graphics draw the HD anim, else the HD2 anim; the HD2 graphics draw the HD2 anim, else the HD anim at half size. Graphics without an
 * anim draw the GRP in the HD graphics too, and graphics without a GRP keep the installed GRP in the classic graphics.
 * <p>
 * Graphics made from a sheet ({@link PictureSheet}) know the frames of their frame sets and may carry the graphics of the sheet's shadow picture,
 * which a shadow image of the same sprite draws ({@link ImageReplacements#findGraphics}).
 * <p>
 * The graphics {@link #HIDDEN} have no GRP and no anim: an image that draws them draws nothing.
 */
public final class ImageGraphics implements ScrImageAnimation {
    /**
     * The graphics that draw nothing.
     */
    public static final @NotNull ImageGraphics HIDDEN = new ImageGraphics(null, null, null, 0, null);


    /**
     * Creates an {@link ImageGraphics} instance.
     *
     * @param grp     the GRP of the classic graphics, or {@code null} for the installed GRP
     * @param hdAnim  the HD anim, or {@code null} for none
     * @param hd2Anim the HD2 anim, or {@code null} for none
     * @return the created {@link ImageGraphics} instance
     * @throws IllegalArgumentException if all three are {@code null}, the HD anim is an HD2 anim or the HD2 anim an HD anim
     */
    public static @NotNull ImageGraphics of(@Nullable ImageGrp grp, @Nullable ImageAnim hdAnim, @Nullable ImageAnim hd2Anim) {
        if (grp == null && hdAnim == null && hd2Anim == null) {
            throw Exceptions.newIllegalArgumentException("The image graphics have no GRP and no anim.");
        }

        if (hdAnim != null && !hdAnim.isHd()) {
            throw Exceptions.newIllegalArgumentException("The HD anim {0} has the asset scale {1}, not that of HD.", hdAnim.getName(),
                                                         hdAnim.getScale());
        }

        if (hd2Anim != null && hd2Anim.isHd()) {
            throw Exceptions.newIllegalArgumentException("The HD2 anim {0} has the asset scale {1}, not that of HD2.", hd2Anim.getName(),
                                                         hd2Anim.getScale());
        }

        return new ImageGraphics(grp, hdAnim, hd2Anim, 0, null);
    }

    /**
     * Creates an {@link ImageGraphics} instance of graphics made from a sheet.
     *
     * @param grp        the GRP of the classic graphics
     * @param anim       the HD or HD2 anim
     * @param directions the frames of a frame set: {@code 1} or {@value SheetLayout#TURNING_DIRECTIONS}
     * @param shadow     the graphics of the sheet's shadow picture, or {@code null} for none
     * @return the created {@link ImageGraphics} instance
     */
    public static @NotNull ImageGraphics ofSheet(@NotNull ImageGrp grp, @NotNull ImageAnim anim, int directions, @Nullable ImageGraphics shadow) {
        return new ImageGraphics(grp, anim.isHd() ? anim : null, anim.isHd() ? null : anim, directions, shadow);
    }


    private final @Nullable ImageGrp grp;
    private final @Nullable ImageAnim hdAnim;
    private final @Nullable ImageAnim hd2Anim;

    private final int directions;
    private final @Nullable ImageGraphics shadow;

    /**
     * Creates an {@link ImageGraphics} instance.
     *
     * @param grp        the GRP, or {@code null}
     * @param hdAnim     the HD anim, or {@code null}
     * @param hd2Anim    the HD2 anim, or {@code null}
     * @param directions the frames of a frame set, or {@code 0} if they are not known
     * @param shadow     the graphics of the shadow, or {@code null}
     */
    private ImageGraphics(@Nullable ImageGrp grp, @Nullable ImageAnim hdAnim, @Nullable ImageAnim hd2Anim, int directions,
                          @Nullable ImageGraphics shadow) {
        this.grp     = grp;
        this.hdAnim  = hdAnim;
        this.hd2Anim = hd2Anim;

        this.directions = directions;
        this.shadow     = shadow;
    }

    /**
     * @return the GRP of the classic graphics, or {@code null} for the installed GRP
     */
    public @Nullable ImageGrp getGrp() {
        return this.grp;
    }

    /**
     * @return the HD anim, or {@code null} for none
     */
    public @Nullable ImageAnim getHdAnim() {
        return this.hdAnim;
    }

    /**
     * @return the HD2 anim, or {@code null} for none
     */
    public @Nullable ImageAnim getHd2Anim() {
        return this.hd2Anim;
    }

    /**
     * @return the frames of a frame set: {@code 1} or {@value SheetLayout#TURNING_DIRECTIONS} for graphics made from a sheet, {@code 0} for
     * graphics of GRP and anim files, whose frame sets the image's {@code images.dat} Graphic Turns tells
     */
    public int getDirections() {
        return this.directions;
    }

    /**
     * @return the graphics of the shadow picture of the sheet the graphics are made from, or {@code null} for none
     */
    public @Nullable ImageGraphics getShadow() {
        return this.shadow;
    }

    /**
     * @return {@code true} if the graphics are made from a sheet, {@code false} otherwise
     */
    public boolean isSheet() {
        return this.directions != 0;
    }

    /**
     * @return {@code true} if the graphics draw nothing ({@link #HIDDEN}), {@code false} otherwise
     */
    public boolean isHidden() {
        return this.grp == null && this.hdAnim == null && this.hd2Anim == null;
    }

    /**
     * @return {@code true} if the graphics have an HD or an HD2 anim, {@code false} otherwise
     */
    public boolean hasAnimation() {
        return this.hdAnim != null || this.hd2Anim != null;
    }

    /**
     * Finds the texture sheet drawn at an asset scale: the anim of that scale, else the anim of the other scale. A sheet that is not prepared yet
     * starts its preparation and is not returned until it is ready; the sheet returned last stays the result meanwhile. The same instance is
     * returned while the anim's bytes are unchanged, so what a renderer makes of a sheet can be kept under that instance.
     *
     * @param scale the asset scale drawn: {@code 4} for HD, {@code 2} for HD2
     * @return the texture sheet, or {@code null} if no anim is ready
     */
    @Override
    public @Nullable TextureSheet findSheet(int scale) {
        EmbeddedAnim hd = this.hdAnim == null ? null : this.hdAnim.getAnim();
        EmbeddedAnim hd2 = this.hd2Anim == null ? null : this.hd2Anim.getAnim();

        return EmbeddedAnim.findSheet(scale, hd, hd2);
    }
}
