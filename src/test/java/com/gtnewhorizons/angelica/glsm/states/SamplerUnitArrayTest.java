package com.gtnewhorizons.angelica.glsm.states;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplerUnitArrayTest {

    @Test
    void unitsBelowTheDriverLimitAreTracked() {
        final SamplerUnitArray units = new SamplerUnitArray();

        assertTrue(units.set(0, 7));
        assertTrue(units.set(15, 9));
        assertTrue(units.size() >= 16, "the backing array always covers at least the GL minimum of 16 units");
        assertEquals(7, units.get(0));
        assertEquals(9, units.get(15));
        assertEquals(0, units.get(1));
    }

    @Test
    void outOfRangeUnitsAreRejected() {
        final SamplerUnitArray units = new SamplerUnitArray();
        assertEquals(0, units.size(), "nothing is allocated before the first bind");

        assertFalse(units.set(-1, 3));

        assertTrue(units.set(0, 3));
        final int size = units.size();
        assertTrue(size >= 16);
        assertFalse(units.set(size, 3), "a unit at or past the tracked size must be refused");
        assertEquals(0, units.get(-1));
        assertEquals(0, units.get(size));
    }

    @Test
    void deletingASamplerUnbindsItEverywhere() {
        final SamplerUnitArray units = new SamplerUnitArray();
        units.set(2, 5);
        units.set(7, 5);
        units.set(8, 6);

        units.unbindEverywhere(5);

        assertEquals(0, units.get(2));
        assertEquals(0, units.get(7));
        assertEquals(6, units.get(8), "other samplers keep their units");
    }
}
