package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import com.gtnewhorizons.angelica.glsm.stacks.RetainedState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;

class RetainedStateIntegrationTest extends GLStateManagerIntegrationFixture {
    private static final StateSet ALL = StateSet.forMask(GL11.GL_ALL_ATTRIB_BITS);
    private static final Object DISPLAY_TOKEN = new Object();

    private int entryDepth;

    @BeforeAll
    static void initializeGlsm() {
        GLStateManager.applyInitConfig(GLSMInitConfig.builder().build());
    }

    @BeforeEach
    void setUp() {
        entryDepth = GLStateManager.getAttribDepth();
        baseline();
    }

    @AfterEach
    void tearDown() {
        GLStateManager.popStateTo(entryDepth);
        baseline();
    }

    @Test
    void retainedDeltaReappliesAfterPop() {
        final int texture = GL11.glGenTextures();
        final RetainedState retained = new RetainedState();
        try {
            final int depth = GLStateManager.pushState(ALL);
            mutate(texture);
            GLStateManager.retainModifiedState(depth, retained);
            GLStateManager.popStateTo(depth);
            assertBaseline();

            GLStateManager.applyRetainedState(retained);
            assertMutated(texture);

            baseline();
            GLStateManager.applyRetainedState(retained);
            assertMutated(texture);
        } finally {
            GL11.glDeleteTextures(texture);
        }
    }

    @Test
    void appliedDeltaIsRestoredByEnclosingPush() {
        final int texture = GL11.glGenTextures();
        final RetainedState retained = new RetainedState();
        try {
            final int depth = GLStateManager.pushState(ALL);
            mutate(texture);
            GLStateManager.retainModifiedState(depth, retained);
            GLStateManager.popStateTo(depth);

            final int outer = GLStateManager.pushState(ALL);
            GLStateManager.applyRetainedState(retained);
            assertMutated(texture);
            GLStateManager.popStateTo(outer);
            assertBaseline();
        } finally {
            GL11.glDeleteTextures(texture);
        }
    }

    @Test
    void statesRestoredByNestedPushAreNotRetained() {
        final RetainedState retained = new RetainedState();
        final int depth = GLStateManager.pushState(ALL);
        GLStateManager.enableBlend();
        final int nested = GLStateManager.pushState(ALL);
        GLStateManager.disableCull();
        GLStateManager.popStateTo(nested);
        GLStateManager.retainModifiedState(depth, retained);
        GLStateManager.popStateTo(depth);

        GLStateManager.applyRetainedState(retained);
        assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
        assertTrue(GLSMEnable.glIsEnabled(GL11.GL_CULL_FACE));
        assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
    }

    @Test
    void secondCaptureInvalidatesFirst() {
        final RetainedState first = new RetainedState();
        final RetainedState second = new RetainedState();
        int depth = GLStateManager.pushState(ALL);
        GLStateManager.enableBlend();
        GLStateManager.retainModifiedState(depth, first);
        GLStateManager.popStateTo(depth);

        depth = GLStateManager.pushState(ALL);
        GLStateManager.disableCull();
        GLStateManager.retainModifiedState(depth, second);
        GLStateManager.popStateTo(depth);

        GLStateManager.applyRetainedState(first);
        assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
        assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
        GLStateManager.applyRetainedState(second);
        assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
        assertFalse(GL11.glIsEnabled(GL11.GL_CULL_FACE));
    }

    @Test
    void writesInOuterPushAreNotRetained() {
        final RetainedState retained = new RetainedState();
        GLSMBlend.glClearColor(0.25F, 0.5F, 0.75F, 1.0F);
        final int outer = GLStateManager.pushState(ALL);
        GLSMBlend.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        final int inner = GLStateManager.pushState(ALL);
        GLStateManager.enableBlend();
        GLStateManager.retainModifiedState(inner, retained);
        GLStateManager.popStateTo(outer);

        GLStateManager.applyRetainedState(retained);
        assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
        assertEquals(0.25F, GLStateManager.getClearColor().getRed(), 0.0F);
        assertEquals(1.0F, GLStateManager.getClearColor().getAlpha(), 0.0F);
    }

