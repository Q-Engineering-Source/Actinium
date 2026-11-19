package com.dhj.actinium.render.fps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class FpsReducerTest {

    @Test
    void pollsOnlyTheHardwareKeyStateBuffer() {
        assertDoesNotThrow(() -> FpsReducer.pollKeyboardActivity(() -> true, key -> {
            if (key >= 256) {
                throw new IndexOutOfBoundsException("key=" + key);
            }
            return false;
        }));
    }
}
