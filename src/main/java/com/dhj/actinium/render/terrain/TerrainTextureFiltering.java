package com.dhj.actinium.render.terrain;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.dhj.actinium.runtime.ActiniumRuntime;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import dhj.embeddedt.embeddium.impl.texture.TextureFilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;

/**
 * Resolves the terrain texture filtering settings that the block atlas sampler and the terrain shader
 * are configured from.
 *
 * <p>Upstream keeps this logic on its sodium options class and reads the vanilla anisotropic-filtering
 * setting for the amount. Vanilla dropped that setting after 1.7.10, so the amount lives in Actinium's
 * own options instead, and the resolution is kept out of the options class because querying the driver
 * needs a live GL context.
 */
public final class TerrainTextureFiltering {

    /** Anisotropic filtering below 2x is indistinguishable from trilinear filtering, so 2x is the floor. */
    private static final int MIN_ANISOTROPY_LEVEL = 1;
    private static final int MAX_ANISOTROPY_LEVEL = 4;

    private TerrainTextureFiltering() {
    }

    /**
     * The largest power-of-two anisotropy level the driver offers, capped at the 16x that every desktop
     * API supports. Zero means the extension is unavailable.
     */
    public static int maxAnisotropyLevel() {
        if (!BackendManager.RENDER_BACKEND.isAnisotropicSupported()) {
            return 0;
        }

        final int driverMax = (int) GLStateManager.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
        if (driverMax < 1 << MIN_ANISOTROPY_LEVEL) {
            return 0;
        }

        return Math.min(31 - Integer.numberOfLeadingZeros(driverMax), MAX_ANISOTROPY_LEVEL);
    }

    public static boolean anisotropySupported() {
        return maxAnisotropyLevel() > 0;
    }

    /** False when the driver offers no amount to choose from, which leaves the amount slider disabled. */
    public static boolean hasAnisotropyRange() {
        return maxAnisotropyLevel() > MIN_ANISOTROPY_LEVEL;
    }

    public static int minAnisotropyLevel() {
        return MIN_ANISOTROPY_LEVEL;
    }

    public static TextureFilterMode effectiveTextureFilterMode() {
        return resolveFilterMode(ActiniumRuntime.options().quality.textureFilterMode);
    }

    /**
     * Whether the terrain fragment shader should pick the mip level itself instead of letting the texture's
     * own filter state choose it. Plain two-argument sampling is the vanilla behaviour and stays in effect
     * only when no filter mode asks for the shader path and the texel-sampling option already wants nearest,
     * because the snapped path reproduces that blend in the shader.
     */
    public static boolean usesTerrainTexelSnap() {
        return effectiveTextureFilterMode() != TextureFilterMode.NONE
            || !ActiniumRuntime.options().quality.texelSampling.isNearest();
    }

    /**
     * The mip level count the block atlas really has, which is what every per-level buffer and shader define
     * has to be sized from. {@code TextureMap.loadTextureAtlas} lowers its own count when a stitched sprite
     * cannot support the configured one, so until the atlas has been loaded once the requested setting is the
     * only thing there is to go on.
     */
    public static int terrainMipmapLevels() {
        final int loaded = SodiumGameOptions.getAtlasMipmapLevels();
        return loaded >= 0 ? loaded : Minecraft.getMinecraft().gameSettings.mipmapLevels;
    }

    public static TextureFilterMode resolveFilterMode(TextureFilterMode mode) {
        return resolveFilterMode(mode, anisotropySupported());
    }

    /** Policy half of {@link #resolveFilterMode(TextureFilterMode)}, split out so it can be verified without a GL context. */
    static TextureFilterMode resolveFilterMode(TextureFilterMode mode, boolean anisotropySupported) {
        if (mode == null) {
            mode = TextureFilterMode.RGSS_ANISOTROPIC;
        }
        if (mode.usesAnisotropy() && !anisotropySupported) {
            // Keep the part of the mode the driver can still honour.
            return mode.usesRgss() ? TextureFilterMode.RGSS : TextureFilterMode.NONE;
        }
        return mode;
    }

    /** The anisotropy amount for the terrain sampler, or 1 when the selected mode does not filter at all. */
    public static int resolvedAnisotropicFiltering() {
        if (!effectiveTextureFilterMode().usesAnisotropy()) {
            return 1;
        }

        return anisotropyAmountFor(ActiniumRuntime.options().quality.anisotropicLevel, maxAnisotropyLevel());
    }

    /** Amount half of {@link #resolvedAnisotropicFiltering()}, split out for the same reason as above. */
    static int anisotropyAmountFor(int level, int maxAnisotropyLevel) {
        return 1 << MathHelper.clamp(level, MIN_ANISOTROPY_LEVEL, maxAnisotropyLevel);
    }
}
