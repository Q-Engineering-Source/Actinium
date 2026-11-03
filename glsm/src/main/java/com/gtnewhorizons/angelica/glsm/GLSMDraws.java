package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandType;
import com.gtnewhorizons.angelica.glsm.recording.CommandRecorder;
import com.gtnewhorizons.angelica.glsm.recording.ImmediateModeRecorder;
import com.gtnewhorizons.angelica.glsm.recording.commands.IndexedDrawCapture;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.KHRDebugCallback;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.DEBUG_DRAW_LOGS;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.displayListIdAllocator;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.initConfig;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isVBOBound;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.preDrawFFP;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.prepareClientArrays;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.prepareWideLineEmulation;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.recordGpuCommand;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMDraws {

    private GLSMDraws() {}

    public static void glBegin(int mode) {
        ctx().unit23TexCoordSetDuringDraw = false;
        if (DisplayListManager.isRecording()) {
            ImmediateModeRecorder.begin(mode);
            return;
        }
        ImmediateModeRecorder.beginLive(mode);
    }

    public static void glEnd() {
        if (DisplayListManager.isRecording()) {
            final DirectTessellator result = ImmediateModeRecorder.end();
            if (result != null) {
                if (DisplayListManager.isCompileAndExecute() && initConfig != null && initConfig.getDirectDrawer() != null) {
                    DisplayListManager.flushMatrix();
                    final var recorder = DisplayListManager.pauseRecording();
                    if (GLSMPerfDebug.isEnabled()) {
                        GLSMPerfDebug.count(GLSMPerfDebug.Source.DIRECT_COMPILE_EXECUTE);
                    }
                    initConfig.getDirectDrawer().accept(result);
                    DisplayListManager.resumeRecording(recorder);
                }
                DisplayListManager.addImmediateModeDraw(result);
            }
            return;
        }
        final DirectTessellator result = ImmediateModeRecorder.end();
        if (result != null && initConfig != null && initConfig.getDirectDrawer() != null) {
            if (GLSMPerfDebug.isEnabled()) {
                GLSMPerfDebug.count(GLSMPerfDebug.Source.DIRECT_LIVE_IMMEDIATE);
            }
            initConfig.getDirectDrawer().accept(result);
        }
    }

    public static void glVertex2f(float x, float y) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.vertex(x, y, 0.0f);
        }
    }

    public static void glVertex3f(float x, float y, float z) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.vertex(x, y, z);
        }
    }

    public static void glDrawElements(int mode, ByteBuffer indices) {
        CommandRecorder savedRecorder = null;
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            final IndexedDrawCapture capture = IndexedDrawCapture.createFromClientIndices(mode, indices.remaining(), GL11.GL_UNSIGNED_BYTE, MemoryUtilities.memAddress(indices), indices.remaining());
            if (capture != null) {
                DisplayListManager.recordIndexedDrawCapture(capture);
            }
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
            savedRecorder = DisplayListManager.pauseRecording();
        } else if (FeedbackManager.isFeedbackMode()) {
            FeedbackManager.processDrawElements(mode, indices);
            return;
        }
        try {
            prepareWideLineEmulation(mode);
            preDrawFFP();
            prepareClientArrays();
            if (DEBUG_DRAW_LOGS) {
                GLSMDebug.logDrawElements("byte-buffer", mode, indices.remaining(), GL11.GL_UNSIGNED_BYTE, -1L);
            }
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, indices.remaining());
            RENDER_BACKEND.drawElements(mode, indices);
        } finally {
            if (savedRecorder != null) {
                DisplayListManager.resumeRecording(savedRecorder);
            }
        }
    }

    public static void glDrawElements(int mode, IntBuffer indices) {
        CommandRecorder savedRecorder = null;
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            final IndexedDrawCapture capture = IndexedDrawCapture.createFromClientIndices(mode, indices.remaining(), GL11.GL_UNSIGNED_INT, MemoryUtilities.memAddress(indices), (long) indices.remaining() << 2);
            if (capture != null) {
                DisplayListManager.recordIndexedDrawCapture(capture);
            }
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
            savedRecorder = DisplayListManager.pauseRecording();
        } else if (FeedbackManager.isFeedbackMode()) {
            FeedbackManager.processDrawElements(mode, indices);
            return;
        }
        try {
            prepareWideLineEmulation(mode);
            preDrawFFP();
            prepareClientArrays();
            if (DEBUG_DRAW_LOGS) {
                GLSMDebug.logDrawElements("int-buffer", mode, indices.remaining(), GL11.GL_UNSIGNED_INT, -1L);
            }
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, indices.remaining());
            if (mode == GL11.GL_QUADS) {
                QuadConverter.drawQuadElementsAsTriangles(indices);
            } else {
                RENDER_BACKEND.drawElements(mode, indices);
            }
        } finally {
            if (savedRecorder != null) {
                DisplayListManager.resumeRecording(savedRecorder);
            }
        }
    }

    public static void glDrawElements(int mode, ShortBuffer indices) {
        CommandRecorder savedRecorder = null;
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            final IndexedDrawCapture capture = IndexedDrawCapture.createFromClientIndices(mode, indices.remaining(), GL11.GL_UNSIGNED_SHORT, MemoryUtilities.memAddress(indices), (long) indices.remaining() << 1);
            if (capture != null) {
                DisplayListManager.recordIndexedDrawCapture(capture);
            }
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
            savedRecorder = DisplayListManager.pauseRecording();
        } else if (FeedbackManager.isFeedbackMode()) {
            FeedbackManager.processDrawElements(mode, indices);
            return;
        }
        try {
            prepareWideLineEmulation(mode);
            preDrawFFP();
            prepareClientArrays();
            if (DEBUG_DRAW_LOGS) {
                GLSMDebug.logDrawElements("short-buffer", mode, indices.remaining(), GL11.GL_UNSIGNED_SHORT, -1L);
            }
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, indices.remaining());
            if (mode == GL11.GL_QUADS) {
                QuadConverter.drawQuadElementsAsTriangles(indices);
            } else {
                RENDER_BACKEND.drawElements(mode, indices);
            }
        } finally {
            if (savedRecorder != null) {
                DisplayListManager.resumeRecording(savedRecorder);
            }
        }
    }

    public static void glDrawElements(int mode, int count, int type, ByteBuffer indices) {
        CommandRecorder savedRecorder = null;
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            final IndexedDrawCapture capture = IndexedDrawCapture.createFromClientIndices(mode, count, type, MemoryUtilities.memAddress(indices), indices.remaining());
            if (capture != null) {
                DisplayListManager.recordIndexedDrawCapture(capture);
            }
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
            savedRecorder = DisplayListManager.pauseRecording();
        } else if (FeedbackManager.isFeedbackMode()) {
            FeedbackManager.processDrawElements(mode, count, type, indices);
            return;
        }
        try {
            prepareWideLineEmulation(mode);
            preDrawFFP();
            prepareClientArrays();
            if (DEBUG_DRAW_LOGS) {
                GLSMDebug.logDrawElements("typed-byte-buffer", mode, count, type, -1L);
            }
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, count);
            if (mode == GL11.GL_QUADS) {
                QuadConverter.drawQuadElementsAsTriangles(count, type, indices);
            } else {
                RENDER_BACKEND.drawElements(mode, count, type, indices);
            }
        } finally {
            if (savedRecorder != null) {
                DisplayListManager.resumeRecording(savedRecorder);
            }
        }
    }

    public static void glDrawElements(int mode, int count, ByteBuffer indices) {
        GLSMDraws.glDrawElements(mode, count, GL11.GL_UNSIGNED_BYTE, indices);
    }

    public static void glDrawElements(int mode, int indices_count, int type, long indices_buffer_offset) {
        CommandRecorder savedRecorder = null;
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            // A VAO is always bound post-init (glBindVertexArray(0) redirects to the default
            // VAO), so EBO-backed draws are baked via IndexedDrawCapture. When baking is not
            // possible fall back to Actinium's immediate-mode VBO tessellation.
            final IndexedDrawCapture capture = IndexedDrawCapture.create(mode, indices_count, type, indices_buffer_offset, ctx().vaos.boundEBO);
            if (capture != null) {
                DisplayListManager.recordIndexedDrawCapture(capture);
            } else if (isVBOBound() || ctx().vaos.hasVBOBoundAttrib()) {
                final DirectTessellator result = ImmediateModeRecorder.processDrawElementsFromVBO(mode, indices_count, type, indices_buffer_offset, ctx().vaos.boundEBO);
                if (result != null) {
                    DisplayListManager.addImmediateModeDraw(result);
                }
            }
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
            savedRecorder = DisplayListManager.pauseRecording();
        } else if (FeedbackManager.isFeedbackMode()) {
            FeedbackManager.processDrawElements(mode, indices_count, type, indices_buffer_offset);
            return;
        }
        try {
            prepareWideLineEmulation(mode);
            preDrawFFP();
            prepareClientArrays();
            if (DEBUG_DRAW_LOGS) {
                GLSMDebug.logDrawElements("ebo-offset", mode, indices_count, type, indices_buffer_offset);
            }
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, indices_count);
            if (mode == GL11.GL_QUADS) {
                QuadConverter.drawQuadElementsAsTriangles(indices_count, type, indices_buffer_offset);
            } else {
                RENDER_BACKEND.drawElements(mode, indices_count, type, indices_buffer_offset);
            }
        } finally {
            if (savedRecorder != null) {
                DisplayListManager.resumeRecording(savedRecorder);
            }
        }
    }

    public static void glDrawArrays(int mode, int first, int count) {
        CommandRecorder savedRecorder = null;
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            final DirectTessellator result = ImmediateModeRecorder.processDrawArraysFromAttribs(mode, first, count);
            if (result != null) {
                DisplayListManager.addImmediateModeDraw(result);
            }
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
            savedRecorder = DisplayListManager.pauseRecording();
        } else if (FeedbackManager.isFeedbackMode()) {
            FeedbackManager.processDrawArrays(mode, first, count);
            return;
        }
        prepareWideLineEmulation(mode);
        preDrawFFP();
        prepareClientArrays(first, count);
        if (DEBUG_DRAW_LOGS) {
            GLSMDebug.logDrawArrays("draw-arrays", mode, first, count);
        }
        recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, count);
        if (mode == GL11.GL_QUADS) {
            QuadConverter.drawQuadsAsTriangles(first, count);
        } else if (mode == GL11.GL_QUAD_STRIP) {
            RENDER_BACKEND.drawArrays(GL11.GL_TRIANGLE_STRIP, first, count & ~1);
        } else if (mode == GL11.GL_POLYGON) {
            RENDER_BACKEND.drawArrays(GL11.GL_TRIANGLE_FAN, first, count);
        } else {
            RENDER_BACKEND.drawArrays(mode, first, count);
        }
        if (savedRecorder != null) DisplayListManager.resumeRecording(savedRecorder);
    }

    public static void glNewList(int list, int mode) {
        DisplayListManager.glNewList(list, mode);
    }

    public static void glEndList() {
        DisplayListManager.glEndList();
    }

    public static int glGenLists(int range) {
        return displayListIdAllocator.allocRange(range);
    }

    public static void glDeleteLists(int list, int range) {
        displayListIdAllocator.freeRange(list, range);
        DisplayListManager.glDeleteLists(list, range);
    }

    public static void glCallList(int list) {
        if (!GLDebug.isActive()) {
            DisplayListManager.glCallList(list);
            return;
        }
        GLDebug.pushGroup("glCallList ", list);
        try {
            DisplayListManager.glCallList(list);
        } finally {
            GLDebug.popGroup();
        }
    }

    public static void glDebugMessageCallback(KHRDebugCallback callback) {
        RENDER_BACKEND.debugMessageCallback(GLDebug.adaptDebugCallback(callback), 0L);
    }

    public static int glRenderMode(int mode) {
        return FeedbackManager.glRenderMode(mode);
    }

}
