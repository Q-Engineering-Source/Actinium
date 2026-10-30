package net.coderbot.iris.celeritas;

import dhj.embeddedt.embeddium.api.shader.BlockRenderLayer;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import net.coderbot.iris.Iris;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.celeritas.vertices.ExtendedChunkVertexType;
import net.coderbot.iris.celeritas.vertices.TerrainVertexFormatRequirements;
import net.coderbot.iris.shadows.ShadowRenderingState;
import net.minecraft.block.Block;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

public class IrisCeleritasShaderProvider implements ShaderProvider {
    private final IrisCeleritasChunkProgramOverrides overrides = new IrisCeleritasChunkProgramOverrides();
    private TerrainVertexFormatRequirements vertexFormatRequirements = TerrainVertexFormatRequirements.all();
    private ChunkVertexType vertexType = new ExtendedChunkVertexType(this.vertexFormatRequirements);
    private RenderPassConfiguration<?> renderPassConfiguration;

    @Override
    public boolean isShadersEnabled() {
        return Iris.getCurrentPack().isPresent();
    }

    @Override
    public boolean isShadowPass() {
        return ShadowRenderingState.areShadowsCurrentlyBeingRendered();
    }

    @Override
    public boolean shouldUseFaceCulling() {
        return !ShadowRenderingState.areShadowsCurrentlyBeingRendered();
    }

    @Override
    @Nullable
    public GlProgram<? extends ChunkShaderInterface> getShaderOverride(TerrainRenderPass pass) {
        if (!isShadersEnabled() || renderPassConfiguration == null) {
            return null;
        }
        return overrides.getProgramOverride(pass, renderPassConfiguration);
    }

    @Override
    public ChunkVertexType getVertexType(ChunkVertexType defaultType) {
        if (isShadersEnabled() && BlockRenderingSettings.INSTANCE.shouldUseExtendedVertexFormat()) {
            return this.vertexType;
        }
        return defaultType;
    }

    @Override
    public void setRenderPassConfiguration(RenderPassConfiguration<?> configuration) {
        this.renderPassConfiguration = configuration;
    }

    @Override
    public int getBlockStateId(Block block, int metadata) {
        return BlockRenderingSettings.INSTANCE.getBlockStateId(block, metadata);
    }

    @Override
    @Nullable
    public Map<Block, BlockRenderLayer> getBlockTypeIds() {
        return BlockRenderingSettings.INSTANCE.getBlockTypeIds();
    }

    public void deleteShaders() {
        overrides.deleteShaders();
    }

    public IrisCeleritasChunkProgramOverrides getOverrides() {
        return overrides;
    }

    /** Updates the shared section-VBO layout once every transformed terrain program has been inspected. */
    public void setVertexFormatRequirements(TerrainVertexFormatRequirements requirements) {
        Objects.requireNonNull(requirements, "Vertex format requirements must not be null");
        if (this.vertexFormatRequirements.equals(requirements)) {
            return;
        }

        this.vertexFormatRequirements = requirements;
        this.vertexType = new ExtendedChunkVertexType(requirements);
        BlockRenderingSettings.INSTANCE.requestRendererReload();
        Iris.logger.info("Celeritas terrain vertex format changed to {} bytes; scheduling renderer reload", this.vertexType.getVertexFormat().getStride());
    }
}
