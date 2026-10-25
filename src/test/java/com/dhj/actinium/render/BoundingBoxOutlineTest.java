package com.dhj.actinium.render;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundingBoxOutlineTest {
    @Test
    void vanillaStripHasVisibleDiagonalConnectorsUnderSmoothInterpolation() {
        BufferBuilder buffer = new BufferBuilder(512);
        buffer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        RenderGlobal.drawBoundingBox(buffer, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0.4F);

        int visibleDiagonals = 0;
        for (int i = 1; i < buffer.getVertexCount(); i++) {
            Vertex start = vertex(buffer, i - 1);
            Vertex end = vertex(buffer, i);
            if (changedAxes(start, end) > 1 && start.alpha + end.alpha > 0) {
                visibleDiagonals++;
            }
        }
        assertEquals(2, visibleDiagonals);
    }

    @Test
    void cubeContainsExactlyTwelveIndependentEdgesWithUniformColor() {
        assertOutline(0, 0, 0, 1, 1, 1);
    }

    @Test
    void partialBlockPreservesTranslatedAndExpandedBounds() {
        assertOutline(-3.002F, 0.498F, -7.002F, -1.998F, 1.002F, -6.498F);
    }

    @Test
    void zeroAlphaRemainsTransparentAtEveryEndpoint() {
        BufferBuilder buffer = new BufferBuilder(512);
        BoundingBoxOutline.build(buffer, -1, -2, -3, 4, 5, 6, 0.25F, 0.5F, 0.75F, 0);
        for (int i = 0; i < buffer.getVertexCount(); i++) {
            assertEquals(0, vertex(buffer, i).alpha);
        }
    }

    private static void assertOutline(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        BufferBuilder buffer = new BufferBuilder(512);
        BoundingBoxOutline.build(buffer, minX, minY, minZ, maxX, maxY, maxZ, 0.25F, 0.5F, 0.75F, 0.4F);

        assertEquals(GL11.GL_LINES, buffer.getDrawMode());
        assertEquals(24, buffer.getVertexCount());
        Set<String> edges = new HashSet<>();
        for (int i = 0; i < buffer.getVertexCount(); i += 2) {
            Vertex start = vertex(buffer, i);
            Vertex end = vertex(buffer, i + 1);
            assertEquals(1, changedAxes(start, end), "Each segment must follow one box edge");
            int startCorner = corner(start, minX, minY, minZ, maxX, maxY, maxZ);
            int endCorner = corner(end, minX, minY, minZ, maxX, maxY, maxZ);
            assertTrue(edges.add(Math.min(startCorner, endCorner) + ":" + Math.max(startCorner, endCorner)),
                "Each box edge must be emitted once");
        }
        assertEquals(12, edges.size());
        for (int i = 0; i < buffer.getVertexCount(); i++) {
            int offset = i * buffer.getVertexFormat().getSize() + buffer.getVertexFormat().getColorOffset();
            ByteBuffer bytes = buffer.getByteBuffer();
            assertEquals(63, Byte.toUnsignedInt(bytes.get(offset)));
            assertEquals(127, Byte.toUnsignedInt(bytes.get(offset + 1)));
            assertEquals(191, Byte.toUnsignedInt(bytes.get(offset + 2)));
            assertEquals(102, vertex(buffer, i).alpha);
        }
    }

    private static int corner(Vertex vertex, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        assertTrue(vertex.x == minX || vertex.x == maxX);
        assertTrue(vertex.y == minY || vertex.y == maxY);
        assertTrue(vertex.z == minZ || vertex.z == maxZ);
        return (vertex.x == maxX ? 1 : 0) | (vertex.y == maxY ? 2 : 0) | (vertex.z == maxZ ? 4 : 0);
    }

    private static int changedAxes(Vertex start, Vertex end) {
        return (start.x != end.x ? 1 : 0) + (start.y != end.y ? 1 : 0) + (start.z != end.z ? 1 : 0);
    }

    private static Vertex vertex(BufferBuilder buffer, int index) {
        ByteBuffer bytes = buffer.getByteBuffer();
        int offset = index * buffer.getVertexFormat().getSize();
        return new Vertex(bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8),
            Byte.toUnsignedInt(bytes.get(offset + buffer.getVertexFormat().getColorOffset() + 3)));
    }

    private record Vertex(float x, float y, float z, int alpha) {
    }
}
