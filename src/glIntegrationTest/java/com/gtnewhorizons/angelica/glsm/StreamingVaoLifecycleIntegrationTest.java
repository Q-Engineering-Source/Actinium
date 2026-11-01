package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;

class StreamingVaoLifecycleIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void splashContextCleanupDoesNotDeleteReplacementVaoName() {
        WindowBackends.useSdl();
        final Object initialDisplayToken = new Object();
        final Object replacementDisplayToken = new Object();
        bindDisplay(initialDisplayToken, context);
        GLStateManager.recreateDefaultVertexArray();
        final int oldVaoName = GLStateManager.glGenVertexArrays();
        GLStateManager.getStreamingVaos().orphanVAOs[0] = oldVaoName;

        final long replacement = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, replacement);
        try {
            bindDisplay(replacementDisplayToken, replacement);
            assertTrue(GLStateManager.displayContextMigrated());
            GL30.glGenVertexArrays();
            final int replacementVaoName = GL30.glGenVertexArrays();
            assertEquals(oldVaoName, replacementVaoName);
            GL30.glBindVertexArray(replacementVaoName);
            assertTrue(GL30.glIsVertexArray(replacementVaoName));

            TessellatorStreamingDrawer.destroy();

            assertEquals(0, GLStateManager.getStreamingVaos().orphanVAOs[0]);
            assertTrue(GL30.glIsVertexArray(replacementVaoName));
        } finally {
            assertTrue(SDL_GL_MakeCurrent(window, MemoryUtil.NULL));
            GL.setCapabilities(null);
            GLStateManager.releaseBoundContext();
            assertTrue(SDL_GL_DestroyContext(replacement));
            bindDisplay(initialDisplayToken, context);
            if (GL30.glIsVertexArray(oldVaoName)) GL30.glDeleteVertexArrays(oldVaoName);
        }
    }

    @Test
    void explicitDestroyDeletesLiveStreamingVao() {
        WindowBackends.useSdl();
        bindDisplay(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        final int vaoName = GLStateManager.glGenVertexArrays();
        GLStateManager.getStreamingVaos().orphanVAOs[0] = vaoName;

        TessellatorStreamingDrawer.destroy();

        assertFalse(GL30.glIsVertexArray(vaoName));
    }

    private static void bindDisplay(Object token, long nativeHandle) {
        assertTrue(SDL_GL_MakeCurrent(window, nativeHandle));
        GL.createCapabilities();
        GLStateManager.bindDisplayContext(token, nativeHandle);
    }
}
