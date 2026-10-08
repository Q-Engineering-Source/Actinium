package dhj.embeddedt.embeddium.impl.common.util;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeBufferLifecycleTest {
    @Test
    void explicitFreeUnregistersEvenWhileTheJavaObjectsStayReachable() {
        int trackedBefore = NativeBuffer.getTrackedBufferCount();
        long allocatedBefore = NativeBuffer.getTotalAllocated();
        List<NativeBuffer> retained = new ObjectArrayList<>();
        for (int i = 0; i < 128; i++) {
            NativeBuffer buffer = new NativeBuffer(64);
            retained.add(buffer);
            buffer.free();
        }
        assertEquals(128, retained.size());
        assertEquals(trackedBefore, NativeBuffer.getTrackedBufferCount());
        assertEquals(allocatedBefore, NativeBuffer.getTotalAllocated());
        assertThrows(IllegalStateException.class, retained.getFirst()::getDirectBuffer);
        assertThrows(IllegalStateException.class, retained.getFirst()::free);
    }

    @Test
    void growthTracksOnlyTheReplacementUntilExplicitFree() {
        int trackedBefore = NativeBuffer.getTrackedBufferCount();
        long allocatedBefore = NativeBuffer.getTotalAllocated();
        NativeBuffer buffer = new NativeBuffer(4);
        try {
            buffer.getDirectBuffer().putInt(0, 0x12345678);
            buffer.ensureCapacity(64);
            assertEquals(0x12345678, buffer.getDirectBuffer().getInt(0));
            assertEquals(trackedBefore + 1, NativeBuffer.getTrackedBufferCount());
            assertEquals(allocatedBefore + 64, NativeBuffer.getTotalAllocated());
        } finally {
            buffer.free();
        }
        assertEquals(trackedBefore, NativeBuffer.getTrackedBufferCount());
        assertEquals(allocatedBefore, NativeBuffer.getTotalAllocated());
    }

    @Test
    void workerAllocationsAndMainThreadFreesKeepAccurateAccounting() throws Exception {
        long allocatedBefore = NativeBuffer.getTotalAllocated();
        int trackedBefore = NativeBuffer.getTrackedBufferCount();
        List<NativeBuffer> buffers = new ObjectArrayList<>();
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<NativeBuffer>> results = new ObjectArrayList<>();
            for (int i = 0; i < 256; i++) {
                results.add(workers.submit(() -> new NativeBuffer(128)));
            }
            for (Future<NativeBuffer> result : results) {
                buffers.add(result.get());
            }
            assertEquals(allocatedBefore + 256 * 128, NativeBuffer.getTotalAllocated());
        } finally {
            buffers.forEach(NativeBuffer::free);
        }
        assertEquals(allocatedBefore, NativeBuffer.getTotalAllocated());
        assertEquals(trackedBefore, NativeBuffer.getTrackedBufferCount());
    }
}
