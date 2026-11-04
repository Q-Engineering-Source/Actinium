package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.FeedbackManager;
import com.gtnewhorizons.angelica.glsm.QuadConverter;
import com.gtnewhorizons.angelica.glsm.backend.GLDebugMessageListener;
import com.gtnewhorizons.angelica.glsm.recording.CompiledDisplayList;
import java.nio.FloatBuffer;
import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandType;
import com.gtnewhorizons.angelica.glsm.ffp.VAOManager;
import com.gtnewhorizons.angelica.glsm.recording.CommandRecorder;
import com.gtnewhorizons.angelica.glsm.recording.ImmediateModeRecorder;
import com.gtnewhorizons.angelica.glsm.recording.commands.IndexedDrawCapture;
import com.gtnewhorizons.angelica.glsm.streaming.LineQuads;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
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
import static com.gtnewhorizons.angelica.glsm.GLStateManager.rejectExitCapture;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.FFP_WARN_ON_UNSUPPORTED;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.LOGGER;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.PIXELTRANSFER_MSG;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.selectWarned;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.setLineStippleActive;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.warnedFFPFunctions;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.warnOnce;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.wideLineEmulationEnabled;

public final class GLSMDraws {

    private GLSMDraws() {}

    public static void glBegin(int mode) {
        rejectExitCapture();
        ctx().unit23TexCoordSetDuringDraw = false;
        if (DisplayListManager.isRecording()) {
            ImmediateModeRecorder.begin(mode);
            return;
        }
        ImmediateModeRecorder.beginLive(mode);
    }

    public static void glEnd() {
        rejectExitCapture();
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
        rejectExitCapture();
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
            if (widensLineDraw(mode, indices.remaining())) {
                drawLinesAsQuads(ctx(), ImmediateModeRecorder.processDrawElementsFromAttribs(mode, indices.remaining(), GL11.GL_UNSIGNED_BYTE, MemoryUtilities.memAddress(indices)));
                return;
            }
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
        rejectExitCapture();
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
            if (widensLineDraw(mode, indices.remaining())) {
                drawLinesAsQuads(ctx(), ImmediateModeRecorder.processDrawElementsFromAttribs(mode, indices.remaining(), GL11.GL_UNSIGNED_INT, MemoryUtilities.memAddress(indices)));
                return;
            }
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
        rejectExitCapture();
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
            if (widensLineDraw(mode, indices.remaining())) {
                drawLinesAsQuads(ctx(), ImmediateModeRecorder.processDrawElementsFromAttribs(mode, indices.remaining(), GL11.GL_UNSIGNED_SHORT, MemoryUtilities.memAddress(indices)));
                return;
            }
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
        rejectExitCapture();
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
            if (widensLineDraw(mode, count)) {
                drawLinesAsQuads(ctx(), ImmediateModeRecorder.processDrawElementsFromAttribs(mode, count, type, MemoryUtilities.memAddress(indices)));
                return;
            }
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
        rejectExitCapture();
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
            if (widensLineDraw(mode, indices_count)) {
                drawLinesAsQuads(ctx(), lineElementsFromBuffer(mode, indices_count, type, indices_buffer_offset));
                return;
            }
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
        rejectExitCapture();
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
        if (widensLineDraw(mode, count)) {
            drawLinesAsQuads(ctx(), ImmediateModeRecorder.processDrawArraysFromAttribs(mode, first, count));
            if (savedRecorder != null) DisplayListManager.resumeRecording(savedRecorder);
            return;
        }
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
        rejectExitCapture();
        DisplayListManager.glNewList(list, mode);
    }

    public static void glEndList() {
        rejectExitCapture();
        DisplayListManager.glEndList();
    }

    public static int glGenLists(int range) {
        rejectExitCapture();
        return displayListIdAllocator.allocRange(range);
    }

    public static void glDeleteLists(int list, int range) {
        rejectExitCapture();
        displayListIdAllocator.freeRange(list, range);
        DisplayListManager.glDeleteLists(list, range);
    }

    public static void glCallList(int list) {
        rejectExitCapture();
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
        rejectExitCapture();
        return FeedbackManager.glRenderMode(mode);
    }

    public static boolean displayListExists(int list) {
            return DisplayListManager.displayListExists(list);
        }

    public static void glPixelTransferf(int pname, float param) {
            guardUnsupportedFFP("glPixelTransfer", PIXELTRANSFER_MSG);
        }

