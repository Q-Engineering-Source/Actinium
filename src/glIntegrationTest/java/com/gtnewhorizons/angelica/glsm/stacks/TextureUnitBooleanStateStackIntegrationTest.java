package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;

class TextureUnitBooleanStateStackIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void unitSwitchRestoresTargetStateAndOriginalActiveUnit() {
        assumeTrue(GLStateManager.MAX_TEXTURE_UNITS > 1);
        TextureUnitBooleanStateStack stack = new TextureUnitBooleanStateStack(GL11.GL_TEXTURE_2D, 1, false, false);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        assertFalse(driverTextureEnabled(1));
        stack.push();
        try {
            stack.setEnabled(true);
            assertTrue(stack.isEnabled());
            assertTrue(driverTextureEnabled(1));
            assertEquals(0, GLStateManager.getActiveTextureUnit());
            assertEquals(GL13.GL_TEXTURE0, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));

            stack.pop();
            assertFalse(stack.isEnabled());
            assertFalse(driverTextureEnabled(1));
            assertEquals(0, GLStateManager.getActiveTextureUnit());
            assertEquals(GL13.GL_TEXTURE0, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
        } finally {
            stack.setEnabled(false);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }

    private static boolean driverTextureEnabled(int unit) {
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        boolean enabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(previousUnit);
        return enabled;
    }
}
