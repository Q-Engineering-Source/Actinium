package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;

class FfpContextResourceIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void drawableOwnsCurrentStateBrightnessAndShaderCache() {
        WindowBackends.useSdl();
        final Object displayToken = new Object();
        final Object drawableToken = new Object();
        activateContext(displayToken, context, true);
        GLStateManager.recreateDefaultVertexArray();
        ShaderManager.getInstance().enable();
        setCurrentState(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f);
        drawTriangle(0);
        final String mainCache = ShaderManager.getInstance().getDebugInfo();

        final long drawable = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, drawable);
        try {
            activateContext(drawableToken, drawable, false);
            GLStateManager.recreateDefaultVertexArray();
            setCurrentState(11.0f, 12.0f, 13.0f, 14.0f, 15.0f, 16.0f, 17.0f, 18.0f, 19.0f);
            drawTriangle(VertexFlags.COLOR_BIT);
            assertCurrentState(11.0f, 12.0f, 13.0f, 14.0f, 15.0f, 16.0f, 17.0f, 18.0f, 19.0f);

            activateContext(displayToken, context, true);
            assertCurrentState(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f);
            assertEquals(mainCache, ShaderManager.getInstance().getDebugInfo());
            drawTriangle(0);
            assertNotEquals(0, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        } finally {
            releaseNativeContext();
            assertTrue(SDL_GL_DestroyContext(drawable));
            activateContext(displayToken, context, true);
        }
    }

    private static void setCurrentState(
        float nx, float ny, float nz,
        float s, float t, float r, float q,
        float brightnessX, float brightnessY
    ) {
        ShaderManager.setCurrentNormal(nx, ny, nz);
        ShaderManager.setCurrentTexCoord(s, t, r, q);
        GLStateManager.setLightmapTextureCoords(GL13.GL_TEXTURE1, brightnessX, brightnessY);
        GLStateManager.flushDeferredVertexAttribs();
    }

    private static void assertCurrentState(
        float nx, float ny, float nz,
        float s, float t, float r, float q,
        float brightnessX, float brightnessY
    ) {
        assertEquals(nx, ShaderManager.getCurrentNormal().x);
        assertEquals(ny, ShaderManager.getCurrentNormal().y);
        assertEquals(nz, ShaderManager.getCurrentNormal().z);
        assertEquals(s, ShaderManager.getCurrentTexCoord().x);
        assertEquals(t, ShaderManager.getCurrentTexCoord().y);
        assertEquals(r, ShaderManager.getCurrentTexCoord().z);
        assertEquals(q, ShaderManager.getCurrentTexCoord().w);
        GLStateManager.flushDeferredVertexAttribs();
        final FloatBuffer lightmap = BufferUtils.createFloatBuffer(4);
        GL20.glGetVertexAttribfv(3, GL20.GL_CURRENT_VERTEX_ATTRIB, lightmap);
        assertEquals(brightnessX, lightmap.get(0));
        assertEquals(brightnessY, lightmap.get(1));
    }

    private static void drawTriangle(int vertexFlags) {
        final int vbo = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final FloatBuffer vertices = BufferUtils.createFloatBuffer(12);
        vertices.put(new float[] { 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f }).flip();
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        GLStateManager.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        GLStateManager.glEnableVertexAttribArray(0);
        if ((vertexFlags & VertexFlags.COLOR_BIT) != 0) {
            GLStateManager.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, 0, 0L);
            GLStateManager.glEnableVertexAttribArray(1);
            ShaderManager.getInstance().setCurrentVertexFlags(VertexFlags.COLOR_BIT);
        } else {
            ShaderManager.getInstance().setCurrentVertexFlags(0);
        }
        GLStateManager.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GLStateManager.glDisableVertexAttribArray(0);
        GLStateManager.glDisableVertexAttribArray(1);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.glDeleteBuffers(vbo);
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
