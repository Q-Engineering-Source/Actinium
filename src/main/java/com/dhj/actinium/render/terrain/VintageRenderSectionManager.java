package com.dhj.actinium.render.terrain;

import dhj.embeddedt.embeddium.impl.render.chunk.*;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.SectionTicker;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.AsyncOcclusionMode;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderFogComponent;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderTextureSlot;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkFogMode;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.GenericSectionSpriteTicker;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import dhj.embeddedt.embeddium.impl.util.position.SectionPos;
import org.jetbrains.annotations.Nullable;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import com.dhj.actinium.compat.depthsupdate.DepthsUpdateCompat;
import com.dhj.actinium.compat.fluxloading.FluxLoadingCompat;
import com.dhj.actinium.world.WorldSlice;
import com.dhj.actinium.world.cloned.ChunkRenderContext;
import com.dhj.actinium.world.cloned.ClonedChunkSectionCache;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.dhj.actinium.render.terrain.compile.task.ChunkBuilderMeshingTask;
import com.dhj.actinium.render.terrain.sprite.SpriteUtil;

import java.util.List;

public class VintageRenderSectionManager extends RenderSectionManager {
    private final WorldClient world;
    @Getter
    private final ClonedChunkSectionCache sectionCache;

    public VintageRenderSectionManager(RenderPassConfiguration<?> configuration, WorldClient world, int renderDistance, CommandList commandList, int minSection, int maxSection) {
        super(configuration, () -> new VintageChunkBuildContext(world, configuration), ChunkRenderer::new, renderDistance, commandList, minSection, maxSection, ActiniumRuntime.options().performance.chunkBuilderThreads, true);
        this.world = world;
        this.sectionCache = new ClonedChunkSectionCache(world);
    }

    public static VintageRenderSectionManager create(ChunkVertexType vertexType, WorldClient world, int renderDistance, CommandList commandList) {
        int minSection = DepthsUpdateCompat.getMinSection(world);
        int maxSection = DepthsUpdateCompat.getMaxSection(world);
        return new VintageRenderSectionManager(VintageRenderPassConfigurationBuilder.build(vertexType), world, renderDistance, commandList, minSection, maxSection);
    }

    @Override
    protected AsyncOcclusionMode getAsyncOcclusionMode() {
        return ActiniumRuntime.options().performance.asyncOcclusionMode;
    }

    @Override
    protected boolean useRasterOcclusionCulling() {
        return ActiniumRuntime.options().performance.useRasterOcclusionCulling;
    }

    @Override
    protected boolean shouldRespectUpdateTaskQueueSizeLimit() {
        return true;
    }

    @Override
    protected boolean useFogOcclusion() {
        if (!ActiniumRuntime.options().performance.useFogOcclusion) {
            return false;
        }

        if (ShaderProviderHolder.isShadowPass()) {
            // Iris/Sodium shadow rendering owns its caster distance through the shadow
            // frustum. The legacy fixed-function fog state belongs to the main view and
            // may truncate the shadow render list when it is reused here.
            return false;
        }

        if (!ShaderProviderHolder.isActive()) {
            return true;
        }

        return ChunkShaderFogComponent.FOG_SERVICE.getFogMode() != ChunkFogMode.NONE;
    }

    @Override
    protected boolean shouldUseOcclusionCulling(Viewport positionedViewport, boolean spectator) {
        if (ShaderProviderHolder.isShadowPass()) {
            // Shadow casters must not depend on paths reachable from the player's view.
            // Sodium/Iris enumerate the shadow frustum independently of main-view
            // occlusion; retaining the BFS here drops open terrain from the shadow map.
            return false;
        }

        final boolean useOcclusionCulling;
        var camBlockPos = positionedViewport.getBlockCoord();
        BlockPos origin = new BlockPos(camBlockPos.x(), camBlockPos.y(), camBlockPos.z());

        if (spectator && this.world.getBlockState(origin).isOpaqueCube())
        {
            useOcclusionCulling = false;
        } else {
            useOcclusionCulling = Minecraft.getMinecraft().renderChunksMany;
        }

        return useOcclusionCulling;
    }

    @Override
    protected boolean isSectionVisuallyEmpty(int x, int y, int z) {
        Chunk chunk = this.world.getChunk(x, z);
        if (chunk.isEmpty()) {
            return true;
        }
        var array = chunk.getBlockStorageArray();
        int storageIndex = DepthsUpdateCompat.toStorageIndex(this.world, y);
        if (storageIndex < 0 || storageIndex >= array.length) {
            return true;
        }
        return array[storageIndex] == Chunk.NULL_BLOCK_STORAGE || array[storageIndex].isEmpty();
    }

