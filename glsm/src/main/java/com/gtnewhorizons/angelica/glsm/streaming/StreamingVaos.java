package com.gtnewhorizons.angelica.glsm.streaming;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;

import java.util.Arrays;

public final class StreamingVaos {
    private static final int FORMAT_COUNT = VertexFlags.BITSET_SIZE;

    public final int[] persistentVAOs = new int[FORMAT_COUNT];
    public final int[] orphanVAOs = new int[FORMAT_COUNT];
    public final int[] extendedPersistentVAOs = new int[FORMAT_COUNT];
    public final int[] extendedOrphanVAOs = new int[FORMAT_COUNT];

    public void reset() {
        Arrays.fill(persistentVAOs, 0);
        Arrays.fill(orphanVAOs, 0);
        Arrays.fill(extendedPersistentVAOs, 0);
        Arrays.fill(extendedOrphanVAOs, 0);
    }
}
