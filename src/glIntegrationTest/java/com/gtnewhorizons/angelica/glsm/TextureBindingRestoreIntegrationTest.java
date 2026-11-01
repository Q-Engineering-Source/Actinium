package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.dsa.DSAARB;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TextureBindingRestoreIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void glBindTextureUnitRestoresCacheAndDriver() {
        assumeTextureUnitDsa();
        int textureA = createInitializedTexture();
        int textureB = createInitializedTexture();
        int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.glBindTextureUnit(1, textureA);
            GLStateManager.glPushAttrib(GL11.GL_TEXTURE_BIT);
            GLStateManager.glBindTextureUnit(1, textureB);
            assertEquals(textureB, driverTextureBinding(1));

            GLStateManager.glPopAttrib();
            assertEquals(textureA, GLStateManager.getTextures().getTextureUnitBindings(1).getBinding());
            assertEquals(textureA, driverTextureBinding(1));
        } finally {
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.glPopAttrib();
            }
            GLStateManager.glBindTextureUnit(1, 0);
            GLStateManager.glDeleteTextures(textureA);
            GLStateManager.glDeleteTextures(textureB);
        }
    }

    @Test
    void dsaArbBindTextureToUnitRestoresCacheAndDriver() {
        assumeTextureUnitDsa();
        DSAARB dsa = new DSAARB();
        int textureA = createInitializedTexture();
        int textureB = createInitializedTexture();
        int entryDepth = GLStateManager.getAttribDepth();
        try {
            dsa.bindTextureToUnit(1, textureA);
            GLStateManager.glPushAttrib(GL11.GL_TEXTURE_BIT);
            dsa.bindTextureToUnit(1, textureB);
            assertEquals(textureB, driverTextureBinding(1));

            GLStateManager.glPopAttrib();
            assertEquals(textureA, GLStateManager.getTextures().getTextureUnitBindings(1).getBinding());
            assertEquals(textureA, driverTextureBinding(1));
        } finally {
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.glPopAttrib();
            }
            dsa.bindTextureToUnit(1, 0);
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
            GLStateManager.glDeleteTextures(textureA);
            GLStateManager.glDeleteTextures(textureB);
        }
    }

    private static int createInitializedTexture() {
        int texture = GLStateManager.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    private static void assumeTextureUnitDsa() {
        assumeTrue(GLStateManager.MAX_TEXTURE_UNITS > 1);
        assumeTrue(GL.getCapabilities().OpenGL45);
    }

    private static int driverTextureBinding(int unit) {
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(previousUnit);
        return binding;
    }
}
