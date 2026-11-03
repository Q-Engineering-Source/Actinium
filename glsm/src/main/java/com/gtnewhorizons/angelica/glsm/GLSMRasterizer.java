package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.stacks.IntegerStateStack;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.BYPASS_CACHE;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAX_CLIP_PLANES;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.hintShift;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isDriverHint;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isHintMode;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.lineWidthMax;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.lineWidthMin;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.mod;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.pointParameter;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMRasterizer {

    private GLSMRasterizer() {}

    public static void glPointParameterf(int pname, float param) {
        pointParameter(pname, param, 0.0f, 0.0f);
    }

    public static void glPointParameteri(int pname, int param) {
        pointParameter(pname, param, 0.0f, 0.0f);
    }

    public static void glPointParameter(int pname, FloatBuffer params) {
        final int pos = params.position();
        final boolean attenuation = pname == GL14.GL_POINT_DISTANCE_ATTENUATION;
        pointParameter(pname, params.get(pos), attenuation ? params.get(pos + 1) : 0.0f, attenuation ? params.get(pos + 2) : 0.0f);
    }

    public static void glPointParameter(int pname, IntBuffer params) {
        final int pos = params.position();
        final boolean attenuation = pname == GL14.GL_POINT_DISTANCE_ATTENUATION;
        pointParameter(pname, params.get(pos), attenuation ? params.get(pos + 1) : 0.0f, attenuation ? params.get(pos + 2) : 0.0f);
    }

    public static void glShadeModel(int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordShadeModel(mode);
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        final int oldValue = ctx().shadeModelState.getValue();
        final boolean needsUpdate = BYPASS_CACHE || !caching || oldValue != mode;

        if (needsUpdate) {
            if (caching) {
                mod(ctx().shadeModelState).setValue(mode);
            }
        }
    }

    public static void glViewport(int x, int y, int width, int height) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordViewport(x, y, width, height);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        RENDER_BACKEND.viewport(x, y, width, height);
        if (isCachingEnabled()) {
            mod(ctx().viewportState).setViewPort(x, y, width, height);
        }
    }

    public static void glLineWidth(float width) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLineWidth(width);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().lineState.getWidth() != width) {
            if (caching) {
                mod(ctx().lineState).setWidth(width);
            }
            RENDER_BACKEND.lineWidth(Math.max(lineWidthMin, Math.min(lineWidthMax, width)));
        }
    }

    public static void glCullFace(int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordCullFace(mode);
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().polygonState.getCullFaceMode() != mode) {
            if (caching) {
                mod(ctx().polygonState).setCullFaceMode(mode);
            }
            RENDER_BACKEND.cullFace(mode);
        }
    }

    public static void glFrontFace(int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordFrontFace(mode);
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().polygonState.getFrontFace() != mode) {
            if (caching) {
                mod(ctx().polygonState).setFrontFace(mode);
            }
            RENDER_BACKEND.frontFace(mode);
        }
    }

    public static void glHint(int target, int hint) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordHint(target, hint);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final int shift = hintShift(target);
        final boolean tracked = shift >= 0 && isHintMode(hint);
        if (tracked) {
            final IntegerStateStack hints = ctx().hintState;
            final int code = hint == GL11.GL_FASTEST ? 1 : hint == GL11.GL_NICEST ? 2 : 0;
            final int packed = hints.getValue() & ~(3 << shift) | code << shift;
            if (packed != hints.getValue()) mod(hints).setValue(packed);
        }
        // The removed targets (perspective correction, point smooth, fog, generate mipmap) would raise
        // GL_INVALID_ENUM on the core profile; a value the driver rejects is still forwarded so it reports
        // the error instead of the call being dropped.
        if (!isDriverHint(target) || !isHintMode(hint)) {
            RENDER_BACKEND.hint(target, hint);
        }
    }

    public static void glLineStipple(int factor, short pattern) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLineStipple(factor, pattern);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        mod(ctx().lineState).setStippleFactor(factor);
        mod(ctx().lineState).setStipplePattern(pattern);
    }

    public static void glPointSize(float size) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPointSize(size);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().pointState.getSize() != size) {
            if (caching) {
                mod(ctx().pointState).setSize(size);
            }
            RENDER_BACKEND.pointSize(size);
        }
    }

    public static void glPolygonMode(int face, int polygonMode) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPolygonMode(face, polygonMode);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        // Track front/back separately in cache (Mesa compat semantics), but always issue GL_FRONT_AND_BACK to the driver (core profile constraint).
        final boolean caching = isCachingEnabled();
        final boolean needsUpdate;
        if (face == GL11.GL_FRONT) {
            needsUpdate = BYPASS_CACHE || !caching || ctx().polygonState.getFrontMode() != polygonMode;
            if (caching && needsUpdate) mod(ctx().polygonState).setFrontMode(polygonMode);
        } else if (face == GL11.GL_BACK) {
            needsUpdate = BYPASS_CACHE || !caching || ctx().polygonState.getBackMode() != polygonMode;
            if (caching && needsUpdate) mod(ctx().polygonState).setBackMode(polygonMode);
        } else { // GL_FRONT_AND_BACK
            needsUpdate = BYPASS_CACHE || !caching || ctx().polygonState.getFrontMode() != polygonMode || ctx().polygonState.getBackMode() != polygonMode;
            if (caching && needsUpdate) {
                mod(ctx().polygonState).setFrontMode(polygonMode);
                mod(ctx().polygonState).setBackMode(polygonMode);
            }
        }
        if (needsUpdate) {
            RENDER_BACKEND.polygonMode(GL11.GL_FRONT_AND_BACK, polygonMode);
        }
    }

    public static void glPolygonOffset(float factor, float units) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPolygonOffset(factor, units);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().polygonState.getOffsetFactor() != factor || ctx().polygonState.getOffsetUnits() != units) {
            if (caching) {
                mod(ctx().polygonState).setOffsetFactor(factor);
                mod(ctx().polygonState).setOffsetUnits(units);
            }
            RENDER_BACKEND.polygonOffset(factor, units);
        }
    }

    public static void glScissor(int x, int y, int width, int height) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordScissor(x, y, width, height);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        RENDER_BACKEND.scissor(x, y, width, height);
        if (isCachingEnabled()) {
            mod(ctx().scissorState).setScissor(x, y, width, height);
        }
    }

    public static void glClipPlane(int plane, DoubleBuffer equation) {
        if (DisplayListManager.isRecording()) {
            final int pos = equation.position();
            DisplayListManager.recordClipPlane(plane, equation.get(pos), equation.get(pos + 1), equation.get(pos + 2), equation.get(pos + 3));
            return;
        }
        final int index = plane - GL11.GL_CLIP_PLANE0;
        if (index < 0 || index >= MAX_CLIP_PLANES) return;
        final int pos = equation.position();
        ctx().clipPlaneState.setPlane(index, equation.get(pos), equation.get(pos + 1), equation.get(pos + 2), equation.get(pos + 3), ctx().modelViewMatrix);
        ctx().clipPlaneGeneration++;
    }

    public static void glCullFace(GlStateManager.CullFace cullFace) {
        glCullFace(cullFace.mode);
    }
}