    @Test
    void unchangedRetainedApplicationDoesNotAdvanceGenerations() {
        final int texture = GL11.glGenTextures();
        final RetainedState retained = new RetainedState();
        try {
            final int depth = GLStateManager.pushState(ALL);
            mutate(texture);
            GLStateManager.retainModifiedState(depth, retained);
            GLStateManager.popStateTo(depth);
            GLStateManager.applyRetainedState(retained);

            final GLContextState glContext = GLStateManager.ctx();
            final int colorGeneration = glContext.colorGeneration;
            final int fragmentGeneration = glContext.fragmentGeneration;
            GLStateManager.applyRetainedState(retained);

            assertEquals(colorGeneration, glContext.colorGeneration);
            assertEquals(fragmentGeneration, glContext.fragmentGeneration);
        } finally {
            GL11.glDeleteTextures(texture);
        }
    }

    @Test
    void unknownBlendFunctionIsReissuedEvenWhenCachedValueMatches() {
        final RetainedState retained = new RetainedState();
        final int depth = GLStateManager.pushState(ALL);
        GLStateManager.tryBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ONE);
        GLStateManager.retainModifiedState(depth, retained);
        GLStateManager.popStateTo(depth);
        GLStateManager.applyRetainedState(retained);

        GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
        GLStateManager.getBlendState().setFuncUnknownState();
        GLStateManager.applyRetainedState(retained);

