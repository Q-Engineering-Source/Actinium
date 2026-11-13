package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import top.outlands.foundation.boot.MainThreadExecutor;

import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_Quit;
import static org.lwjgl.sdl.SDLVideo.SDL_CreateWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL;

public abstract class GLStateManagerIntegrationFixture {
    protected static final int WINDOW_WIDTH = 64;
    protected static final int WINDOW_HEIGHT = 64;

    protected static long window;
    protected static long context;

    /**
     * Installs the host config the streaming upload paths read. Subclasses outside this package cannot reach
     * {@code GLStateManager.applyInitConfig}, which stays package-private.
     */
    protected static void installDefaultGlsmConfig() {
        GLStateManager.applyInitConfig(GLSMInitConfig.builder().build());
    }

    private static boolean formatRoutingRegistered;
    private static boolean routeFormats;

    /**
     * Routes {@code VertexFormat.setupBufferState} through GLSM generic attribute pointers. The production
     * redirector rewrites those calls; the harness has no redirector, so the compat client-array entry points
     * would reach the core-profile driver and silently leave the attribute unset.
     */
    protected static void routeVertexFormats(boolean enabled) {
        routeFormats = enabled;
        if (formatRoutingRegistered) return;
        formatRoutingRegistered = true;
        VertexFormat.registerSetupBufferStateOverride((format, offset) -> {
            if (!routeFormats) return false;
            final int stride = format.getVertexSize();
            int elementOffset = (int) offset;
            for (VertexFormatElement element : format.getElements()) {
                final VertexFormatElement.Usage usage = element.getUsage();
                final boolean normalized = usage == VertexFormatElement.Usage.COLOR
                    || usage == VertexFormatElement.Usage.NORMAL;
                GLSMVertexInput.glEnableVertexAttribArray(usage.getAttributeLocation());
                GLSMVertexInput.glVertexAttribPointer(usage.getAttributeLocation(), element.getCount(),
                    element.getType().getGlType(), normalized, stride, elementOffset);
                elementOffset += element.getByteSize();
            }
            return true;
        });
    }

    @BeforeAll
    static void createGlContext() {
        MainThreadExecutor.init();
        WindowBackends.useSdl();
        assertTrue(SDL_Init(SDL_INIT_VIDEO), () -> sdlError("SDL_Init"));
        window = SDL_CreateWindow(
            "Actinium GLSM integration", WINDOW_WIDTH, WINDOW_HEIGHT, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
        assertNotEquals(MemoryUtil.NULL, window, () -> sdlError("SDL_CreateWindow"));
        context = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, context, () -> sdlError("SDL_GL_CreateContext"));
        assertTrue(SDL_GL_MakeCurrent(window, context), () -> sdlError("SDL_GL_MakeCurrent"));
        GL.createCapabilities();
    }

    @AfterAll
    static void destroyGlContext() {
        try {
            if (context != MemoryUtil.NULL) {
                assertTrue(SDL_GL_DestroyContext(context), () -> sdlError("SDL_GL_DestroyContext"));
            }
        } finally {
            try {
                if (window != MemoryUtil.NULL) {
                    SDL_DestroyWindow(window);
                }
            } finally {
                SDL_Quit();
            }
        }
    }

    private static String sdlError(String operation) {
        return operation + " failed: " + SDL_GetError();
    }
}
