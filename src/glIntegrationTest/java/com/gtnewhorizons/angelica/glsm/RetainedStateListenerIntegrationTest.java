package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.stacks.RetainedState;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RetainedStateListenerIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void applyingRetainedStatePreservesAlphaBlendAndTextureListeners() {
        assumeTrue(GLStateManager.MAX_TEXTURE_UNITS > 1);
        final int[] alphaEvents = {0};
        final int[] blendEvents = {0};
        final int[] textureEvents = {0};
        GLSMHooks.ALPHA_STATE_CHANGE.addListener(event -> alphaEvents[0]++);
        GLSMHooks.BLEND_FUNC_CHANGE.addListener(event -> blendEvents[0]++);
        GLSMHooks.TEXTURE_UNIT_STATE.addListener(event -> textureEvents[0]++);

        GLSMDepthStencilAlpha.glAlphaFunc(GL11.GL_GREATER, 0.1F);
        GLStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
        GLStateManager.disableTexture();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

        final int depth = GLStateManager.pushState(StateSet.forMask(GL11.GL_ALL_ATTRIB_BITS));
        GLSMDepthStencilAlpha.glAlphaFunc(GL11.GL_GEQUAL, 0.5F);
        GLStateManager.tryBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ONE);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
        GLStateManager.enableTexture();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        final RetainedState retained = new RetainedState();
        GLStateManager.retainModifiedState(depth, retained);
        GLStateManager.popStateTo(depth);

        final int alphaBeforeApply = alphaEvents[0];
        final int blendBeforeApply = blendEvents[0];
        final int textureBeforeApply = textureEvents[0];
        GLStateManager.applyRetainedState(retained);

        assertEquals(alphaBeforeApply + 1, alphaEvents[0]);
        assertEquals(blendBeforeApply + 1, blendEvents[0]);
        assertEquals(textureBeforeApply + 1, textureEvents[0]);
        assertEquals(GL11.GL_GEQUAL, GLSMQueries.glGetInteger(GL11.GL_ALPHA_TEST_FUNC));
        assertTrue(GLStateManager.getTextures().getTextureUnitStates(1).isEnabled());
    }
}
