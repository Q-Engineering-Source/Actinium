package com.dhj.actinium.render.voxelization;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutInt;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memRealloc;

/**
 * Collects the vertex ranges of one shadow-pass voxelization walk, grouped per render region.
 *
 * <p>Each region contributes one entry per maximal vertex run: a {@code uvec2} of the vertex range's
 * first global vertex index and the region-relative vertex index that run starts at. Adjacent runs
 * are folded into the previous entry, and consecutive regions never merge, so a region's entries form
 * a monotone sequence the compute shader can binary-search for a global vertex index.</p>
 *
 * <p>Written by {@link ShadowVoxelizer} through {@link ShadowVoxelizer.Sink} and read back by
 * {@link SdlShadowVoxelizationSink} when it uploads the table as an SSBO.</p>
 */
public final class VoxelRangeTable {
    private static final int ENTRY_BYTES = 8;

    private ByteBuffer entries;
    private long entriesAddr;
    private int entryCount;
    private int runEnd;

    private int regionCount;
    private int[] regVbuf = new int[16];
    private float[] regX = new float[16];
    private float[] regY = new float[16];
    private float[] regZ = new float[16];
    private int[] regBase = new int[16];
    private int[] regCount = new int[16];
    private int[] regTotal = new int[16];

    /**
     * Starts a region, making it the target of every following {@link #addRange(int, int)}.
     *
     * @param vbufGlId vertex buffer holding the region's mesh data
     * @param x region origin relative to the camera
     * @param y region origin relative to the camera
     * @param z region origin relative to the camera
     */
    public void beginRegion(int vbufGlId, float x, float y, float z) {
        final int g = regionCount++;
        if (g == regVbuf.length) {
            final int n = g << 1;
            regVbuf = Arrays.copyOf(regVbuf, n);
            regX = Arrays.copyOf(regX, n);
            regY = Arrays.copyOf(regY, n);
            regZ = Arrays.copyOf(regZ, n);
            regBase = Arrays.copyOf(regBase, n);
            regCount = Arrays.copyOf(regCount, n);
            regTotal = Arrays.copyOf(regTotal, n);
        }
        regVbuf[g] = vbufGlId;
        regX[g] = x;
        regY[g] = y;
        regZ[g] = z;
        regBase[g] = entryCount;
        regCount[g] = 0;
        regTotal[g] = 0;
    }

    /**
     * Adds a vertex run to the current region, folding it into the previous entry when it continues
     * that run's vertex range.
     */
    public void addRange(int vertexStart, int vertexCount) {
        final int g = regionCount - 1;
        if (regCount[g] > 0 && vertexStart == runEnd) {
            regTotal[g] += vertexCount;
            runEnd += vertexCount;
            return;
        }
        if (entries == null) {
            entries = memAlloc(4096);
            entriesAddr = memAddress0(entries);
        } else if ((entryCount + 1) * ENTRY_BYTES > entries.capacity()) {
            entries = memRealloc(entries, entries.capacity() << 1);
            entriesAddr = memAddress0(entries);
        }
        final long p = entriesAddr + (long) entryCount * ENTRY_BYTES;
        memPutInt(p, vertexStart);
        memPutInt(p + 4, regTotal[g]);
        entryCount++;
        regCount[g]++;
        regTotal[g] += vertexCount;
        runEnd = vertexStart + vertexCount;
    }

    /** {@return the packed entries, one {@code uvec2} each, for upload as an SSBO} */
    public ByteBuffer entries() {
        entries.position(0).limit(entryCount * ENTRY_BYTES);
        return entries;
    }

    public int entryCount() {
        return entryCount;
    }

    public int regionCount() {
        return regionCount;
    }

    public int regionVbuf(int i) {
        return regVbuf[i];
    }

    public float regionX(int i) {
        return regX[i];
    }

    public float regionY(int i) {
        return regY[i];
    }

    public float regionZ(int i) {
        return regZ[i];
    }

    /** {@return the index of this region's first entry in {@link #entries()}} */
    public int regionRangeBase(int i) {
        return regBase[i];
    }

    /** {@return how many entries this region owns} */
    public int regionRangeCount(int i) {
        return regCount[i];
    }

    /** {@return the number of vertices this region's runs cover, i.e. its dispatch size} */
    public int regionVertexTotal(int i) {
        return regTotal[i];
    }

    /** Drops the recorded regions and entries but keeps the buffer for reuse. */
    public void clear() {
        regionCount = 0;
        entryCount = 0;
    }

    /** Releases the backing buffer. The table is reusable afterwards. */
    public void free() {
        if (entries != null) {
            memFree(entries);
            entries = null;
        }
    }
}
