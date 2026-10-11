package com.gtnewhorizons.angelica.glsm.streaming;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;

import java.nio.ByteBuffer;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memCopy;


/**
 * Persistent-mapped ring buffer for streaming vertex data on GL4.4+
 */
public class PersistentStreamingBuffer implements StreamingBuffer {

    public static final int DEFAULT_CAPACITY = 16 * 1024 * 1024; // 16 MB
    static final int FENCE_BATCH_SEGMENTS = 4;

    private int bufferId;
    private final int capacity;
    private final ByteBuffer mappedBuffer;
    private final long mappedAddress;
    private final ObjectArrayFIFOQueue<FencedRegion> fenceQueue = new ObjectArrayFIFOQueue<>();

    private int writePos;
    private int remaining;
    private int pendingBytes;
    private boolean fenceRequested;

    public PersistentStreamingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public PersistentStreamingBuffer(int capacity) {
        this.capacity = capacity;
        this.remaining = capacity;

        this.bufferId = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, bufferId);

        final int storageFlags = GL44.GL_MAP_PERSISTENT_BIT | GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_COHERENT_BIT;
        RenderSystem.bufferStorage(GL15.GL_ARRAY_BUFFER, capacity, storageFlags);

        final int mapFlags = GL44.GL_MAP_PERSISTENT_BIT | GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_COHERENT_BIT;
        this.mappedBuffer = GLStateManager.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0, capacity, mapFlags);

        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        if (this.mappedBuffer == null) {
            GLStateManager.glDeleteBuffers(bufferId);
            bufferId = 0;
            throw new RuntimeException("Failed to persistently map streaming buffer");
        }
        this.mappedAddress = memAddress0(mappedBuffer);
    }

    @Override
    public int upload(ByteBuffer data, int vertexStride) {
        final int dataSize = data.remaining();
        if (dataSize > capacity) {
            return -1;
        }

        reclaim();

        final int alignUnit = vertexStride * 4;
        final int alignedPos = ((writePos + alignUnit - 1) / alignUnit) * alignUnit;
        final int alignmentWaste = alignedPos - writePos;
        final int tailSpace = capacity - alignedPos;

        if (dataSize <= tailSpace) {
            // Fits at tail — just need enough remaining budget
            if (ensureRemaining(alignmentWaste + dataSize)) {
                return writeAt(alignedPos, data, dataSize, alignmentWaste, vertexStride);
            }
        } else {
            // Must wrap to start — burn remaining tail space
            final int wrapWaste = capacity - writePos;
            if (ensureRemaining(wrapWaste + dataSize)) {
                return writeAtStart(data, dataSize, wrapWaste);
            }
        }

        return -1;
    }

    /** Sync fences until {@code needed} bytes are available, or no fences remain. */
    private boolean ensureRemaining(int needed) {
        while (remaining < needed) {
            if (!syncOldest()) {
                fenceRequested = pendingBytes > 0;
                return false;
            }
        }
        return true;
    }

    private int writeAt(int offset, ByteBuffer data, int dataSize, int waste, int vertexStride) {
        memCopy(memAddress0(data) + data.position(), mappedAddress + offset, dataSize);
        // The SDL GPU backend emulates persistent-mapped buffers with CPU staging memory; it only
        // uploads the region to the GPU when notified. The OpenGL backend ignores this call.
        RENDER_BACKEND.onPersistentBufferWrite(bufferId, offset, dataSize);

        final int totalCost = waste + dataSize;
        writePos = offset + dataSize;
        remaining -= totalCost;
        pendingBytes += totalCost;
        return offset / vertexStride;
    }

    private int writeAtStart(ByteBuffer data, int dataSize, int wrapWaste) {
        memCopy(memAddress0(data) + data.position(), mappedAddress, dataSize);
        RENDER_BACKEND.onPersistentBufferWrite(bufferId, 0, dataSize);

        final int totalCost = wrapWaste + dataSize;
        writePos = dataSize;
        remaining -= totalCost;
        pendingBytes += totalCost;
        return 0;
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    public int getRemaining() { return remaining; }

    @Override
    public int getBufferId() {
        return bufferId;
    }

    @Override
    public void postDraw() {
        if (shouldCreateFence(pendingBytes, capacity, fenceRequested)) {
            createPendingFence();
        }
    }

    static boolean shouldCreateFence(int pendingBytes, int capacity, boolean fenceRequested) {
        return pendingBytes > 0 && (fenceRequested || pendingBytes >= Math.max(1, capacity / FENCE_BATCH_SEGMENTS));
    }

    private void createPendingFence() {
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.STREAM_FENCE_CREATE) : 0L;
        final long fenceId = GLStateManager.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_FENCE_CREATE, perfStart);
        }
        if (fenceId == 0) {
            throw new RuntimeException("Failed to create persistent streaming fence");
        }
        fenceQueue.enqueue(new FencedRegion(new GlFence(fenceId), pendingBytes));
        if (perfDebugEnabled) {
            GLSMPerfDebug.recordFenceQueueDepth(fenceQueue.size());
        }
        pendingBytes = 0;
        fenceRequested = false;
    }

    @Override
    public void destroy() {
        while (!fenceQueue.isEmpty()) {
            fenceQueue.dequeue().fence.delete();
        }
        pendingBytes = 0;
        fenceRequested = false;
        if (bufferId != 0) {
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, bufferId);
            GLStateManager.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glDeleteBuffers(bufferId);
            bufferId = 0;
        }
    }

    private void reclaim() {
        while (!fenceQueue.isEmpty()) {
            final FencedRegion region = fenceQueue.first();
            final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
            final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.STREAM_FENCE_POLL) : 0L;
            final boolean completed = region.fence.isCompleted();
            if (perfDebugEnabled) {
                GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_FENCE_POLL, perfStart);
            }
            if (!completed) break;
            region.fence.delete();
            fenceQueue.dequeue();
            remaining += region.bytes;
            if (perfDebugEnabled) {
                GLSMPerfDebug.recordFenceReclaim(region.bytes);
            }
        }
    }

    /** Sync on oldest fence. Returns true if bytes were reclaimed. */
    private boolean syncOldest() {
        if (fenceQueue.isEmpty()) return false;
        final FencedRegion region = fenceQueue.first();
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.STREAM_FENCE_WAIT) : 0L;
        region.fence.sync();
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_FENCE_WAIT, perfStart);
        }
        region.fence.delete();
        fenceQueue.dequeue();
        remaining += region.bytes;
        if (perfDebugEnabled) {
            GLSMPerfDebug.recordFenceReclaim(region.bytes);
        }
        reclaim(); // opportunistically reclaim any additional completed fences
        return true;
    }

    // Package-private accessors for testing
    ByteBuffer getMappedBuffer() { return mappedBuffer; }

    private record FencedRegion(GlFence fence, int bytes) {}
}
