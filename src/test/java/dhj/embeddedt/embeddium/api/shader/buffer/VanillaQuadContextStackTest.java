package dhj.embeddedt.embeddium.api.shader.buffer;

import dhj.embeddedt.embeddium.api.shader.vertex.ExtendedDataHelper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VanillaQuadContextStackTest {
    @Test
    void restoresOuterMaterialAfterNestedRender() {
        VanillaQuadContextStack stack = new VanillaQuadContextStack();
        VanillaQuadContext outer = context(1, ExtendedDataHelper.FLUID_RENDER_TYPE);
        VanillaQuadContext inner = context(2, ExtendedDataHelper.BLOCK_RENDER_TYPE);
        List<Short> capturedRenderTypes = new ArrayList<>();

        stack.push(outer);
        assertSame(outer, stack.active());
        capturedRenderTypes.add(stack.active().renderType());

        stack.push(inner);
        assertSame(inner, stack.active());
        capturedRenderTypes.add(stack.active().renderType());

        stack.pop();
        assertSame(outer, stack.active());
        capturedRenderTypes.add(stack.active().renderType());

        stack.pop();
        assertNull(stack.active());
        assertEquals(List.of(
                ExtendedDataHelper.FLUID_RENDER_TYPE,
                ExtendedDataHelper.BLOCK_RENDER_TYPE,
                ExtendedDataHelper.FLUID_RENDER_TYPE), capturedRenderTypes);
    }

    @Test
    void rejectsUnbalancedPopAndReset() {
        VanillaQuadContextStack stack = new VanillaQuadContextStack();
        VanillaQuadContext outer = context(1, ExtendedDataHelper.BLOCK_RENDER_TYPE);

        assertThrows(IllegalStateException.class, stack::pop);
        stack.push(outer);
        assertThrows(IllegalStateException.class, stack::reset);
        stack.pop();
        stack.reset();
        assertNull(stack.active());
    }

    @Test
    void lazyBufferStartPreservesAnOpenRenderScope() {
        VanillaQuadContextStack stack = new VanillaQuadContextStack();
        VanillaQuadContext outer = context(1, ExtendedDataHelper.FLUID_RENDER_TYPE);

        stack.push(outer);
        stack.beginBuffer();
        assertSame(outer, stack.active());

        stack.pop();
        stack.beginBuffer();
        assertNull(stack.active());
    }

    private static VanillaQuadContext context(int blockStateId, short renderType) {
        return new VanillaQuadContext(0, 0, 0, blockStateId, renderType, (byte) 0);
    }
}
