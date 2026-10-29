package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers that the capabilities shader pack parsing consults are answered from values captured at renderer
 * initialization instead of from the live context.
 *
 * <p>That is a correctness requirement, not only a test convenience: parsing also runs on the read-ahead
 * thread, where the mutable {@code GLStateManager.capabilities} object is not ours to read.</p>
 *
 * <p>These tests run before {@code RenderSystem.initRenderer()} has ever been called, so there is no context
 * capability object at all. It cannot even be observed from here: loading {@code GLStateManager} runs its
 * static initializer, which calls {@code glGetInteger} and aborts the JVM without a context. Which is the
 * point - a capability that parsing depends on must not come from there.</p>
 */
class RenderSystemBufferBlendingTest {

    @AfterEach
    void restoreDefaultCapabilities() {
        RenderSystem.initializeBufferBlendingSupportForTesting(false);
    }

    @Test
    void perBufferBlendingIsAnsweredWithoutAContext() {
        assertFalse(RenderSystem.supportsBufferBlending(), "no context has been initialized in this test");

        RenderSystem.initializeBufferBlendingSupportForTesting(true);
        assertTrue(RenderSystem.supportsBufferBlending(), "the answer is the capability, not a context probe");

        RenderSystem.initializeBufferBlendingSupportForTesting(false);
        assertFalse(RenderSystem.supportsBufferBlending());
    }

    @Test
    void computeShadersAreAnsweredWithoutAContext() {
        // Read through FeatureFlags.COMPUTE_SHADERS by every pack parse
        assertFalse(RenderSystem.supportsCompute(), "no context has been initialized in this test");
    }

    @Test
    void theOtherParsingRelevantCapabilitiesAreAlreadyAnswers() {
        // FeatureFlags consults these for CUSTOM_IMAGES, SSBO and TESSELLATION_SHADERS
        assertFalse(RenderSystem.supportsImageLoadStore());
        assertFalse(RenderSystem.supportsSSBO());
        assertFalse(RenderSystem.supportsTesselation());
    }
}
