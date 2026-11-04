package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.stacks.BooleanStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.LineStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.PointStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.PolygonStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.ScissorStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.ViewPortStateStack;
import com.gtnewhorizons.angelica.glsm.states.ClipPlaneState;
import java.nio.ByteBuffer;
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
import static com.gtnewhorizons.angelica.glsm.GLSMDraws.guardUnsupportedFFP;

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
        ctx().exitCapture.write(ExitStateCapture.SHADE_MODEL);
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
        ctx().exitCapture.write(ExitStateCapture.CULL_FACE);
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
        ctx().exitCapture.write(ExitStateCapture.FRONT_FACE);
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
        ctx().exitCapture.write(ExitStateCapture.OFFSET_VALUES);
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

    public static void glGetClipPlane(int plane, DoubleBuffer equation) {
            final int index = plane - GL11.GL_CLIP_PLANE0;
            if (index < 0 || index >= MAX_CLIP_PLANES) return;
            ctx().clipPlaneState.getEyePlane(index, equation);
        }

    public static void enableScissorTest() {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordEnable(GL11.GL_SCISSOR_TEST);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            ctx().scissorTest.enable();
        }

    public static void disableScissorTest() {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordDisable(GL11.GL_SCISSOR_TEST);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            ctx().scissorTest.disable();
        }

    public static void enableCull() {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordEnable(GL11.GL_CULL_FACE);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            ctx().cullState.enable();
        }

    public static void disableCull() {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordDisable(GL11.GL_CULL_FACE);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            ctx().cullState.disable();
        }

    public static void glPolygonOffsetClamp(float factor, float units, float clamp) {
            if (clamp == 0.0f) {
                glPolygonOffset(factor, units);
                return;
            }
            if (DisplayListManager.isRecording()) {
                throw new UnsupportedOperationException("glPolygonOffsetClamp in display lists not yet implemented - if you see this, please report!");
            }
            ctx().exitCapture.write(ExitStateCapture.OFFSET_VALUES);
            final boolean caching = isCachingEnabled();
            if (BYPASS_CACHE || !caching || ctx().polygonState.getOffsetFactor() != factor || ctx().polygonState.getOffsetUnits() != units || ctx().polygonState.getOffsetClamp() != clamp) {
                if (caching) {
                    mod(ctx().polygonState).setOffsetFactor(factor);
                    mod(ctx().polygonState).setOffsetUnits(units);
                    mod(ctx().polygonState).setOffsetClamp(clamp);
                }
                RENDER_BACKEND.polygonOffsetClamp(factor, units, clamp);
            }
        }

    public static boolean anyClipPlaneEnabled() {
            return ctx().clipPlaneEnabledMask != 0;
        }

    public static void glSampleCoverage(float value, boolean invert) {
            if (DisplayListManager.isRecording()) {
                throw new UnsupportedOperationException("glSampleCoverage in display lists not yet implemented - if you see this, please report!");
            }
            RENDER_BACKEND.sampleCoverage(value, invert);
        }

    public static void glPolygonStipple(ByteBuffer pattern) {
            guardUnsupportedFFP("glPolygonStipple", "glPolygonStipple is not available in GL 3.3 core profile.");
        }

    public static IntegerStateStack getShadeModelState() {
            return ctx().shadeModelState;
        }

    public static BooleanStateStack getScissorTest() {
            return ctx().scissorTest;
        }

    public static BooleanStateStack getCullState() {
            return ctx().cullState;
        }

    public static BooleanStateStack getDitherState() {
            return ctx().ditherState;
        }

    public static BooleanStateStack getLineSmoothState() {
            return ctx().lineSmoothState;
        }

    public static BooleanStateStack getLineStippleState() {
            return ctx().lineStippleState;
        }

    public static BooleanStateStack getPointSmoothState() {
            return ctx().pointSmoothState;
        }

    public static BooleanStateStack getPolygonSmoothState() {
            return ctx().polygonSmoothState;
        }

    public static BooleanStateStack getPolygonStippleState() {
            return ctx().polygonStippleState;
        }

    public static BooleanStateStack getMultisampleState() {
            return ctx().multisampleState;
        }

    public static BooleanStateStack getSampleAlphaToCoverageState() {
            return ctx().sampleAlphaToCoverageState;
        }

    public static BooleanStateStack getSampleAlphaToOneState() {
            return ctx().sampleAlphaToOneState;
        }

    public static BooleanStateStack getSampleCoverageState() {
            return ctx().sampleCoverageState;
        }

    public static BooleanStateStack getPolygonOffsetPointState() {
            return ctx().polygonOffsetPointState;
        }

    public static BooleanStateStack getPolygonOffsetLineState() {
            return ctx().polygonOffsetLineState;
        }

    public static BooleanStateStack getPolygonOffsetFillState() {
            return ctx().polygonOffsetFillState;
        }

    public static LineStateStack getLineState() {
            return ctx().lineState;
        }

    public static PointStateStack getPointState() {
            return ctx().pointState;
        }

    public static IntegerStateStack getHintState() {
            return ctx().hintState;
        }

    public static BooleanStateStack getPointSpriteState() {
            return ctx().pointSpriteState;
        }

    public static BooleanStateStack getEdgeFlagState() {
            return ctx().edgeFlagState;
        }

    public static PolygonStateStack getPolygonState() {
            return ctx().polygonState;
        }

    public static BooleanStateStack getAutoNormalState() {
            return ctx().autoNormalState;
        }

    public static BooleanStateStack getMap1Color4State() {
            return ctx().map1Color4State;
        }

    public static BooleanStateStack getMap1IndexState() {
            return ctx().map1IndexState;
        }

    public static BooleanStateStack getMap1NormalState() {
            return ctx().map1NormalState;
        }

    public static BooleanStateStack getMap1TextureCoord1State() {
            return ctx().map1TextureCoord1State;
        }

    public static BooleanStateStack getMap1TextureCoord2State() {
            return ctx().map1TextureCoord2State;
        }

    public static BooleanStateStack getMap1TextureCoord3State() {
            return ctx().map1TextureCoord3State;
        }

    public static BooleanStateStack getMap1TextureCoord4State() {
            return ctx().map1TextureCoord4State;
        }

    public static BooleanStateStack getMap1Vertex3State() {
            return ctx().map1Vertex3State;
        }

    public static BooleanStateStack getMap1Vertex4State() {
            return ctx().map1Vertex4State;
        }

    public static BooleanStateStack getMap2Color4State() {
            return ctx().map2Color4State;
        }

    public static BooleanStateStack getMap2IndexState() {
            return ctx().map2IndexState;
        }

    public static BooleanStateStack getMap2NormalState() {
            return ctx().map2NormalState;
        }

    public static BooleanStateStack getMap2TextureCoord1State() {
            return ctx().map2TextureCoord1State;
        }

    public static BooleanStateStack getMap2TextureCoord2State() {
            return ctx().map2TextureCoord2State;
        }

    public static BooleanStateStack getMap2TextureCoord3State() {
            return ctx().map2TextureCoord3State;
        }

    public static BooleanStateStack getMap2TextureCoord4State() {
            return ctx().map2TextureCoord4State;
        }

    public static BooleanStateStack getMap2Vertex3State() {
            return ctx().map2Vertex3State;
        }

    public static BooleanStateStack getMap2Vertex4State() {
            return ctx().map2Vertex4State;
        }

    public static BooleanStateStack[] getClipPlaneStates() {
            return ctx().clipPlaneStates;
        }

    public static ClipPlaneState getClipPlaneState() {
            return ctx().clipPlaneState;
        }

    public static ViewPortStateStack getViewportState() {
            return ctx().viewportState;
        }

    public static ScissorStateStack getScissorState() {
            return ctx().scissorState;
        }
}
