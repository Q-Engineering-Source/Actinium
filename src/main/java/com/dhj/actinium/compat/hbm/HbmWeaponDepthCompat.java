package com.dhj.actinium.compat.hbm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.coderbot.iris.apiimpl.IrisApiV0Impl;
import net.coderbot.iris.pipeline.HandRenderer;

/**
 * Keeps HBM's first-person weapon depth handling out of Actinium's shader-pipeline hand pass.
 *
 * <p>HBM's Sedna weapon renderers probe OptiFine's shader state through
 * {@code com.hbm.util.ShaderHelper}, which resolves to a no-op unless the OptiFine class exists.
 * Actinium ships no OptiFine class, so the probe is always negative and HBM takes its "no shaders"
 * branch: a depth-buffer clear inside {@link HandRenderer}, which discards the world depth every
 * following depth-reading effect needs. Replacing that clear with the projection compression
 * OptiFine's hand-depth treatment performs keeps the weapon unclipped without touching depth
 * contents.</p>
 */
public final class HbmWeaponDepthCompat {
    private HbmWeaponDepthCompat() {
    }

    /**
     * Returns whether HBM must leave the depth buffer alone in the current frame: with a shader
     * pack loaded the buffer carries world depth the pipeline reads back. Without a pack the
     * clear is HBM's intended unclipping and stays in place.
     */
    public static boolean shouldSkipWeaponDepthClear() {
        return IrisApiV0Impl.INSTANCE.isShaderPackInUse();
    }

    /**
     * Applies the hand-depth projection compression HBM expects from OptiFine. HBM calls this
     * between loading an identity projection and rebuilding the hand projection, so the scale
     * multiplies into the weapon projection exactly as it does under OptiFine.
     */
    public static void applyShaderHandDepth() {
        GLStateManager.glScalef(1.0F, 1.0F, HandRenderer.DEPTH);
    }
}
