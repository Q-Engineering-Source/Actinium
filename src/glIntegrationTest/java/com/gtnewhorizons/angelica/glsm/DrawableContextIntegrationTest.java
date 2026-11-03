package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;

class DrawableContextIntegrationTest extends GLStateManagerIntegrationFixture {
    private static final Object DISPLAY_TOKEN = new Object();

    @BeforeEach
    void bindDisplay() {
        WindowBackends.useSdl();
        activateContext(DISPLAY_TOKEN, context, true);
        resetMainState();
    }

    @Test
    void drawableSwitchRestoresItsOwnCacheAndDriverState() {
        final long drawable = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, drawable);
        final Object drawableToken = new Object();
        try {
            GLStateManager.disableBlend();
            GLSMDepthStencilAlpha.glDepthMask(true);
            final GLContextState mainState = GLStateManager.ctx();

            activateContext(drawableToken, drawable, false);
            final GLContextState drawableState = GLStateManager.ctx();
            assertNotSame(mainState, drawableState);
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertTrue(GLStateManager.getDepthState().isMaskEnabled());

            GLStateManager.enableBlend();
            GLStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GLSMDepthStencilAlpha.glDepthMask(false);
            assertBlendAndDepthState(true, GL11.GL_SRC_ALPHA, false);

            activateContext(DISPLAY_TOKEN, context, true);
            assertSame(mainState, GLStateManager.ctx());
            assertBlendAndDepthState(false, GL11.GL_ONE, true);

            activateContext(drawableToken, drawable, false);
            assertSame(drawableState, GLStateManager.ctx());
            assertBlendAndDepthState(true, GL11.GL_SRC_ALPHA, false);

            final long start = System.nanoTime();
            for (int i = 0; i < 1_000_000; i++) {
                assertSame(drawableState, GLStateManager.ctx());
            }
            System.out.printf("drawable ctx() 1000000 lookups: %d ns%n", System.nanoTime() - start);

            releaseNativeContext();
            GLStateManager.retireDrawableContext(drawableToken);
            activateContext(drawableToken, drawable, false);
            assertNotSame(drawableState, GLStateManager.ctx());
        } finally {
            releaseNativeContext();
            assertTrue(SDL_GL_DestroyContext(drawable));
            activateContext(DISPLAY_TOKEN, context, true);
            resetMainState();
        }
    }

    @Test
    void firstDrawableBindKeepsTheNativeWindowViewport() {
        final long drawable = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, drawable);
        final Object drawableToken = new Object();
        try {
            assertTrue(SDL_GL_MakeCurrent(window, drawable));
            GL.createCapabilities();
            final IntBuffer viewport = BufferUtils.createIntBuffer(4);
            GL11C.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            assertEquals(WINDOW_WIDTH, viewport.get(2));
            assertEquals(WINDOW_HEIGHT, viewport.get(3));

            GLStateManager.bindDrawableContext(drawableToken, drawable);

            viewport.clear();
            GL11C.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            assertEquals(0, viewport.get(0));
            assertEquals(0, viewport.get(1));
            assertEquals(WINDOW_WIDTH, viewport.get(2));
            assertEquals(WINDOW_HEIGHT, viewport.get(3));
        } finally {
            releaseNativeContext();
            assertTrue(SDL_GL_DestroyContext(drawable));
            activateContext(DISPLAY_TOKEN, context, true);
            resetMainState();
        }
    }

    @Test
    void workerDrawableDoesNotMutateMainCacheOrDriverState() throws Exception {
        final long workerContext = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, workerContext);
        final Object workerToken = new Object();
        final GLContextState mainState = GLStateManager.ctx();
        final CountDownLatch workerModified = new CountDownLatch(1);
        final CountDownLatch allowWorkerExit = new CountDownLatch(1);
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        releaseNativeContext();
        try {
            final Future<?> worker = pool.submit(() -> {
                try {
                    activateContext(workerToken, workerContext, false);
                    assertNotSame(mainState, GLStateManager.ctx());
                    GLStateManager.enableBlend();
                    GLStateManager.tryBlendFuncSeparate(
                        GL11.GL_DST_COLOR, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
                    GLSMDepthStencilAlpha.glDepthMask(false);
                    assertBlendAndDepthState(true, GL11.GL_DST_COLOR, false);
                    workerModified.countDown();
                    if (!allowWorkerExit.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release the worker context");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally {
                    releaseNativeContext();
                }
            });

            final boolean modified = workerModified.await(10, TimeUnit.SECONDS);
            if (!modified) {
                allowWorkerExit.countDown();
                worker.get(10, TimeUnit.SECONDS);
            }
            assertTrue(modified);
            allowWorkerExit.countDown();
            worker.get(15, TimeUnit.SECONDS);
            activateContext(DISPLAY_TOKEN, context, true);
            assertSame(mainState, GLStateManager.ctx());
            assertBlendAndDepthState(false, GL11.GL_ONE, true);
        } finally {
            allowWorkerExit.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(SDL_GL_DestroyContext(workerContext));
            activateContext(DISPLAY_TOKEN, context, true);
            resetMainState();
        }
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

    private static void resetMainState() {
        GLStateManager.disableBlend();
        GLStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
        GLSMDepthStencilAlpha.glDepthMask(true);
    }

    private static void assertBlendAndDepthState(boolean blend, int sourceRgb, boolean depthMask) {
        assertEquals(blend, GLStateManager.getBlendMode().isEnabled());
        assertEquals(sourceRgb, GLStateManager.getBlendState().getSrcRgb());
        assertEquals(depthMask, GLStateManager.getDepthState().isMaskEnabled());
        assertEquals(blend, GL11.glIsEnabled(GL11.GL_BLEND));
        assertEquals(sourceRgb, GL14.glGetInteger(GL14.GL_BLEND_SRC_RGB));
        assertEquals(depthMask, GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
    }
}
