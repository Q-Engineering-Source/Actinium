package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.BufferBuilder;

import java.nio.ByteBuffer;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;
import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.LIGHT_INDEX;
import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.VERTEX_SIZE;

public final class TemplateCapture {

    private TemplateCapture() {}

    public static TemplateBuffer toTemplate(DirectTessellator direct) {
        final int vertexCount = direct.getVertexCount();
        if (vertexCount == 0) return null;
        final int drawMode = direct.getDrawMode();
        final VertexFormat format = direct.getVertexFormat();
        final ByteBuffer copy = direct.allocateBufferCopy();
        final int[] data = VertexTransform.decode(memAddress0(copy), format, vertexCount, drawMode);
        memFree(copy);
        return new TemplateBuffer(data, vertexCount, drawMode);
    }

    /**
     * Converts the vertices a vanilla {@link BufferBuilder} holds into a template.
     *
     * <p>{@code DefaultVertexFormats.ITEM} carries no lightmap element, so the shared layout's
     * lightmap slot would stay zero and the item would draw unlit. The brightness the frame had
     * while the vertices were written is stored instead, which is the value the fixed function path
     * would have sampled for them.</p>
     *
     * @param buffer builder holding an open or finished draw
     * @param packedLight packed block and sky light of the frame the vertices were written in
     * @return the template, or {@code null} when the builder holds no vertices
     */
    public static TemplateBuffer fromItemBuffer(BufferBuilder buffer, int packedLight) {
        final int vertexCount = buffer.getVertexCount();
        if (vertexCount == 0) return null;
        final int[] data = new int[vertexCount * VERTEX_SIZE];
        SharedVertexLayout.pack(buffer.getByteBuffer(), buffer.getVertexFormat(), vertexCount, data);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            data[vertex * VERTEX_SIZE + LIGHT_INDEX] = packedLight;
        }
        return new TemplateBuffer(data, vertexCount, buffer.getDrawMode());
    }
}
