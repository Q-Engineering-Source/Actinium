package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.stacks.CowDepths;
import com.gtnewhorizons.angelica.glsm.stacks.IntegerStateStack;
import com.gtnewhorizons.angelica.glsm.streaming.StubRenderBackend;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the copy-on-write attribute stacks: which stacks an attribute group saves, that only the
 * modified ones are restored, and that a scope which changed nothing drives no backend calls.
 */
class CowStateStackTest {

    /** Answers only the calls a depth/scissor restore makes and records them. */
    private static final class RecordingBackend extends StubRenderBackend {
        int depthFuncCalls;
        int depthFunc = -1;
        int depthMaskCalls;
        boolean depthMask;
        int clearDepthCalls;
        int scissorCalls;
        int scissorX = -1;

        @Override public void depthFunc(int func) { depthFuncCalls++; depthFunc = func; }
        @Override public void depthMask(boolean enabled) { depthMaskCalls++; depthMask = enabled; }
        @Override public void clearDepth(double depth) { clearDepthCalls++; }
        @Override public void scissor(int x, int y, int width, int height) { scissorCalls++; scissorX = x; }

        /** GLStateManager reads the texture unit count while it initializes. */
        @Override public int getInteger(int pname) { return 32; }

        @Override public void enable(int cap) { enabledCaps.add(cap); }
        @Override public void disable(int cap) { enabledCaps.remove(cap); }

        final java.util.Set<Integer> enabledCaps = new java.util.HashSet<>();
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
    void attributeGroupsSaveOnlyTheirOwnStacks() {
        final GLContextState ctx = GLStateManager.ctx();

        final StateSet depth = StateSet.forMask(GL11.GL_DEPTH_BUFFER_BIT);
        assertTrue(depth.contains(ctx.depthState.stackId()), "GL_DEPTH_BUFFER_BIT saves the depth state");
        assertFalse(depth.contains(ctx.blendState.stackId()), "GL_DEPTH_BUFFER_BIT does not save the blend state");
        assertNotEquals(0, depth.restore & StateSet.R_DEPTH);
        assertEquals(0, depth.restore & StateSet.R_BLEND);
        assertFalse(depth.blendSnapshot);

        final StateSet scissor = StateSet.forMask(GL11.GL_SCISSOR_BIT);
        assertTrue(scissor.contains(ctx.scissorState.stackId()), "GL_SCISSOR_BIT saves the scissor box");
        assertNotEquals(0, scissor.restore & StateSet.R_SCISSOR);

        final StateSet colorBuffer = StateSet.forMask(GL11.GL_COLOR_BUFFER_BIT);
        assertTrue(colorBuffer.contains(ctx.blendState.stackId()));
        assertNotEquals(0, colorBuffer.restore & StateSet.R_BLEND);
        assertTrue(colorBuffer.blendSnapshot, "GL_COLOR_BUFFER_BIT snapshots the vanilla blend function");

        final StateSet transform = StateSet.forMask(GL11.GL_TRANSFORM_BIT);
        assertNotEquals(0, transform.bump & StateSet.B_TRANSFORM);
        assertEquals(0, StateSet.forMask(GL11.GL_DEPTH_BUFFER_BIT).bump & StateSet.B_TRANSFORM);
    }

    @Test
    void popRestoresTheModifiedStacksAndKeepsTheUnsavedOnes() {
        final RecordingBackend backend = installBackend();
        GLStateManager.glDepthFunc(GL11.GL_LEQUAL);
        GLStateManager.glScissor(1, 2, 3, 4);

        GLStateManager.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_ENABLE_BIT);
        GLStateManager.glDepthFunc(GL11.GL_GREATER);
        GLStateManager.glScissor(9, 9, 9, 9);
        GLStateManager.glEnable(GL11.GL_DEPTH_TEST);
        assertTrue(GLStateManager.getDepthTest().isEnabled());

        GLStateManager.glPopAttrib();

        assertEquals(GL11.GL_LEQUAL, GLStateManager.getDepthState().getFunc(), "the saved depth function comes back");
        assertEquals(GL11.GL_LEQUAL, backend.depthFunc, "the restore reaches the backend");
        assertFalse(GLStateManager.getDepthTest().isEnabled(), "the saved depth test comes back");
        assertEquals(9, GLStateManager.getScissorState().x, "a group that does not save the scissor box keeps the new value");
        assertEquals(9, backend.scissorX, "no scissor restore is issued for an unsaved group");
    }

