package dhj.embeddedt.embeddium.impl.render.chunk.vertex.builder;

import dhj.embeddedt.embeddium.impl.common.util.NativeBuffer;
import dhj.embeddedt.embeddium.impl.common.util.ScratchRetentionWindow;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import dhj.embeddedt.embeddium.impl.render.chunk.sorting.SortState;
import dhj.embeddedt.embeddium.impl.render.chunk.sorting.TranslucentQuadRecorder;
import org.jetbrains.annotations.Nullable;
import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkMeshBufferBuilder {
    private static final AtomicLong RETAINED_SCRATCH_BYTES = new AtomicLong();
    private static final AtomicLong PEAK_RETAINED_SCRATCH_BYTES = new AtomicLong();
    private static final AtomicLong RETAINED_SCRATCH_BUFFERS = new AtomicLong();
    private static final AtomicLong RETENTION_RELEASED_BYTES = new AtomicLong();
    private static final AtomicLong RETENTION_RELEASE_COUNT = new AtomicLong();

    private final ChunkVertexEncoder encoder;
    private final int stride;

    private final int initialCapacity;
    private final TranslucentQuadRecorder analyzer;
    private final ScratchRetentionWindow scratchRetention = new ScratchRetentionWindow();

    // Off-heap scratch storage retained across build tasks; only destroy() hands the block back
    // to the OS, while start() just resets the write position so the next task reuses the capacity.
    @Nullable
    private NativeBuffer buffer;
    // Cached view over the native block, refreshed whenever the block is allocated or enlarged.
    // Its position stays at zero so absolute address computation remains valid.
    @Nullable
    private ByteBuffer directBuffer;
    private int count;
    private int sectionIndex;

    public static ScratchMemoryStats getScratchMemoryStats() {
        return new ScratchMemoryStats(RETAINED_SCRATCH_BYTES.get(), PEAK_RETAINED_SCRATCH_BYTES.get(),
                RETAINED_SCRATCH_BUFFERS.get(), RETENTION_RELEASED_BYTES.get(), RETENTION_RELEASE_COUNT.get());
    }

    public record ScratchMemoryStats(long retainedBytes, long peakRetainedBytes, long retainedBuffers,
                                    long retentionReleasedBytes, long retentionReleaseCount) {
    }

    public ChunkMeshBufferBuilder(ChunkVertexEncoder encoder, int stride, int initialCapacity, boolean collectSortState) {
        this.encoder = encoder;
        this.stride = stride;

        this.buffer = null;
        this.directBuffer = null;

        this.initialCapacity = initialCapacity;

        this.analyzer = collectSortState ? new TranslucentQuadRecorder() : null;
    }

    public void push(ChunkVertexEncoder.Vertex[] vertices, Material material) {
        if (this.encoder.supportsBilinearCorrection()) {
            postprocessVertices(vertices);
        }

        var vertexStart = this.count * this.stride;
        var vertexSize = vertices.length * this.stride;

        if (this.directBuffer == null || vertexStart + vertexSize >= this.directBuffer.capacity()) {
            this.grow(vertexSize);
        }

        long ptr = MemoryUtilities.memAddress(this.directBuffer, vertexStart);

        if (this.analyzer != null) {
            for (ChunkVertexEncoder.Vertex vertex : vertices) {
                this.analyzer.capture(vertex);
            }
        }

        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            ptr = this.encoder.write(ptr, material, vertex, this.sectionIndex);
        }

        this.count += vertices.length;
    }

    static void postprocessVertices(ChunkVertexEncoder.Vertex[] vertices) {
        if (vertices.length != 4) {
            for (var vertex : vertices) {
                vertex.rdhFactor = 0;
            }
            return;
        }

        int color0 = vertices[0].color;
        int color1 = vertices[1].color;
        int color2 = vertices[2].color;
        int color3 = vertices[3].color;
        int factor = encodeBilinearCorrection(color3, color1, color0, color2, 0)
                | encodeBilinearCorrection(color3, color1, color0, color2, 8) << 8
                | encodeBilinearCorrection(color3, color1, color0, color2, 16) << 16
                | encodeBilinearCorrection(color3, color1, color0, color2, 24) << 24;

        for (var vertex : vertices) {
            vertex.rdhFactor = factor;
        }
    }

    static int encodeBilinearCorrection(int positive0, int positive1, int negative0, int negative1, int shift) {
        int factor = ((positive0 >> shift) & 0xFF) + ((positive1 >> shift) & 0xFF)
                - ((negative0 >> shift) & 0xFF) - ((negative1 >> shift) & 0xFF);
        return Math.round(factor * (127.0F / 510.0F)) & 0xFF;
    }

    private void grow(int bytesNeeded) {
        int currentCapacity = this.directBuffer != null ? this.directBuffer.capacity() : 0;
        int newCapacity = Math.max(currentCapacity * 2, currentCapacity + bytesNeeded);
        newCapacity = Math.max(newCapacity, this.initialCapacity);

        long oldCapacity = this.buffer != null ? this.buffer.getLength() : 0;

        if (this.buffer == null) {
            this.buffer = new NativeBuffer(newCapacity);
        } else {
            // Retains the previously written bytes; ensureCapacity never shrinks.
            this.buffer.ensureCapacity(newCapacity);
        }

        this.directBuffer = this.buffer.getDirectBuffer();
        recordCapacityChange(oldCapacity, this.buffer.getLength());
    }

    public void start(int sectionIndex) {
        this.count = 0;
        this.sectionIndex = sectionIndex;
        if(this.analyzer != null) {
            this.analyzer.clear();
        }
    }

    @Nullable
    public SortState getSortState() {
        return this.analyzer != null ? this.analyzer.getSortState() : null;
    }

    public void resetSortState() {
        if (this.analyzer != null) {
            this.analyzer.clear();
        }
    }

    /** Reclaims oversized native and translucent-sort scratch after sustained low-use build work. */
    public void finishTask(final boolean used) {
        if (this.analyzer != null) {
            this.analyzer.finishTask(used);
        }
        if (!ScratchRetentionWindow.isEnabled() || this.buffer == null) {
            return;
        }

        final long capacity = this.buffer.getLength();
        final long usedBytes = used ? (long) this.count * this.stride : 0;
        if (this.scratchRetention.endTask(usedBytes, capacity, this.initialCapacity)) {
            long releasedBytes = this.buffer.getLength();
            this.buffer.free();
            this.buffer = null;
            this.directBuffer = null;
            this.count = 0;
            recordCapacityChange(releasedBytes, 0);
            RETENTION_RELEASED_BYTES.addAndGet(releasedBytes);
            RETENTION_RELEASE_COUNT.incrementAndGet();
        }
    }

    /**
     * Frees the retained off-heap block. This is only invoked when the owning build context is
     * discarded (executor shutdown); per-task cleanup keeps the block allocated so the next build
     * reuses its capacity.
     */
    public void destroy() {
        if (this.buffer != null) {
            long releasedBytes = this.buffer.getLength();
            this.buffer.free();
            this.buffer = null;
            this.directBuffer = null;
            recordCapacityChange(releasedBytes, 0);
        }

        this.resetSortState();
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public ByteBuffer slice() {
        if (this.isEmpty()) {
            throw new IllegalStateException("No vertex data in buffer");
        }

        var direct = Objects.requireNonNull(this.directBuffer, "Buffer has not been allocated");
        direct.limit(this.stride * this.count);

        return direct.slice();
    }

    public int count() {
        return this.count;
    }

    private static void recordCapacityChange(long previousCapacity, long newCapacity) {
        if (previousCapacity == newCapacity) {
            return;
        }

        long retainedBytes = RETAINED_SCRATCH_BYTES.addAndGet(newCapacity - previousCapacity);
        if (newCapacity > previousCapacity) {
            updatePeak(PEAK_RETAINED_SCRATCH_BYTES, retainedBytes);
            if (previousCapacity == 0) {
                RETAINED_SCRATCH_BUFFERS.incrementAndGet();
            }
        } else if (newCapacity == 0) {
            RETAINED_SCRATCH_BUFFERS.decrementAndGet();
        }
    }

    private static void updatePeak(AtomicLong peak, long candidate) {
        long current = peak.get();
        while (candidate > current && !peak.compareAndSet(current, candidate)) {
            current = peak.get();
        }
    }
}
