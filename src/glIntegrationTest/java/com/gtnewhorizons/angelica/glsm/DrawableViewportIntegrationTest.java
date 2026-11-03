package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;

class DrawableViewportIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void firstDrawableViewportInitDoesNotJoinPreviousContextAttribScope() {
        WindowBackends.useSdl();
        final Object displayToken = new Object();
        final Object drawableToken = new Object();
        activateContext(displayToken, context, true);
        GLSMRasterizer.glViewport(1, 2, 3, 4);
        GLSMAttribStack.glPushAttrib(GL11.GL_VIEWPORT_BIT);
        GLSMRasterizer.glViewport(5, 6, 7, 8);

        final long drawable = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, drawable);
        try {
            activateContext(drawableToken, drawable, false);
            assertViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);

            GLSMAttribStack.glPushAttrib(GL11.GL_VIEWPORT_BIT);
            GLSMRasterizer.glViewport(10, 20, 30, 40);
            GLSMAttribStack.glPopAttrib();
            assertViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);

            activateContext(displayToken, context, true);
            GLSMAttribStack.glPopAttrib();
            assertViewport(1, 2, 3, 4);
        } finally {
            releaseNativeContext();
            assertTrue(SDL_GL_DestroyContext(drawable));
            activateContext(displayToken, context, true);
            while (GLStateManager.getAttribDepth() > 0) GLSMAttribStack.glPopAttrib();
            GLSMRasterizer.glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
        }
    }

    private static void assertViewport(int x, int y, int width, int height) {
        assertEquals(x, GLStateManager.getViewportState().x);
        assertEquals(y, GLStateManager.getViewportState().y);
        assertEquals(width, GLStateManager.getViewportState().width);
        assertEquals(height, GLStateManager.getViewportState().height);
        final IntBuffer viewport = BufferUtils.createIntBuffer(4);
        GL11C.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        assertEquals(x, viewport.get(0));
        assertEquals(y, viewport.get(1));
        assertEquals(width, viewport.get(2));
        assertEquals(height, viewport.get(3));
    }

    private static void activateContext(Object token, long nativeHandle, boolean display) {
        assertTrue(SDL_GL_MakeCurrent(window, nativeHandle));
        GL.createCapabilities();
        if (display) {
            GLStateManager.bindDisplayContext(token, nativeHandle);
        } else {
            GLStateManager.bindDrawableContext(token, nativeHandle);
        }
        assertEquals(nativeHandle, SDL_GL_GetCurrentContext());
    }

    private static void releaseNativeContext() {
        if (SDL_GL_GetCurrentContext() != MemoryUtil.NULL) {
            assertTrue(SDL_GL_MakeCurrent(window, MemoryUtil.NULL));
        }
        GL.setCapabilities(null);
        GLStateManager.releaseBoundContext();
    }
}
