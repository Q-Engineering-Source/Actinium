package dhj.embeddedt.embeddium.impl.render.chunk.vertex.builder;

import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static ChunkVertexEncoder.Vertex vertex(int color) {
        ChunkVertexEncoder.Vertex vertex = new ChunkVertexEncoder.Vertex();
        vertex.color = color;
        vertex.rdhFactor = 0x13572468;
        return vertex;
    }
}
