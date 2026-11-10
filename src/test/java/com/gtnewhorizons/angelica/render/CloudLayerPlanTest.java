package com.gtnewhorizons.angelica.render;

import com.gtnewhorizons.angelica.api.clouds.CloudLayer;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static com.gtnewhorizons.angelica.render.CloudDisc.CELLS_PER_CHUNK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudLayerPlanTest {

    private static final float CELL_WIDTH = 12.0F;
    private static final float CELL_HEIGHT = 4.0F;
    private static final float LAYER_HEIGHT = 120.0F;

    @Test
    void theCameraCellCoordinateAddsTheViewOffsetOnce() {
        final CloudView view = view(2.5F, 1.0F, -1.25F);
        final CloudLayerPlan.Camera camera = new CloudLayerPlan.Camera(100.0, 100.0, 64.0, 64.0, -40.0, -40.0);

        final CloudLayerPlan plan = plan(camera, view, layer(LAYER_HEIGHT), fancy(8), false,
            true, 1);

        assertEquals((100.0 + 2.5) / CELL_WIDTH, plan.anchorX() + plan.cellFractionX(), 1.0E-4);
        assertEquals((-40.0 - 1.25) / CELL_WIDTH, plan.anchorZ() + plan.cellFractionZ(), 1.0E-4);
        assertEquals(2.5F / CELL_WIDTH, plan.entityToCameraX(), 1.0E-5);
        assertEquals(-1.25F / CELL_WIDTH, plan.entityToCameraZ(), 1.0E-5);
    }

    @Test
    void theCameraInterpolatesThePreviousTickPosition() {
        final CloudLayerPlan.Camera camera = new CloudLayerPlan.Camera(0.0, 10.0, 60.0, 70.0, 20.0, 30.0);

        assertEquals(5.0, camera.interpolatedX(0.5F), 1.0E-9);
        assertEquals(25.0, camera.interpolatedZ(0.5F), 1.0E-9);
        assertEquals(65.0, camera.interpolatedY(0.5F), 1.0E-9);
    }

    @Test
    void aRaisedViewLowersTheDeckButKeepsTheEntityRelativeBase() {
        final CloudLayer layer = layer(LAYER_HEIGHT);
        final CloudLayerPlan.Camera camera = new CloudLayerPlan.Camera(0.0, 0.0, 70.0, 70.0, 0.0, 0.0);
        final CloudLayerPlan eyeLevel = plan(camera, view(0.0F, 0.0F, 0.0F), layer,
            fancy(8), false, true, 1);
        final CloudLayerPlan raisedEye = plan(camera, view(0.0F, 6.0F, 0.0F), layer,
            fancy(8), false, true, 1);

        assertEquals(eyeLevel.cloudBaseRelativeY() - 6.0F, raisedEye.cloudBaseRelativeY(), 1.0E-4);
        assertEquals(eyeLevel.deckBaseY(), raisedEye.deckBaseY(), 1.0E-4);
        assertEquals(raisedEye.cloudBaseRelativeY() + 6.0F, raisedEye.deckBaseY(), 1.0E-4);
    }

    @Test
    void theFacesFollowTheSideOfTheDeckTheCameraIsOn() {
        final CloudView view = view(0.0F, 0.0F, 0.0F);

        final CloudLayerPlan below = planAtY(LAYER_HEIGHT - CELL_HEIGHT, view);
        assertTrue(below.emitUnderside());
        assertFalse(below.emitTopSurface());
        assertFalse(below.insideDeck());

        final CloudLayerPlan above = planAtY(LAYER_HEIGHT + CELL_HEIGHT * 2.0F, view);
        assertFalse(above.emitUnderside());
        assertTrue(above.emitTopSurface());
        assertFalse(above.insideDeck());

        final CloudLayerPlan inside = planAtY(LAYER_HEIGHT + CELL_HEIGHT * 0.5F, view);
        assertTrue(inside.emitUnderside());
        assertTrue(inside.emitTopSurface());
        assertTrue(inside.insideDeck());
    }

    @Test
    void theFaceMeshIsOnlyUsedInsideTheDeckWithoutShaderPacks() {
        final CloudView view = view(0.0F, 0.0F, 0.0F);

        assertTrue(planAtY(LAYER_HEIGHT + CELL_HEIGHT * 0.5F, view).faceMeshActive());

        final CloudLayerPlan withPack = plan(insideCamera(), view, layer(LAYER_HEIGHT),
            fancy(8), true, true, 1);
        assertFalse(withPack.faceMeshActive());

        final CloudLayerPlan fast = plan(insideCamera(), view, layer(LAYER_HEIGHT),
            fast(8), false, true, 1);
        assertFalse(fast.faceMeshActive());

        assertFalse(planAtY(LAYER_HEIGHT - CELL_HEIGHT, view).faceMeshActive());
    }

    @Test
    void theRadiusCoversTheConfiguredRenderDistanceInWholeChunks() {
        final CloudView view = view(0.0F, 0.0F, 0.0F);
        int previousCells = 0;

        for (int distance : new int[] {2, 8, 16}) {
            final CloudLayerPlan plan = plan(insideCamera(), view, layer(LAYER_HEIGHT),
                fancy(distance), false, true, 1);

            assertTrue(plan.radiusCells() * CELL_WIDTH >= distance * 64.0F, "distance " + distance);
            assertEquals(0, plan.radiusCells() % CELLS_PER_CHUNK, "distance " + distance);
            assertTrue(plan.radiusCells() >= previousCells, "distance " + distance);
            previousCells = plan.radiusCells();
        }
    }

    @Test
    void aNewShapeOrShaderStateInvalidatesTheCachedGeometry() {
        final CloudView view = view(0.0F, 0.0F, 0.0F);
        final CloudLayerPlan base = plan(insideCamera(), view, layer(LAYER_HEIGHT), fancy(8),
            false, true, 1);
        final CloudLayerPlan nextShape = plan(insideCamera(), view, layer(LAYER_HEIGHT),
            fancy(8), false, true, 2);
        final CloudLayerPlan withPack = plan(insideCamera(), view, layer(LAYER_HEIGHT),
            fancy(8), true, true, 1);

        assertTrue(base.key().matches(base.key()));
        assertFalse(nextShape.key().matches(base.key()));
        assertFalse(withPack.key().matches(base.key()));
    }

    @Test
    void theKeyIgnoresThePlateDetailForAFaceMeshBuild() {
        final CloudLayerPlan.Key faceMesh = new CloudLayerPlan.Key(1, true, true, 8, CELL_HEIGHT, false,
            Integer.MAX_VALUE, 32, true);
        final CloudLayerPlan.Key otherPlateDetail = new CloudLayerPlan.Key(1, true, true, 8, CELL_HEIGHT, false,
            Integer.MAX_VALUE, 64, true);
        final CloudLayerPlan.Key vertexMesh = new CloudLayerPlan.Key(1, false, true, 8, CELL_HEIGHT, false,
            96, 32, false);
        final CloudLayerPlan.Key otherVertexPlateDetail = new CloudLayerPlan.Key(1, false, true, 8, CELL_HEIGHT,
            false, 96, 64, false);

        assertTrue(faceMesh.matches(otherPlateDetail));
        assertFalse(vertexMesh.matches(otherVertexPlateDetail));
    }

    @Test
    void theLodCutsAreDisabledWhenTheyCannotBeApplied() {
        final CloudView flat = new CloudView();
        flat.update(new Matrix4f(), new Matrix4f(), 1280, 720);
        final CloudLayerPlan noPerspective = plan(insideCamera(), flat, layer(LAYER_HEIGHT),
            fancy(8), false, true, 1);
        assertEquals(Integer.MAX_VALUE, noPerspective.wallCutCells());
        assertEquals(Integer.MAX_VALUE, noPerspective.plateLodCells());

        final CloudView perspective = view(0.0F, 0.0F, 0.0F);
        final CloudLayerPlan coarseCover = planAtY(LAYER_HEIGHT - CELL_HEIGHT, perspective, true);
        final CloudLayerPlan fineCover = planAtY(LAYER_HEIGHT - CELL_HEIGHT, perspective, false);

        assertNotEquals(Integer.MAX_VALUE, coarseCover.wallCutCells());
        assertNotEquals(Integer.MAX_VALUE, coarseCover.plateLodCells());
        assertEquals(Integer.MAX_VALUE, fineCover.plateLodCells());
    }

    private static CloudLayerPlan planAtY(float cameraY, CloudView view) {
        return planAtY(cameraY, view, true);
    }

    private static CloudLayerPlan planAtY(float cameraY, CloudView view, boolean coarseCoverUsable) {
        final CloudLayerPlan.Camera camera = new CloudLayerPlan.Camera(0.0, 0.0, cameraY, cameraY, 0.0, 0.0);
        return plan(camera, view, layer(LAYER_HEIGHT), fancy(8), false, coarseCoverUsable, 1);
    }

    private static CloudLayerPlan.Camera insideCamera() {
        return new CloudLayerPlan.Camera(0.0, 0.0, LAYER_HEIGHT + CELL_HEIGHT * 0.5, LAYER_HEIGHT + CELL_HEIGHT * 0.5,
            0.0, 0.0);
    }

    private static CloudLayerPlan.Settings fancy(int distance) {
        return new CloudLayerPlan.Settings(CloudLayerRenderer.MODE_FANCY, distance, 1);
    }

    private static CloudLayerPlan.Settings fast(int distance) {
        return new CloudLayerPlan.Settings(CloudLayerRenderer.MODE_FAST, distance, 1);
    }

    private static CloudLayerPlan plan(CloudLayerPlan.Camera camera, CloudView view, CloudLayer layer,
                                       CloudLayerPlan.Settings settings, boolean shadersActive,
                                       boolean coarseCoverUsable, int shapeGeneration) {
        return CloudLayerPlan.compute(camera, 0.0F, view, layer, settings, shadersActive, coarseCoverUsable,
            shapeGeneration);
    }

    private static CloudLayer layer(float height) {
        return new CloudLayer("test:clouds", new ResourceLocation("textures/environment/clouds.png"), height,
            CELL_WIDTH, CELL_HEIGHT, CELL_WIDTH, 0.0, 0.0, 1.0F, 1.0F, 1.0F, 0.8F);
    }

    private static CloudView view(float offsetX, float offsetY, float offsetZ) {
        final CloudView view = new CloudView();
        view.update(perspective(), new Matrix4f().translate(-offsetX, -offsetY, -offsetZ), 1280, 720);
        return view;
    }

    private static Matrix4f perspective() {
        return new Matrix4f().setPerspective((float) Math.toRadians(70.0), 1280.0F / 720.0F, 0.05F, 512.0F);
    }
}
