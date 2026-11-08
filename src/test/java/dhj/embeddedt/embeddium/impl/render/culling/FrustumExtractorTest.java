package dhj.embeddedt.embeddium.impl.render.culling;

import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the frustum test the compute shader performs against JOML's frustum intersection for the same
 * projection and model-view pair.
 *
 * <p>The UBO planes are folded together with the padded section box and the fractional part of the camera, so an error
 * in that folding only shows up as terrain disappearing at the screen edge. The reference therefore evaluates a
 * section box around the camera-relative origin, the same box the compute shader tests, and the sweep skips sections
 * whose classification changes within the boundaries of the single-precision arithmetic.
 */
class FrustumExtractorTest {
    private static final float EYE_HEIGHT = 0.12f;
    private static final double BOUNDARY_SKIP = 1e-3;
    private static final int SWEEP_SECTIONS = 6;

    @Test
    void shaderFrustumAgreesWithJomlForEveryPoseAndCameraPosition() {
        final float[] distances = { 0.1f, 4.0f };
        final double[][] cameras = {
            { 8.3, 70.6, 8.7 },
            { -1234.4, 65.2, -987.6 },
            { 3000000.37, 80.5, -2999999.63 }
        };
        final float[] yaws = { 0f, 37f, 90f, 163f, 271f };
        final float[] pitches = { -60f, 0f, 25f, 80f };
        final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(69.0), 16f / 9f, 0.05f, 512.0f);
        final Matrix4f modelView = new Matrix4f();
        final Matrix4f referenceMvp = new Matrix4f();
        final Matrix4f mvp = new Matrix4f();
        final FrustumIntersection reference = new FrustumIntersection();
        final ByteBuffer ubo = FrustumExtractor.allocateUboByteBuffer();
        final StringBuilder report = new StringBuilder();
        int mismatches = 0;

        for (final float distance : distances) {
            for (final double[] cameraPosition : cameras) {
                final CameraTransform camera = new CameraTransform(cameraPosition[0], cameraPosition[1], cameraPosition[2]);
                final int baseX = Math.floorDiv((int) Math.floor(cameraPosition[0]), 16);
                final int baseY = Math.floorDiv((int) Math.floor(cameraPosition[1]), 16);
                final int baseZ = Math.floorDiv((int) Math.floor(cameraPosition[2]), 16);
                int tested = 0;
                int wronglyCulled = 0;
                int wronglyKept = 0;

                for (final float yaw : yaws) {
                    for (final float pitch : pitches) {
                        modelView.identity()
                            .translate(0f, 0f, -distance)
                            .rotateX((float) Math.toRadians(pitch))
                            .rotateY((float) Math.toRadians(yaw + 180.0f))
                            .translate(0f, EYE_HEIGHT, 0f);
                        reference.set(projection.mul(modelView, referenceMvp));
                        FrustumExtractor.writeFrustum(projection, modelView, camera, mvp, ubo);

                        for (int offsetX = -SWEEP_SECTIONS; offsetX <= SWEEP_SECTIONS; offsetX++) {
                            for (int offsetY = -SWEEP_SECTIONS; offsetY <= SWEEP_SECTIONS; offsetY++) {
                                for (int offsetZ = -SWEEP_SECTIONS; offsetZ <= SWEEP_SECTIONS; offsetZ++) {
                                    final int originX = (baseX + offsetX) * 16;
                                    final int originY = (baseY + offsetY) * 16;
                                    final int originZ = (baseZ + offsetZ) * 16;
                                    final boolean tight = jomlVisible(reference, camera, originX, originY, originZ, FrustumExtractor.SECTION_PADDING - BOUNDARY_SKIP);
                                    final boolean loose = jomlVisible(reference, camera, originX, originY, originZ, FrustumExtractor.SECTION_PADDING + BOUNDARY_SKIP);
                                    if (tight != loose) {
                                        continue;
                                    }

                                    final boolean actual = shaderInsideFrustum(ubo, originX, originY, originZ);
                                    tested++;
                                    if (tight && !actual) {
                                        wronglyCulled++;
                                    }
                                    if (!tight && actual) {
                                        wronglyKept++;
                                    }
                                }
                            }
                        }
                    }
                }

                mismatches += wronglyCulled + wronglyKept;
                report.append("\n  distance=").append(distance)
                    .append(" camera=(").append(cameraPosition[0]).append(", ").append(cameraPosition[1]).append(", ").append(cameraPosition[2])
                    .append("): tested=").append(tested)
                    .append(" wronglyCulled=").append(wronglyCulled)
                    .append(" wronglyKept=").append(wronglyKept);
            }
        }

