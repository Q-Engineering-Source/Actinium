package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.streaming.StubRenderBackend;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VertexAttribUploadLengthTest {

    /** Answers only the query GLStateManager makes while it initializes; the tests must not touch GL. */
    private static final class InitBackend extends StubRenderBackend {
        @Override public int getInteger(int pname) { return 32; }
    }

    private RenderBackend original;

    // The install has to happen before anything touches GLStateManager: its clinit queries the backend.
    @BeforeEach
    void installBackend() {
        original = BackendManager.RENDER_BACKEND;
        Reflect.setStaticFinal(BackendManager.class, "RENDER_BACKEND", RenderBackend.class, new InitBackend());
    }

    @AfterEach
    void restoreBackend() {
        if (original != null) Reflect.setStaticFinal(BackendManager.class, "RENDER_BACKEND", RenderBackend.class, original);
        original = null;
    }
    private static VertexAttribState.Attrib attrib(int size, int type, int stride, int bufferBytes) {
        final VertexAttribState.Attrib a = new VertexAttribState.Attrib();
        a.size = size;
        a.type = type;
        a.stride = stride;
        a.clientPointer = ByteBuffer.allocate(bufferBytes);
        return a;
    }

    @Test
    void tightlyPackedFloatPosition() {
        // stride=0 means tightly packed: 3 floats = 12 bytes per vertex; [0, 100) reads 1200 bytes.
        assertEquals(1200, GLStateManager.ctx().vao.computeUploadLength(attrib(3, GL11.GL_FLOAT, 0, 4096), 0, 100));
    }

    @Test
    void explicitStrideCoversWholeVertices() {
        // stride=32 with a 12-byte vertex: last vertex of [0, 100) ends at 99*32+12 = 3180.
        assertEquals(3180, GLStateManager.ctx().vao.computeUploadLength(attrib(3, GL11.GL_FLOAT, 32, 8192), 0, 100));
    }

    @Test
    void nonZeroFirstStillUploadsFromAllocationStart() {
        // QUADS-as-triangles style range [16, 20): bytes up to (16+4-1)*12+12 = 240 are needed.
        assertEquals(240, GLStateManager.ctx().vao.computeUploadLength(attrib(3, GL11.GL_FLOAT, 0, 4096), 16, 4));
    }

    @Test
    void hugeAllocationShrinksToDrawRange() {
        // The HBM-CE case: a 4.2MB reused allocation, a 4-vertex GUI draw reads only 48 bytes.
        assertEquals(48, GLStateManager.ctx().vao.computeUploadLength(attrib(3, GL11.GL_FLOAT, 0, 4_200_768), 0, 4));
    }

    @Test
    void clampedToCapturedAllocation() {
        assertEquals(1024, GLStateManager.ctx().vao.computeUploadLength(attrib(3, GL11.GL_FLOAT, 0, 1024), 0, 10000));
    }

    @Test
    void respectsBufferPosition() {
        final VertexAttribState.Attrib a = attrib(3, GL11.GL_FLOAT, 0, 4096);
        a.clientPointer.position(1024);
        assertEquals(1200, GLStateManager.ctx().vao.computeUploadLength(a, 0, 100));
        assertEquals(3072, GLStateManager.ctx().vao.computeUploadLength(a, 0, 1000));
    }

    @Test
    void zeroCountUploadsNothing() {
        assertEquals(0, GLStateManager.ctx().vao.computeUploadLength(attrib(3, GL11.GL_FLOAT, 0, 4096), 0, 0));
    }

    @Test
    void byteAndShortTypes() {
        // RGBA color as 4 unsigned bytes: 4 bytes per vertex.
        assertEquals(400, GLStateManager.ctx().vao.computeUploadLength(attrib(4, GL11.GL_UNSIGNED_BYTE, 0, 4096), 0, 100));
        // 2 shorts = 4-byte vertex, stride=8, range [2, 12): (2+10-1)*8+4 = 92.
        assertEquals(92, GLStateManager.ctx().vao.computeUploadLength(attrib(2, GL11.GL_SHORT, 8, 4096), 2, 10));
    }

    @Test
    void narrowStrideKeepsLastVertexCovered() {
        // stride=8 < 12-byte vertex: the last vertex's tail must still be uploaded.
        assertEquals(11 * 8 + 12, GLStateManager.ctx().vao.computeUploadLength(attrib(3, GL11.GL_FLOAT, 8, 4096), 0, 12));
    }

    @Test
    void negativeStrideFallsBackToFullAllocation() {
        assertEquals(4096, GLStateManager.ctx().vao.computeUploadLength(attrib(3, GL11.GL_FLOAT, -1, 4096), 0, 4));
    }
}
