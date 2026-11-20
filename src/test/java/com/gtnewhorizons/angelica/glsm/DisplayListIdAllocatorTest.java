package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayListIdAllocatorTest {
    @Test
    void explicitHighNameSplitsTheFreeRangeAndCanBeReusedAfterDeletion() {
        final DisplayListIDAllocator allocator = new DisplayListIDAllocator();
        final int high = 1 << 24;
        allocator.reserve(high);
        allocator.reserve(high);

        assertEquals(1, allocator.allocRange(high - 1));
        assertEquals(high + 1, allocator.allocRange(3));
        assertTrue(allocator.isAllocated(high));

        allocator.free(high);
        assertEquals(high, allocator.allocRange(1));
    }

    @Test
    void contiguousAllocationAndDeletionCanCrossTheUpstreamSegmentBoundary() {
        final DisplayListIDAllocator allocator = new DisplayListIDAllocator();
        final int boundary = 1 << 22;
        allocator.allocRange(boundary - 33);
        allocator.reserve(boundary + 32);

        assertEquals(boundary - 32, allocator.allocRange(64));
        allocator.freeRange(boundary - 1, 2);
        assertTrue(allocator.isAllocated(boundary - 2));
        assertTrue(allocator.isAllocated(boundary + 1));
        assertEquals(boundary - 1, allocator.allocRange(2));
    }

    @Test
    void deletionClampsAtTheLargestPositiveIdAndAllocationReportsExhaustion() {
        final DisplayListIDAllocator allocator = new DisplayListIDAllocator();
        allocator.reserve(Integer.MAX_VALUE);
        assertEquals(1, allocator.allocRange(Integer.MAX_VALUE - 1));
        assertEquals(0, allocator.allocRange(1));

        allocator.freeRange(Integer.MAX_VALUE - 1, 3);
        assertTrue(allocator.isAllocated(Integer.MAX_VALUE - 2));
        assertFalse(allocator.isAllocated(Integer.MAX_VALUE));
        assertFalse(allocator.isAllocated(0));
        assertEquals(Integer.MAX_VALUE - 1, allocator.allocRange(2));
    }
}
