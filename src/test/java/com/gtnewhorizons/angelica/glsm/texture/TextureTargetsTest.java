package com.gtnewhorizons.angelica.glsm.texture;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the texture-target classification the DSA fallback paths rely on: a target without a
 * {@code GL_TEXTURE_BINDING_*} of its own must resolve to the 2D binding, and proxy targets must be
 * recognised so callers do not query a binding for a target that never had one.
 */
class TextureTargetsTest {

    @Test
    void bindingQueryMapsEachTargetToItsBindingPname() {
        assertEquals(GL11.GL_TEXTURE_BINDING_2D, TextureTargets.bindingQuery(GL11.GL_TEXTURE_2D));
        assertEquals(GL12.GL_TEXTURE_BINDING_3D, TextureTargets.bindingQuery(GL12.GL_TEXTURE_3D));
        assertEquals(GL13.GL_TEXTURE_BINDING_CUBE_MAP, TextureTargets.bindingQuery(GL13.GL_TEXTURE_CUBE_MAP));
        assertEquals(GL30.GL_TEXTURE_BINDING_2D_ARRAY, TextureTargets.bindingQuery(GL30.GL_TEXTURE_2D_ARRAY));
    }

    @Test
    void targetWithoutItsOwnBindingQueryFallsBackToThe2DBinding() {
        assertEquals(GL11.GL_TEXTURE_BINDING_2D, TextureTargets.bindingQuery(GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X));
    }

    @Test
    void proxyTargetIsDetected() {
        assertTrue(TextureTargets.isProxyTarget(GL11.GL_PROXY_TEXTURE_2D));
    }

    @Test
    void realTextureTargetIsNotAProxyTarget() {
        assertFalse(TextureTargets.isProxyTarget(GL11.GL_TEXTURE_2D));
    }
}
