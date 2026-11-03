package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;

class ScissorStateIntegrationTest extends GLStateManagerIntegrationFixture {
    private static final Object DISPLAY_TOKEN = new Object();

    @BeforeEach
    void resetScissor() {
        GLStateManager.disableScissorTest();
        GLSMRasterizer.glScissor(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
    }

    @Test
    void scissorBitRestoresBoxAndEnableBitOnlyRestoresEnable() {
        final int[] initial = {10, 20, 30, 40};
        final int[] changed = {5, 6, 7, 8};
        GLSMRasterizer.glScissor(initial[0], initial[1], initial[2], initial[3]);
        GLSMAttribStack.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GLStateManager.enableScissorTest();
        GLSMRasterizer.glScissor(changed[0], changed[1], changed[2], changed[3]);
        GLSMAttribStack.glPopAttrib();

        assertScissorBox(initial);
        assertFalse(GLSMEnable.glIsEnabled(GL11.GL_SCISSOR_TEST));
        assertFalse(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));

        GLSMAttribStack.glPushAttrib(GL11.GL_ENABLE_BIT);
        GLStateManager.enableScissorTest();
        GLSMRasterizer.glScissor(changed[0], changed[1], changed[2], changed[3]);
        GLSMAttribStack.glPopAttrib();

        assertScissorBox(changed);
        assertFalse(GLSMEnable.glIsEnabled(GL11.GL_SCISSOR_TEST));
        assertFalse(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
    }

    @Test
    void drawableContextsKeepIndependentScissorBoxes() {
        WindowBackends.useSdl();
        activateContext(DISPLAY_TOKEN, context, true);
        final int[] mainBox = {1, 2, 30, 40};
        final int[] drawableBox = {3, 4, 20, 50};
        GLSMRasterizer.glScissor(mainBox[0], mainBox[1], mainBox[2], mainBox[3]);

        final long drawable = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, drawable);
        final Object drawableToken = new Object();
        try {
            activateContext(drawableToken, drawable, false);
            GLSMRasterizer.glScissor(drawableBox[0], drawableBox[1], drawableBox[2], drawableBox[3]);
            assertScissorBox(drawableBox);

            activateContext(DISPLAY_TOKEN, context, true);
            assertScissorBox(mainBox);

            activateContext(drawableToken, drawable, false);
            assertScissorBox(drawableBox);
        } finally {
            if (MemoryUtil.NULL != drawable) {
                activateContext(DISPLAY_TOKEN, context, true);
                GLStateManager.retireDrawableContext(drawableToken);
                assertTrue(SDL_GL_DestroyContext(drawable));
            }
        }
    }

    private static void assertScissorBox(int[] expected) {
        final int[] cached = new int[4];
        final int[] driver = new int[4];
        GLSMQueries.glGetInteger(GL11.GL_SCISSOR_BOX, cached);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, driver);
        assertArrayEquals(expected, cached);
        assertArrayEquals(expected, driver);
    }

    private static void activateContext(Object token, long nativeHandle, boolean display) {
        assertTrue(SDL_GL_MakeCurrent(window, nativeHandle));
        GL.createCapabilities();
        if (display) {
            GLStateManager.bindDisplayContext(token, nativeHandle);
        } else {
            GLStateManager.bindDrawableContext(token, nativeHandle);
        }
    }
}
