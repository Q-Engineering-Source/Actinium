package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import com.gtnewhorizons.angelica.glsm.stacks.RetainedState;
import com.gtnewhorizons.angelica.glsm.stacks.TextureUnitBooleanStateStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CowDepthPruneIntegrationTest extends GLStateManagerIntegrationFixture {
    private int entryDepth;

    @BeforeAll
    static void initializeGlsm() {
        GLStateManager.applyInitConfig(GLSMInitConfig.builder().build());
    }

    @BeforeEach
    void setUp() {
        entryDepth = GLStateManager.getAttribDepth();
        GLStateManager.glDepthFunc(GL11.GL_LESS);
        GLStateManager.enableCull();
        GLStateManager.enableTexture();
    }

    @AfterEach
    void tearDown() {
        GLStateManager.popStateTo(entryDepth);
        GLStateManager.glDepthFunc(GL11.GL_LESS);
        GLStateManager.enableCull();
        GLStateManager.enableTexture();
    }

    @Test
    void undeclaredSlotTakesNoSnapshot() {
        final int depth = GLStateManager.pushState(StateSet.CULL);
        try {
            GLStateManager.glDepthFunc(GL11.GL_ALWAYS);
            assertFalse(GLStateManager.ctx().savesState(GLStateManager.getDepthState().stackId()),
                "a CULL bracket does not declare the depth state");
            assertFalse(GLStateManager.getDepthState().cowDepths().claimedAt(depth + 1),
                "an undeclared slot must not snapshot at the bracket depth");
        } finally {
            GLStateManager.popStateTo(depth);
        }

        assertEquals(GL11.GL_ALWAYS, GLStateManager.getDepthState().getFunc(),
            "an undeclared mutation must survive the pop");
        assertEquals(GL11.GL_ALWAYS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
    }

    @Test
    void declaredSlotRestoresAfterRepeatedMutations() {
        final int depth = GLStateManager.pushState(StateSet.MASKS);
        try {
            assertTrue(GLStateManager.ctx().savesState(GLStateManager.getDepthState().stackId()),
                "a MASKS bracket declares the depth state");
            GLStateManager.glDepthFunc(GL11.GL_ALWAYS);
            GLStateManager.glDepthFunc(GL11.GL_NEVER);
            GLStateManager.glDepthFunc(GL11.GL_EQUAL);
            assertTrue(GLStateManager.getDepthState().cowDepths().claimedAt(depth + 1),
                "a declared slot must snapshot at the bracket depth");
        } finally {
            GLStateManager.popStateTo(depth);
        }

        assertEquals(GL11.GL_LESS, GLStateManager.getDepthState().getFunc(),
            "repeated mutations must share the single bracket-entry snapshot");
        assertEquals(GL11.GL_LESS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
    }

    @Test
    void repeatedBracketsNeverSnapshotUndeclaredSlots() {
        for (int i = 0; i < 8; i++) {
            final int depth = GLStateManager.pushState(StateSet.CULL);
            GLStateManager.glDepthFunc(GL11.GL_ALWAYS);
            assertFalse(GLStateManager.getDepthState().cowDepths().claimedAt(depth + 1), "iteration " + i);
            GLStateManager.popStateTo(depth);
            assertEquals(GL11.GL_ALWAYS, GLStateManager.getDepthState().getFunc(), "iteration " + i);
        }
    }

    @Test
    void declaredSlotInsideADeclaringBracketStillRestoresAcrossRepeatedBrackets() {
        for (int i = 0; i < 8; i++) {
            final int depth = GLStateManager.pushState(StateSet.MASKS);
            GLStateManager.glDepthFunc(GL11.GL_ALWAYS);
            GLStateManager.popStateTo(depth);
            assertEquals(GL11.GL_LESS, GLStateManager.getDepthState().getFunc(), "iteration " + i);
            assertEquals(GL11.GL_LESS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC), "iteration " + i);
        }
    }

    @Test
    void outerBracketRestoresThroughNonDeclaringInner() {
        final int outer = GLStateManager.pushState(StateSet.MASKS);
        try {
            GLStateManager.glDepthFunc(GL11.GL_ALWAYS);
            final int inner = GLStateManager.pushState(StateSet.CULL);
            GLStateManager.glDepthFunc(GL11.GL_NEVER);
            GLStateManager.popStateTo(inner);

            assertEquals(GL11.GL_NEVER, GLStateManager.getDepthState().getFunc(),
                "the inner pop must not restore a slot it never declared");
            assertEquals(GL11.GL_NEVER, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        } finally {
            GLStateManager.popStateTo(outer);
        }

        assertEquals(GL11.GL_LESS, GLStateManager.getDepthState().getFunc(),
            "the outer bracket still owns the entry snapshot");
        assertEquals(GL11.GL_LESS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
    }

    @Test
    void retainedDeltaStillReplaysAfterAPruningBracket() {
        final RetainedState retained = new RetainedState();
        final int depth = GLStateManager.pushState(StateSet.MASKS);
        GLStateManager.glDepthFunc(GL11.GL_ALWAYS);
        GLStateManager.retainModifiedState(depth, retained);
        GLStateManager.popStateTo(depth);

        assertEquals(GL11.GL_LESS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));

        GLStateManager.applyRetainedState(retained);
        assertEquals(GL11.GL_ALWAYS, GLStateManager.getDepthState().getFunc());
        assertEquals(GL11.GL_ALWAYS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
    }

    @Test
    void enclosingBracketStillRestoresAnAppliedRetainedDelta() {
        final RetainedState retained = new RetainedState();
        final int captured = GLStateManager.pushState(StateSet.MASKS);
        GLStateManager.glDepthFunc(GL11.GL_ALWAYS);
        GLStateManager.retainModifiedState(captured, retained);
        GLStateManager.popStateTo(captured);

        final int enclosing = GLStateManager.pushState(StateSet.MASKS);
        try {
            GLStateManager.applyRetainedState(retained);
            assertEquals(GL11.GL_ALWAYS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        } finally {
            GLStateManager.popStateTo(enclosing);
        }

        assertEquals(GL11.GL_LESS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
            "the enclosing bracket declares the slot, so the retained delta must be snapshotted");
    }

    @Test
    void unchangedTextureUnitToggleTakesNoSnapshot() {
        final TextureUnitBooleanStateStack unit = GLStateManager.getTextures().getTextureUnitStates(0);
        assertTrue(unit.isEnabled());
        final int depth = GLStateManager.pushState(StateSet.forMask(GL11.GL_ENABLE_BIT));
        try {
            GLStateManager.enableTexture();
            assertFalse(unit.cowDepths().claimedAt(depth + 1),
                "re-enabling an enabled unit must not snapshot a slot the bracket would save");
        } finally {
            GLStateManager.popStateTo(depth);
        }
        assertTrue(unit.isEnabled());
    }

    @Test
    void declaredTextureUnitToggleRestoresOnce() {
        final TextureUnitBooleanStateStack unit = GLStateManager.getTextures().getTextureUnitStates(0);
        final int depth = GLStateManager.pushState(StateSet.forMask(GL11.GL_ENABLE_BIT));
        try {
            GLStateManager.disableTexture();
            assertTrue(unit.cowDepths().claimedAt(depth + 1), "a changed toggle must snapshot");
            GLStateManager.disableTexture();
            assertTrue(unit.cowDepths().claimedAt(depth + 1), "the second toggle reuses the snapshot");
        } finally {
            GLStateManager.popStateTo(depth);
        }

        assertTrue(unit.isEnabled(), "the bracket restores the entry value");
    }

    @Test
    void undeclaredTextureUnitToggleSurvivesPop() {
        final TextureUnitBooleanStateStack unit = GLStateManager.getTextures().getTextureUnitStates(0);
        final int depth = GLStateManager.pushState(StateSet.CULL);
        try {
            GLStateManager.disableTexture();
            assertFalse(unit.cowDepths().claimedAt(depth + 1),
                "a CULL bracket does not declare texture unit state");
        } finally {
            GLStateManager.popStateTo(depth);
        }

        assertFalse(unit.isEnabled(), "an undeclared toggle must survive the pop");
    }
}
