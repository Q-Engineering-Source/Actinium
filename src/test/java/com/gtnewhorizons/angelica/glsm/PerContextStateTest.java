package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.streaming.StubRenderBackend;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.streaming.StreamingVaos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the per-context ownership of the state COW-B moved out of GLStateManager statics: a second
 * context must not share any of it with the primary one.
 */
class PerContextStateTest {


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

    private GLContextState worker;

    @AfterEach
    void unbind() {
        if (worker != null) {
            GLStateManager.exitWorkerContext();
            worker = null;
        }
    }

    @Test
    void aBoundContextOwnsItsOwnStateHolders() {
        final GLContextState primary = GLStateManager.ctx();

        worker = GLStateManager.enterWorkerContext();

        assertNotSame(primary, worker, "a bound context is a context of its own");
        assertSame(worker, GLStateManager.ctx(), "ctx() resolves to the bound context");
        assertNotSame(primary.ffp, worker.ffp, "each context owns an FFP manager");
        assertNotSame(primary.vao, worker.vao, "each context owns its vertex attribute state");
        assertNotSame(primary.streamingVaos, worker.streamingVaos, "each context owns its streaming VAOs");
        assertNotSame(primary.vaoEboMap, worker.vaoEboMap, "each context owns its VAO -> EBO map");
        assertNotSame(primary.ffp.normalStack, worker.ffp.normalStack, "each context owns its normal stack");
    }

    @Test
    void stateWrittenThroughOneContextDoesNotLeakIntoTheOther() {
        final GLContextState primary = GLStateManager.ctx();
        ShaderManager.setCurrentNormal(0.0f, 0.0f, 1.0f);

        worker = GLStateManager.enterWorkerContext();
        ShaderManager.setCurrentNormal(1.0f, 0.0f, 0.0f);
        assertEquals(1.0f, worker.ffp.currentNormal.x, 0.0f, "the bound context sees its own normal");
        assertSame(worker.ffp.currentNormal, ShaderManager.getCurrentNormal(), "the static accessor reads the bound context");

        GLStateManager.exitWorkerContext();
        worker = null;
        assertEquals(0.0f, primary.ffp.currentNormal.x, 0.0f, "the primary context kept its own normal");
        assertSame(primary.ffp.currentNormal, ShaderManager.getCurrentNormal());
    }

    @Test
    void streamingVaoArraysArePerContext() {
        final GLContextState primary = GLStateManager.ctx();
        final StreamingVaos primaryVaos = primary.streamingVaos;
        primaryVaos.persistentVAOs[3] = 1234;

        worker = GLStateManager.enterWorkerContext();
        assertEquals(0, worker.streamingVaos.persistentVAOs[3], "a new context starts with no streaming VAOs");
        worker.streamingVaos.orphanVAOs[3] = 4321;
        assertEquals(0, primaryVaos.orphanVAOs[3], "and does not write into the primary context's arrays");
    }
}