    @Override
    protected @Nullable ChunkBuilderTask<ChunkBuildOutput> createRebuildTask(RenderSection render, int frame) {
        ChunkRenderContext context = WorldSlice.prepare(this.world, new SectionPos(render.getChunkX(), render.getChunkY(), render.getChunkZ()), this.sectionCache);

        if (context == null) {
            return null;
        }

        return new ChunkBuilderMeshingTask(render, context, frame, this.cameraPosition, this.useRasterOcclusionCulling());
    }

    @Override
    protected boolean allowImportantRebuilds() {
        return !ActiniumRuntime.options().performance.alwaysDeferChunkUpdates;
    }

    @Override
    protected void scheduleSectionForRebuild(int x, int y, int z, boolean important) {
        this.sectionCache.invalidate(x, y, z);
        super.scheduleSectionForRebuild(x, y, z, important);
    }

    @Override
    public void updateChunks(boolean updateImmediately) {
        this.sectionCache.cleanup();
        super.updateChunks(updateImmediately);
        FluxLoadingCompat.onRenderSectionManagerUpdateChunks();
    }

    /**
     * This ridiculous workaround is needed because some mods rely on side effects of calling getRenderBoundingBox
     * to initialize tile entity state. It can be removed if we start using getRenderBoundingBox for TE rendering
     * again.
     */
    @SuppressWarnings("unchecked")
    private static void retrieveBBForList(List<?> blockEntities) {
        if (!blockEntities.isEmpty()) {
            for (var be : (List<TileEntity>)blockEntities) {
                be.getRenderBoundingBox();
            }
        }
    }

    @Override
    protected boolean updateSectionInfo(RenderSection render, @Nullable BuiltRenderSectionData info) {
        if (info instanceof MinecraftBuiltRenderSectionData<?,?> mcData) {
            retrieveBBForList(mcData.culledBlockEntities);
            retrieveBBForList(mcData.globalBlockEntities);
        }
        return super.updateSectionInfo(render, info);
    }

    @Override
    protected @Nullable SectionTicker createSectionTicker() {
        return new GenericSectionSpriteTicker<>(SpriteUtil::markSpriteActive);
    }

    @Override
    public boolean isInShadowPass() {
        return ShaderProviderHolder.isShadowPass();
    }

    @Override
    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, CameraTransform occlusionCamera, CameraTransform camera) {
        if (ShaderProviderHolder.isShadowPass()) {
            RenderDebugHooksHolder.logShadowTerrainLayer("before-render-layer", pass.name(), getVisibleChunkCount());
        }
        super.renderLayer(matrices, pass, occlusionCamera, camera);
    }

    private static class ChunkRenderer extends DefaultChunkRenderer {
        private GlProgram<? extends ChunkShaderInterface> actiniumProgram;
        private boolean usingActiniumProgram;

        public ChunkRenderer(RenderDevice device, RenderPassConfiguration<?> renderPassConfiguration) {
            super(device, renderPassConfiguration);

            ShaderProvider provider = ShaderProviderHolder.getProvider();
            if (provider != null) {
                provider.setRenderPassConfiguration(renderPassConfiguration);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void begin(TerrainRenderPass pass) {
            ShaderProvider provider = ShaderProviderHolder.getProvider();
            if (provider != null && provider.isShadersEnabled()) {
                GlProgram<? extends ChunkShaderInterface> override = provider.getShaderOverride(pass);
                if (override != null) {
                    pass.startDrawing();
                    override.bind();
                    override.getInterface().setupState(pass);
                    this.activeProgram = (GlProgram<ChunkShaderInterface>) override;
                    this.actiniumProgram = override;
                    this.usingActiniumProgram = true;
                    return;
                }
            }

            this.usingActiniumProgram = false;
            this.actiniumProgram = null;
            super.begin(pass);
        }

        @Override
        protected void end(TerrainRenderPass pass) {
            if (this.usingActiniumProgram && this.actiniumProgram != null) {
                RenderDebugHooksHolder.logCurrentFramebufferSamples("terrain:" + pass.name(), 1);
                this.actiniumProgram.getInterface().restoreState();
                this.actiniumProgram.unbind();
                this.actiniumProgram = null;
                this.usingActiniumProgram = false;
                this.activeProgram = null;
                pass.endDrawing();
                return;
            }

            super.end(pass);
        }

        @Override
        public boolean useBlockFaceCulling(){
            return ActiniumRuntime.options().performance.useBlockFaceCulling && ShaderProviderHolder.shouldUseFaceCulling();
        }

        @Override
        protected void configureShaderInterface(ChunkShaderInterface shader) {
            shader.setTextureSlot(ChunkShaderTextureSlot.BLOCK, 0);
            shader.setTextureSlot(ChunkShaderTextureSlot.LIGHT, 1);
        }
    }
}

