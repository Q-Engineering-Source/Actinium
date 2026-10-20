package com.gtnewhorizons.angelica.glsm.states;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelPackStateTest {

    @Test
    void defaults() {
        assertTrue(PixelPackState.DEFAULT.isDefault());
        assertEquals(4, PixelPackState.DEFAULT.alignment());
        assertEquals(0, PixelPackState.DEFAULT.rowLength());
        assertFalse(PixelPackState.DEFAULT.swapBytes());
        assertFalse(PixelPackState.DEFAULT.lsbFirst());
    }

    @Test
    void withUpdatesPackParams() {
        PixelPackState s = PixelPackState.DEFAULT
            .with(GL11.GL_PACK_ALIGNMENT, 1)
            .with(GL11.GL_PACK_ROW_LENGTH, 64)
            .with(GL11.GL_PACK_SKIP_ROWS, 2)
            .with(GL11.GL_PACK_SKIP_PIXELS, 3)
            .with(GL12.GL_PACK_IMAGE_HEIGHT, 32)
            .with(GL12.GL_PACK_SKIP_IMAGES, 1)
            .with(GL11.GL_PACK_SWAP_BYTES, 1)
            .with(GL11.GL_PACK_LSB_FIRST, 1);
        assertEquals(new PixelPackState(1, 64, 2, 3, 32, 1, true, true), s);
        assertFalse(s.isDefault());
    }

    @Test
    void invalidValuesIgnored() {
        assertSame(PixelPackState.DEFAULT, PixelPackState.DEFAULT.with(GL11.GL_PACK_ALIGNMENT, 3));
        assertSame(PixelPackState.DEFAULT, PixelPackState.DEFAULT.with(GL11.GL_PACK_ALIGNMENT, 0));
        assertSame(PixelPackState.DEFAULT, PixelPackState.DEFAULT.with(GL11.GL_PACK_ROW_LENGTH, -1));
        assertSame(PixelPackState.DEFAULT, PixelPackState.DEFAULT.with(GL11.GL_PACK_SKIP_ROWS, -5));
        assertSame(PixelPackState.DEFAULT, PixelPackState.DEFAULT.with(GL12.GL_PACK_IMAGE_HEIGHT, -1));
    }

    @Test
    void booleansAcceptAnyNonzero() {
        assertTrue(PixelPackState.DEFAULT.with(GL11.GL_PACK_SWAP_BYTES, 2).swapBytes());
        assertTrue(PixelPackState.DEFAULT.with(GL11.GL_PACK_LSB_FIRST, -1).lsbFirst());
        PixelPackState on = PixelPackState.DEFAULT.with(GL11.GL_PACK_SWAP_BYTES, 1);
        assertFalse(on.with(GL11.GL_PACK_SWAP_BYTES, 0).swapBytes());
    }

    @Test
    void unpackAndUnknownPnamesIgnored() {
        assertSame(PixelPackState.DEFAULT, PixelPackState.DEFAULT.with(GL11.GL_UNPACK_ALIGNMENT, 1));
        assertSame(PixelPackState.DEFAULT, PixelPackState.DEFAULT.with(GL11.GL_UNPACK_ROW_LENGTH, 16));
        assertSame(PixelPackState.DEFAULT, PixelPackState.DEFAULT.with(0x1234, 1));
    }
}
