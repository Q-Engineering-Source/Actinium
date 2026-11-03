package com.dhj.actinium.render;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;

public final class VanillaBufferBuilderRenderer {
    private static final Map<VertexFormat, Integer> VAOS = new HashMap<>();
    private static final Map<VertexFormat, Integer> VBOS = new HashMap<>();
    private static final Map<VertexFormat, Integer> VERTEX_FLAGS = new HashMap<>();

    private VanillaBufferBuilderRenderer() {
    }

    public static boolean shouldUseVanillaPositionDraw(BufferBuilder bufferBuilder) {
        VertexFormat format = bufferBuilder.getVertexFormat();
        if (format == null || format.getElementCount() != 1) {
            return false;
        }

        VertexFormatElement element = format.getElement(0);
        return element.getUsage() == VertexFormatElement.EnumUsage.POSITION
            && element.getElementCount() == 3
            && element.getType() == VertexFormatElement.EnumType.FLOAT;
    }

    public static void draw(BufferBuilder bufferBuilder, String debugSource) {
        if (BufferBuilderStreamingDrawer.isEnabled()) {
            BufferBuilderStreamingDrawer.draw(bufferBuilder, debugSource);
            return;
        }

        if (bufferBuilder.getVertexCount() <= 0) {
            bufferBuilder.reset();
            return;
        }

        VertexFormat format = bufferBuilder.getVertexFormat();
        int vertexCount = bufferBuilder.getVertexCount();
        int drawMode = bufferBuilder.getDrawMode();
        int stride = format.getSize();
        int byteCount = vertexCount * stride;
        ByteBuffer buffer = bufferBuilder.getByteBuffer().duplicate();
        buffer.position(0);
        buffer.limit(byteCount);

        drawRaw(buffer, format, vertexCount, drawMode, debugSource);
        bufferBuilder.reset();
    }

    public static void drawRaw(ByteBuffer buffer, VertexFormat format, int vertexCount, int drawMode, String debugSource) {
        if (BufferBuilderStreamingDrawer.isEnabled()) {
            BufferBuilderStreamingDrawer.drawRaw(buffer, format, vertexCount, drawMode, debugSource);
            return;
        }

        if (vertexCount <= 0) {
            return;
        }

        ensureDrawState(format);
        int vao = VAOS.get(format);
        int vbo = VBOS.get(format);
        int vertexFlags = VERTEX_FLAGS.get(format);
        int stride = format.getSize();
        int byteCount = vertexCount * stride;
        ByteBuffer upload = buffer.duplicate();
        upload.position(0);
        upload.limit(byteCount);

        int savedVbo = GLStateManager.getBoundVBO();
        GLSMVertexInput.glBindVertexArray(vao);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW);

        boolean logDrawDiagnostics = GLSMDebug.shouldLogDrawDiagnostics();
        boolean checkDrawErrors = RenderDebugHooksHolder.shouldCaptureGlState();
        String formatDescription = logDrawDiagnostics || checkDrawErrors ? format.toString() : null;
        if (logDrawDiagnostics) {
            GLSMDebug.logBufferBuilderUpload(formatDescription, drawMode, vertexFlags, stride, vertexCount, byteCount, vao, vbo);
        }

        GLStateManager.prepareWideLineEmulation(drawMode);
        ShaderManager.getInstance().preDraw(vertexFlags);
        if (checkDrawErrors) {
            RenderDebugHooksHolder.checkDrawError("bufferbuilder:after-predraw", debugSource, drawMode, vertexFlags, stride, vertexCount, formatDescription, vao, vbo);
        }
        VanillaVertexBufferRenderer.drawArrays(drawMode, vertexCount);
        if (checkDrawErrors) {
            RenderDebugHooksHolder.checkDrawError("bufferbuilder:after-draw", debugSource, drawMode, vertexFlags, stride, vertexCount, formatDescription, vao, vbo);
        }

        GLSMVertexInput.glBindVertexArray(0);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo);
        if (checkDrawErrors) {
            RenderDebugHooksHolder.checkDrawError("bufferbuilder:after-restore", debugSource, drawMode, vertexFlags, stride, vertexCount, formatDescription, vao, vbo);
        }
    }

    private static void ensureDrawState(VertexFormat format) {
        if (!VAOS.containsKey(format)) {
            int vbo = GLSMBuffers.glGenBuffers();
            int vertexFlags = VanillaVertexBufferRenderer.vertexFlags(format);
            int vao = VanillaVertexBufferRenderer.createStreamingVertexArray(format, vbo);
            VAOS.put(format, vao);
            VBOS.put(format, vbo);
            VERTEX_FLAGS.put(format, vertexFlags);
        }
    }
}

