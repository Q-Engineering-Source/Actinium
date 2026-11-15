package com.gtnewhorizons.angelica.glsm.streaming;

import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import net.minecraft.client.renderer.Tessellator;

import java.util.Arrays;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutLong;

/**
 * Entity, block entity and item ids of the vertices in the main Tessellator batch.
 *
 * <p>Ids only change at entity boundaries, so the batch is stored as runs: a start vertex plus the packed
 * ids that apply from there on. Runs are merged when a new id equals the previous one, which keeps the
 * expanded per-vertex array cheap to write at draw time.</p>
 */
public final class TessellatorEntityIds {

    private static ITessellatorData owner;
    private static int runCount;
    private static int[] runStarts = new int[8];
    private static long[] runIds = new long[8];

    private TessellatorEntityIds() {}

    /** Records that the packed ids changed while {@code previous} covered the vertices before this point. */
    public static void onIdsChanged(long previous, long next) {
        final ITessellatorData tess = (ITessellatorData) Tessellator.getInstance();
        if (tess == null || !tess.isDrawing() || tess.getVertexCount() == 0) return;

        final int start = tess.getVertexCount();
        if (owner != tess) {
            owner = tess;
            runCount = 0;
        }
        if (runCount == 0) addRun(0, previous);
        if (runStarts[runCount - 1] == start) {
            runIds[runCount - 1] = next;
            if (runCount > 1 && runIds[runCount - 2] == next) runCount--;
        } else {
            addRun(start, next);
        }
    }

    /** Drops the tracked runs when the tessellator resets. */
    public static void onReset(ITessellatorData tess) {
        if (tess != owner) return;
        owner = null;
        runCount = 0;
    }

    /** @return how many id runs cover the first {@code vertexCount} vertices */
    public static int runsFor(ITessellatorData tess, int vertexCount) {
        if (tess != owner) return 0;
        int runs = runCount;
        while (runs > 0 && runStarts[runs - 1] >= vertexCount) runs--;
        return runs;
    }

    static long firstRunIds() {
        return runIds[0];
    }

    /** Writes the packed ids of every vertex into {@code dst}, using {@code stride} bytes per vertex. */
    public static void write(long dst, int stride, int vertexCount, int runs) {
        for (int run = 0; run < runs; run++) {
            final int end = run + 1 < runs ? runStarts[run + 1] : vertexCount;
            final long ids = runIds[run];
            for (int vertex = runStarts[run]; vertex < end; vertex++) {
                memPutLong(dst + (long) vertex * stride, ids);
            }
        }
    }

    private static void addRun(int start, long ids) {
        if (runCount == runStarts.length) {
            runStarts = Arrays.copyOf(runStarts, runCount * 2);
            runIds = Arrays.copyOf(runIds, runCount * 2);
        }
        runStarts[runCount] = start;
        runIds[runCount] = ids;
        runCount++;
    }
}
