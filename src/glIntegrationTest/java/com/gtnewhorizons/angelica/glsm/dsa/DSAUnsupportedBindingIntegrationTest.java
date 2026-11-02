package com.gtnewhorizons.angelica.glsm.dsa;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;

class DSAUnsupportedBindingIntegrationTest extends GLStateManagerIntegrationFixture {
    private static final Object DISPLAY_TOKEN = new Object();

    private final DSAUnsupported dsa = new DSAUnsupported();

    @BeforeEach
    void bindDisplayContext() {
        WindowBackends.useSdl();
        assertTrue(SDL_GL_MakeCurrent(window, context));
        GL.createCapabilities();
        GLStateManager.bindDisplayContext(DISPLAY_TOKEN, context);
    }

    @AfterEach
    void restoreDefaultBindings() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_1D, 0);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
    }

    @Test
    void nonTwoDParameterEditsRestoreTheTargetBinding() {
        final int twoD = GLStateManager.glGenTextures();
        final int cube = GL11.glGenTextures();
        final int otherCube = GL11.glGenTextures();
        try {
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, twoD);
            for (int texture : new int[] { cube, otherCube }) {
                GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, texture);
                for (int face = 0; face < 6; face++) {
                    GL11.glTexImage2D(
                        GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                }
            }
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());

            dsa.texParameteri(cube, GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            assertEquals(
                GL11.GL_NEAREST, dsa.getTexParameteri(cube, GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_MIN_FILTER));
            assertEquals(otherCube, GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP));
            assertEquals(twoD, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, 0);
            GL11.glDeleteTextures(cube);
            GL11.glDeleteTextures(otherCube);
            GLStateManager.glDeleteTextures(twoD);
        }
    }

    @Test
    void twoDParameterEditRestoresTheCachedBinding() {
        final int bound = GLStateManager.glGenTextures();
        final int edited = GLStateManager.glGenTextures();
        try {
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            dsa.texParameteri(edited, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            assertEquals(bound, GLStateManager.getBoundTextureForServerState());
            assertEquals(bound, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, edited);
            assertEquals(GL11.GL_NEAREST, GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
        } finally {
            GLStateManager.glDeleteTextures(edited);
            GLStateManager.glDeleteTextures(bound);
        }
    }

    @Test
    void twoDEditWithANonTwoDTextureBoundRestoresTheDriverTwoDBinding() {
        assumeTrue(GLStateManager.MAX_TEXTURE_UNITS > 3);
        final int bound = GLStateManager.glGenTextures();
        final int edited = GLStateManager.glGenTextures();
        final int volume = GL11.glGenTextures();
        try {
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE3);
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, edited);
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            GLStateManager.glBindTexture(GL12.GL_TEXTURE_3D, volume);
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // Drain errors raised while staging the initial binding layout.
            }

            dsa.texParameteri(edited, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            assertEquals(bound, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
            assertEquals(volume, GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D));
            assertEquals(bound, GLStateManager.getBoundTextureForServerState());

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, edited);
            assertEquals(GL11.GL_NEAREST, GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
        } finally {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
            GLStateManager.glDeleteTextures(bound);
            GLStateManager.glDeleteTextures(edited);
            GL11.glDeleteTextures(volume);
        }
    }
}
