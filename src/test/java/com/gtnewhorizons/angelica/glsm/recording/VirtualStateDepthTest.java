package com.gtnewhorizons.angelica.glsm.recording;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualStateDepthTest {

    @Test
    void nestedRecordedScopesBalanceWithoutChangingTheLiveStack() {
        final VirtualStateDepth scopes = new VirtualStateDepth(2);

        scopes.push();
        scopes.push();
        assertEquals(2, scopes.depth());
        assertTrue(scopes.popIfPending());
        assertEquals(1, scopes.depth());
        assertTrue(scopes.popIfPending());
        assertEquals(0, scopes.depth());
        assertFalse(scopes.popIfPending());
    }

    @Test
    void nestedCompilationRestoresTheParentVirtualDepthAndResetClearsIt() {
        final VirtualStateDepth scopes = new VirtualStateDepth(3);
        scopes.push();
        final int parentDepth = scopes.depth();
        scopes.push();

        scopes.clear();
        assertEquals(0, scopes.depth());
        scopes.restore(parentDepth);
        assertEquals(1, scopes.depth());
        scopes.clear();
        assertEquals(0, scopes.depth());
    }

    @Test
    void aRecordedStateScopeFailsFastAtTheConfiguredLimit() {
        final VirtualStateDepth scopes = new VirtualStateDepth(1);
        scopes.push();

        assertThrows(IllegalStateException.class, scopes::push);
        assertEquals(1, scopes.depth());
    }
}
