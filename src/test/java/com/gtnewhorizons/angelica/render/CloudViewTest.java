package com.gtnewhorizons.angelica.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudViewTest {

    @Test
    void cameraOffsetsAreTheInverseViewTranslation() {
        final CloudView view = new CloudView();
        view.update(perspective(), new Matrix4f().translate(-12.5F, -70.0F, 33.25F), 1280, 720);

        assertEquals(12.5F, view.offsetX, 1.0E-5F);
        assertEquals(70.0F, view.offsetY, 1.0E-5F);
        assertEquals(-33.25F, view.offsetZ, 1.0E-5F);
    }

    @Test
    void facingFollowsTheCameraForwardDirection() {
        final float[] yaws = {0.0F, 0.7F, -1.2F, 2.9F};
        for (float yaw : yaws) {
            final CloudView view = new CloudView();
            view.update(perspective(), lookingAlong(yaw, 0.0F, 70.0F, 0.0F), 1280, 720);
            assertEquals(yaw, view.facing, 1.0E-5, "yaw " + yaw);
        }
    }

    @Test
    void pixelScaleComesFromTheProjection() {
        final int width = 1280;
        final int height = 720;
        final double fovDegrees = 70.0;
        final CloudView view = new CloudView();
        view.update(perspective(fovDegrees, width, height), new Matrix4f(), width, height);

        assertEquals(
            height * 0.5 / Math.tan(Math.toRadians(fovDegrees) * 0.5),
            view.pixelsPerRadian,
            1.0E-2
        );
    }

    @Test
    void halfSpanGrowsWithCloudHeightAndStaysWithinAQuarterTurn() {
        final CloudView view = new CloudView();
        view.update(perspective(), new Matrix4f(), 1280, 720);

        final double levelSpan = view.halfSpan(0.0, 0.0);
        assertTrue(levelSpan > 0.0 && levelSpan < Math.PI, "level clouds must stay cullable");

        double previous = 0.0;
        for (int heightCells = 0; heightCells <= 80; heightCells += 4) {
            final double span = view.halfSpan(heightCells, 0.0);
            assertTrue(span >= previous, "half span shrank at height " + heightCells);
            assertTrue(span <= Math.PI, "half span left a quarter turn at height " + heightCells);
            previous = span;
        }
        assertEquals(Math.PI, previous, 1.0E-9, "clouds far above the camera must lose the cull");
    }

    @Test
    void halfSpanTreatsADeckBelowTheCameraLikeOneAbove() {
        final CloudView view = new CloudView();
        view.update(perspective(), new Matrix4f(), 1280, 720);

        double previous = 0.0;
        for (int heightCells = 0; heightCells <= 80; heightCells += 4) {
            final double above = view.halfSpan(heightCells, 0.0);
            final double below = view.halfSpan(-heightCells, 0.0);
            assertEquals(above, below, 0.0, "height " + heightCells);
            assertTrue(below >= previous, "half span shrank below the camera at " + heightCells);
            assertTrue(below <= Math.PI, "half span left a quarter turn below the camera at " + heightCells);
            previous = below;
        }
        assertEquals(Math.PI, previous, 1.0E-9, "a deck far below the camera must lose the cull");
    }

    @Test
    void projectionsWithoutAPerspectiveDivideAreUncullable() {
        final CloudView orthographic = new CloudView();
        orthographic.update(new Matrix4f().ortho(-1.0F, 1.0F, -1.0F, 1.0F, 0.05F, 512.0F), new Matrix4f(), 1280, 720);
        assertEquals(Math.PI, orthographic.halfSpan(0.0, 0.0), 1.0E-9);

        final Matrix4f offAxis = perspective();
        offAxis.m01(0.25F);
        final CloudView skewed = new CloudView();
        skewed.update(offAxis, new Matrix4f(), 1280, 720);
        assertEquals(Math.PI, skewed.halfSpan(0.0, 0.0), 1.0E-9);
    }

    @Test
    void scaledOrBrokenViewsAreUncullable() {
        final CloudView scaled = new CloudView();
        scaled.update(perspective(), new Matrix4f().scale(2.0F), 1280, 720);
        assertEquals(Math.PI, scaled.halfSpan(0.0, 0.0), 1.0E-9);

        final Matrix4f brokenView = new Matrix4f();
        brokenView.m00(Float.NaN);
        final CloudView broken = new CloudView();
        broken.update(perspective(), brokenView, 1280, 720);
        assertEquals(Math.PI, broken.halfSpan(0.0, 0.0), 1.0E-9);
        assertEquals(0.0F, broken.offsetX, 1.0E-6F);
        assertEquals(0.0F, broken.offsetZ, 1.0E-6F);
    }

    @Test
    void driftBeyondTheAlwaysDrawnRadiusDisablesCulling() {
        final CloudView view = new CloudView();
        view.update(perspective(), new Matrix4f(), 1280, 720);

        assertTrue(view.halfSpan(16.0, 4.0) > view.halfSpan(16.0, 0.0), "drift must widen the span");
        assertEquals(Math.PI, view.halfSpan(16.0, CloudDisc.ALWAYS_DRAWN_CELLS / 2.0), 1.0E-9);
        assertEquals(Math.PI, view.halfSpan(16.0, CloudDisc.ALWAYS_DRAWN_CELLS), 1.0E-9);
    }

    @Test
    void repeatedUpdatesKeepTheFacingWhileTheCameraMoves() {
        final CloudView view = new CloudView();
        final Matrix4f projection = perspective();
        view.update(projection, lookingAlong(0.7F, 10.0F, 70.0F, 20.0F), 1280, 720);
        final double facing = view.facing;
        assertEquals(0.7, facing, 1.0E-5);

        view.update(projection, lookingAlong(0.7F, -30.0F, 70.0F, 55.0F), 1280, 720);
        assertEquals(facing, view.facing, 0.0);
        assertEquals(-30.0F, view.offsetX, 1.0E-5F);
        assertEquals(70.0F, view.offsetY, 1.0E-5F);
        assertEquals(55.0F, view.offsetZ, 1.0E-5F);
    }

    /**
     * Builds a view matrix for a camera at the given position whose horizontal forward direction is
     * {@code (cos yaw, 0, sin yaw)}. The rotation rows are right, up and backwards, because a camera looks
     * along negative Z in view space.
     */
    private static Matrix4f lookingAlong(float yaw, float cameraX, float cameraY, float cameraZ) {
        final float forwardX = (float) Math.cos(yaw);
        final float forwardZ = (float) Math.sin(yaw);
        final Matrix4f view = new Matrix4f();
        view.m00(-forwardZ).m20(forwardX);
        view.m11(1.0F);
        view.m02(-forwardX).m22(-forwardZ);
        return view.translate(-cameraX, -cameraY, -cameraZ);
    }

    private static Matrix4f perspective() {
        return perspective(70.0, 1280, 720);
    }

    private static Matrix4f perspective(double fovDegrees, int width, int height) {
        return new Matrix4f().perspective(
            (float) Math.toRadians(fovDegrees),
            (float) width / height,
            0.05F,
            512.0F
        );
    }
}
