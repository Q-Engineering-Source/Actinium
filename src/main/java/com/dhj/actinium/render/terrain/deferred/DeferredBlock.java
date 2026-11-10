package com.dhj.actinium.render.terrain.deferred;

import dhj.embeddedt.embeddium.api.shader.BlockRenderLayer;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import net.minecraft.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * One block of a section whose geometry has to be produced on the render thread.
 *
 * <p>A deferred mesh section is scanned on a build thread; the blocks it can keep there are meshed
 * immediately, and the blocks that need the render thread are recorded as this entry so the
 * render-thread capture can render exactly them, then hand the raw vertices back to a build
 * thread.</p>
 *
 * @param x world x coordinate of the block
 * @param y world y coordinate of the block
 * @param z world z coordinate of the block
 * @param block the block to render
 * @param passMask bitmask over the {@code net.minecraft.util.BlockRenderLayer} ordinals that still
 *                 need geometry
 * @param materialOverride shader-pack material for the layer the shader pack moved this block onto,
 *                         or {@code null} when the block renders through its vanilla layers
 */
public record DeferredBlock(int x, int y, int z, Block block, int passMask,
                            @Nullable Material materialOverride) {
    /** {@return whether the shader pack replaced the vanilla layers of this block} */
    public boolean isShaderPackOverride() {
        return this.materialOverride != null;
    }

    /**
     * {@return the material the captured vertices are encoded with}.
     *
     * <p>A block that a shader pack moved onto another layer keeps the material recorded for that
     * layer, so the finisher encodes the capture exactly as the scan classified it. Everything else,
     * including a captured fluid (which has no deferred block), is encoded with the material of the
     * layer being captured.</p>
     *
     * @param block the deferred block the vertices belong to, or {@code null} for a fluid
     * @param layerMaterial material of the layer being captured
     */
    public static Material captureMaterial(@Nullable DeferredBlock block, Material layerMaterial) {
        return block != null && block.materialOverride != null ? block.materialOverride : layerMaterial;
    }

    /**
     * {@return the material of the layer a shader pack assigned}. The pass configuration is keyed by
     * the vanilla layers, so the shader layer is mapped to its vanilla counterpart first.
     */
    public static Material shaderLayerMaterial(RenderPassConfiguration<?> configuration, BlockRenderLayer layer) {
        return configuration.getMaterialForRenderType(layer.toVanillaLayer());
    }
}
