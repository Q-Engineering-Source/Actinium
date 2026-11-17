package com.dhj.actinium.mixin.core.terrain;

import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BufferBuilderTessellatorDataTest {

    @Test
    void exposesTheVertexCountRequiredByTheBatchDrawer() {
        ITessellatorData data = new Probe();
        assertEquals(0, data.getVertexCount());
    }

    private static final class Probe extends BufferBuilderMixin {
        @Override
        public void reset() {
        }

        @Override
        protected void actinium$nextVertexFormatIndex() {
        }
    }
}
