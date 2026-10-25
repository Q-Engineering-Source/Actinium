package dhj.embeddedt.embeddium.impl.render.chunk;

import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;

/**
 * The chunk render backend takes care of managing the graphics resource state of chunk render containers. This includes
 * the handling of uploading their data to the graphics card and rendering responsibilities.
 */
public interface ChunkRenderer {
    /**
     * Renders the given chunk render list to the active framebuffer.
     *
     * @param occlusionCamera The camera context that should be used for block face culling
     * @param camera      The camera context containing chunk offsets for the current render
     */
    void render(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists,
                TerrainRenderPass pass, CameraTransform occlusionCamera, CameraTransform camera);

    /**
     * Deletes this render backend and any resources attached to it.
     */
    void delete(CommandList commandList);

    RenderPassConfiguration<?> getRenderPassConfiguration();
}
