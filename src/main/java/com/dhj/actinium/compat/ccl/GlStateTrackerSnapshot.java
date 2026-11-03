package com.dhj.actinium.compat.ccl;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.stacks.BlendStateStack;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.Deque;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;

/**
 * Replacement save/restore logic for CodeChickenLib's {@code GlStateTracker}.
 *
 * <p>CCL's tracker saves/restores GL state by reading the vanilla
 * {@code net.minecraft.client.renderer.GlStateManager} field cache. Under
 * Actinium's GLSM redirector those methods never run, so the field cache stays
 * frozen at its Java initial values; DE's per-frame HUD drawing therefore resets
 * the real GL state (blend/alpha/depth/cull/lighting) to those defaults every
 * frame, corrupting clouds, grass tinting and the main menu. This class reads
 * and restores the real GLSM tracked state instead.
 *
 * <p>Kept outside the mixin package because mixin classes must not expose
 * helper classes that the mixin target (or its callers) reference directly.
 */
public final class GlStateTrackerSnapshot {

    private static final Deque<Saved> STATE_STACK = new ArrayDeque<>();

    private GlStateTrackerSnapshot() {
    }

    public static void push() {
        final Saved state = new Saved();
        state.alphaTest = GLStateManager.getAlphaTest().isEnabled();
        state.alphaFunc = GLStateManager.getAlphaState().getFunction();
        state.alphaRef = GLStateManager.getAlphaState().getReference();
        state.lighting = GLStateManager.getLightingState().isEnabled();
        final BlendStateStack blend = GLStateManager.getBlendState();
        state.blend = blend.isEnabled();
        state.blendSrcRgb = blend.getSrcRgb();
        state.blendDstRgb = blend.getDstRgb();
        state.blendSrcAlpha = blend.getSrcAlpha();
        state.blendDstAlpha = blend.getDstAlpha();
        state.depthTest = GLStateManager.getDepthTest().isEnabled();
        state.depthFunc = GLStateManager.getDepthState().getFunc();
        state.depthMask = GLStateManager.getDepthState().isMaskEnabled();
        state.cull = GLStateManager.getCullState().isEnabled();
        state.cullMode = GLSMQueries.glGetInteger(GL11.GL_CULL_FACE_MODE);
        state.rescaleNormal = GLStateManager.getRescaleNormalState().isEnabled();
        STATE_STACK.push(state);
    }

    public static void pop() {
        if (STATE_STACK.isEmpty()) {
            throw new IllegalStateException("Unable to pop the GL state as there is no saved state!");
        }
        final Saved state = STATE_STACK.pop();
        if (state.alphaTest) {
            GLStateManager.enableAlphaTest();
        } else {
            GLStateManager.disableAlphaTest();
        }
        GLSMDepthStencilAlpha.glAlphaFunc(state.alphaFunc, state.alphaRef);
        if (state.lighting) {
            GLStateManager.enableLighting();
        } else {
            GLStateManager.disableLighting();
        }
        if (state.blend) {
            GLStateManager.enableBlend();
        } else {
            GLStateManager.disableBlend();
        }
        GLStateManager.tryBlendFuncSeparate(state.blendSrcRgb, state.blendDstRgb, state.blendSrcAlpha, state.blendDstAlpha);
        if (state.depthTest) {
            GLStateManager.enableDepthTest();
        } else {
            GLStateManager.disableDepthTest();
        }
        GLSMDepthStencilAlpha.glDepthFunc(state.depthFunc);
        GLSMDepthStencilAlpha.glDepthMask(state.depthMask);
        if (state.cull) {
            GLStateManager.enableCull();
        } else {
            GLStateManager.disableCull();
        }
        GLSMRasterizer.glCullFace(state.cullMode);
        if (state.rescaleNormal) {
            GLStateManager.enableRescaleNormal();
        } else {
            GLStateManager.disableRescaleNormal();
        }
    }

    private static final class Saved {
        boolean alphaTest;
        int alphaFunc;
        float alphaRef;
        boolean lighting;
        boolean blend;
        int blendSrcRgb;
        int blendDstRgb;
        int blendSrcAlpha;
        int blendDstAlpha;
        boolean depthTest;
        int depthFunc;
        boolean depthMask;
        boolean cull;
        int cullMode;
        boolean rescaleNormal;
    }
}
