package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.hooks.BatchStateGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the guard that drains a queued TESR batch before a state change it cannot replay.
 *
 * <p>Fog, scissor, stencil and non-zero texture matrices are changes the batch does not record per
 * draw, so each of them has to reach {@link BatchStateGuard#beforeChange()}. Changes the batch does
 * record per draw, such as the blend function, must not, and neither may anything inside a
 * suspended guard.</p>
 */
class UncapturedStateGuardGLTest extends GLStateManagerIntegrationFixture {

    private final AtomicInteger flushes = new AtomicInteger();
    private Runnable previousFlush;
    private int previousGeneration;

    @BeforeAll
    static void enableStateCaching() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        installDefaultGlsmConfig();
        GLStateManager.markSplashComplete("UncapturedStateGuardGLTest");
    }

    @BeforeEach
    void bindDisplay() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        previousFlush = BatchStateGuard.flush;
        BatchStateGuard.flush = () -> flushes.incrementAndGet();
        previousGeneration = GLStateManager.ctx().batchStateGeneration;
    }

    @AfterEach
    void restoreGuard() {
        BatchStateGuard.flush = previousFlush;
        while (BatchStateGuard.isSuspended()) BatchStateGuard.resume();
        GLStateManager.ctx().batchStateGeneration = previousGeneration;
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
    }

    @Test
    void fogColourFlushesTheOpenBatch() {
        assertEquals(0, flushes.get());

        GLSMFog.fogColor(0.1F, 0.2F, 0.3F, 1.0F);

        assertEquals(1, flushes.get(), "a fog colour change is not part of the captured draw state");
        assertEquals(previousGeneration + 1, GLStateManager.ctx().batchStateGeneration);
    }

    @Test
    void stencilAndScissorChangesFlushTheOpenBatch() {
        GLSMDepthStencilAlpha.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        GLSMRasterizer.glScissor(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);

        assertEquals(2, flushes.get());
    }

    @Test
    void textureMatrixOnAnotherUnitFlushesButModelViewDoesNot() {
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glTranslatef(1.0F, 2.0F, 3.0F);
        assertEquals(0, flushes.get(), "the modelview is replayed by the batch itself");

        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
        GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
        GLSMMatrices.glLoadIdentity();

        assertEquals(1, flushes.get(), "the texture matrix of unit 1 is not part of the captured draw state");
    }

    @Test
    void blendFunctionIsCapturedPerDrawAndDoesNotFlush() {
        GLSMBlend.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        assertEquals(0, flushes.get(), "the batch records the blend factors per draw");
    }

    @Test
    void blendEquationFlushesTheOpenBatch() {
        GLSMBlend.glBlendEquation(GL14.GL_FUNC_SUBTRACT);

        assertEquals(1, flushes.get(), "the blend equation is not part of the captured draw state");
    }

    @Test
    void suspendedGuardSuppressesTheFlush() {
        BatchStateGuard.suspend();
        try {
            GLSMFog.fogColor(0.4F, 0.5F, 0.6F, 1.0F);
        } finally {
            BatchStateGuard.resume();
        }

        assertEquals(0, flushes.get(), "the guard is suspended while the batch drains its own state");
    }

    @Test
    void batchingPredicateIsHostSupplied() {
        final java.util.function.BooleanSupplier previous = BatchStateGuard.batchingAllowed;
        assertFalse(BatchStateGuard.isBatching(), "no host installs a batching predicate in this test");

        BatchStateGuard.batchingAllowed = () -> true;
        try {
            assertTrue(BatchStateGuard.isBatching());
        } finally {
            BatchStateGuard.batchingAllowed = previous;
        }
    }

    @Test
    void textureSupersamplingFlushesOnlyWhenTheValueChanges() {
        final int previous = GLStateManager.setTextureSupersamplingSamples(2);
        try {
            assertEquals(1, flushes.get(), "the FFP supersampling factor is not part of the draw state");
            GLStateManager.setTextureSupersamplingSamples(2);
            assertEquals(1, flushes.get(), "writing the same factor again changes nothing");
        } finally {
            GLStateManager.setTextureSupersamplingSamples(previous);
        }
    }

    @Test
    void atlasGutterMarkerFlushesOnlyWhenTheGutterChanges() {
        final int texture = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        try {
            GLStateManager.markTextureAsAtlas(texture, 1);
            assertEquals(1, flushes.get(), "the atlas gutter feeds the fragment shader, so a batch drains first");
            GLStateManager.markTextureAsAtlas(texture, 1);
            assertEquals(1, flushes.get(), "the same gutter is not a change");
        } finally {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void attribScopeThatChangedUncapturedStateFlushesOnPop() {
        final int depth = GLSMAttribStack.pushState(StateSet.BLEND);
        try {
            GLSMFog.fogColor(0.7F, 0.8F, 0.9F, 1.0F);
            assertEquals(1, flushes.get());
        } finally {
            GLSMAttribStack.popStateTo(depth);
        }

        assertEquals(2, flushes.get(), "popping a scope that changed uncaptured state drains the batch spanning it");
    }
}
