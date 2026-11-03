package dhj.embeddedt.embeddium.impl.render.chunk.sorting;

import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how translucent sorting treats quads whose positions are not finite. A sort order indexes the geometry
 * buffer, so such a quad keeps its slot, and the analysis has to reach the verdict it would without it.
 */
class TranslucentQuadRecorderTest {
    @Test
    void nonFiniteQuadsAreRecordedAsDegenerate() {
        TranslucentQuadRecorder recorder = new TranslucentQuadRecorder();

        captureQuad(recorder, corners(Float.NaN, 0, 0, Float.NaN, 0, 1, Float.NaN, 1, 1, Float.NaN, 1, 0));
        captureQuad(recorder, corners(0, 0, 0, 0, 0, Float.POSITIVE_INFINITY, 1, 0, Float.POSITIVE_INFINITY, 1, 0, 0));

        QuadSet quads = recorder.toQuadSet();

        assertEquals(2, quads.count(), "every recorded quad must keep its index in the geometry buffer");
        assertTrue(quads.isDegenerate(0));
        assertTrue(quads.isDegenerate(1));
        assertEquals(SortState.NONE, recorder.getSortState());
    }

    @Test
    void recordedValuesStayFiniteSoTheAnalysisNeverSeesOne() {
        TranslucentQuadRecorder recorder = new TranslucentQuadRecorder();

        captureQuad(recorder, corners(0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 0));
        captureQuad(recorder, corners(Float.NaN, 0, 0, Float.NaN, 0, 1, Float.NaN, 1, 1, Float.NaN, 1, 0));

        QuadSet quads = recorder.toQuadSet();

        assertAllFinite(quads.centers());
        assertAllFinite(quads.bounds());
        assertAllFinite(quads.normals());
        assertAllFinite(quads.dots());
    }

    @Test
    void nonFiniteQuadDoesNotBreakTheCoplanarShortcut() {
        TranslucentQuadRecorder recorder = new TranslucentQuadRecorder();

        // two coplanar slanted quads on the plane y = z, so no axis-aligned face claims them
        captureQuad(recorder, corners(0, 0, 0, 1, 0, 0, 1, 1, 1, 0, 1, 1));
        captureQuad(recorder, corners(1, 0, 0, 2, 0, 0, 2, 1, 1, 1, 1, 1));
        captureQuad(recorder, corners(Float.NaN, 0, 0, Float.NaN, 0, 1, Float.NaN, 1, 1, Float.NaN, 1, 0));

        assertEquals(SortState.NONE, recorder.getSortState());
    }

    @Test
    void nonFiniteQuadDoesNotMoveTheBoundingBoxOfAnOutwardFacingSection() {
        TranslucentQuadRecorder recorder = new TranslucentQuadRecorder();

        // four faces of the box (2,2,2)-(4,4,4), each facing outwards, so no two can overlap on screen
        captureQuad(recorder, corners(2, 2, 2, 4, 2, 2, 4, 2, 4, 2, 2, 4));
        captureQuad(recorder, corners(2, 4, 2, 2, 4, 4, 4, 4, 4, 4, 4, 2));
        captureQuad(recorder, corners(2, 2, 2, 2, 2, 4, 2, 4, 4, 2, 4, 2));
        captureQuad(recorder, corners(4, 2, 2, 4, 4, 2, 4, 4, 4, 4, 2, 4));
        captureQuad(recorder, corners(Float.NaN, 0, 0, Float.NaN, 0, 1, Float.NaN, 1, 1, Float.NaN, 1, 0));

        assertEquals(SortState.NONE, recorder.getSortState());
    }

    private static void assertAllFinite(float[] values) {
        for (float value : values) {
            assertTrue(Float.isFinite(value), "recorded " + value + " would reach the sort analysis");
        }
    }

    private static boolean isPermutation(int[] order, int count) {
        if (order.length != count) {
            return false;
        }

        boolean[] seen = new boolean[count];

        for (int value : order) {
            if (value < 0 || value >= count || seen[value]) {
                return false;
            }

            seen[value] = true;
        }

        return true;
    }

    private static void captureQuad(TranslucentQuadRecorder recorder, float[][] vertices) {
        ChunkVertexEncoder.Vertex vertex = new ChunkVertexEncoder.Vertex();

        for (float[] position : vertices) {
            vertex.x = position[0];
            vertex.y = position[1];
            vertex.z = position[2];
            recorder.capture(vertex);
        }
    }

    /** The four corners of a quad, in the winding order the encoder writes them. */
    private static float[][] corners(float x0, float y0, float z0, float x1, float y1, float z1,
                                     float x2, float y2, float z2, float x3, float y3, float z3) {
        return new float[][]{
                {x0, y0, z0},
                {x1, y1, z1},
                {x2, y2, z2},
                {x3, y3, z3}
        };
    }
}
