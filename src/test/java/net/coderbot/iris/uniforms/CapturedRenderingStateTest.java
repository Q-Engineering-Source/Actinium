package net.coderbot.iris.uniforms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapturedRenderingStateTest {

    private final CapturedRenderingState state = CapturedRenderingState.INSTANCE;

    @AfterEach
    void resetBlockEntity() {
        state.setCurrentBlockEntity(0);
    }

    @Test
    void blockEntityScopeRestoresTheOuterId() {
        state.setCurrentBlockEntity(7);
        state.pushCurrentBlockEntity();
        state.setCurrentBlockEntity(19);

        state.popCurrentBlockEntity();
        assertEquals(7, state.getCurrentRenderedBlockEntity());

        state.pushCurrentBlockEntity();
        state.setCurrentBlockEntity(23);
        state.popCurrentBlockEntity();
        assertEquals(7, state.getCurrentRenderedBlockEntity());
    }

    @Test
    void unbalancedPopFailsImmediately() {
        assertThrows(NoSuchElementException.class, state::popCurrentBlockEntity);
    }
}
