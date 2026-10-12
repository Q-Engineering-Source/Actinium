package net.coderbot.iris.uniforms;

import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code gbufferPrevious*} / {@code dhPrevious*} uniforms share one tracker per uniform name:
 * the matrices are world render state, so every program declaring the uniform must read the same
 * previous-frame value instead of keeping (and uploading) a copy of its own.
 */
class PreviousMatrixSharingTest {
    private final Matrix4f source = new Matrix4f();

    @BeforeEach
    void resetFrameCounter() {
        SystemTimeUniforms.COUNTER.reset();
    }

    @Test
    void sharesOneTrackerPerUniformName() {
        final MatrixUniforms.Previous modelView = MatrixUniforms.Previous.shared("gbufferPreviousModelViewSharingTest", () -> source);
        final MatrixUniforms.Previous sameName = MatrixUniforms.Previous.shared("gbufferPreviousModelViewSharingTest", () -> source);

        assertSame(modelView, sameName, "the same uniform name must reuse the tracker");
        assertNotSame(modelView, MatrixUniforms.Previous.shared("gbufferPreviousProjectionSharingTest", () -> source),
            "a different uniform name must track the previous value on its own");
    }

    @Test
    void advancesOncePerFrameAndReportsThePreviousFrameValue() {
        final MatrixUniforms.Previous previous = new MatrixUniforms.Previous(() -> source);

        source.translation(1f, 0f, 0f);
        assertEquals(new Matrix4f(), new Matrix4f(previous.get()), "the first read of the first frame reports identity");

        SystemTimeUniforms.COUNTER.beginFrame();
        source.translation(2f, 0f, 0f);
        final Matrix4f secondFrameFirstRead = new Matrix4f(previous.get());
        assertEquals(new Matrix4f().translation(1f, 0f, 0f), secondFrameFirstRead,
            "the second frame reports the value read during the first frame");

        source.translation(3f, 0f, 0f);
        assertEquals(secondFrameFirstRead, new Matrix4f(previous.get()),
            "later reads in the same frame repeat that frame's snapshot");

        SystemTimeUniforms.COUNTER.beginFrame();
        assertEquals(new Matrix4f().translation(2f, 0f, 0f), new Matrix4f(previous.get()),
            "the third frame reports the value captured by the second frame's first read, not the mid-frame change");
    }

    @Test
    void doesNotAdvanceAgainWhenASecondProgramReadsTheSharedTracker() {
        final MatrixUniforms.Previous firstProgram = MatrixUniforms.Previous.shared("gbufferPreviousModelViewSharedAdvanceTest", () -> source);
        final MatrixUniforms.Previous secondProgram = MatrixUniforms.Previous.shared("gbufferPreviousModelViewSharedAdvanceTest", () -> source);

        source.translation(4f, 0f, 0f);
        assertEquals(new Matrix4f(), new Matrix4f(firstProgram.get()), "the first program reports identity on the first frame");
        assertEquals(new Matrix4f(), new Matrix4f(secondProgram.get()), "the second program sees that same first-frame value");

        SystemTimeUniforms.COUNTER.beginFrame();
        source.translation(5f, 0f, 0f);
        assertEquals(new Matrix4f().translation(4f, 0f, 0f), new Matrix4f(secondProgram.get()),
            "the second program reports the previous frame without advancing the tracker a second time");

        source.translation(6f, 0f, 0f);
        SystemTimeUniforms.COUNTER.beginFrame();
        assertEquals(new Matrix4f().translation(5f, 0f, 0f), new Matrix4f(firstProgram.get()),
            "the next frame reports the value captured by the shared tracker's first read, not the later change");
    }

    /**
     * The report's instance count is the sharing made visible: it counts uniform names, where a per-program
     * tracker would count every (program, uniform) pair. The count is compared against its previous value
     * here because the registry is shared by the whole test class.
     */
    @Test
    void reportsTheSharedInstancesAndTheReadsOfThePeriod() {
        MatrixUniforms.previousStatsSummary();

        final MatrixUniforms.Previous tracker = MatrixUniforms.Previous.shared("gbufferPreviousStatsTest", () -> source);
        tracker.get();
        tracker.get();

        final String summary = MatrixUniforms.previousStatsSummary();

        assertTrue(summary.startsWith("uniformPrevious[instances="), summary);
        assertTrue(summary.endsWith(",reads=2]"), summary);
        assertEquals("", MatrixUniforms.previousStatsSummary(),
            "an unchanged instance count and no reads leave nothing to report");
    }
}