    public static void glPixelTransferi(int pname, int param) {
            guardUnsupportedFFP("glPixelTransfer", PIXELTRANSFER_MSG);
        }

    private static final String RASTERPOS_MSG = "glRasterPos is not available in GL 3.3 core profile.";

    public static void glRasterPos2f(float x, float y) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos2d(double x, double y) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos2i(int x, int y) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos3f(float x, float y, float z) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos3d(double x, double y, double z) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos3i(int x, int y, int z) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos4f(float x, float y, float z, float w) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos4d(double x, double y, double z, double w) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos4i(int x, int y, int z, int w) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }

    static boolean guardUnsupportedFFP(String function, String explanation) {
            if (!FFP_WARN_ON_UNSUPPORTED) {
                throw new UnsupportedOperationException(function + ": " + explanation);
            }
            if (warnedFFPFunctions.add(function)) {
                LOGGER.warn("{}: {}", function, explanation, new Throwable("Stack trace"));
            }
            return true;
        }

    public static void glVertex2i(int x, int y) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.vertex((float) x, (float) y, 0.0f);
            }
        }

    public static void glVertex2d(double x, double y) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.vertex((float) x, (float) y, 0.0f);
            }
        }

    public static void glVertex3d(double x, double y, double z) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.vertex((float) x, (float) y, (float) z);
            }
        }

    public static void glVertex3i(int x, int y, int z) {
            if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.vertex((float) x, (float) y, (float) z);
            }
        }

    public static void preDraw(int drawMode) {
            prepareWideLineEmulation(drawMode);
            preDrawFFP();
            prepareClientArrays();
        }

    public static void preDraw() {
            setLineStippleActive(false);
            preDrawFFP();
            prepareClientArrays();
        }

    public static void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
        rejectExitCapture();
            preDrawFFP();
            prepareClientArrays();
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, drawcount);
            RENDER_BACKEND.multiDrawElementsIndirect(mode, type, indirect, drawcount, stride);
        }

    public static void glMultiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) {
        rejectExitCapture();
            preDrawFFP();
            prepareClientArrays();
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, drawcount);
            RENDER_BACKEND.multiDrawElementsBaseVertex(mode, pCount, type, pIndices, drawcount, pBaseVertex);
        }

    public static void glDrawElementsBaseVertex(int mode, int count, int type, long indices, int basevertex) {
        rejectExitCapture();
            preDrawFFP();
            prepareClientArrays();
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, count);
            RENDER_BACKEND.drawElementsBaseVertex(mode, count, type, indices, basevertex);
        }

    public static void glDrawElementsInstanced(int mode, int count, int type, long indices, int primcount) {
        rejectExitCapture();
            warnIfInstancedWideLines(mode);
            preDrawFFP();
            prepareClientArrays();
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, count);
            RENDER_BACKEND.drawElementsInstanced(mode, count, type, indices, primcount);
        }

    public static void glDrawArraysInstanced(int mode, int first, int count, int primcount) {
        rejectExitCapture();
            prepareWideLineEmulation(mode);
            warnIfInstancedWideLines(mode);
            preDrawFFP();
            prepareClientArrays(first, count);
            recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, count);
            if (mode == GL11.GL_QUADS) {
                QuadConverter.drawQuadsAsTrianglesInstanced(first, count, primcount);
            } else if (mode == GL11.GL_QUAD_STRIP) {
                RENDER_BACKEND.drawArraysInstanced(GL11.GL_TRIANGLE_STRIP, first, count & ~1, primcount);
            } else if (mode == GL11.GL_POLYGON) {
                RENDER_BACKEND.drawArraysInstanced(GL11.GL_TRIANGLE_FAN, first, count, primcount);
            } else {
                RENDER_BACKEND.drawArraysInstanced(mode, first, count, primcount);
            }
        }

    public static void glDrawRangeElements(int mode, int start, int end, int count, int type, long indices) {
        rejectExitCapture();
            prepareWideLineEmulation(mode);
            if (widensLineDraw(mode, count)) {
                drawLinesAsQuads(ctx(), lineElementsFromBuffer(mode, count, type, indices));
                return;
            }
            preDrawFFP();
            prepareClientArrays();
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, count);
            RENDER_BACKEND.drawRangeElements(mode, start, end, count, type, indices);
        }

    public static void glDrawRangeElementsBaseVertex(int mode, int start, int end, int count, int type, long indices, int baseVertex) {
        rejectExitCapture();
            preDrawFFP();
            prepareClientArrays();
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, count);
            RENDER_BACKEND.drawRangeElementsBaseVertex(mode, start, end, count, type, indices, baseVertex);
        }

    public static void glMultiDrawArrays(int mode, IntBuffer firsts, IntBuffer counts) {
        rejectExitCapture();
            prepareWideLineEmulation(mode);
            if (LineQuads.isLineMode(mode) && ffpWidensLineQuads()) {
                // Each range is its own line primitive; widening them as one draw would join the ranges
                for (int i = firsts.position(); i < firsts.limit(); i++) {
                    final int count = counts.get(counts.position() + i - firsts.position());
                    if (LineQuads.segmentCount(mode, count) == 0) continue;
                    drawLinesAsQuads(ctx(), ImmediateModeRecorder.processDrawArraysFromAttribs(mode, firsts.get(i), count));
                }
                return;
            }
            preDrawFFP();
            prepareClientArrays();
            recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, counts.remaining());
            RENDER_BACKEND.multiDrawArrays(mode, firsts, counts);
        }

    public static void glMultiDrawArrays(int mode, int[] firsts, int[] counts) {
            preDrawFFP();
            prepareClientArrays();
            recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, counts.length);
            RENDER_BACKEND.multiDrawArrays(mode, firsts, counts);
        }

    public static void glPrimitiveRestartIndex(int index) {
        rejectExitCapture();
            RENDER_BACKEND.primitiveRestartIndex(index);
        }

    public static boolean glIsList(int list) {
        rejectExitCapture();
            return displayListIdAllocator.isAllocated(list);
        }

    public static CompiledDisplayList getDisplayList(int list) {
            return DisplayListManager.getDisplayList(list);
        }

    public static int getListMode() {
            return DisplayListManager.getListMode();
        }

    public static void glFlush() {
        rejectExitCapture();
            RENDER_BACKEND.flush();
        }

    public static void glFinish() {
        rejectExitCapture();
            RENDER_BACKEND.finish();
        }

    public static void glMultiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) {
        rejectExitCapture();
            preDrawFFP();
            prepareClientArrays();
            recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, maxdrawcount);
            RENDER_BACKEND.multiDrawArraysIndirectCount(mode, indirect, drawcount, maxdrawcount, stride);
        }

    public static void glMultiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount, int stride) {
        rejectExitCapture();
            preDrawFFP();
            prepareClientArrays();
            recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, maxdrawcount);
            RENDER_BACKEND.multiDrawElementsIndirectCount(mode, type, indirect, drawcount, maxdrawcount, stride);
        }

    public static void glCallLists(IntBuffer lists) {
            while (lists.hasRemaining()) {
                final int listId = lists.get() + ctx().listBase;
                glCallList(listId);
            }
        }

    public static void glCallLists(ShortBuffer lists) {
            while (lists.hasRemaining()) {
                final int listId = (lists.get() & 0xFFFF) + ctx().listBase;
                glCallList(listId);
            }
        }

    public static void glCallLists(ByteBuffer lists) {
            while (lists.hasRemaining()) {
                final int listId = (lists.get() & 0xFF) + ctx().listBase;
                glCallList(listId);
            }
        }

    public static void glListBase(int base) {
        rejectExitCapture();
            ctx().listBase = base;
        }

    public static void nglMultiDrawElementsBaseVertex(int mode, long count, int type, long indices, int primcount, long basevertex) {
        rejectExitCapture();
            RENDER_BACKEND.multiDrawElementsBaseVertex(mode, count, type, indices, primcount, basevertex);
        }

    public static void glObjectLabel(int identifier, int name, CharSequence label) { RENDER_BACKEND.objectLabel(identifier, name, label); }

    public static String glGetObjectLabel(int identifier, int name, int bufSize) { rejectExitCapture(); return RENDER_BACKEND.getObjectLabel(identifier, name); }

    public static boolean ffpWidensLineQuads() {
        final GLContextState glCtx = ctx();
        return wideLineEmulationEnabled && glCtx.activeProgram == 0 && glCtx.lineState.getWidth() > 1.0f
            && !DisplayListManager.isRecording();
    }

    private static boolean widensLineDraw(int mode, int count) {
        return LineQuads.isLineMode(mode) && LineQuads.segmentCount(mode, count) > 0 && ffpWidensLineQuads();
    }

    /**
     * Widens a line draw by expanding it into quads and streaming them through the FFP program. The draw path
     * leaves the VAO and array buffer bound, so both are restored before control returns to the caller.
     */
    private static void drawLinesAsQuads(GLContextState glCtx, DirectTessellator lines) {
        if (lines == null) return;
        if (readsBufferObject(glCtx.vaos)) {
            warnOnce("line-readback", "Wide GL_LINES drawn from a buffer object; widening them reads the buffer back, which waits for the GPU");
        }
        final int vao = glCtx.boundVAO;
        final int vbo = glCtx.boundVBO;
        TessellatorStreamingDrawer.drawDirect(lines);
        GLSMVertexInput.glBindVertexArray(vao);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
    }

    /** Reads an index range out of the bound element buffer so the indexed line draw can be expanded. */
    private static DirectTessellator lineElementsFromBuffer(int mode, int count, int type, long offset) {
        if (ctx().vaos.boundEBO == 0) return null;
        final ByteBuffer indices = MemoryUtilities.memAlloc(count * GLTypes.sizeBytes(type));
        try {
            GLSMBuffers.glGetBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, offset, indices);
            return ImmediateModeRecorder.processDrawElementsFromAttribs(mode, count, type, MemoryUtilities.memAddress0(indices));
        } finally {
            MemoryUtilities.memFree(indices);
        }
    }

    private static void warnIfInstancedWideLines(int mode) {
        if (LineQuads.isLineMode(mode) && ffpWidensLineQuads()) {
            warnOnce("instanced-wide-lines", "Instanced wide GL_LINES are not widened and draw 1 px wide");
        }
    }

    private static boolean readsBufferObject(VAOManager vaos) {
        for (int i = 0; i < VAOManager.MAX_ATTRIBS; i++) {
            final VAOManager.Attrib attrib = vaos.attrib(i);
            if (attrib != null && attrib.enabled && attrib.vboId != 0) return true;
        }
        return false;
    }

    public static boolean isContinuousDraw(int drawMode) {
        return drawMode == GL11.GL_TRIANGLE_STRIP || drawMode == GL11.GL_TRIANGLE_FAN
            || drawMode == GL11.GL_LINE_STRIP || drawMode == GL11.GL_LINE_LOOP
            || drawMode == GL11.GL_QUAD_STRIP || drawMode == GL11.GL_POLYGON;
    }

    public static void preDraw(GLContextState glCtx, int drawMode) {
        prepareWideLineEmulation(drawMode);
        glCtx.ffp.preDraw();
        prepareClientArrays();
    }

    public static void glPushDebugGroup(int source, int id, CharSequence message) { RENDER_BACKEND.pushDebugGroup(source, id, message); }

    public static void glPopDebugGroup() { RENDER_BACKEND.popDebugGroup(); }

    public static void glDebugMessageInsert(int source, int type, int id, int severity, CharSequence buf) { RENDER_BACKEND.debugMessageInsert(source, type, id, severity, buf); }

    public static void glDebugMessageControl(int source, int type, int severity, IntBuffer ids, boolean enabled) { RENDER_BACKEND.debugMessageControl(source, type, severity, ids, enabled); }

    public static int glGetDebugMessageLog(int count, IntBuffer sources, IntBuffer types, IntBuffer ids, IntBuffer severities, IntBuffer lengths, ByteBuffer messageLog) {
        rejectExitCapture();
            return RENDER_BACKEND.getDebugMessageLog(count, sources, types, ids, severities, lengths, messageLog);
        }

    public static void registerDebugMessageListener(GLDebugMessageListener listener, long userParam) {
            RENDER_BACKEND.debugMessageCallback(listener, userParam);
        }

    public static void glAccum(int op, float value) {
            guardUnsupportedFFP("glAccum", "The accumulation buffer is not available in GL 3.3 core profile.");
        }

    public static void glFeedbackBuffer(int type, FloatBuffer buffer) {
        rejectExitCapture();
            FeedbackManager.glFeedbackBuffer(type, buffer);
        }

    public static void glPassThrough(float token) {
        rejectExitCapture();
            FeedbackManager.glPassThrough(token);
        }

    public static void glSelectBuffer(IntBuffer buffer) {
        rejectExitCapture();
            if (!selectWarned) {
                LOGGER.warn("glSelectBuffer: selection mode not emulated");
                selectWarned = true;
            }
        }

    public static void glInitNames() { /* no-op: ignored outside GL_SELECT */ }

    public static void glPushName(int name) { /* no-op */ }

    public static void glPopName() { /* no-op */ }

    public static void glLoadName(int name) { /* no-op */ }
}
