package com.gtnewhorizons.angelica.glsm.streaming;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;

import java.util.Arrays;

/** Stores streaming-drawer VAO names for one native GL context. */
public final class StreamingVaos {

    private static final int FORMAT_COUNT = VertexFlags.BITSET_SIZE;

    /** VAOs that capture the persistent mapped streaming buffer for each vertex format. */
    public final int[] persistentVAOs = new int[FORMAT_COUNT];

    /** VAOs that capture the orphaned streaming buffer for each vertex format. */
    public final int[] orphanVAOs = new int[FORMAT_COUNT];

    /** Drops names when the native context lifetime that owned them ends. */
    public void clear() {
        Arrays.fill(persistentVAOs, 0);
        Arrays.fill(orphanVAOs, 0);
    }
}
