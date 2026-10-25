package com.dhj.actinium.compat.lumenized;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.stacks.BlendStateStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Save/restore of the GLSM-tracked GL state around the GTCEu/Lumenized bloom pass.
 *
 * <p>The bloom pass re-binds framebuffers, runs external CCL shader programs and re-points
 * texture units at its own FBO textures. Its Unreal pipeline enables {@code GL_TEXTURE_2D} on
 * units 0..nMips-1 and only unbinds the textures afterwards, never disabling the units. Vanilla
 * code never touches units above 1, so the leaked enables on units 2/3/4 (bound to texture 0)
 * enter GLSM's fixed-function shader key: texture 0 samples as black and the first-person hand
 * and held item render fully black. Snapshot the tracked state of all texture units on entry and
 * restore it on exit; the first restore logs the diverged fields to identify the leak source.
 */
public final class BloomStateGuard {

    private static final Logger LOGGER = LogManager.getLogger("ActiniumBloomStateGuard");

    private static final Saved SAVED = new Saved();
    private static boolean occupied;
    private static boolean divergenceLogged;

    private BloomStateGuard() {
    }

    /** Snapshots the GLSM-tracked state that the bloom pass is allowed to disturb. */
    public static void push() {
        if (occupied) {
            LOGGER.warn("Bloom GL state push while a snapshot is already held; discarding the older one");
        }
        SAVED.framebuffer = GLStateManager.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        SAVED.activeUnit = GLStateManager.getActiveTextureUnit();
        for (int unit = 0; unit < GLStateManager.MAX_TEXTURE_UNITS; unit++) {
            SAVED.bindings[unit] = GLStateManager.getBoundTextureForServerState(unit);
            SAVED.textures[unit] = textureEnabled(unit);
        }
        final BlendStateStack blend = GLStateManager.getBlendState();
        SAVED.blend = blend.isEnabled();
        SAVED.blendSrcRgb = blend.getSrcRgb();
        SAVED.blendDstRgb = blend.getDstRgb();
        SAVED.blendSrcAlpha = blend.getSrcAlpha();
        SAVED.blendDstAlpha = blend.getDstAlpha();
        SAVED.depthTest = GLStateManager.getDepthTest().isEnabled();
        SAVED.depthMask = GLStateManager.getDepthState().isMaskEnabled();
        SAVED.cull = GLStateManager.getCullState().isEnabled();
        occupied = true;
    }

    /** Restores the snapshot taken by {@link #push()}. */
    public static void pop() {
        if (!occupied) {
            LOGGER.error("Bloom GL state pop without a matching push; skipping restore");
            return;
        }
        occupied = false;
        logDivergenceOnce();
        // The bloom pass must run with program 0 afterwards; CCL releases its programs
        // but force it in case an early return skipped a release.
        GLStateManager.glUseProgram(0);
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, SAVED.framebuffer);
        for (int unit = GLStateManager.MAX_TEXTURE_UNITS - 1; unit >= 0; unit--) {
            restoreUnit(unit, SAVED.bindings[unit], SAVED.textures[unit]);
        }
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + SAVED.activeUnit);
        if (SAVED.blend) {
            GLStateManager.enableBlend();
        } else {
            GLStateManager.disableBlend();
        }
        GLStateManager.tryBlendFuncSeparate(SAVED.blendSrcRgb, SAVED.blendDstRgb, SAVED.blendSrcAlpha, SAVED.blendDstAlpha);
        if (SAVED.depthTest) {
            GLStateManager.enableDepthTest();
        } else {
            GLStateManager.disableDepthTest();
        }
        GLStateManager.glDepthMask(SAVED.depthMask);
        if (SAVED.cull) {
            GLStateManager.enableCull();
        } else {
            GLStateManager.disableCull();
        }
    }

    private static boolean textureEnabled(int unit) {
        final int previous = GLStateManager.getActiveTextureUnit();
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        final boolean enabled = GLStateManager.glIsEnabled(GL11.GL_TEXTURE_2D);
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + previous);
        return enabled;
    }

    private static void restoreUnit(int unit, int binding, boolean enabled) {
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GLStateManager.bindTexture(binding);
        if (enabled) {
            GLStateManager.enableTexture();
        } else {
            GLStateManager.disableTexture();
        }
    }

    /** Logs the state fields the bloom pass actually disturbed, on the first restore only. */
    private static void logDivergenceOnce() {
        if (divergenceLogged) {
            return;
        }
        divergenceLogged = true;
        final StringBuilder report = new StringBuilder("Bloom pass GL state divergence on first frame:");
        appendDiff(report, "framebuffer", SAVED.framebuffer, GLStateManager.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING));
        appendDiff(report, "activeTextureUnit", SAVED.activeUnit, GLStateManager.getActiveTextureUnit());
        for (int unit = 0; unit < GLStateManager.MAX_TEXTURE_UNITS; unit++) {
            appendDiff(report, "textureBinding[" + unit + "]", SAVED.bindings[unit], GLStateManager.getBoundTextureForServerState(unit));
            appendDiff(report, "texture2D[" + unit + "]", SAVED.textures[unit], textureEnabled(unit));
        }
        final BlendStateStack blend = GLStateManager.getBlendState();
        appendDiff(report, "blend", SAVED.blend, blend.isEnabled());
        appendDiff(report, "depthTest", SAVED.depthTest, GLStateManager.getDepthTest().isEnabled());
        appendDiff(report, "depthMask", SAVED.depthMask, GLStateManager.getDepthState().isMaskEnabled());
        appendDiff(report, "cull", SAVED.cull, GLStateManager.getCullState().isEnabled());
        LOGGER.info(report.toString());
    }

    private static void appendDiff(StringBuilder report, String name, Object saved, Object current) {
        if (!saved.equals(current)) {
            report.append(' ').append(name).append('=').append(saved).append("->").append(current).append(';');
        }
    }

    private static final class Saved {
        int framebuffer;
        int activeUnit;
        final int[] bindings = new int[GLStateManager.MAX_TEXTURE_UNITS];
        final boolean[] textures = new boolean[GLStateManager.MAX_TEXTURE_UNITS];
        boolean blend;
        int blendSrcRgb;
        int blendDstRgb;
        int blendSrcAlpha;
        int blendDstAlpha;
        boolean depthTest;
        boolean depthMask;
        boolean cull;
    }
}
