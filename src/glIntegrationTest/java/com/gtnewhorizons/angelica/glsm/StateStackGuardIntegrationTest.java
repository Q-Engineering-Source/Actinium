package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateStackGuardIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void attribPopUnderflowFailsBeforeChangingDepth() {
        final int depth = GLStateManager.getAttribDepth();
        assertThrows(IllegalStateException.class, GLStateManager::popState);
        assertEquals(depth, GLStateManager.getAttribDepth());
    }
}
