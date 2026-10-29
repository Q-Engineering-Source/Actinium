package dhj.embeddedt.embeddium.impl.render.chunk;

import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkTaskOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobResult;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds completed worker results until the render thread uploads or discards them, while accounting for
 * the native buffers whose ownership moves with each queued result.
 */
final class ChunkBuildResultQueue {
    private final ConcurrentLinkedDeque<ChunkJobResult<? extends ChunkTaskOutput>> results = new ConcurrentLinkedDeque<>();
    private final AtomicLong resultCount = new AtomicLong();
    private final AtomicLong nativeBytes = new AtomicLong();
    private final AtomicLong peakResultCount = new AtomicLong();
    private final AtomicLong peakNativeBytes = new AtomicLong();

    void add(ChunkJobResult<? extends ChunkTaskOutput> result) {
        long resultBytes = nativeBytes(result);
        // Publish counters before the result so a concurrent consumer cannot decrement an uncounted entry.
        // A perf snapshot can briefly include this in-flight publication, but the settled counters are exact.
        long queuedCount = this.resultCount.incrementAndGet();
        long queuedBytes = this.nativeBytes.addAndGet(resultBytes);
        updatePeak(this.peakResultCount, queuedCount);
        updatePeak(this.peakNativeBytes, queuedBytes);
        this.results.addLast(result);
    }

    ChunkJobResult<? extends ChunkTaskOutput> poll() {
        ChunkJobResult<? extends ChunkTaskOutput> result = this.results.pollFirst();
        if (result != null) {
            // Result buffers remain owned by this queue until poll returns them to the render-thread caller.
            this.nativeBytes.addAndGet(-nativeBytes(result));
            this.resultCount.decrementAndGet();
        }
        return result;
    }

    /**
     * Returns an atomic-counter snapshot. During an in-progress enqueue or poll it can briefly include a result
     * whose deque publication/removal has not completed; once producers and consumers settle, it is exact.
     */
    MemoryStats getMemoryStats() {
        return new MemoryStats(this.resultCount.get(), this.nativeBytes.get(), this.peakResultCount.get(), this.peakNativeBytes.get());
    }

    private static long nativeBytes(ChunkJobResult<? extends ChunkTaskOutput> result) {
        if (result instanceof ChunkJobResult.Success<? extends ChunkTaskOutput> success) {
            return success.output().getNativeMemoryBytes();
        }
        return 0;
    }

    private static void updatePeak(AtomicLong peak, long candidate) {
        long current = peak.get();
        while (candidate > current && !peak.compareAndSet(current, candidate)) {
            current = peak.get();
        }
    }

    record MemoryStats(long resultCount, long nativeBytes, long peakResultCount, long peakNativeBytes) {
    }
}
