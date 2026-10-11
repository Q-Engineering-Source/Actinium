package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.streaming.StubRenderBackend;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import com.gtnewhorizons.angelica.glsm.states.GenerationTrackedState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upload markers are generation numbers of one GLSM context, so they must not survive a switch to
 * another one (a program used by two drawables would otherwise skip its uploads in the second).
 */
class GenerationTrackedStateTest {


    /** Answers only the query GLStateManager makes while it initializes. */
    private static final class InitBackend extends StubRenderBackend {
        @Override public int getInteger(int pname) { return 32; }
    }

    private RenderBackend original;

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

    private static final int CATEGORY = 0;

    private static final class Marker extends GenerationTrackedState {
        Marker() {
            super(1);
        }

        boolean needs(int generation) {
            return needsUpload(CATEGORY, generation);
        }

        void mark(int generation) {
            markUploaded(CATEGORY, generation);
        }
    }

    @Test
    void markersSurviveWhileTheContextStaysTheSame() {
        final Marker marker = new Marker();
        marker.bindOwner(GLStateManager.ctx());

        assertTrue(marker.needs(7), "an unmarked category needs an upload");
        marker.mark(7);
        assertFalse(marker.needs(7), "the same generation does not need another upload");
        marker.bindOwner(GLStateManager.ctx());
        assertFalse(marker.needs(7), "rebinding the same context keeps the markers");
    }

    @Test
    void switchingContextDropsTheMarkers() {
        final Marker marker = new Marker();
        marker.bindOwner(GLStateManager.ctx());
        marker.mark(7);

        final GLContextState other = GLStateManager.enterWorkerContext();
        try {
            marker.bindOwner(GLStateManager.ctx());
            assertTrue(marker.needs(7), "another context's generations say nothing about this one");
            marker.mark(7);
            assertFalse(marker.needs(7));
        } finally {
            GLStateManager.exitWorkerContext();
        }
        assertFalse(other == GLStateManager.ctx(), "the worker binding was released");
    }
}
