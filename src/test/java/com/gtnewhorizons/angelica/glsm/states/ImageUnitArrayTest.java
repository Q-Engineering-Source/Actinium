package com.gtnewhorizons.angelica.glsm.states;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageUnitArrayTest {

    @Test
    void lazyAllocationOnFirstAccess() {
        ImageUnitArray array = new ImageUnitArray();
        assertEquals(0, array.size(), "no storage before first access");
        ImageUnitBinding first = array.get(0);
        assertTrue(array.size() >= 8, "allocation covers at least the 8 guaranteed units");
        assertSame(first, array.get(0), "repeated access returns the same binding object");
    }

    @Test
    void bindingsAreIndependentInstances() {
        ImageUnitArray array = new ImageUnitArray();
        array.get(0).setBinding(7, 1, true, 2, 0, 0);
        assertNotSame(array.get(0), array.get(1));
        assertEquals(7, array.get(0).getTexture());
        assertEquals(1, array.get(0).getLevel());
        assertTrue(array.get(0).isLayered());
        assertEquals(0, array.get(1).getTexture(), "neighbouring unit stays at defaults");
    }

    @Test
    void outOfRangeUnitsReturnNull() {
        ImageUnitArray array = new ImageUnitArray();
        assertNull(array.get(-1));
        array.get(0);
        assertNull(array.get(array.size()), "units beyond the allocated range report null");
    }

    @Test
    void bindingCopyAndSameAs() {
        ImageUnitBinding a = new ImageUnitBinding();
        a.setBinding(3, 2, false, 1, 4, 5);
        ImageUnitBinding b = a.copy();
        assertNotSame(a, b);
        assertTrue(a.sameAs(b));
        b.setBinding(3, 2, true, 1, 4, 5);
        assertFalse(a.sameAs(b), "layered flag difference must be observed");
        a.set(b);
        assertTrue(a.sameAs(b));
    }
}
