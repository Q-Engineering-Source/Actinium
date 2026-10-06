package dhj.embeddedt.embeddium.impl.common.util;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class NativeBuffer {
    private static final Logger LOGGER = LogManager.getLogger(NativeBuffer.class);

    private static final ReferenceQueue<NativeBuffer> RECLAIM_QUEUE = new ReferenceQueue<>();
    private static final Reference2ReferenceMap<Reference<NativeBuffer>, BufferReference> ACTIVE_BUFFERS =
            Reference2ReferenceMaps.synchronize(new Reference2ReferenceOpenHashMap<>());

    private static final AtomicLong ALLOCATED = new AtomicLong();

    // Re-assigned by ensureCapacity when the native block is swapped for a larger one.
    private BufferReference ref;
    // Keeps the mapping between this buffer and its current native block discoverable when the
    // block is swapped out by ensureCapacity, so the reclaim queue always tracks the live block.
    private final PhantomReference<NativeBuffer> reclaimHandle;

    public static boolean ENABLE_MEMORY_TRACING = false;

    public NativeBuffer(int capacity) {
        this.ref = allocate(capacity);
        this.reclaimHandle = new PhantomReference<>(this, RECLAIM_QUEUE);

        ACTIVE_BUFFERS.put(this.reclaimHandle, this.ref);
    }

    public static NativeBuffer copy(ByteBuffer src) {
        NativeBuffer dst = new NativeBuffer(src.remaining());
        MemoryUtilities.memCopy(MemoryUtilities.memAddress(src), dst.ref.address, src.remaining());
        return dst;
    }

    public ByteBuffer getDirectBuffer() {
        this.ref.checkFreed();

        return MemoryUtilities.memByteBuffer(this.ref.address, this.ref.length);
    }

    public void free() {
        try {
            deallocate(this.ref);
            // Explicitly freed mesh uploads need no later GC notification or retained trace.
            ACTIVE_BUFFERS.remove(this.reclaimHandle);
            this.reclaimHandle.clear();
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Ensures the buffer can hold at least the given number of bytes, copying the existing
     * contents into the enlarged block. The buffer is never shrunk: when the current capacity
     * already covers the request this is a no-op, so scratch buffers can be reused across work
     * units without paying for repeated allocation and copy cycles.
     */
    public void ensureCapacity(int capacity) {
        this.ref.checkFreed();

        if (capacity <= this.ref.length) {
            return;
        }

        try {
            BufferReference replacement = allocate(capacity);
            BufferReference previous = this.ref;
            MemoryUtilities.memCopy(previous.address, replacement.address, previous.length);
            deallocate(previous);
            this.ref = replacement;

            // The reclaim queue entry must follow the live block throughout replacement.
            if (ACTIVE_BUFFERS.replace(this.reclaimHandle, replacement) != previous) {
                throw new IllegalStateException("NativeBuffer reclaim entry went missing while growing the buffer");
            }
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public int getLength() {
        return this.ref.length;
    }

    public static void reclaim(boolean forceGc) {
        if (forceGc) {
            System.gc();
        }

        Reference<? extends NativeBuffer> ref;

        while ((ref = RECLAIM_QUEUE.poll()) != null) {
            BufferReference buf = ACTIVE_BUFFERS.remove(ref);

            if (buf.freed) {
                continue;
            }

            deallocate(buf);

            if (buf.allocationSite != null) {
                LOGGER.warn("Reclaimed {} bytes at address {} that were leaked from allocation site:\n{}",
                        buf.length, buf.address,
                        Arrays.stream(buf.allocationSite)
                                .map(StackTraceElement::toString)
                                .collect(Collectors.joining("\n")));
            } else {
                LOGGER.warn("Reclaimed {} bytes at address {} that were leaked from an unknown location (logging is disabled)",
                        buf.length, buf.address);
            }
        }
    }

    public static long getTotalAllocated() {
        return ALLOCATED.get();
    }

    /** Number of allocations still registered for leak recovery, including live scratch buffers. */
    public static int getTrackedBufferCount() {
        return ACTIVE_BUFFERS.size();
    }

    private static StackTraceElement[] getStackTrace() {
        return ENABLE_MEMORY_TRACING ? Thread.currentThread().getStackTrace() : null;
    }

    private static final int MAX_ALLOCATION_ATTEMPTS = 3;

    private static BufferReference allocate(int bytes) {
        long address = 0;
        int attempts = 0;

        while (++attempts <= MAX_ALLOCATION_ATTEMPTS) {
            address = MemoryUtilities.nmemAlloc(bytes);

            if (address != MemoryUtilities.NULL) {
                break;
            }

            LOGGER.error("EMERGENCY: Tried to allocate {} bytes but the allocator reports failure", bytes);
            LOGGER.error("EMERGENCY: ... Attempting to force a garbage collection cycle (attempt {}/{})", attempts, MAX_ALLOCATION_ATTEMPTS);

            // If memory allocation fails, force a garbage collection
            reclaim(true);
        }

        if (address == MemoryUtilities.NULL) {
            throw new OutOfMemoryError("Couldn't allocate %s bytes after %s attempts".formatted(bytes, attempts));
        }

        StackTraceElement[] stackTrace = getStackTrace();

        BufferReference ref = new BufferReference(address, bytes, stackTrace);
        ALLOCATED.addAndGet(ref.length);

        return ref;
    }

    private static void deallocate(BufferReference ref) {
        ref.checkFreed();
        ref.freed = true;

        MemoryUtilities.nmemFree(ref.address);

        ALLOCATED.addAndGet(-ref.length);
    }

    private static class BufferReference {
        public final long address;
        public final int length;

        public final StackTraceElement[] allocationSite;

        public boolean freed;

        private BufferReference(long address, int length, StackTraceElement[] allocationSite) {
            this.address = address;
            this.length = length;
            this.allocationSite = allocationSite;
        }

        private void checkFreed() {
            if (this.freed) {
                throw new IllegalStateException("Buffer has been deleted");
            }
        }
    }
}
