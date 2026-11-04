package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.GLContextState;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.stacks.BooleanStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.Color4Stack;
import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.recording.ImmediateModeRecorder;
import org.lwjgl.opengl.GL13;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.changeColor;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.changeSecondaryColor;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.setLightmapTextureCoords;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ub2f;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.b2f;

public final class GLSMCurrentAttribs {

    private GLSMCurrentAttribs() {}

    public static void glNormal3f(float nx, float ny, float nz) {
        ShaderManager.setCurrentNormal(nx, ny, nz);
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setNormal(nx, ny, nz);
            return;
        }
        ctx().dirtyNormalAttrib = true;
    }

    public static void glEdgeFlag(boolean flag) {
        // Edge flags are removed in the core profile and have no vertex-format equivalent; the value is
        // tracked so glGetBoolean(GL_EDGE_FLAG) and the GL_CURRENT_BIT attribute group keep answering.
        // A display list being compiled records the call, so the enclosing state stays untouched.
        if (DisplayListManager.getRecordMode() == RecordMode.COMPILE) return;
        ctx().edgeFlagState.setEnabled(flag);
    }

    public static void glColor4f(float red, float green, float blue, float alpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordColor(red, green, blue, alpha);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        changeColor(red, green, blue, alpha);
    }

    public static void glColor4ub(byte red, byte green, byte blue, byte alpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordColor(ub2f(red), ub2f(green), ub2f(blue), ub2f(alpha));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        changeColor(ub2f(red), ub2f(green), ub2f(blue), ub2f(alpha));
    }

    public static void glSecondaryColor3f(float red, float green, float blue) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordSecondaryColor(red, green, blue);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        changeSecondaryColor(red, green, blue);
    }

    public static void glTexCoord2f(float s, float t) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setTexCoord(s, t);
            return;
        }
        ShaderManager.setCurrentTexCoord(s, t, 0.0f, 1.0f);
        ctx().dirtyTexCoordAttrib = true;
    }

    public static void glTexCoord4f(float s, float t, float r, float q) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setTexCoord(s, t);
            return;
        }
        ShaderManager.setCurrentTexCoord(s, t, r, q);
        ctx().dirtyTexCoordAttrib = true;
    }

    public static void glVertexAttrib2f(int index, float v0, float v1) {
        RENDER_BACKEND.vertexAttrib2f(index, v0, v1);
    }

    public static void glVertexAttrib2s(int index, short v0, short v1) {
        RENDER_BACKEND.vertexAttrib2f(index, v0, v1);
    }

    public static void glVertexAttrib4f(int index, float v0, float v1, float v2, float v3) {
        RENDER_BACKEND.vertexAttrib4f(index, v0, v1, v2, v3);
    }

    public static void glMultiTexCoord2f(int target, float s, float t) {
        if (target == GL13.GL_TEXTURE0) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setTexCoord(s, t);
                return;
            }
            ShaderManager.setCurrentTexCoord(s, t, 0.0f, 1.0f);
            ctx().dirtyTexCoordAttrib = true;
        } else if (target == GL13.GL_TEXTURE1) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setLightmapCoord(s, t);
                return;
            }
            setLightmapTextureCoords(target, s, t);
        } else {
            final int unit = target - GL13.GL_TEXTURE0;
            if (unit >= 2 && unit < 4) {
                if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                    ctx().unit23TexCoordSetDuringDraw = true;
                }
                ShaderManager.setCurrentTexCoord(unit, s, t, 0.0f, 1.0f);
            } else {
                ctx().exitCapture.reject();
            }
        }
    }

    public static void glColor4f(float red, float green, float blue) {
        GLSMCurrentAttribs.glColor4f(red, green, blue, 1.0F);
    }

    public static void setOverlayColor(float r, float g, float b, float a) {
            final GLContextState glCtx = ctx();
            glCtx.exitCapture.write(ExitStateCapture.OVERLAY_COLOR);
            if (r == glCtx.overlayR && g == glCtx.overlayG && b == glCtx.overlayB && a == glCtx.overlayA) return;
            glCtx.overlayR = r; glCtx.overlayG = g; glCtx.overlayB = b; glCtx.overlayA = a;
            glCtx.fragmentGeneration++;
        }

    public static void setShaderColor(float r, float g, float b, float a) {
            final GLContextState glCtx = ctx();
            glCtx.exitCapture.write(ExitStateCapture.SHADER_COLOR);
            if (r == glCtx.shaderColorR && g == glCtx.shaderColorG && b == glCtx.shaderColorB && a == glCtx.shaderColorA) return;
            glCtx.shaderColorR = r; glCtx.shaderColorG = g; glCtx.shaderColorB = b; glCtx.shaderColorA = a;
            if (GLSMHooks.SHADER_COLOR_CHANGE.hasListeners()) {
                GLSMHooks.shaderColorChangeEvent.red = r;
                GLSMHooks.shaderColorChangeEvent.green = g;
                GLSMHooks.shaderColorChangeEvent.blue = b;
                GLSMHooks.shaderColorChangeEvent.alpha = a;
                GLSMHooks.SHADER_COLOR_CHANGE.post(GLSMHooks.shaderColorChangeEvent);
            }
        }

    public static boolean consumeUnit23TexCoordSetDuringDraw() {
            final GLContextState glCtx = ctx();
            final boolean v = glCtx.unit23TexCoordSetDuringDraw;
            glCtx.unit23TexCoordSetDuringDraw = false;
            return v;
        }

    public static void forceAttribDefaultsDirty() {
            final GLContextState glCtx = ctx();
            glCtx.dirtyNormalAttrib = true;
            glCtx.dirtyTexCoordAttrib = true;
        }

    public static void flushDeferredVertexAttribs() {
            final GLContextState glCtx = ctx();
            RENDER_BACKEND.vertexAttrib4f(Usage.COLOR.getAttributeLocation(), glCtx.color.getRed(), glCtx.color.getGreen(), glCtx.color.getBlue(), glCtx.color.getAlpha());
            glCtx.dirtyColorAttrib = false;
            RENDER_BACKEND.vertexAttrib4f(Usage.SECONDARY_UV.getAttributeLocation(), glCtx.lastBrightnessX, glCtx.lastBrightnessY, 0.0f, 1.0f);
            glCtx.dirtyLightmapAttrib = false;
            if (glCtx.dirtyNormalAttrib) {
                final var n = ShaderManager.getCurrentNormal();
                RENDER_BACKEND.vertexAttrib3f(Usage.NORMAL.getAttributeLocation(), n.x, n.y, n.z);
                glCtx.dirtyNormalAttrib = false;
            }
            if (glCtx.dirtyTexCoordAttrib) {
                final var tc = ShaderManager.getCurrentTexCoord();
                RENDER_BACKEND.vertexAttrib4f(Usage.PRIMARY_UV.getAttributeLocation(), tc.x, tc.y, tc.z, tc.w);
                glCtx.dirtyTexCoordAttrib = false;
            }
        }

    public static void glNormal3b(byte nx, byte ny, byte nz) {
            final float fnx = b2f(nx), fny = b2f(ny), fnz = b2f(nz);
            ShaderManager.setCurrentNormal(fnx, fny, fnz);
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setNormal(fnx, fny, fnz);
                return;
            }
            ctx().dirtyNormalAttrib = true;
        }

    public static void glNormal3d(double nx, double ny, double nz) {
            final float fnx = (float) nx, fny = (float) ny, fnz = (float) nz;
            ShaderManager.setCurrentNormal(fnx, fny, fnz);
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setNormal(fnx, fny, fnz);
                return;
            }
            ctx().dirtyNormalAttrib = true;
        }

    public static void glNormal3i(int nx, int ny, int nz) {
            final float fnx = nx / 2147483647.0f, fny = ny / 2147483647.0f, fnz = nz / 2147483647.0f;
            ShaderManager.setCurrentNormal(fnx, fny, fnz);
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setNormal(fnx, fny, fnz);
                return;
            }
            ctx().dirtyNormalAttrib = true;
        }

    public static void glColor4d(double red, double green, double blue, double alpha) {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordColor((float) red, (float) green, (float) blue, (float) alpha);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            changeColor((float) red, (float) green, (float) blue, (float) alpha);
        }

    public static void glColor4b(byte red, byte green, byte blue, byte alpha) {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordColor(b2f(red), b2f(green), b2f(blue), b2f(alpha));
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            changeColor(b2f(red), b2f(green), b2f(blue), b2f(alpha));
        }

    public static void glColor3f(float red, float green, float blue) {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordColor(red, green, blue, 1.0F);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            changeColor(red, green, blue, 1.0F);
        }

    public static void glColor3d(double red, double green, double blue) {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordColor((float) red, (float) green, (float) blue, 1.0F);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            changeColor((float) red, (float) green, (float) blue, 1.0F);
        }

    public static void glColor3b(byte red, byte green, byte blue) {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordColor(b2f(red), b2f(green), b2f(blue), 1.0F);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            changeColor(b2f(red), b2f(green), b2f(blue), 1.0F);
        }

    public static void glColor3ub(byte red, byte green, byte blue) {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordColor(ub2f(red), ub2f(green), ub2f(blue), 1.0F);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            changeColor(ub2f(red), ub2f(green), ub2f(blue), 1.0F);
        }

    public static void glSecondaryColor3d(double red, double green, double blue) {
            glSecondaryColor3f((float) red, (float) green, (float) blue);
        }

    public static void glSecondaryColor3b(byte red, byte green, byte blue) {
            glSecondaryColor3f(b2f(red), b2f(green), b2f(blue));
        }

    public static void glSecondaryColor3ub(byte red, byte green, byte blue) {
            glSecondaryColor3f(ub2f(red), ub2f(green), ub2f(blue));
        }

    public static void glTexCoord1f(float s) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setTexCoord(s, 0.0f);
                return;
            }
            ShaderManager.setCurrentTexCoord(s, 0.0f, 0.0f, 1.0f);
            ctx().dirtyTexCoordAttrib = true;
        }

    public static void glTexCoord1d(double s) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setTexCoord((float) s, 0.0f);
                return;
            }
            ShaderManager.setCurrentTexCoord((float) s, 0.0f, 0.0f, 1.0f);
            ctx().dirtyTexCoordAttrib = true;
        }

    public static void glTexCoord2d(double s, double t) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setTexCoord((float) s, (float) t);
                return;
            }
            ShaderManager.setCurrentTexCoord((float) s, (float) t, 0.0f, 1.0f);
            ctx().dirtyTexCoordAttrib = true;
        }

    public static void glTexCoord3f(float s, float t, float r) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setTexCoord(s, t);  // Only track s,t for 2D textures
                return;
            }
            ShaderManager.setCurrentTexCoord(s, t, r, 1.0f);
            ctx().dirtyTexCoordAttrib = true;
        }

    public static void glTexCoord3d(double s, double t, double r) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setTexCoord((float) s, (float) t);
                return;
            }
            ShaderManager.setCurrentTexCoord((float) s, (float) t, (float) r, 1.0f);
            ctx().dirtyTexCoordAttrib = true;
        }

    public static void glTexCoord4d(double s, double t, double r, double q) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setTexCoord((float) s, (float) t);
                return;
            }
            ShaderManager.setCurrentTexCoord((float) s, (float) t, (float) r, (float) q);
            ctx().dirtyTexCoordAttrib = true;
        }

    public static void glVertexAttrib3f(int index, float v0, float v1, float v2) {
            RENDER_BACKEND.vertexAttrib3f(index, v0, v1, v2);
        }

    public static void glMultiTexCoord2d(int target, double s, double t) {
            glMultiTexCoord2f(target, (float) s, (float) t);
        }

    public static void glMultiTexCoord2s(int target, short s, short t) {
            glMultiTexCoord2f(target, s, t);
        }

    public static void setLightmapTextureCoords(int unit, float x, float y) {
            if (unit == GL13.GL_TEXTURE1) {
                final GLContextState glCtx = ctx();
                glCtx.exitCapture.write(ExitStateCapture.LIGHTMAP);
                glCtx.lastBrightnessX = x;
                glCtx.lastBrightnessY = y;
                glCtx.dirtyLightmapAttrib = true;
                if (GLSMHooks.LIGHTMAP_COORDS.hasListeners()) {
                    GLSMHooks.lightmapCoordsEvent.x = x;
                    GLSMHooks.lightmapCoordsEvent.y = y;
                    GLSMHooks.LIGHTMAP_COORDS.post(GLSMHooks.lightmapCoordsEvent);
                }
            }
        }

    public static float getOverlayR() {
            return ctx().overlayR;
        }

    public static float getOverlayG() {
            return ctx().overlayG;
        }

    public static float getOverlayB() {
            return ctx().overlayB;
        }

    public static float getOverlayA() {
            return ctx().overlayA;
        }

    public static float getShaderColorR() {
            return ctx().shaderColorR;
        }

    public static float getShaderColorG() {
            return ctx().shaderColorG;
        }

    public static float getShaderColorB() {
            return ctx().shaderColorB;
        }

    public static float getShaderColorA() {
            return ctx().shaderColorA;
        }

    public static Color4Stack getColor() {
            return ctx().color;
        }

    public static Color4Stack getSecondaryColor() {
            return ctx().secondaryColor;
        }

    public static BooleanStateStack getColorSumState() {
            return ctx().colorSumState;
        }
}
