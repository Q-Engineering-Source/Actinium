package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredBlendHandler;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredDepthColorHandler;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.BYPASS_CACHE;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.issueBlendFunc;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.mod;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.postBlendFuncChange;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.postVanillaBlendChangeIfMoved;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.snapshotVanillaBlendFunc;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.tryBlendFuncSeparate;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMBlend {

    private GLSMBlend() {}

    public static void glBlendColor(float red, float green, float blue, float alpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordBlendColor(red, green, blue, alpha);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        final boolean bypass = BYPASS_CACHE || !caching;
        if (bypass || red != ctx().blendState.getBlendColorR() || green != ctx().blendState.getBlendColorG() || blue != ctx().blendState.getBlendColorB() || alpha != ctx().blendState.getBlendColorA()) {
            if (caching) {
                mod(ctx().blendState);
                ctx().blendState.setBlendColorR(red);
                ctx().blendState.setBlendColorG(green);
                ctx().blendState.setBlendColorB(blue);
                ctx().blendState.setBlendColorA(alpha);
            }
            RENDER_BACKEND.blendColor(red, green, blue, alpha);
        }
    }

    public static void glBlendFunc(int srcFactor, int dstFactor) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordBlendFunc(srcFactor, dstFactor, srcFactor, dstFactor);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean snapshotted = snapshotVanillaBlendFunc();
        final DeferredBlendHandler bh = GLSMHooks.blendHandler;
        if (bh != null && bh.isBlendLocked()) {
            ctx().blendState.beforeModify();
            bh.deferBlendFunc(srcFactor, dstFactor, srcFactor, dstFactor);
            postVanillaBlendChangeIfMoved(snapshotted);
            return;
        }
        // Cache thread check - only update state on main thread, but always make GL call if needed
        final boolean caching = isCachingEnabled();
        final boolean bypass = BYPASS_CACHE || !caching;
        if (bypass
                || ctx().blendState.getSrcRgb() != srcFactor
                || ctx().blendState.getDstRgb() != dstFactor
                || ctx().blendState.getSrcAlpha() != srcFactor
                || ctx().blendState.getDstAlpha() != dstFactor) {
            if (caching) mod(ctx().blendState).setAll(srcFactor, dstFactor, srcFactor, dstFactor);
            issueBlendFunc(srcFactor, dstFactor, srcFactor, dstFactor);
            postBlendFuncChange(
                srcFactor, dstFactor,
                GLSMConfig.hudCacheOverride ? GL11.GL_ONE : srcFactor,
                GLSMConfig.hudCacheOverride ? GL11.GL_ONE_MINUS_SRC_ALPHA : dstFactor);
        }
        postVanillaBlendChangeIfMoved(snapshotted);
    }

    public static void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glBlendEquationSeparate in display lists not yet implemented - if you see this, please report!");
        }
        final boolean caching = isCachingEnabled();
        final boolean bypass = BYPASS_CACHE || !caching;
        if (bypass || ctx().blendState.getEquationRgb() != modeRGB || ctx().blendState.getEquationAlpha() != modeAlpha) {
            if (caching) {
                mod(ctx().blendState);
                ctx().blendState.setEquationRgb(modeRGB);
                ctx().blendState.setEquationAlpha(modeAlpha);
            }
            RENDER_BACKEND.blendEquationSeparate(modeRGB, modeAlpha);
        }
    }

    public static void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        tryBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordColorMask(red, green, blue, alpha);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final DeferredDepthColorHandler dch = GLSMHooks.depthColorHandler;
        if (dch != null && dch.isDepthColorLocked()) {
            ctx().colorMask.beforeModify();
            dch.deferColorMask(red, green, blue, alpha);
            return;
        }
        // Cache thread check - only update state on main thread, but always make GL call if needed
        final boolean caching = isCachingEnabled();
        final boolean bypass = BYPASS_CACHE || !caching;
        if (bypass || red != ctx().colorMask.red || green != ctx().colorMask.green || blue != ctx().colorMask.blue || alpha != ctx().colorMask.alpha) {
            if (caching) mod(ctx().colorMask).setAll(red, green, blue, alpha);
            RENDER_BACKEND.colorMask(red, green, blue, alpha);
        }
    }

    public static void glClearColor(float red, float green, float blue, float alpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordClearColor(red, green, blue, alpha);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || red != ctx().clearColor.getRed() || green != ctx().clearColor.getGreen() || blue != ctx().clearColor.getBlue() || alpha != ctx().clearColor.getAlpha()) {
            if (caching) {
                mod(ctx().clearColor);
                ctx().clearColor.setRed(red);
                ctx().clearColor.setGreen(green);
                ctx().clearColor.setBlue(blue);
                ctx().clearColor.setAlpha(alpha);
            }
            RENDER_BACKEND.clearColor(red, green, blue, alpha);
        }
    }

    public static void glLogicOp(int opcode) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLogicOp(opcode);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) mod(ctx().logicOpMode).setValue(opcode);
        RENDER_BACKEND.logicOp(opcode);
    }

    public static void glBlendFunc(GlStateManager.SourceFactor srcFactor,
                                   GlStateManager.DestFactor dstFactor) {
        glBlendFunc(srcFactor.factor, dstFactor.factor);
    }

    public static void glLogicOp(GlStateManager.LogicOp logicOperation) {
        glLogicOp(logicOperation.opcode);
    }
}
