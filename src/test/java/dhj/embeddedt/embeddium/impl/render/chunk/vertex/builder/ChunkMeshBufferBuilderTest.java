package dhj.embeddedt.embeddium.impl.render.chunk.vertex.builder;

import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChunkMeshBufferBuilderTest {
    @Test
    void appliesOneBilinearCorrectionFactorToEveryQuadVertex() {
        ChunkVertexEncoder.Vertex[] vertices = new ChunkVertexEncoder.Vertex[] {
                vertex(0x00643214),
                vertex(0xFF0A6450),
                vertex(0x00323228),
                vertex(0xFF00C864)
        };
        setQuadPositions(vertices);

        ChunkMeshBufferBuilder.postprocessVertices(vertices);

        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            assertEquals(0x7FDD321E, vertex.rdhFactor);
        }
    }

    @Test
    void clearsThePreviousCorrectionWhenAQuadEncodesATriangle() {
        int[] colors = {0x00643214, 0xFF0A6450, 0x00323228, 0xFF00C864};
        var vertices = new ChunkVertexEncoder.Vertex[] {
                vertex(colors[0]), vertex(colors[1]), vertex(colors[2]), vertex(colors[3])
        };
        setQuadPositions(vertices);
        ChunkMeshBufferBuilder.postprocessVertices(vertices);
        assertNotEquals(0, vertices[0].rdhFactor);

        vertices[3].x = vertices[2].x;
        vertices[3].y = vertices[2].y;
        vertices[3].z = vertices[2].z;
        ChunkMeshBufferBuilder.postprocessVertices(vertices);

        for (int i = 0; i < vertices.length; i++) {
            assertEquals(0, vertices[i].rdhFactor);
            assertEquals(colors[i], vertices[i].color);
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

    private static void setQuadPositions(ChunkVertexEncoder.Vertex[] vertices) {
        vertices[1].x = 1;
        vertices[2].x = 1;
        vertices[2].y = 1;
        vertices[3].y = 1;
    }

    private static ChunkVertexEncoder.Vertex vertex(int color) {
        ChunkVertexEncoder.Vertex vertex = new ChunkVertexEncoder.Vertex();
        vertex.color = color;
        vertex.rdhFactor = 0x13572468;
        return vertex;
    }
}