    @Test
    void aScopeThatChangesNothingDrivesNoBackendCalls() {
        final RecordingBackend backend = installBackend();
        GLStateManager.glDepthFunc(GL11.GL_LEQUAL);
        final int depthCallsBefore = backend.depthFuncCalls;
        final int scissorCallsBefore = backend.scissorCalls;

        GLStateManager.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GLStateManager.glPopAttrib();

        assertEquals(depthCallsBefore, backend.depthFuncCalls, "an untouched scope must not re-issue the depth function");
        assertEquals(0, backend.depthMaskCalls);
        assertEquals(0, backend.clearDepthCalls);
        assertEquals(scissorCallsBefore, backend.scissorCalls);
    }

    @Test
    void popRestoresTheScissorBoxWhenTheGroupSavesIt() {
        final RecordingBackend backend = installBackend();
        GLStateManager.glScissor(1, 2, 3, 4);

        GLStateManager.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GLStateManager.glScissor(9, 9, 9, 9);
        GLStateManager.glPopAttrib();

        assertEquals(1, GLStateManager.getScissorState().x);
        assertEquals(1, backend.scissorX, "the scissor restore reaches the backend");
    }

    @Test
    void nestedScopesRestoreInOrder() {
        final RecordingBackend backend = installBackend();
        GLStateManager.glDepthFunc(GL11.GL_LEQUAL);

        GLStateManager.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT);
        GLStateManager.glDepthFunc(GL11.GL_GREATER);
        GLStateManager.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT);
        GLStateManager.glDepthFunc(GL11.GL_ALWAYS);
        GLStateManager.glPopAttrib();
        assertEquals(GL11.GL_GREATER, GLStateManager.getDepthState().getFunc(), "the inner pop restores the inner push");
        GLStateManager.glPopAttrib();
        assertEquals(GL11.GL_LEQUAL, GLStateManager.getDepthState().getFunc(), "the outer pop restores the outer push");
        assertEquals(GL11.GL_LEQUAL, backend.depthFunc);
    }

    @Test
    void aStackModifiedInAScopeThatDoesNotSaveItIsRestoredByTheParent() {
        final RecordingBackend backend = installBackend();
        GLStateManager.glDepthFunc(GL11.GL_LEQUAL);

        GLStateManager.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        // The inner scope does not save the depth state, so its change has to survive the inner pop
        // and be undone when the outer scope pops.
        GLStateManager.glPushAttrib(GL11.GL_LINE_BIT);
        GLStateManager.glDepthFunc(GL11.GL_ALWAYS);
        GLStateManager.glPopAttrib();
        assertEquals(GL11.GL_ALWAYS, GLStateManager.getDepthState().getFunc(), "the inner scope does not save the depth state");

        GLStateManager.glPopAttrib();
        assertEquals(GL11.GL_LEQUAL, GLStateManager.getDepthState().getFunc(), "the outer scope still restores it");
        assertEquals(GL11.GL_LEQUAL, backend.depthFunc);
    }

    @Test
    void cowDepthsOnlyClaimsASlotOncePerDepth() {
        final CowDepths depths = new CowDepths();

        assertEquals(0, depths.claim(1));
        assertTrue(depths.claimedAt(1));
        assertFalse(depths.claimedAt(2));
        assertEquals(1, depths.claim(2));
        assertTrue(depths.claimedAt(2));

        assertEquals(1, depths.pop(), "the deepest slot pops first");
        assertEquals(0, depths.pop());
        assertEquals(-1, depths.pop(), "an empty stack pops nothing");
    }

    @Test
    void cowDepthsPromoteASlotWhenTheScopeDidNotSaveTheStack() {
        final CowDepths depths = new CowDepths();
        depths.claim(3);

        assertEquals(0, depths.discard(4), "a slot claimed deeper than the popped scope is promoted to the parent");
        assertTrue(depths.claimedAt(3));

        final CowDepths nested = new CowDepths();
        nested.claim(3);
        nested.claim(4);
        assertEquals(-1, nested.discard(4), "a slot the parent scope already covers is dropped");
        assertTrue(nested.claimedAt(3));
    }

    @Test
    void integerStacksCaptureAndRestoreThroughTheCowDepths() {
        final IntegerStateStack stack = new IntegerStateStack(1, -1);

        stack.push();
        stack.setValue(2);
        assertEquals(2, stack.getValue());
        stack.pop();
        assertEquals(1, stack.getValue(), "the pushed value comes back");
    }
}
