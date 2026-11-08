package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Indexed buffer bindings tracked by GLSM must answer every indexed query entry point, including the cases where the
 * driver holds a different name because a caller bound a buffer behind GLSM's back, and the cases where the queried
 * slot lies outside the tracked range and only the driver knows the answer.
 */
class GLSM_IndexedBufferQuery_GLTest extends GLStateManagerIntegrationFixture {

    private static final int BUFFER_SIZE = 16;

    @BeforeAll
    static void enableStateCaching() {
        installDefaultGlsmConfig();
        GLStateManager.markSplashComplete("GLSM_IndexedBufferQuery_GLTest");
        assumeTrue(GL.getCapabilities().OpenGL31, "no uniform buffer objects on this context");
    }

    private static int makeBuffer(int target) {
        final int buffer = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(target, buffer);
        GLStateManager.glBufferData(target, BUFFER_SIZE, GL15.GL_DYNAMIC_DRAW);
        GLStateManager.glBindBuffer(target, 0);
        return buffer;
    }

    private static void assertIndexedBinding(int target, int pname, int index, int expected) {
        assertEquals(expected, GLStateManager.glGetInteger(pname, index));
        assertEquals(expected, GLStateManager.glGetIntegeri(pname, index));

        final IntBuffer intParams = BufferUtils.createIntBuffer(1);
        GLStateManager.glGetIntegeri(pname, index, intParams);
        assertEquals(expected, intParams.get(0));
        GLStateManager.glGetIntegeri_v(pname, index, intParams);
        assertEquals(expected, intParams.get(0));

        final int[] arrayParams = new int[1];
        GLStateManager.glGetIntegeri_v(pname, index, arrayParams);
        assertEquals(expected, arrayParams[0]);

        assertEquals(expected != 0, GLStateManager.glGetBooleani(pname, index));
        final ByteBuffer boolParams = BufferUtils.createByteBuffer(1);
        GLStateManager.glGetBooleani_v(pname, index, boolParams);
        assertEquals(expected != 0 ? GL11.GL_TRUE : GL11.GL_FALSE, boolParams.get(0));

        assertEquals(expected, RenderSystem.getIndexedBufferBinding(target, index));
    }

    @Test
    void uniformBufferQueriesAnswerFromTrackedBinding() {
        final int index = 3;
        final int tracked = makeBuffer(GL31.GL_UNIFORM_BUFFER);
        final int bypassed = makeBuffer(GL31.GL_UNIFORM_BUFFER);
        try {
            GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, tracked);
            GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, bypassed);
            assertEquals(bypassed, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, index),
                "the bypassed binding must replace the tracked one on the driver");

            assertIndexedBinding(GL31.GL_UNIFORM_BUFFER, GL31.GL_UNIFORM_BUFFER_BINDING, index, tracked);

            GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, tracked);
        } finally {
            GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, 0);
            GLStateManager.glDeleteBuffers(bypassed);
            GLStateManager.glDeleteBuffers(tracked);
        }
    }

    @Test
    void shaderStorageBufferQueriesAnswerFromTrackedBinding() {
        assumeTrue(GL.getCapabilities().OpenGL43, "no shader storage buffer objects on this context");
        final int index = 5;
        final int tracked = makeBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
        final int bypassed = makeBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
        try {
            GLStateManager.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, tracked);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, bypassed);
            assertEquals(bypassed, GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, index),
                "the bypassed binding must replace the tracked one on the driver");

            assertIndexedBinding(GL43.GL_SHADER_STORAGE_BUFFER, GL43.GL_SHADER_STORAGE_BUFFER_BINDING, index, tracked);

            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, tracked);
        } finally {
            GLStateManager.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, 0);
            GLStateManager.glDeleteBuffers(bypassed);
            GLStateManager.glDeleteBuffers(tracked);
        }
    }

    @Test
    void deletedBufferLeavesNoStaleIndexedBinding() {
        final int index = 7;
        final int buffer = makeBuffer(GL31.GL_UNIFORM_BUFFER);
        GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, buffer);
        assertIndexedBinding(GL31.GL_UNIFORM_BUFFER, GL31.GL_UNIFORM_BUFFER_BINDING, index, buffer);

        GLStateManager.glDeleteBuffers(buffer);

        assertEquals(0, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, index),
            "the driver drops bindings of a deleted buffer");
        assertIndexedBinding(GL31.GL_UNIFORM_BUFFER, GL31.GL_UNIFORM_BUFFER_BINDING, index, 0);
    }

    @Test
    void renderSystemBindBufferBaseUpdatesTrackedBinding() {
        assumeTrue(GL.getCapabilities().OpenGL43, "no shader storage buffer objects on this context");
        final int index = 4;
        final int buffer = makeBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
        try {
            RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, buffer);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, 0);
            assertEquals(0, GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, index),
                "the driver-only unbind applies");

            assertEquals(buffer, RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, index),
                "RenderSystem must record a successfully forwarded binding");
        } finally {
            GLStateManager.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, 0);
            GLStateManager.glDeleteBuffers(buffer);
        }
    }

    @Test
    void renderSystemDeleteBuffersDropsTrackedBinding() {
        assumeTrue(GL.getCapabilities().OpenGL43, "no shader storage buffer objects on this context");
        final int index = 4;
        final int buffer = makeBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
        GLStateManager.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, buffer);

        RenderSystem.deleteBuffers(buffer);

        assertEquals(0, RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, index),
            "RenderSystem must invalidate the tracked name when deleting a buffer");
    }

    @Test
    void indexBeyondTrackedSlotsReadsTheDriver() {
        final int maxBindings = GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS);
        assumeTrue(GLStateManager.INDEXED_BUFFER_SLOTS < maxBindings,
            "driver tracks no uniform buffer binding beyond the GLSM cache range");

        final int index = GLStateManager.INDEXED_BUFFER_SLOTS;
        final int tracked = makeBuffer(GL31.GL_UNIFORM_BUFFER);
        final int bypassed = makeBuffer(GL31.GL_UNIFORM_BUFFER);
        try {
            GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, tracked);
            assertIndexedBinding(GL31.GL_UNIFORM_BUFFER, GL31.GL_UNIFORM_BUFFER_BINDING, index, tracked);

            GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, bypassed);
            assertIndexedBinding(GL31.GL_UNIFORM_BUFFER, GL31.GL_UNIFORM_BUFFER_BINDING, index, bypassed);
        } finally {
            GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, 0);
            GLStateManager.glDeleteBuffers(bypassed);
            GLStateManager.glDeleteBuffers(tracked);
        }
    }
}
