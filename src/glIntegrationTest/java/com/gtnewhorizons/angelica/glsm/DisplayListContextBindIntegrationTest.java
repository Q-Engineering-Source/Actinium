package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.recording.commands.AttribLayoutKey;
import com.gtnewhorizons.angelica.glsm.recording.commands.IndexedDrawBatch;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_SetAttribute;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_SHARE_WITH_CURRENT_CONTEXT;

/**
 * Vertex array names belong to the context that generated them, so a display list or an
 * indexed draw batch compiled in one context must not replay its array in another.
 */
class DisplayListContextBindIntegrationTest extends GLStateManagerIntegrationFixture {
    private static final Object DISPLAY_TOKEN = new Object();

    @Test
    void recordedVertexArrayBindReplaysOnlyInItsCompileContext() {
        WindowBackends.useSdl();
        final Object drawableToken = new Object();
        activateContext(DISPLAY_TOKEN, context, true);

        final int bound = GLStateManager.glGenVertexArrays();
        final int local = GLStateManager.glGenVertexArrays();
        final int localList = GLStateManager.glGenLists(1);
        GLStateManager.glNewList(localList, GL11.GL_COMPILE);
        GLStateManager.glBindVertexArray(local);
        GLStateManager.glEndList();

        final long drawable = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, drawable);
        final int[] foreignList = new int[1];
        try {
            activateContext(drawableToken, drawable, false);
            int foreign;
            do {
                foreign = GLStateManager.glGenVertexArrays();
            } while (foreign == bound);
            foreignList[0] = GLStateManager.glGenLists(1);
            GLStateManager.glNewList(foreignList[0], GL11.GL_COMPILE);
            GLStateManager.glBindVertexArray(foreign);
            GLStateManager.glEndList();
        } finally {
            releaseNativeContext();
            activateContext(DISPLAY_TOKEN, context, true);
        }

        try {
            GLStateManager.glBindVertexArray(bound);
            GLStateManager.glCallList(foreignList[0]);
            assertEquals(bound, GLStateManager.getBoundVAO());
            GLStateManager.glCallList(localList);
            assertEquals(local, GLStateManager.getBoundVAO());
        } finally {
            releaseNativeContext();
            assertTrue(SDL_GL_DestroyContext(drawable));
            activateContext(DISPLAY_TOKEN, context, true);
            GLStateManager.glBindVertexArray(0);
            GLStateManager.glDeleteLists(localList, 1);
            GLStateManager.glDeleteLists(foreignList[0], 1);
            GLStateManager.glDeleteVertexArrays(bound);
            GLStateManager.glDeleteVertexArrays(local);
        }
    }

    @Test
    void indexedDrawBatchBindsAVertexArrayItCreatesInTheReplayContext() {
        WindowBackends.useSdl();
        final Object drawableToken = new Object();
        activateContext(DISPLAY_TOKEN, context, true);

        final FloatBuffer vertices = BufferUtils.createFloatBuffer(9);
        vertices.put(new float[] {-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0f, 0.5f, 0f});
        vertices.flip();
        final int vbo = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);

        final ByteBuffer indices = BufferUtils.createByteBuffer(3 * Integer.BYTES);
        indices.putInt(0).putInt(1).putInt(2);
        indices.flip();
        final int ebo = GLStateManager.glGenBuffers();
        GLStateManager.glBindVertexArray(GLStateManager.glGenVertexArrays());
        GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GLStateManager.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);

        final AttribLayoutKey layout = new AttribLayoutKey(
            new int[] {0}, new int[] {3}, new int[] {GL11.GL_FLOAT}, new boolean[] {false});
        final IndexedDrawBatch batch = new IndexedDrawBatch(layout, vbo, ebo);

        final long drawable = createSharedDrawable();
        assertNotEquals(MemoryUtil.NULL, drawable);
        try {
            activateContext(drawableToken, drawable, false);
            batch.bind();
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            assertEquals(ebo, GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));

            releaseNativeContext();
            activateContext(DISPLAY_TOKEN, context, true);
            batch.bind();
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            assertEquals(ebo, GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));
            assertNotEquals(0, GLStateManager.getBoundVAO());
        } finally {
            releaseNativeContext();
            assertTrue(SDL_GL_DestroyContext(drawable));
            activateContext(DISPLAY_TOKEN, context, true);
            batch.delete();
            GLStateManager.glBindVertexArray(0);
            GLStateManager.glDeleteBuffers(vbo);
            GLStateManager.glDeleteBuffers(ebo);
        }
    }

    private static long createSharedDrawable() {
        SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 1);
        try {
            return SDL_GL_CreateContext(window);
        } finally {
            SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 0);
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
}
