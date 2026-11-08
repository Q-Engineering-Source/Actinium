package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Deleting a buffer has to drop the cached binding as well as the driver binding. A cache that keeps answering with a
 * deleted name feeds it back to the driver, which then recreates an empty object under that name. Indexed base and
 * range bindings are covered together with the plain targets so that a deletion path that only repairs the named
 * bindings fails here.
 */
class GLSM_DeletedBufferBinding_GLTest extends GLStateManagerIntegrationFixture {

    private static final int BUFFER_SIZE = 64;

    @BeforeAll
    static void enableStateCaching() {
        installDefaultGlsmConfig();
        GLStateManager.markSplashComplete("GLSM_DeletedBufferBinding_GLTest");
        GLStateManager.recreateDefaultVertexArray();
        assumeTrue(GL.getCapabilities().OpenGL31, "no uniform buffer objects on this context");
    }

    private static int makeBoundBuffer(int target) {
        final int buffer = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(target, buffer);
        GLStateManager.glBufferData(target, BUFFER_SIZE, GL15.GL_STREAM_DRAW);
        return buffer;
    }

    private static void deleteAndExpectDriverUnbound(int binding, int buffer) {
        GLStateManager.glDeleteBuffers(buffer);
        assertEquals(0, GL11.glGetInteger(binding), "the driver unbinds a deleted buffer");
    }

