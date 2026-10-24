package com.dhj.actinium.render;

import net.minecraft.tileentity.TileEntityEndPortal;
import org.lwjgl.opengl.GL14;

/**
 * Decides whether a TileEntityEndPortalRenderer render call is answered by the core-profile
 * replacement renderer or left on the legacy vanilla path (executed through the glsm
 * fixed-function simulation).
 */
public final class EndPortalRenderPolicy {
    private EndPortalRenderPolicy() {
    }

    /**
     * The dispatcher only renders block entities that belong to a world. A world-less block
     * entity is a synthetic render call issued by another renderer, e.g. BetterPortals draws
     * its starfield overlay through a dummy block entity and relies on the exact legacy blend
     * state hooks (CONSTANT_ALPHA via shouldRenderFace) and projective texgen behavior. Without
     * a shader pack those synthetic calls stay on the legacy vanilla path, which the glsm
     * fixed-function simulation services faithfully; with a shader pack an Iris gbuffers program
     * owns the draw and glsm defers to it, so the projective texgen emulation is silently
     * bypassed and the overlay degrades to garbage. Synthetic calls therefore go through the
     * shader-compatible replacement renderer, which reproduces the fade hook via
     * {@link #isLegacyFadeHookBlend(int, int)}.
     *
     * @param portal block entity passed to the renderer
     * @param shaderPackInUse whether an Iris shader pack currently owns world rendering
     * @return true to use the core-profile replacement renderer; false to keep the legacy path
     */
    public static boolean shouldUseReplacementRenderer(TileEntityEndPortal portal, boolean shaderPackInUse) {
        return portal.getWorld() != null || shaderPackInUse;
    }

    /**
     * Recognizes the legacy fade hook that synthetic overlay renderers (BetterPortals) fire from
     * a shouldRenderFace override: the first-layer blend factors are switched to
     * CONSTANT_ALPHA/ONE_MINUS_CONSTANT_ALPHA and the constant alpha carries the overlay opacity.
     *
     * @param srcRgb tracked source RGB blend factor observed after face evaluation
     * @param dstRgb tracked destination RGB blend factor observed after face evaluation
     * @return true when the blend factors can only come from the overlay fade hook
     */
    public static boolean isLegacyFadeHookBlend(int srcRgb, int dstRgb) {
        return srcRgb == GL14.GL_CONSTANT_ALPHA && dstRgb == GL14.GL_ONE_MINUS_CONSTANT_ALPHA;
    }
}
