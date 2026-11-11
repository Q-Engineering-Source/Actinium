package com.dhj.actinium.render.terrain.voxelization;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memRealloc;

/** Packs vertex starts and cumulative invocation offsets into the compute range table. */
final class VoxelRangeTable {
    private static final int ENTRY_BYTES = Integer.BYTES * 2;

    private ByteBuffer entries;
    private int entryCount;
    private int runEnd;
    private int regionCount;
    private int[] vertexBuffers = new int[16];
    private float[] offsetsX = new float[16];
    private float[] offsetsY = new float[16];
    private float[] offsetsZ = new float[16];
    private int[] rangeBases = new int[16];
    private int[] rangeCounts = new int[16];
    private int[] vertexTotals = new int[16];

    void beginRegion(int vertexBuffer, float x, float y, float z) {
        int region = this.regionCount++;
        if (region == this.vertexBuffers.length) {
            int capacity = region * 2;
            this.vertexBuffers = Arrays.copyOf(this.vertexBuffers, capacity);
            this.offsetsX = Arrays.copyOf(this.offsetsX, capacity);
            this.offsetsY = Arrays.copyOf(this.offsetsY, capacity);
            this.offsetsZ = Arrays.copyOf(this.offsetsZ, capacity);
            this.rangeBases = Arrays.copyOf(this.rangeBases, capacity);
            this.rangeCounts = Arrays.copyOf(this.rangeCounts, capacity);
            this.vertexTotals = Arrays.copyOf(this.vertexTotals, capacity);
        }
        this.vertexBuffers[region] = vertexBuffer;
        this.offsetsX[region] = x;
        this.offsetsY[region] = y;
        this.offsetsZ[region] = z;
        this.rangeBases[region] = this.entryCount;
        this.rangeCounts[region] = 0;
        this.vertexTotals[region] = 0;
    }

    void addRange(int vertexStart, int vertexCount) {
        if (this.regionCount == 0 || vertexStart < 0 || vertexCount <= 0) {
            throw new IllegalArgumentException("A vertex range requires a region and a positive vertex span");
        }
        int region = this.regionCount - 1;
        int total = this.vertexTotals[region];
        if (this.rangeCounts[region] == 0 || vertexStart != this.runEnd) {
            int bytes = Math.multiplyExact(this.entryCount + 1, ENTRY_BYTES);
            if (this.entries == null) {
                this.entries = memAlloc(Math.max(4096, bytes));
            } else if (bytes > this.entries.capacity()) {
                this.entries = memRealloc(this.entries, Math.max(bytes, Math.multiplyExact(this.entries.capacity(), 2)));
            }
            this.entries.limit(this.entries.capacity());
            int offset = this.entryCount * ENTRY_BYTES;
            this.entries.putInt(offset, vertexStart);
            this.entries.putInt(offset + Integer.BYTES, total);
            this.entryCount++;
            this.rangeCounts[region]++;
        }
        this.vertexTotals[region] = Math.addExact(total, vertexCount);
        this.runEnd = Math.addExact(vertexStart, vertexCount);
    }

    ByteBuffer entries() {
        return this.entries.position(0).limit(this.entryCount * ENTRY_BYTES);
    }

    int entryCount() { return this.entryCount; }
    int regionCount() { return this.regionCount; }
    int vertexBuffer(int region) { return this.vertexBuffers[region]; }
    float offsetX(int region) { return this.offsetsX[region]; }
    float offsetY(int region) { return this.offsetsY[region]; }
    float offsetZ(int region) { return this.offsetsZ[region]; }
    int rangeBase(int region) { return this.rangeBases[region]; }
    int rangeCount(int region) { return this.rangeCounts[region]; }
    int vertexTotal(int region) { return this.vertexTotals[region]; }

    void clear() {
        this.regionCount = 0;
        this.entryCount = 0;
    }

    void free() {
        if (this.entries != null) {
            memFree(this.entries);
            this.entries = null;
        }
        this.clear();
    }
}