        assertEquals(0, mismatches, "shader frustum test disagrees with the CPU frustum:" + report);
    }

    @Test
    void sectionCrossingTheScreenEdgeIsKeptWhileItsExactBoxIsOutside() {
        final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(69.0), 16f / 9f, 0.05f, 512.0f);
        final Matrix4f modelView = new Matrix4f()
            .translate(0f, 0f, -4f)
            .rotateY((float) Math.toRadians(37f + 180f))
            .translate(0f, EYE_HEIGHT, 0f);
        final Matrix4f referenceMvp = new Matrix4f();
        final FrustumIntersection reference = new FrustumIntersection();
        final CameraTransform camera = new CameraTransform(8.3, 70.6, 8.7);
        final ByteBuffer ubo = FrustumExtractor.allocateUboByteBuffer();
        reference.set(projection.mul(modelView, referenceMvp));
        FrustumExtractor.writeFrustum(projection, modelView, camera, new Matrix4f(), ubo);

        final int baseX = Math.floorDiv((int) Math.floor(camera.x), 16);
        final int baseY = Math.floorDiv((int) Math.floor(camera.y), 16);
        final int baseZ = Math.floorDiv((int) Math.floor(camera.z), 16);
        int edgeSections = 0;
        int culledSections = 0;

        for (int offsetX = -SWEEP_SECTIONS; offsetX <= SWEEP_SECTIONS; offsetX++) {
            for (int offsetY = -SWEEP_SECTIONS; offsetY <= SWEEP_SECTIONS; offsetY++) {
                for (int offsetZ = -SWEEP_SECTIONS; offsetZ <= SWEEP_SECTIONS; offsetZ++) {
                    final int originX = (baseX + offsetX) * 16;
                    final int originY = (baseY + offsetY) * 16;
                    final int originZ = (baseZ + offsetZ) * 16;
                    final boolean insideExact = jomlVisible(reference, camera, originX, originY, originZ, 0.0);
                    final boolean insidePadded = jomlVisible(reference, camera, originX, originY, originZ, FrustumExtractor.SECTION_PADDING - BOUNDARY_SKIP);
                    final boolean outsidePadded = !jomlVisible(reference, camera, originX, originY, originZ, FrustumExtractor.SECTION_PADDING + BOUNDARY_SKIP);
                    final boolean actual = shaderInsideFrustum(ubo, originX, originY, originZ);

                    if (!insideExact && insidePadded) {
                        assertTrue(actual, "section at (" + originX + ", " + originY + ", " + originZ
                            + ") crosses the screen edge: its exact box is outside the frustum but its padded box is inside,"
                            + " so the padded test must keep it");
                        edgeSections++;
                    } else if (outsidePadded) {
                        assertFalse(actual, "section at (" + originX + ", " + originY + ", " + originZ
                            + ") is outside the padded frustum and must be culled");
                        culledSections++;
                    }
                }
            }
        }

        assertTrue(edgeSections > 0, "the sweep found no section crossing the screen edge; the fixture no longer covers the regression");
        assertTrue(culledSections > 0, "the sweep found no section outside the frustum; the test cannot tell culling from keeping everything");
    }

    private static boolean jomlVisible(FrustumIntersection reference, CameraTransform camera, int originX, int originY, int originZ, double padding) {
        final float minX = (float) (originX - padding - camera.x);
        final float minY = (float) (originY - padding - camera.y);
        final float minZ = (float) (originZ - padding - camera.z);
        final float maxX = (float) (originX + 16.0 + padding - camera.x);
        final float maxY = (float) (originY + 16.0 + padding - camera.y);
        final float maxZ = (float) (originZ + 16.0 + padding - camera.z);
        return reference.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Evaluates the plane equations exactly the way {@code chunk_cull.csh} does, reading them back from the UBO.
     */
    private static boolean shaderInsideFrustum(ByteBuffer ubo, int originX, int originY, int originZ) {
        final float relativeX = originX - ubo.getInt(FrustumExtractor.CAMERA_BLOCK_OFFSET);
        final float relativeY = originY - ubo.getInt(FrustumExtractor.CAMERA_BLOCK_OFFSET + 4);
        final float relativeZ = originZ - ubo.getInt(FrustumExtractor.CAMERA_BLOCK_OFFSET + 8);
        for (int plane = 0; plane < FrustumExtractor.PLANE_COUNT; plane++) {
            final int offset = FrustumExtractor.PLANE_ARRAY_OFFSET + plane * FrustumExtractor.PLANE_STRIDE_BYTES;
            final float a = ubo.getFloat(offset);
            final float b = ubo.getFloat(offset + 4);
            final float c = ubo.getFloat(offset + 8);
            final float w = ubo.getFloat(offset + 12);
            if (a * relativeX + b * relativeY + c * relativeZ + w < 0.0f) {
                return false;
            }
        }
        return true;
    }
}
