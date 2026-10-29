package grondag.bitraster;

import org.junit.jupiter.api.Test;

import static grondag.bitraster.Constants.PROJECTED_VERTEX_STRIDE;
import static grondag.bitraster.Constants.PV_W;
import static grondag.bitraster.Constants.PV_Z;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerspectiveNearClipTest {
    @Test
    void vertexBeyondFarPlaneDoesNotNeedNearClipping() {
        PerspectiveRasterizer rasterizer = new PerspectiveRasterizer();
        setClipPosition(rasterizer, 12.0f, 10.0f);

        assertEquals(0, rasterizer.needsNearClip(0));
    }

    @Test
    void verticesBehindNearPlaneOrEyeStillNeedClipping() {
        PerspectiveRasterizer rasterizer = new PerspectiveRasterizer();
        for (float[] position : new float[][]{{-1, 10}, {0, 10}, {-1, -10}, {1, 0}}) {
            setClipPosition(rasterizer, position[0], position[1]);
            assertEquals(1, rasterizer.needsNearClip(0));
        }
    }

    @Test
    void boxFastPathAcceptsVerticesBeyondFarPlane() {
        PerspectiveRasterizer rasterizer = new PerspectiveRasterizer();
        Matrix4L matrix = new Matrix4L();
        matrix.set(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 2, 0, 0, 0, 1);
        rasterizer.setMatrix(matrix);

        assertTrue(rasterizer.setupBoxVertices(corners(), 0, 0, 0, 1, 1, 1));
        for (int corner : corners()) {
            rasterizer.setupVertex(corner, 0, 0, 1);
            assertEquals(0, rasterizer.needsNearClip(corner));
        }
    }

    @Test
    void boxCrossingNearPlaneUsesClippingFallback() {
        PerspectiveRasterizer rasterizer = new PerspectiveRasterizer();
        Matrix4L matrix = new Matrix4L();
        matrix.set(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        rasterizer.setMatrix(matrix);

        assertFalse(rasterizer.setupBoxVertices(corners(), 0, 0, -1, 1, 1, 1));
    }

    private static void setClipPosition(PerspectiveRasterizer rasterizer, float z, float w) {
        rasterizer.vertexData[PV_Z] = Float.floatToRawIntBits(z);
        rasterizer.vertexData[PV_W] = Float.floatToRawIntBits(w);
    }

    private static int[] corners() {
        int[] corners = new int[8];
        for (int i = 0; i < corners.length; i++) {
            corners[i] = i * PROJECTED_VERTEX_STRIDE;
        }
        return corners;
    }
}
