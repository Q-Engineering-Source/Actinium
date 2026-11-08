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
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
        GLSMDepthStencilAlpha.glDepthMask(false);
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
        final int initialEbo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, initialEbo);
        GLSMVertexInput.glEnableVertexAttribArray(0);
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
            GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GLSMBuffers.glDeleteBuffers(initialEbo);
        }
    }

    @Test
    void splashMigrationDropsIndexedBufferBindingsOfTheReplacedContext() {
        assumeTrue(GL.getCapabilities().OpenGL31, "no uniform buffer objects on this context");
        final boolean storageBuffers = GL.getCapabilities().OpenGL43;
        WindowBackends.useSdl();
        final Object initialDisplayToken = new Object();
        final Object replacementDisplayToken = new Object();
        bindDisplay(initialDisplayToken, context);
        GLStateManager.recreateDefaultVertexArray();

        final int uboIndex = 4;
        final int ssboIndex = 5;
        final int ubo = makeIndexedBufferBound(GL31.GL_UNIFORM_BUFFER, uboIndex, 64);
        final int ssbo = storageBuffers ? makeIndexedBufferBound(GL43.GL_SHADER_STORAGE_BUFFER, ssboIndex, 64) : 0;
        assertEquals(ubo, GLSMBuffers.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, uboIndex),
            "the initial context records the uniform buffer base binding");
        if (storageBuffers) {
            assertEquals(ssbo, GLSMBuffers.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, ssboIndex),
                "the initial context records the shader storage buffer base binding");
        }

        final long replacement = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, replacement);
        try {
            bindDisplay(replacementDisplayToken, replacement);
            assertTrue(GLStateManager.displayContextMigrated());

            GLStateManager.recreateDefaultVertexArray();
            assertEquals(0, GLSMBuffers.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, uboIndex),
                "the replacement context has no uniform buffer binding for the name recorded by the old context");
            assertEquals(0, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, uboIndex),
                "the replacement context driver never held that uniform buffer binding");
            if (storageBuffers) {
                assertEquals(0, GLSMBuffers.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, ssboIndex),
                    "the replacement context has no storage buffer binding for the name recorded by the old context");
                assertEquals(0, GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, ssboIndex),
                    "the replacement context driver never held that storage buffer binding");
            }
        } finally {
            assertTrue(SDL_GL_MakeCurrent(window, MemoryUtil.NULL));
            GL.setCapabilities(null);
            GLStateManager.releaseBoundContext();
            assertTrue(SDL_GL_DestroyContext(replacement));
            bindDisplay(initialDisplayToken, context);
            GLStateManager.recreateDefaultVertexArray();
            GLSMBuffers.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, uboIndex, 0);
            if (storageBuffers) {
                GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, ssboIndex, 0);
            }
            GLSMBuffers.glDeleteBuffers(ubo);
            if (storageBuffers) {
                GLSMBuffers.glDeleteBuffers(ssbo);
            }
        }
    }

    private static int makeIndexedBufferBound(int target, int index, long size) {
        final int buffer = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(target, buffer);
        GLSMBuffers.glBufferData(target, size, GL15.GL_STREAM_DRAW);
        GLSMBuffers.glBindBuffer(target, 0);
        GLSMBuffers.glBindBufferBase(target, index, buffer);
        return buffer;
    }

    private static void drawTriangle() {
        final int vbo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final FloatBuffer vertices = BufferUtils.createFloatBuffer(9);
        vertices.put(new float[] { 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f }).flip();
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        GLSMVertexInput.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        GLSMVertexInput.glEnableVertexAttribArray(0);
        ShaderManager.getInstance().setCurrentVertexFlags(0);
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLSMBuffers.glDeleteBuffers(vbo);
    }

    private static void bindDisplay(Object token, long nativeHandle) {
        assertTrue(SDL_GL_MakeCurrent(window, nativeHandle));
        GL.createCapabilities();
        GLStateManager.bindDisplayContext(token, nativeHandle);
    }
}