        assertEquals(GL11.GL_DST_COLOR, GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB));
        assertEquals(GL11.GL_ZERO, GL11.glGetInteger(GL14.GL_BLEND_DST_RGB));
        assertFalse(GLStateManager.getBlendState().isFuncUnknown());
    }

    @Test
    void deletedProgramIsNotRebound() {
        final RetainedState retained = new RetainedState();
        final int program = linkTrivialProgram();
        final int depth = GLStateManager.pushState(ALL);
        GLSMPrograms.glUseProgram(program);
        GLStateManager.retainModifiedState(depth, retained);
        GLStateManager.popStateTo(depth);
        GLSMPrograms.glUseProgram(0);
        GLSMPrograms.glDeleteProgram(program);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
        }

        GLStateManager.applyRetainedState(retained);
        assertEquals(0, GLStateManager.getActiveProgram());
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void retainedCaptureBelongsToItsDrawableContext() {
        WindowBackends.useSdl();
        activateContext(DISPLAY_TOKEN, context, true);
        final RetainedState retained = new RetainedState();
        final int depth = GLStateManager.pushState(ALL);
        GLStateManager.enableBlend();
        GLStateManager.retainModifiedState(depth, retained);
        GLStateManager.popStateTo(depth);

        final long drawable = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, drawable);
        final Object drawableToken = new Object();
        try {
            activateContext(drawableToken, drawable, false);
            GLStateManager.disableBlend();
            GLStateManager.applyRetainedState(retained);
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));

            activateContext(DISPLAY_TOKEN, context, true);
            GLStateManager.applyRetainedState(retained);
            assertTrue(GLStateManager.getBlendMode().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
        } finally {
            activateContext(DISPLAY_TOKEN, context, true);
            GLStateManager.retireDrawableContext(drawableToken);
            assertTrue(SDL_GL_DestroyContext(drawable));
        }
    }

    private static void baseline() {
        GLStateManager.enableCull();
        GLStateManager.disableBlend();
        GLStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMDepthStencilAlpha.glDepthFunc(GL11.GL_LEQUAL);
        GLSMDepthStencilAlpha.glAlphaFunc(GL11.GL_GREATER, 0.1F);
        GLSMCurrentAttribs.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GLSMBlend.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private static void mutate(int texture) {
        GLStateManager.disableCull();
        GLStateManager.enableBlend();
        GLStateManager.tryBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ONE);
        GLSMDepthStencilAlpha.glDepthMask(false);
        GLSMDepthStencilAlpha.glDepthFunc(GL11.GL_ALWAYS);
        GLSMDepthStencilAlpha.glAlphaFunc(GL11.GL_GEQUAL, 0.5F);
        GLSMCurrentAttribs.glColor4f(0.25F, 0.5F, 0.75F, 0.5F);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    private static void assertBaseline() {
        assertTrue(GLSMEnable.glIsEnabled(GL11.GL_CULL_FACE));
        assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
        assertFalse(GLSMEnable.glIsEnabled(GL11.GL_BLEND));
        assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
        assertEquals(GL11.GL_SRC_ALPHA, GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB));
        assertEquals(GL11.GL_ZERO, GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA));
        assertTrue(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        assertEquals(GL11.GL_LEQUAL, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        assertEquals(GL11.GL_GREATER, GLSMQueries.glGetInteger(GL11.GL_ALPHA_TEST_FUNC));
        assertEquals(1.0F, GLStateManager.getColor().getRed(), 0.0F);
        assertEquals(0, driverTextureBinding(0));
    }

    private static void assertMutated(int texture) {
        assertFalse(GLSMEnable.glIsEnabled(GL11.GL_CULL_FACE));
        assertFalse(GL11.glIsEnabled(GL11.GL_CULL_FACE));
        assertTrue(GLSMEnable.glIsEnabled(GL11.GL_BLEND));
        assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
        assertEquals(GL11.GL_DST_COLOR, GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB));
        assertEquals(GL11.GL_ZERO, GL11.glGetInteger(GL14.GL_BLEND_DST_RGB));
        assertEquals(GL11.GL_ONE, GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA));
        assertEquals(GL11.GL_ONE, GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA));
        assertEquals(GL11.GL_DST_COLOR, GLSMQueries.glGetInteger(GL14.GL_BLEND_SRC_RGB));
        assertFalse(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        assertEquals(GL11.GL_ALWAYS, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        assertEquals(GL11.GL_GEQUAL, GLSMQueries.glGetInteger(GL11.GL_ALPHA_TEST_FUNC));
        assertEquals(0.25F, GLStateManager.getColor().getRed(), 0.0F);
        assertEquals(0.5F, GLStateManager.getColor().getAlpha(), 0.0F);
        assertEquals(texture, driverTextureBinding(0));
        assertEquals(texture, GLStateManager.getBoundTextureForServerState(0));
    }

    private static int driverTextureBinding(int unit) {
        final int saved = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        final int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(saved);
        return binding;
    }

    private static int linkTrivialProgram() {
        final int vertex = GLSMPrograms.glCreateShader(GL20.GL_VERTEX_SHADER);
        GLSMPrograms.glShaderSource(vertex, "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }\n");
        GLSMPrograms.glCompileShader(vertex);
        assertEquals(GL11.GL_TRUE, GL20.glGetShaderi(vertex, GL20.GL_COMPILE_STATUS));
        final int fragment = GLSMPrograms.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GLSMPrograms.glShaderSource(fragment, "#version 330 core\nout vec4 color; void main() { color = vec4(1.0); }\n");
        GLSMPrograms.glCompileShader(fragment);
        assertEquals(GL11.GL_TRUE, GL20.glGetShaderi(fragment, GL20.GL_COMPILE_STATUS));

        final int program = GLSMPrograms.glCreateProgram();
        GLSMPrograms.glAttachShader(program, vertex);
        GLSMPrograms.glAttachShader(program, fragment);
        GLSMPrograms.glLinkProgram(program);
        GLSMPrograms.glDeleteShader(vertex);
        GLSMPrograms.glDeleteShader(fragment);
        assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS));
        return program;
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
