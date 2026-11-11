package com.dhj.actinium.render.terrain.voxelization;

import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;

/** Receives the vertex ranges of one complete terrain pass before compute dispatch. */
interface ShadowVoxelizationSink {
    /** Selects the region VBO and camera-relative offset; returns false when resources are absent. */
    boolean region(RenderRegion region, float offsetX, float offsetY, float offsetZ);

    /** Appends a contiguous vertex span from the selected region. */
    void range(int vertexOffset, int vertexCount);

    /** Uploads the collected ranges and completes their compute encoder. */
    void flush();

    /** Clears the pass while retaining reusable storage. */
    void discard();

    /** Frees the GPU and native storage owned by this sink. */
    void delete();
}
