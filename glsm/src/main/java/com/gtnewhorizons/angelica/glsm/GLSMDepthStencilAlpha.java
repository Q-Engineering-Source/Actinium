package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredAlphaHandler;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredDepthColorHandler;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.BYPASS_CACHE;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.mod;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMDepthStencilAlpha {

    private GLSMDepthStencilAlpha() {}

    public static void glDepthFunc(int func) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDepthFunc(func);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || func != ctx().depthState.getFunc()) {
            if (caching) mod(ctx().depthState).setFunc(func);
            RENDER_BACKEND.depthFunc(func);
        }
    }

    public static void glDepthMask(boolean mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDepthMask(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final DeferredDepthColorHandler dch = GLSMHooks.depthColorHandler;
        if (dch != null && dch.isDepthColorLocked()) {
            ctx().depthState.beforeModify();
            dch.deferDepthEnable(mask);
            return;
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || mask != ctx().depthState.isMaskEnabled()) {
            if (caching) mod(ctx().depthState).setMaskEnabled(mask);
            RENDER_BACKEND.depthMask(mask);
        }
    }

    public static void glClearDepth(double depth) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordClearDepth(depth);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) mod(ctx().depthState).setClearValue(depth);
        RENDER_BACKEND.clearDepth(depth);
    }

    public static void glAlphaFunc(int function, float reference) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordAlphaFunc(function, reference);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final DeferredAlphaHandler ah = GLSMHooks.alphaHandler;
        if (ah != null && ah.isAlphaTestLocked()) {
            ctx().alphaState.beforeModify();
            ah.deferAlphaFunc(function, reference);
            return;
        }
        if (isCachingEnabled()
            && (function != ctx().alphaState.getFunction() || Float.compare(reference, ctx().alphaState.getReference()) != 0)) {
            mod(ctx().alphaState);
            ctx().alphaState.setFunction(function);
            ctx().alphaState.setReference(reference);
            ctx().fragmentGeneration++;
            if (GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
        }
    }

    public static void glStencilFunc(int func, int ref, int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilFunc(func, ref, mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().stencilState.getFuncFront() != func || ctx().stencilState.getRefFront() != ref || ctx().stencilState.getValueMaskFront() != mask) {
            if (caching) {
                mod(ctx().stencilState).setFunc(func, ref, mask);
            }
            RENDER_BACKEND.stencilFunc(func, ref, mask);
        }
    }

    public static void glStencilMask(int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilMask(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().stencilState.getWriteMaskFront() != mask) {
            if (caching) {
                mod(ctx().stencilState).setWriteMask(mask);
            }
            RENDER_BACKEND.stencilMask(mask);
        }
    }

    public static void glStencilOp(int fail, int zfail, int zpass) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilOp(fail, zfail, zpass);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().stencilState.getFailOpFront() != fail || ctx().stencilState.getZFailOpFront() != zfail || ctx().stencilState.getZPassOpFront() != zpass) {
            if (caching) {
                mod(ctx().stencilState).setOp(fail, zfail, zpass);
            }
            RENDER_BACKEND.stencilOp(fail, zfail, zpass);
        }
    }

    public static void glClearStencil(int s) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordClearStencil(s);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().stencilState.getClearValue() != s) {
            if (caching) {
                mod(ctx().stencilState).setClearValue(s);
            }
            RENDER_BACKEND.clearStencil(s);
        }
    }

    public static void glStencilFuncSeparate(int face, int func, int ref, int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilFuncSeparate(face, func, ref, mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        boolean needsUpdate = BYPASS_CACHE || !caching;
        if (!needsUpdate) {
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                needsUpdate = ctx().stencilState.getFuncFront() != func || ctx().stencilState.getRefFront() != ref || ctx().stencilState.getValueMaskFront() != mask;
            }
            if (!needsUpdate && (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK)) {
                needsUpdate = ctx().stencilState.getFuncBack() != func || ctx().stencilState.getRefBack() != ref || ctx().stencilState.getValueMaskBack() != mask;
            }
        }
        if (needsUpdate) {
            if (caching) {
                if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setFuncFront(func);
                    mod(ctx().stencilState).setRefFront(ref);
                    mod(ctx().stencilState).setValueMaskFront(mask);
                }
                if (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setFuncBack(func);
                    mod(ctx().stencilState).setRefBack(ref);
                    mod(ctx().stencilState).setValueMaskBack(mask);
                }
            }
            RENDER_BACKEND.stencilFuncSeparate(face, func, ref, mask);
        }
    }

    public static void glStencilMaskSeparate(int face, int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilMaskSeparate(face, mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        boolean needsUpdate = BYPASS_CACHE || !caching;
        if (!needsUpdate) {
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                needsUpdate = ctx().stencilState.getWriteMaskFront() != mask;
            }
            if (!needsUpdate && (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK)) {
                needsUpdate = ctx().stencilState.getWriteMaskBack() != mask;
            }
        }
        if (needsUpdate) {
            if (caching) {
                if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setWriteMaskFront(mask);
                }
                if (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setWriteMaskBack(mask);
                }
            }
            RENDER_BACKEND.stencilMaskSeparate(face, mask);
        }
    }

    public static void glStencilOpSeparate(int face, int sfail, int dpfail, int dppass) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilOpSeparate(face, sfail, dpfail, dppass);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        boolean needsUpdate = BYPASS_CACHE || !caching;
        if (!needsUpdate) {
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                needsUpdate = ctx().stencilState.getFailOpFront() != sfail || ctx().stencilState.getZFailOpFront() != dpfail || ctx().stencilState.getZPassOpFront() != dppass;
            }
            if (!needsUpdate && (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK)) {
                needsUpdate = ctx().stencilState.getFailOpBack() != sfail || ctx().stencilState.getZFailOpBack() != dpfail || ctx().stencilState.getZPassOpBack() != dppass;
            }
        }
        if (needsUpdate) {
            if (caching) {
                if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setFailOpFront(sfail);
                    mod(ctx().stencilState).setZFailOpFront(dpfail);
                    mod(ctx().stencilState).setZPassOpFront(dppass);
                }
                if (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setFailOpBack(sfail);
                    mod(ctx().stencilState).setZFailOpBack(dpfail);
                    mod(ctx().stencilState).setZPassOpBack(dppass);
                }
            }
            RENDER_BACKEND.stencilOpSeparate(face, sfail, dpfail, dppass);
        }
    }
}
