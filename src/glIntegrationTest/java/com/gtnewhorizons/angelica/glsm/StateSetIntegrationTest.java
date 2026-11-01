package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateSetIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void fontSetsContainColorMaskAndPolygonMembers() {
        for (StateSet set : List.of(StateSet.FONT, StateSet.FONT_PIPELINE)) {
            assertTrue(set.contains(GLStateManager.getColorMask().stackId()));
            assertTrue(set.contains(GLStateManager.getCullState().stackId()));
            assertTrue(set.contains(GLStateManager.getPolygonState().stackId()));
            assertTrue(set.contains(GLStateManager.getPolygonOffsetFillState().stackId()));
            assertTrue((set.restore & StateSet.R_COLOR_MASK) != 0);
            assertTrue((set.restore & StateSet.R_POLYGON) != 0);
        }
    }

    @Test
    void attribMasksMapToCowMemberIds() {
        StateSet eval = StateSet.forMask(GL11.GL_EVAL_BIT);
        assertTrue(eval.contains(GLStateManager.getAutoNormalState().stackId()));
        assertTrue(eval.contains(GLStateManager.getMap1Color4State().stackId()));
        assertTrue(eval.contains(GLStateManager.getMap2Vertex4State().stackId()));

        StateSet multisample = StateSet.forMask(GL13.GL_MULTISAMPLE_BIT);
        assertTrue(multisample.contains(GLStateManager.getMultisampleState().stackId()));
        assertTrue(multisample.contains(GLStateManager.getSampleAlphaToCoverageState().stackId()));
        assertTrue(multisample.contains(GLStateManager.getSampleCoverageState().stackId()));

        StateSet transform = StateSet.forMask(GL11.GL_TRANSFORM_BIT);
        assertTrue(transform.contains(GLStateManager.getClipPlaneStates()[0].stackId()));
        assertTrue(transform.contains(GLStateManager.getNormalizeState().stackId()));

        StateSet texture = StateSet.forMask(GL11.GL_TEXTURE_BIT);
        assertTrue(texture.contains(GLStateManager.getActiveTextureUnitStack().stackId()));
        assertTrue(texture.contains(GLStateManager.getTextures().getTextureUnitBindings(0).stackId()));
        assertTrue(texture.contains(GLStateManager.getTextures().getTexEnvState(0).stackId()));
    }

    @Test
    void stateSetPopRestoresMembersAndCarriesNonMemberChangesOutward() {
        int entryDepth = GLStateManager.getAttribDepth();
        GLStateManager.disableBlend();
        GLStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
        GLStateManager.glDisable(GL11.GL_CULL_FACE);
        try {
            int outer = GLStateManager.pushState(StateSet.BLEND);
            GLStateManager.enableBlend();
            GLStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

            int inner = GLStateManager.pushState(StateSet.CULL);
            GLStateManager.tryBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_ZERO, GL11.GL_DST_COLOR, GL11.GL_ZERO);
            GLStateManager.glEnable(GL11.GL_CULL_FACE);
            GLStateManager.popStateTo(inner);

            assertFalse(GLStateManager.getCullState().isEnabled());
            assertEquals(GL11.GL_DST_COLOR, GLStateManager.getBlendState().getSrcRgb());
            assertEquals(GL11.GL_DST_COLOR, GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB));

            GLStateManager.popStateTo(outer);
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertEquals(GL11.GL_ONE, GLStateManager.getBlendState().getSrcRgb());
            assertEquals(GL11.GL_ZERO, GLStateManager.getBlendState().getDstRgb());
            assertEquals(GL11.GL_ONE, GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB));
            assertEquals(GL11.GL_ZERO, GL11.glGetInteger(GL14.GL_BLEND_DST_RGB));
        } finally {
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLStateManager.disableBlend();
            GLStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
            GLStateManager.glDisable(GL11.GL_CULL_FACE);
        }
    }
}
