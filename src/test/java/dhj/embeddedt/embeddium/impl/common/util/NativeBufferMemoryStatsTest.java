package dhj.embeddedt.embeddium.impl.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBufferMemoryStatsTest {
    @Test
    void accountsAllocationResizeAndExplicitFreeExactlyOnce() {
        NativeBuffer.MemoryStats before = NativeBuffer.getMemoryStats();
        NativeBuffer buffer = new NativeBuffer(16);
        boolean freed = false;

        try {
            NativeBuffer.MemoryStats allocated = NativeBuffer.getMemoryStats();
            assertEquals(before.liveBytes() + 16, allocated.liveBytes());
            assertEquals(before.liveBlocks() + 1, allocated.liveBlocks());
            assertEquals(before.allocationCount() + 1, allocated.allocationCount());
            assertEquals(before.trackedBuffers() + 1, allocated.trackedBuffers());

            buffer.ensureCapacity(64);

            NativeBuffer.MemoryStats resized = NativeBuffer.getMemoryStats();
            assertEquals(before.liveBytes() + 64, resized.liveBytes());
            assertEquals(before.liveBlocks() + 1, resized.liveBlocks());
            assertEquals(before.allocationCount() + 2, resized.allocationCount());
            assertEquals(before.trackedBuffers() + 1, resized.trackedBuffers());
            assertEquals(before.resizedFreeBytes() + 16, resized.resizedFreeBytes());
            assertEquals(before.resizedFreeCount() + 1, resized.resizedFreeCount());
            assertTrue(resized.peakLiveBytes() >= resized.liveBytes());

            buffer.free();
            freed = true;

            NativeBuffer.MemoryStats afterFree = NativeBuffer.getMemoryStats();
            assertEquals(before.liveBytes(), afterFree.liveBytes());
            assertEquals(before.liveBlocks(), afterFree.liveBlocks());
            assertEquals(before.trackedBuffers(), afterFree.trackedBuffers());
            assertEquals(before.explicitFreeBytes() + 64, afterFree.explicitFreeBytes());
            assertEquals(before.explicitFreeCount() + 1, afterFree.explicitFreeCount());
        } finally {
            if (!freed) {
                buffer.free();
            }
        }
    }
}
