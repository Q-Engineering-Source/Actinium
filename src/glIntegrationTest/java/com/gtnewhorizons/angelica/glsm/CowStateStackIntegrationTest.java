package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.stacks.IntegerStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.TextureUnitBooleanStateStack;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CowStateStackIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void directPushPopRestoresNestedValues() {
        IntegerStateStack stack = new IntegerStateStack(7, -1);
        stack.push();
        stack.setValue(11);
        stack.push();
        stack.setValue(13);

        stack.pop();
        assertEquals(11, stack.getValue());
        stack.pop();
        assertEquals(7, stack.getValue());
        assertTrue(stack.isEmpty());
    }

    @Test
    void nestedMemberScopesRestoreValuesAtEachDepth() {
        int entryDepth = GLStateManager.getAttribDepth();
        GLSMDepthStencilAlpha.glDepthFunc(GL11.GL_LESS);
        try {
            GLSMAttribStack.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT);
            GLSMDepthStencilAlpha.glDepthFunc(GL11.GL_ALWAYS);
            GLSMAttribStack.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT);
            GLSMDepthStencilAlpha.glDepthFunc(GL11.GL_NEVER);

            GLSMAttribStack.glPopAttrib();
            assertEquals(GL11.GL_ALWAYS, GLStateManager.getDepthState().getFunc());
            assertEquals(GL11.GL_ALWAYS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
            GLSMAttribStack.glPopAttrib();
            assertEquals(GL11.GL_LESS, GLStateManager.getDepthState().getFunc());
            assertEquals(GL11.GL_LESS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        } finally {
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLSMAttribStack.glPopAttrib();
            }
            GLSMDepthStencilAlpha.glDepthFunc(GL11.GL_LESS);
        }
    }

    @Test
    void nonMemberChangeSurvivesNestedAttribPop() {
        int entryDepth = GLStateManager.getAttribDepth();
        GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        try {
            GLSMAttribStack.glPushAttrib(GL11.GL_ENABLE_BIT);
            GLSMEnable.glEnable(GL11.GL_CULL_FACE);
            GLSMAttribStack.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT);
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);

            GLSMAttribStack.glPopAttrib();
            assertFalse(GLStateManager.getCullState().isEnabled());

            GLSMAttribStack.glPopAttrib();
            assertFalse(GLStateManager.getCullState().isEnabled());
        } finally {
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLSMAttribStack.glPopAttrib();
            }
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        }
    }

    @Test
    void textureUnitRestoreKeepsOriginalUnitActive() {
        assumeTrue(GLStateManager.MAX_TEXTURE_UNITS > 1);
        int entryDepth = GLStateManager.getAttribDepth();
        TextureUnitBooleanStateStack unit1 = GLStateManager.getTextures().getTextureUnitStates(1);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
        GLStateManager.disableTexture();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        try {
            unit1.push();
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            GLStateManager.enableTexture();
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            unit1.pop();
            assertFalse(unit1.isEnabled());
            assertEquals(0, GLStateManager.getActiveTextureUnit());
            assertEquals(GL13.GL_TEXTURE0, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));

            GLSMAttribStack.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_TEXTURE_BIT);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            GLStateManager.enableTexture();
            GLSMAttribStack.glPopAttrib();
            assertFalse(unit1.isEnabled());
            assertEquals(0, GLStateManager.getActiveTextureUnit());
        } finally {
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLSMAttribStack.glPopAttrib();
            }
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            GLStateManager.disableTexture();
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }
}
