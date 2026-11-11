package com.dhj.actinium.render.terrain.voxelization;

import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import dhj.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;

/** Replays shadow terrain image writes before the terrain graphics pass. */
public interface ShadowVoxelizer {
    /** Creates the render-thread owner of the reusable range and compute resources. */
    static ShadowVoxelizer create() {
        return new ShadowVoxelizerImpl();
    }

    /** Runs the active shader pack's extracted compute program for the visible terrain ranges. */
    void voxelize(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists,
                  TerrainRenderPass renderPass, GlVertexFormat format, CameraTransform occlusionCamera,
                  CameraTransform camera, boolean useBlockFaceCulling);

    /** Releases the buffers when the owning chunk renderer is deleted. */
    void delete();
}
