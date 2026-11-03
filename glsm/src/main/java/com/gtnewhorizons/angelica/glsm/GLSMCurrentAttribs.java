package com.gtnewhorizons.angelica.glsm;

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
            }
        }
    }

    public static void glColor4f(float red, float green, float blue) {
        GLSMCurrentAttribs.glColor4f(red, green, blue, 1.0F);
    }

}
