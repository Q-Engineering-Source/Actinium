package com.dhj.actinium.texture.mipmap;

import dhj.embeddedt.embeddium.api.util.ColorARGB;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import dhj.embeddedt.embeddium.impl.util.color.ColorSRGB;

import java.util.List;

/**
 * Reads the per-sprite facts the mipmap choice is made from.
 *
 * <p>Both the pixel scan and the decision it feeds live here rather than in the {@code TextureAtlasSprite}
 * mixin: the mixin only forwards what it has, and the two questions - how transparent is this sprite, and does
 * its requested mode still apply - can be answered without a sprite.
 */
public final class SpriteMipmaps {

    /** How many texels the transparency scan reads before checking whether it already knows the answer. */
    private static final int SCAN_BLOCK = 256;
    private static final int SEEN_TRANSPARENT = 1;
    private static final int SEEN_TRANSLUCENT = 2;
    private static final int SEEN_ALL = SEEN_TRANSPARENT | SEEN_TRANSLUCENT;

    private SpriteMipmaps() {
    }

    /**
     * What a sprite's frames say about its transparency.
     *
     * @param level the sprite's transparency level, which the pass optimization demotes materials with
     * @param hasTransparentTexels whether any texel is fully transparent, which is what makes a sprite a cutout
     */
    public record Transparency(SpriteTransparencyLevel level, boolean hasTransparentTexels) {}

    /**
     * Classifies a sprite's frames.
     *
     * <p>The loop is deliberately nothing but int arithmetic and static calls. An earlier shape of this scan
     * called a polymorphic method per texel and sent JDK 25's C2 SuperWord into a compilation that never
     * converged and allocated until the JVM aborted, so a per-texel enum comparison is already too much.
     *
     * @param frames the sprite's frame data, or null for a sprite that has none; a sprite with no base level
     *               reports {@link SpriteTransparencyLevel#TRANSLUCENT}, the level a sprite starts at and the
     *               conservative one, so nothing without pixels is demoted into a pass its texture may not
     *               belong to
     */
    public static Transparency scan(List<int[][]> frames) {
        if (frames == null) {
            return new Transparency(SpriteTransparencyLevel.TRANSLUCENT, false);
        }

        int seen = 0;
        boolean scanned = false;

        outer:
        for (final int[][] frame : frames) {
            if (frame == null || frame.length == 0 || frame[0] == null) {
                continue;
            }
            scanned = true;
            final int[] pixels = frame[0];
            for (int base = 0; base < pixels.length; base += SCAN_BLOCK) {
                final int end = Math.min(base + SCAN_BLOCK, pixels.length);
                for (int i = base; i < end; i++) {
                    final int alpha = ColorARGB.unpackAlpha(pixels[i]);
                    if (alpha == 0) {
                        seen |= SEEN_TRANSPARENT;
                    } else if (alpha < 255) {
                        seen |= SEEN_TRANSLUCENT;
                    }
                }
                if (seen == SEEN_ALL) {
                    break outer;
                }
            }
        }

        if (!scanned) {
            return new Transparency(SpriteTransparencyLevel.TRANSLUCENT, false);
        }

        final SpriteTransparencyLevel level = (seen & SEEN_TRANSLUCENT) != 0
            ? SpriteTransparencyLevel.TRANSLUCENT
            : (seen & SEEN_TRANSPARENT) != 0 ? SpriteTransparencyLevel.TRANSPARENT : SpriteTransparencyLevel.OPAQUE;

        return new Transparency(level, (seen & SEEN_TRANSPARENT) != 0);
    }

