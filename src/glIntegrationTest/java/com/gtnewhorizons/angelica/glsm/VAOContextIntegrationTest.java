package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;

class VAOContextIntegrationTest extends GLStateManagerIntegrationFixture {
    private static final Object DISPLAY_TOKEN = new Object();

    @BeforeEach
    void bindDisplay() {
        WindowBackends.useSdl();
        activateContext(DISPLAY_TOKEN, context, true);
        GLStateManager.recreateDefaultVertexArray();
        ShaderManager.getInstance().enable();
    }

    @Test
    void vaoAttributesAndEboRemainIndependentAcrossDrawables() {
        final int mainVao = GLSMVertexInput.glGenVertexArrays();
        final int mainVbo = GLSMBuffers.glGenBuffers();
        final int mainEbo = GLSMBuffers.glGenBuffers();
        configureVboVao(mainVao, mainVbo, mainEbo, 3, 12);

        final long drawable = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, drawable);
        final Object drawableToken = new Object();
        int drawableVao = 0;
        int drawableVbo = 0;
        int drawableEbo = 0;
        try {
            activateContext(drawableToken, drawable, false);
            GLStateManager.recreateDefaultVertexArray();
            drawableVao = GLSMVertexInput.glGenVertexArrays();
            drawableVbo = GLSMBuffers.glGenBuffers();
            drawableEbo = GLSMBuffers.glGenBuffers();
            configureVboVao(drawableVao, drawableVbo, drawableEbo, 4, 16);

            activateContext(DISPLAY_TOKEN, context, true);
            GLSMVertexInput.glBindVertexArray(mainVao);
            assertVboVaoState(mainVbo, mainEbo, 3, 12);

            activateContext(drawableToken, drawable, false);
            GLSMVertexInput.glBindVertexArray(drawableVao);
            assertVboVaoState(drawableVbo, drawableEbo, 4, 16);
        } finally {
            if (drawableVao != 0) {
                activateContext(drawableToken, drawable, false);
                GLSMVertexInput.glBindVertexArray(0);
                GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                GLSMVertexInput.glDeleteVertexArrays(drawableVao);
                GLSMBuffers.glDeleteBuffers(drawableVbo);
                GLSMBuffers.glDeleteBuffers(drawableEbo);
            }
            releaseNativeContext();
            assertTrue(SDL_GL_DestroyContext(drawable));
            activateContext(DISPLAY_TOKEN, context, true);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMVertexInput.glDeleteVertexArrays(mainVao);
            GLSMBuffers.glDeleteBuffers(mainVbo);
            GLSMBuffers.glDeleteBuffers(mainEbo);
        }
    }

    @Test
    void clientArrayUploadKeepsDrawLengthAndVaoPointersAcrossDrawables() {
        final ByteBuffer mainPosition = positionBuffer(4_200_768);
        final ByteBuffer mainColor = BufferUtils.createByteBuffer(1024);
        final int mainVao = GLSMVertexInput.glGenVertexArrays();
        GLSMVertexInput.glBindVertexArray(mainVao);
        GLSMVertexInput.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, mainPosition);
        GLSMVertexInput.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, 4, mainColor);
        GLSMVertexInput.glEnableVertexAttribArray(0);
        GLSMVertexInput.glEnableVertexAttribArray(1);
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 4);
        assertEquals(48L, GL20.glGetVertexAttribPointer(1, GL20.GL_VERTEX_ATTRIB_ARRAY_POINTER));
        GLSMVertexInput.glBindVertexArray(0);

        final long drawable = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, drawable);
        final Object drawableToken = new Object();
        int drawableVao = 0;
        try {
            activateContext(drawableToken, drawable, false);
            GLStateManager.recreateDefaultVertexArray();
            drawableVao = GLSMVertexInput.glGenVertexArrays();
            GLSMVertexInput.glBindVertexArray(drawableVao);
            GLSMVertexInput.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, positionBuffer(4096));
            GLSMVertexInput.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, 4, BufferUtils.createByteBuffer(128));
            GLSMVertexInput.glEnableVertexAttribArray(0);
            GLSMVertexInput.glEnableVertexAttribArray(1);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 4);
            assertEquals(32L, GL20.glGetVertexAttribPointer(1, GL20.GL_VERTEX_ATTRIB_ARRAY_POINTER));
            GLSMVertexInput.glBindVertexArray(0);

            activateContext(DISPLAY_TOKEN, context, true);
            GLSMVertexInput.glBindVertexArray(mainVao);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 4);
            assertEquals(48L, GL20.glGetVertexAttribPointer(1, GL20.GL_VERTEX_ATTRIB_ARRAY_POINTER));
        } finally {
            if (drawableVao != 0) {
                activateContext(drawableToken, drawable, false);
                GLSMVertexInput.glBindVertexArray(0);
                GLSMVertexInput.glDeleteVertexArrays(drawableVao);
            }
            releaseNativeContext();
            assertTrue(SDL_GL_DestroyContext(drawable));
            activateContext(DISPLAY_TOKEN, context, true);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(mainVao);
        }
    }

    private static void configureVboVao(int vao, int vbo, int ebo, int size, int stride) {
        GLSMVertexInput.glBindVertexArray(vao);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, 256L, GL15.GL_STATIC_DRAW);
        GLSMVertexInput.glVertexAttribPointer(0, size, GL11.GL_FLOAT, false, stride, 0L);
        GLSMVertexInput.glEnableVertexAttribArray(0);
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GLSMBuffers.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, 64L, GL15.GL_STATIC_DRAW);
        GLSMVertexInput.glBindVertexArray(0);
    }

    private static void assertVboVaoState(int vbo, int ebo, int size, int stride) {
        assertEquals(vbo, GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING));
        assertEquals(size, GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));
        assertEquals(stride, GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE));
        assertEquals(GL11.GL_TRUE, GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));
        assertEquals(ebo, GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));
        assertEquals(ebo, GLStateManager.getBoundEBO());
    }

    private static ByteBuffer positionBuffer(int bytes) {
        final ByteBuffer buffer = BufferUtils.createByteBuffer(bytes);
        for (int i = 0; i < bytes; i++) buffer.put(i, (byte) 0x5A);
        return buffer;
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
