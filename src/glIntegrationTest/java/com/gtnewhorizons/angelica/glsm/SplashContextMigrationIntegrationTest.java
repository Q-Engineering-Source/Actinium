package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;

class SplashContextMigrationIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void splashMigrationReusesMainCacheAndRecreatesDefaultVertexArray() {
        WindowBackends.useSdl();
        final Object initialDisplayToken = new Object();
        final Object replacementDisplayToken = new Object();
        bindDisplay(initialDisplayToken, context);
        GLStateManager.recreateDefaultVertexArray();
        GLStateManager.enableBlend();
        GLStateManager.glDepthMask(false);
        final GLContextState mainState = GLStateManager.ctx();
        assertTrue(GL30.glIsVertexArray(GLStateManager.getDefaultVAO()));

        final long replacement = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, replacement);
        try {
            bindDisplay(replacementDisplayToken, replacement);
            assertSame(mainState, GLStateManager.ctx());
            assertTrue(GLStateManager.displayContextMigrated());

            GLStateManager.recreateDefaultVertexArray();
            assertFalse(GLStateManager.displayContextMigrated());
            assertTrue(GL30.glIsVertexArray(GLStateManager.getDefaultVAO()));
            assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
            assertFalse(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            assertEquals(
                GLStateManager.getDefaultVAO(), GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING));

            GLStateManager.markSplashComplete("nativeIntegration");
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDepthMask(true);
            BackendManager.RENDER_BACKEND.onFrameBegin();
            assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
            assertFalse(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            assertFalse(GLStateManager.takeStateSeedPending());
        } finally {
            assertTrue(SDL_GL_MakeCurrent(window, MemoryUtil.NULL));
            GL.setCapabilities(null);
            GLStateManager.releaseBoundContext();
            assertTrue(SDL_GL_DestroyContext(replacement));
            bindDisplay(initialDisplayToken, context);
        }
    }

    @Test
    void splashMigrationRecreatesDefaultVaoStateAndFfpPrograms() {
        WindowBackends.useSdl();
        final Object initialDisplayToken = new Object();
        final Object replacementDisplayToken = new Object();
        bindDisplay(initialDisplayToken, context);
        GLStateManager.recreateDefaultVertexArray();
        ShaderManager.getInstance().enable();
        final int initialEbo = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, initialEbo);
        GLStateManager.glEnableVertexAttribArray(0);
        drawTriangle();

        final long replacement = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, replacement);
        try {
            bindDisplay(replacementDisplayToken, replacement);
            assertTrue(GLStateManager.displayContextMigrated());
            GLStateManager.recreateDefaultVertexArray();

            assertEquals(0, GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));
            assertEquals(GL11.GL_FALSE, GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));
            drawTriangle();
            assertNotEquals(0, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        } finally {
            assertTrue(SDL_GL_MakeCurrent(window, MemoryUtil.NULL));
            GL.setCapabilities(null);
            GLStateManager.releaseBoundContext();
            assertTrue(SDL_GL_DestroyContext(replacement));
            bindDisplay(initialDisplayToken, context);
            GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GLStateManager.glDeleteBuffers(initialEbo);
        }
    }

    private static void drawTriangle() {
        final int vbo = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final FloatBuffer vertices = BufferUtils.createFloatBuffer(9);
        vertices.put(new float[] { 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f }).flip();
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        GLStateManager.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        GLStateManager.glEnableVertexAttribArray(0);
        ShaderManager.getInstance().setCurrentVertexFlags(0);
        GLStateManager.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.glDeleteBuffers(vbo);
    }

    private static void bindDisplay(Object token, long nativeHandle) {
        assertTrue(SDL_GL_MakeCurrent(window, nativeHandle));
        GL.createCapabilities();
        GLStateManager.bindDisplayContext(token, nativeHandle);
    }
}
