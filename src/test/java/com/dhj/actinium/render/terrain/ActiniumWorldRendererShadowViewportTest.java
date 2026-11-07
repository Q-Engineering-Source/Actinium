package com.dhj.actinium.render.terrain;

import dhj.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiniumWorldRendererShadowViewportTest {
    private static final SimpleWorldRenderer.CameraState CAMERA_STATE =
        new SimpleWorldRenderer.CameraState(8.0, 64.0, 8.0, 0.0f, 0.0f, 128.0f);

    @Test
    void consumesCapturedPlayerViewportOnceThenFallsBackToLastTerrainViewport() {
        RecordingWorldRenderer renderer = new RecordingWorldRenderer();
        Viewport terrainViewport = viewportAt(0.0, 64.0, 0.0);
        Viewport capturedViewport = viewportAt(32.0, 70.0, 32.0);
        Viewport firstShadowViewport = viewportAt(0.0, 64.0, 0.0);
        Viewport secondShadowViewport = viewportAt(4.0, 64.0, 4.0);

        renderer.setCurrentViewport(terrainViewport);
        renderer.setShadowPassPlayerViewport(capturedViewport);

        renderer.setupShadowTerrain(firstShadowViewport, CAMERA_STATE, 12, false);

        assertEquals(1, renderer.calls.size());
        ShadowTerrainCall first = renderer.calls.get(0);
        assertSame(capturedViewport, first.playerViewport());
        assertSame(firstShadowViewport, first.shadowViewport());
        assertEquals(12, first.frame());
        assertFalse(first.spectator());

        renderer.setupShadowTerrain(secondShadowViewport, CAMERA_STATE, 13, true);

        assertEquals(2, renderer.calls.size());
        ShadowTerrainCall second = renderer.calls.get(1);
        assertSame(terrainViewport, second.playerViewport());
        assertSame(secondShadowViewport, second.shadowViewport());
        assertEquals(13, second.frame());
        assertTrue(second.spectator());
    }

    @Test
    void reusesACapturedViewportForTheNextShadowPassUntilItIsConsumed() {
        RecordingWorldRenderer renderer = new RecordingWorldRenderer();
        Viewport terrainViewport = viewportAt(0.0, 64.0, 0.0);
        Viewport capturedViewport = viewportAt(32.0, 70.0, 32.0);

        renderer.setCurrentViewport(terrainViewport);
        renderer.setShadowPassPlayerViewport(capturedViewport);
        renderer.setupShadowTerrain(viewportAt(0.0, 64.0, 0.0), CAMERA_STATE, 1, false);

        renderer.setShadowPassPlayerViewport(capturedViewport);
        renderer.setupShadowTerrain(viewportAt(0.0, 64.0, 0.0), CAMERA_STATE, 2, false);

        assertEquals(2, renderer.calls.size());
        assertSame(capturedViewport, renderer.calls.get(0).playerViewport());
        assertSame(capturedViewport, renderer.calls.get(1).playerViewport());
    }

    @Test
    void rejectsShadowTerrainWhenNoViewportWasCapturedOrCached() {
        RecordingWorldRenderer renderer = new RecordingWorldRenderer();

        assertThrows(IllegalStateException.class,
            () -> renderer.setupShadowTerrain(viewportAt(0.0, 64.0, 0.0), CAMERA_STATE, 3, false));
        assertTrue(renderer.calls.isEmpty());
    }

    private static Viewport viewportAt(double x, double y, double z) {
        return new Viewport((minX, minY, minZ, maxX, maxY, maxZ) -> true, new Vector3d(x, y, z));
    }

    private record ShadowTerrainCall(Viewport playerViewport, Viewport shadowViewport,
                                     SimpleWorldRenderer.CameraState cameraState, int frame, boolean spectator) {
    }

    private static final class RecordingWorldRenderer extends ActiniumWorldRenderer {
        private final List<ShadowTerrainCall> calls = new ArrayList<>();

        @Override
        public void setupShadowTerrain(Viewport playerViewport, Viewport shadowViewport,
                                       SimpleWorldRenderer.CameraState cameraState, int frame, boolean spectator) {
            this.calls.add(new ShadowTerrainCall(playerViewport, shadowViewport, cameraState, frame, spectator));
        }
    }
}