    /**
     * Classifies a sprite's base frames for mipmap generation and, for eligible block sprites, gives each
     * transparent texel the frame's alpha-weighted visible colour. The rewrite happens in place before
     * downsampling so transparent atlas borders do not bleed into the sprite's mip levels.
     *
     * <p>A null frame list keeps the conservative transparency level used by the sprite mixin. A present but
     * empty list has no pixels to classify and therefore remains opaque, matching the initialized level in the
     * original generation hook.
     *
     * @param frames sprite frame data, or null when no frame data exists
     * @param mipLevel highest mip level requested by the caller
     * @param iconName sprite name used to decide whether block-colour correction applies
     * @return the combined transparency classification and whether any frame has fully transparent texels
     */
    public static Transparency analyzeForGeneration(List<int[][]> frames, int mipLevel, String iconName) {
        if (frames == null) {
            return new Transparency(SpriteTransparencyLevel.TRANSLUCENT, false);
        }

        final boolean rewriteTransparentColors = mipLevel > 0 && isBlockTexture(iconName)
            && !iconName.contains("leaves");
        int seen = 0;

        outer:
        for (final int[][] frame : frames) {
            if (frame == null || frame.length == 0 || frame[0] == null) {
                continue;
            }

            final int[] pixels = frame[0];
            float red = 0.0F;
            float green = 0.0F;
            float blue = 0.0F;
            float totalWeight = 0.0F;
            for (int base = 0; base < pixels.length; base += SCAN_BLOCK) {
                final int end = Math.min(base + SCAN_BLOCK, pixels.length);
                for (int i = base; i < end; i++) {
                    final int color = pixels[i];
                    final int alpha = ColorARGB.unpackAlpha(color);
                    if (alpha == 0) {
                        seen |= SEEN_TRANSPARENT;
                    } else if (alpha < 255) {
                        seen |= SEEN_TRANSLUCENT;
                    }
                    if (rewriteTransparentColors && alpha > 0) {
                        final float weight = alpha;
                        red += ColorSRGB.srgbToLinear(ColorARGB.unpackRed(color)) * weight;
                        green += ColorSRGB.srgbToLinear(ColorARGB.unpackGreen(color)) * weight;
                        blue += ColorSRGB.srgbToLinear(ColorARGB.unpackBlue(color)) * weight;
                        totalWeight += weight;
                    }
                }
                if (!rewriteTransparentColors && seen == SEEN_ALL) {
                    break outer;
                }
            }

            if (rewriteTransparentColors && totalWeight > 0.0F) {
                final int averageColor = ColorSRGB.linearToSrgb(
                    red / totalWeight, green / totalWeight, blue / totalWeight, 0);
                for (int i = 0; i < pixels.length; i++) {
                    if (ColorARGB.unpackAlpha(pixels[i]) == 0) {
                        pixels[i] = averageColor;
                    }
                }
            }
        }

        final SpriteTransparencyLevel level = (seen & SEEN_TRANSLUCENT) != 0
            ? SpriteTransparencyLevel.TRANSLUCENT
            : (seen & SEEN_TRANSPARENT) != 0 ? SpriteTransparencyLevel.TRANSPARENT : SpriteTransparencyLevel.OPAQUE;
        return new Transparency(level, (seen & SEEN_TRANSPARENT) != 0);
    }

    private static boolean isBlockTexture(String iconName) {
        return iconName != null && iconName.startsWith("block", iconName.indexOf(':') + 1);
    }

    /**
     * The strategy {@link MipmapGenerator} should be given for a sprite.
     *
     * @param requested a mode declared by the sprite's {@code .mcmeta}, or a name-based fallback, or null
     * @param explicit  true when the sprite's {@code .mcmeta} declared {@code requested}, in which case it
     *                  always applies; false for a name-based fallback, which only means anything while the
     *                  sprite really is a cutout
     * @param hasTransparentTexels whether the sprite has any fully transparent texel
     * @return the mode to generate with, or null to let the generator resolve {@link MipmapStrategy#AUTO} from
     *         the transparency scan
     */
    public static MipmapStrategy resolve(MipmapStrategy requested, boolean explicit, boolean hasTransparentTexels) {
        if (requested != null && !explicit && !hasTransparentTexels) {
            return null;
        }
        return requested;
    }
}
