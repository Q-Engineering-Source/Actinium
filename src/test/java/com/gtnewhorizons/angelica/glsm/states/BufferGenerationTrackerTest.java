package com.gtnewhorizons.angelica.glsm.states;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferGenerationTrackerTest {

    @Test
    void sameNumericBufferNameHasIndependentLifetimesInSeparateShareGroups() {
        final long firstGroup = 9_105_001L;
        final long secondGroup = 9_105_002L;
        final int sameName = 37;
        final BufferGenerationTracker firstTracker = new BufferGenerationTracker(firstGroup);
        final BufferGenerationTracker secondTracker = new BufferGenerationTracker(secondGroup);
        final long firstGeneration = firstTracker.register(sameName);
        final long secondGeneration = secondTracker.register(sameName);

        assertEquals(firstGeneration, secondGeneration);
        assertNotEquals(firstTracker.shareGroupId(), secondTracker.shareGroupId());
        firstTracker.delete(sameName);

        assertFalse(firstTracker.isCurrent(sameName, firstGeneration));
        assertTrue(secondTracker.isCurrent(sameName, secondGeneration));

        final long reusedFirstNameGeneration = firstTracker.register(sameName);
        assertNotEquals(firstGeneration, reusedFirstNameGeneration);
        assertFalse(firstTracker.isCurrent(sameName, firstGeneration));
        assertTrue(firstTracker.isCurrent(sameName, reusedFirstNameGeneration));
        assertTrue(secondTracker.isCurrent(sameName, secondGeneration));
    }
}
