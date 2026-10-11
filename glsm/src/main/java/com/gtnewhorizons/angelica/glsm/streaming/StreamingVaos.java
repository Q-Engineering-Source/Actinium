package com.gtnewhorizons.angelica.glsm.streaming;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;

/**
 * Streaming drawer VAO ids of one GLSM context.
 *
 * <p>Vertex arrays are container objects: they belong to the context that created them, so the
 * drawer keeps its per-format VAOs per context instead of in static arrays (a splash or offscreen
 * target context would otherwise hand its id to another context's draw).</p>
 */
public final class StreamingVaos {
    private static final int FORMAT_COUNT = VertexFlags.BITSET_SIZE;

    public final int[] persistentVAOs = new int[FORMAT_COUNT];
    public final int[] orphanVAOs = new int[FORMAT_COUNT];
}
