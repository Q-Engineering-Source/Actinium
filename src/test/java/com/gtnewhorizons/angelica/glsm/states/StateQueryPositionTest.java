package com.gtnewhorizons.angelica.glsm.states;

import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateQueryPositionTest {
    @Test
    void colorQueryStartsAtTheCallerPosition() {
        final FloatBuffer values = FloatBuffer.allocate(7);
        values.put(0, -9).position(3);
        new Color4(0.25f, 0.5f, 0.75f, 1).get(values);
        assertEquals(-9, values.get(0));
        assertEquals(0.25f, values.get(3));
        assertEquals(0.5f, values.get(4));
        assertEquals(0.75f, values.get(5));
        assertEquals(1, values.get(6));
        assertEquals(3, values.position());
    }

    @Test
    void viewportQueryStartsAtTheCallerPosition() {
        final IntBuffer values = IntBuffer.allocate(6);
        values.put(0, -9).position(2);
        final ViewportState viewport = new ViewportState();
        viewport.setViewPort(-3, 7, 640, 480);
        viewport.get(values);
        assertEquals(-9, values.get(0));
        assertEquals(-3, values.get(2));
        assertEquals(7, values.get(3));
        assertEquals(640, values.get(4));
        assertEquals(480, values.get(5));
        assertEquals(2, values.position());
    }
}
