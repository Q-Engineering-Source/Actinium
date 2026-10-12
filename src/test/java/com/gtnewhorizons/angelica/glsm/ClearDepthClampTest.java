package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.streaming.StubRenderBackend;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the range {@code glClearDepth} clamps its argument to. Callers pass values outside [0, 1] for
 * depth testing, and both the cached clear value and the backend call have to see the clear value the
 * GL specification defines, not the raw argument.
 */
class ClearDepthClampTest {

    /** Answers the calls {@code glClearDepth} makes and records the value it was handed. */
    private static final class RecordingBackend extends StubRenderBackend {
        double clearDepth = Double.NaN;

        @Override public void clearDepth(double depth) { clearDepth = depth; }

        /** GLStateManager reads the texture unit count while it initializes. */
        @Override public int getInteger(int pname) { return 32; }
    }

    private RenderBackend original;

    @AfterEach
    void restoreBackend() {
        if (original != null) Reflect.setStaticFinal(BackendManager.class, "RENDER_BACKEND", RenderBackend.class, original);
        original = null;
    }

    private RecordingBackend installBackend() {
        final RecordingBackend backend = new RecordingBackend();
        original = BackendManager.RENDER_BACKEND;
        Reflect.setStaticFinal(BackendManager.class, "RENDER_BACKEND", RenderBackend.class, backend);
        return backend;
    }

    @Test
    void valuesBelowTheUnitRangeClearAtZero() {
        final RecordingBackend backend = installBackend();

        GLStateManager.glClearDepth(-0.5);

        assertEquals(0.0, backend.clearDepth, "the backend is asked to clear at the bottom of the range");
        assertEquals(0.0, GLStateManager.getDepthState().getClearValue(), "the cached clear value matches it");
    }

    @Test
    void valuesAboveTheUnitRangeClearAtOne() {
        final RecordingBackend backend = installBackend();

        GLStateManager.glClearDepth(1.5);

        assertEquals(1.0, backend.clearDepth, "the backend is asked to clear at the top of the range");
        assertEquals(1.0, GLStateManager.getDepthState().getClearValue(), "the cached clear value matches it");
    }

    @Test
    void valuesInsideTheUnitRangeArePassedThrough() {
        final RecordingBackend backend = installBackend();

        GLStateManager.glClearDepth(0.5);
        assertEquals(0.5, backend.clearDepth);

        GLStateManager.glClearDepth(0.0);
        assertEquals(0.0, backend.clearDepth, "the low end of the range is a valid clear value");

        GLStateManager.glClearDepth(1.0);
        assertEquals(1.0, backend.clearDepth, "the high end of the range is a valid clear value");
    }
}
