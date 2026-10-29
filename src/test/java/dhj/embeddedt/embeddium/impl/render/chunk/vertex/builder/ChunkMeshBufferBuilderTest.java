package dhj.embeddedt.embeddium.impl.render.chunk.vertex.builder;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import dhj.embeddedt.embeddium.impl.common.util.ScratchRetentionWindow;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMeshBufferBuilderTest {
    @Test
    void appliesOneBilinearCorrectionFactorToEveryQuadVertex() {
        ChunkVertexEncoder.Vertex[] vertices = new ChunkVertexEncoder.Vertex[] {
                vertex(0x00643214),
                vertex(0xFF0A6450),
                vertex(0x00323228),
                vertex(0xFF00C864)
        };

        ChunkMeshBufferBuilder.postprocessVertices(vertices);

        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            assertEquals(0x7FDD321E, vertex.rdhFactor);
        }
    }

    @Test
    void clearsCorrectionForNonQuadVertexBatches() {
        ChunkVertexEncoder.Vertex[] vertices = new ChunkVertexEncoder.Vertex[] {
                vertex(0xFFFFFFFF),
                vertex(0x00000000),
                vertex(0x12345678)
        };

        ChunkMeshBufferBuilder.postprocessVertices(vertices);

        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            assertEquals(0, vertex.rdhFactor);
        }
    }

    @Test
    void clampsCorrectionEncodingToSignedByteRange() {
        assertEquals(127, ChunkMeshBufferBuilder.encodeBilinearCorrection(255, 255, 0, 0, 0));
        assertEquals(129, ChunkMeshBufferBuilder.encodeBilinearCorrection(0, 0, 255, 255, 0));
    }

    @Test
    void tracksWorkerScratchGrowthAndRetentionRelease() {
        boolean wasEnabled = ScratchRetentionWindow.isEnabled();
        ScratchRetentionWindow.setEnabled(true);

        ChunkMeshBufferBuilder builder = new ChunkMeshBufferBuilder(
                (ptr, material, vertex, sectionIndex) -> {
                    MemoryUtilities.memPutInt(ptr, sectionIndex);
                    return ptr + Integer.BYTES;
                }, Integer.BYTES, 16, false);
        ChunkMeshBufferBuilder.ScratchMemoryStats before = ChunkMeshBufferBuilder.getScratchMemoryStats();

        try {
            builder.start(0);
            builder.push(vertices(256), null);
            builder.finishTask(true);

            ChunkMeshBufferBuilder.ScratchMemoryStats grown = ChunkMeshBufferBuilder.getScratchMemoryStats();
            assertEquals(before.retainedBytes() + 1024, grown.retainedBytes());
            assertEquals(before.retainedBuffers() + 1, grown.retainedBuffers());
            assertTrue(grown.peakRetainedBytes() >= grown.retainedBytes());

            // The first window includes the oversized task; two later low-use windows trigger the existing release policy.
            for (int task = 0; task < 191; task++) {
                builder.start(0);
                builder.push(vertices(4), null);
                builder.finishTask(true);
            }

            ChunkMeshBufferBuilder.ScratchMemoryStats released = ChunkMeshBufferBuilder.getScratchMemoryStats();
            assertEquals(before.retainedBytes(), released.retainedBytes());
            assertEquals(before.retainedBuffers(), released.retainedBuffers());
            assertEquals(before.retentionReleasedBytes() + 1024, released.retentionReleasedBytes());
            assertEquals(before.retentionReleaseCount() + 1, released.retentionReleaseCount());
        } finally {
            builder.destroy();
            ScratchRetentionWindow.setEnabled(wasEnabled);
        }
    }

    private static ChunkVertexEncoder.Vertex vertex(int color) {
        ChunkVertexEncoder.Vertex vertex = new ChunkVertexEncoder.Vertex();
        vertex.color = color;
        vertex.rdhFactor = 0x13572468;
        return vertex;
    }

    private static ChunkVertexEncoder.Vertex[] vertices(int count) {
        ChunkVertexEncoder.Vertex[] vertices = new ChunkVertexEncoder.Vertex[count];
        for (int index = 0; index < count; index++) {
            vertices[index] = new ChunkVertexEncoder.Vertex();
        }
        return vertices;
    }
}
