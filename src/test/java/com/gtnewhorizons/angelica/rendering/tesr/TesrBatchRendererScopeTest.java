package com.gtnewhorizons.angelica.rendering.tesr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TesrBatchRendererScopeTest {

    private final TesrBatchRenderer renderer = TesrBatchRenderer.INSTANCE;

    @Test
    void nestedBeginKeepsOuterIdOutermostEndReturnsToZeroAndIdsAreUnique() {
        renderer.beginScope();
        final long outer = renderer.currentScope();
        assertNotEquals(0L, outer);
        renderer.beginScope();
        assertEquals(outer, renderer.currentScope());
        renderer.endScope();
        assertEquals(outer, renderer.currentScope());
        renderer.endScope();
        assertEquals(0L, renderer.currentScope());
        renderer.beginScope();
        final long second = renderer.currentScope();
        renderer.endScope();
        assertNotEquals(outer, second);
    }
}