    @Test
    void deletedPixelUnpackBufferDoesNotBlockTexImage2D() {
        final int pbo = makeBoundBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
        deleteAndExpectDriverUnbound(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING, pbo);
        assertEquals(0, GLStateManager.ctx().boundPixelUnpackBuffer, "the cache drops the deleted pixel unpack buffer");

        final int tex = GLStateManager.glGenTextures();
        try {
            drainDriverErrors();
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GLStateManager.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(4));
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(),
                "the upload must not restore the deleted pixel unpack buffer");
        } finally {
            GLStateManager.glDeleteTextures(tex);
        }
    }

    @Test
    void deletedPixelPackBufferDoesNotBlockReadPixels() {
        drainDriverErrors();
        final int pbo = makeBoundBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        deleteAndExpectDriverUnbound(GL21.GL_PIXEL_PACK_BUFFER_BINDING, pbo);
        assertEquals(0, GLStateManager.ctx().boundPixelPackBuffer, "the cache drops the deleted pixel pack buffer");

        GLStateManager.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(4));
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(),
            "the read-back must not rebind the deleted pixel pack buffer");
    }

    @Test
    void deletedCopyReadBufferIsDroppedFromTheCache() {
        final int buffer = makeBoundBuffer(GL31.GL_COPY_READ_BUFFER);
        deleteAndExpectDriverUnbound(GL42.GL_COPY_READ_BUFFER_BINDING, buffer);
        assertEquals(0, GLStateManager.ctx().boundCopyReadBuffer, "the cache drops the deleted copy read buffer");
    }

    @Test
    void deletedCopyWriteBufferIsDroppedFromTheCache() {
        final int buffer = makeBoundBuffer(GL31.GL_COPY_WRITE_BUFFER);
        deleteAndExpectDriverUnbound(GL42.GL_COPY_WRITE_BUFFER_BINDING, buffer);
        assertEquals(0, GLStateManager.ctx().boundCopyWriteBuffer, "the cache drops the deleted copy write buffer");
    }

    @Test
    void deletedArrayBufferDoesNotBlockClientVertexPointer() {
        drainDriverErrors();
        final int vbo = makeBoundBuffer(GL15.GL_ARRAY_BUFFER);
        deleteAndExpectDriverUnbound(GL15.GL_ARRAY_BUFFER_BINDING, vbo);
        assertEquals(0, GLStateManager.ctx().boundVBO, "the cache drops the deleted array buffer");

        GLStateManager.glVertexPointer(3, 0, BufferUtils.createFloatBuffer(9));
    }

    @Test
    void deletedElementArrayBufferDoesNotBlockClientDrawElements() {
        drainDriverErrors();
        final int ebo = makeBoundBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER);
        deleteAndExpectDriverUnbound(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING, ebo);
        assertEquals(0, GLStateManager.ctx().vaos.boundEBO, "the cache drops the deleted element array buffer");

        GLStateManager.glDrawElements(GL11.GL_TRIANGLES, BufferUtils.createIntBuffer(3));
    }

    @Test
    void uniformBufferBaseBindingReachesCacheAndDriver() {
        final int index = 3;
        final int ubo = makeBoundBuffer(GL31.GL_UNIFORM_BUFFER);
        try {
            GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, ubo);
            assertEquals(ubo, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, index),
                "the base binding reaches the driver");
            assertEquals(ubo, GLSMBuffers.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, index),
                "the base binding reaches the cache");
        } finally {
            GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, 0);
            GLStateManager.glDeleteBuffers(ubo);
        }
    }

    @Test
    void deletedIndexedUniformBufferIsNotRestoredFromCache() {
        final int index = 4;
        final int ubo = makeBoundBuffer(GL31.GL_UNIFORM_BUFFER);
        GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, ubo);
        assertEquals(ubo, GLSMBuffers.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, index),
            "the base binding reaches the cache");

        GLStateManager.glDeleteBuffers(ubo);
        assertEquals(0, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, index),
            "the driver unbinds a deleted buffer");
        assertEquals(0, GLSMBuffers.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, index),
            "the cache must not answer with the deleted buffer");
    }

    @Test
    void uniformBufferRangeBindingReachesCacheAndDriver() {
        final int index = 5;
        final int ubo = makeBoundBuffer(GL31.GL_UNIFORM_BUFFER);
        try {
            GLStateManager.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, index, ubo, 0, BUFFER_SIZE);
            assertEquals(ubo, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, index),
                "the range binding reaches the driver");
            assertEquals(ubo, GLSMBuffers.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, index),
                "the range binding reaches the cache");
        } finally {
            GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, 0);
            GLStateManager.glDeleteBuffers(ubo);
        }
    }

    @Test
    void deletedUniformBufferRangeBindingIsNotRestoredFromCache() {
        final int index = 6;
        final int ubo = makeBoundBuffer(GL31.GL_UNIFORM_BUFFER);
        GLStateManager.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, index, ubo, 0, BUFFER_SIZE);
        assertEquals(ubo, GLSMBuffers.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, index),
            "the range binding reaches the cache");

        GLStateManager.glDeleteBuffers(ubo);
        assertEquals(0, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, index),
            "the driver unbinds a deleted buffer");
        assertEquals(0, GLSMBuffers.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, index),
            "the cache must not answer with the deleted buffer");
    }

    @Test
    void cachedIndexedBindingSurvivesDriverOnlyChange() {
        final int index = 7;
        final int ubo = makeBoundBuffer(GL31.GL_UNIFORM_BUFFER);
        try {
            GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, ubo);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, 0);
            assertEquals(0, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, index), "the driver-only unbind applies");
            assertEquals(ubo, GLSMBuffers.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, index),
                "the cached binding answers without consulting the driver");
        } finally {
            GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, 0);
            GLStateManager.glDeleteBuffers(ubo);
        }
    }

    @Test
    void shaderStorageBufferBaseBindingReachesCacheAndIsDroppedOnDelete() {
        assumeTrue(GL.getCapabilities().OpenGL43, "no shader storage buffer objects on this context");
        final int index = 2;
        final int ssbo = makeBoundBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
        GLStateManager.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, ssbo);
        assertEquals(ssbo, GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, index),
            "the base binding reaches the driver");
        assertEquals(ssbo, GLSMBuffers.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, index),
            "the base binding reaches the cache");

        GLStateManager.glDeleteBuffers(ssbo);
        assertEquals(0, GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, index),
            "the driver unbinds a deleted buffer");
        assertEquals(0, GLSMBuffers.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, index),
            "the cache must not answer with the deleted buffer");
    }

    private static void drainDriverErrors() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Drain stale errors so each test only reports the calls it made.
        }
    }
}
