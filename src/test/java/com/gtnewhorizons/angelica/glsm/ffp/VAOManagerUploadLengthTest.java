package com.gtnewhorizons.angelica.glsm.ffp;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VAOManagerUploadLengthTest {
    private static VAOManager.Attrib attrib(int size, int type, int stride, int bufferBytes) {
        final VAOManager.Attrib a = new VAOManager.Attrib();
        a.size = size;
        a.type = type;
        a.stride = stride;
        a.clientPointer = ByteBuffer.allocate(bufferBytes);
        return a;
    }

    @Test
    void tightlyPackedFloatPosition() {
        assertEquals(1200, VAOManager.computeUploadLength(attrib(3, GL11.GL_FLOAT, 0, 4096), 0, 100));
    }

    @Test
    void explicitStrideCoversWholeVertices() {
        assertEquals(3180, VAOManager.computeUploadLength(attrib(3, GL11.GL_FLOAT, 32, 8192), 0, 100));
    }

    @Test
    void nonZeroFirstStillUploadsFromAllocationStart() {
        assertEquals(240, VAOManager.computeUploadLength(attrib(3, GL11.GL_FLOAT, 0, 4096), 16, 4));
    }

    @Test
    void hugeAllocationShrinksToDrawRange() {
        assertEquals(48, VAOManager.computeUploadLength(attrib(3, GL11.GL_FLOAT, 0, 4_200_768), 0, 4));
    }

    @Test
    void clampedToCapturedAllocation() {
        assertEquals(1024, VAOManager.computeUploadLength(attrib(3, GL11.GL_FLOAT, 0, 1024), 0, 10000));
    }

    @Test
    void respectsBufferPosition() {
        final VAOManager.Attrib a = attrib(3, GL11.GL_FLOAT, 0, 4096);
        a.clientPointer.position(1024);
        assertEquals(1200, VAOManager.computeUploadLength(a, 0, 100));
        assertEquals(3072, VAOManager.computeUploadLength(a, 0, 1000));
    }

    @Test
    void zeroCountUploadsNothing() {
        assertEquals(0, VAOManager.computeUploadLength(attrib(3, GL11.GL_FLOAT, 0, 4096), 0, 0));
    }

    @Test
    void byteAndShortTypes() {
        assertEquals(400, VAOManager.computeUploadLength(attrib(4, GL11.GL_UNSIGNED_BYTE, 0, 4096), 0, 100));
        assertEquals(92, VAOManager.computeUploadLength(attrib(2, GL11.GL_SHORT, 8, 4096), 2, 10));
    }

    @Test
    void narrowStrideKeepsLastVertexCovered() {
        assertEquals(100, VAOManager.computeUploadLength(attrib(3, GL11.GL_FLOAT, 8, 4096), 0, 12));
    }

    @Test
    void negativeStrideFallsBackToFullAllocation() {
        assertEquals(4096, VAOManager.computeUploadLength(attrib(3, GL11.GL_FLOAT, -1, 4096), 0, 4));
    }
}
