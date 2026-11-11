package com.gtnewhorizons.angelica.sdlgpu;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IndexedEnableQueryTest {
    @Test
    void rangeBindingRetainsGenericBufferAndFullWidthOffsets() {
        SDLGPURenderBackend backend = new SDLGPURenderBackend();
        int first = backend.genBuffers();
        int second = backend.genBuffers();
        long offset = 1L << 34;
        long size = (1L << 33) + 64;
        backend.bindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 9, first, offset, size);
        backend.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, second);
        assertEquals(second, backend.getInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING));
        assertEquals(first, backend.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, 9));
        assertEquals(offset, backend.getInteger64Indexed(GL43.GL_SHADER_STORAGE_BUFFER_START, 9));
        assertEquals(size, backend.getInteger64Indexed(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, 9));
        backend.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 9, first);
        assertEquals(0, backend.getInteger64Indexed(GL43.GL_SHADER_STORAGE_BUFFER_START, 9));
        assertEquals(0, backend.getInteger64Indexed(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, 9));
    }

    @Test
    void indexedBlendQueriesFollowIndependentDrawBufferChanges() {
        SDLGPURenderBackend backend = new SDLGPURenderBackend();
        backend.disable(GL11.GL_BLEND);
        backend.enablei(GL11.GL_BLEND, 1);
        assertFalse(backend.isEnabledIndexed(GL11.GL_BLEND, 0));
        assertTrue(backend.isEnabledIndexed(GL11.GL_BLEND, 1));
        backend.enable(GL11.GL_BLEND);
        backend.disablei(GL11.GL_BLEND, 1);
        assertTrue(backend.isEnabledIndexed(GL11.GL_BLEND, 0));
        assertFalse(backend.isEnabledIndexed(GL11.GL_BLEND, 1));
    }
}
