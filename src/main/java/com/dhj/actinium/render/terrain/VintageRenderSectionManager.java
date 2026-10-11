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
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
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
import com.dhj.actinium.render.voxelization.SdlShadowVoxelizationSink;
import com.dhj.actinium.render.voxelization.ShadowVoxelizer;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.program.ComputeProgram;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.shadows.ShadowRenderingState;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;

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
        private static final int BLOCK_TEXTURE_UNIT = 0;

        private GlProgram<? extends ChunkShaderInterface> actiniumProgram;
        private boolean usingActiniumProgram;
        private int terrainSampler;
        private boolean terrainSamplerResolved;
        private int terrainSamplerAnisotropy = -1;
        private boolean terrainSamplerNearest;
        private boolean terrainSamplerMipped;
        private int packTerrainSampler;
        private boolean packTerrainSamplerResolved;
        private int packTerrainSamplerAnisotropy = -1;
        private boolean packTerrainSamplerMipped;
        private boolean terrainSamplerBound;

        private final SdlShadowVoxelizationSink shadowVoxelSink = new SdlShadowVoxelizationSink();
        private static final ShadowVoxelizer shadowVoxelizer = new ShadowVoxelizer();
        private static int loggedVoxelizationSkips;

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
                    bindPackTerrainSampler();
                    return;
                }
            }

            this.usingActiniumProgram = false;
            this.actiniumProgram = null;
            super.begin(pass);
            bindTerrainSampler();
        }

        @Override
        protected void end(TerrainRenderPass pass) {
            unbindTerrainSampler();

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
        public void render(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists,
                           TerrainRenderPass renderPass, CameraTransform occlusionCamera, CameraTransform camera) {
            voxelizeShadowTerrain(matrices, renderLists, renderPass, occlusionCamera, camera);
            super.render(matrices, commandList, renderLists, renderPass, occlusionCamera, camera);
        }

        /**
         * Replays this pass's terrain vertices through the pack's voxelization compute shader.
         *
         * <p>Shader packs that write images from the shadow terrain stage get their vertex-stage image
         * writes moved into a compute pre-pass (the SDL GPU backend has no vertex-stage image writes).
         * That compute shader replays the pass's vertices from the terrain vertex buffer, so it has to
         * be told exactly which vertex runs the pass would draw - which is what {@link ShadowVoxelizer}
         * collects from the same render lists the draw uses. Only the SDL GPU backend needs this: the
         * OpenGL path keeps the original vertex-stage writes.</p>
         */
        private void voxelizeShadowTerrain(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform occlusionCamera, CameraTransform camera) {
            if (!BackendManager.RENDER_BACKEND.isSDLGPU()) return;
            if (!ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return;
            if (!renderLists.hasPass(renderPass)) {
                logVoxelizationSkipOnce(VoxelizationSkip.NO_RENDER_LISTS);
                return;
            }

            final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (!(pipeline instanceof DeferredWorldRenderingPipeline deferred)) {
                logVoxelizationSkipOnce(VoxelizationSkip.NO_DEFERRED_PIPELINE);
                return;
            }
            final ComputeProgram voxelCompute = deferred.getShadowVoxelizationCompute();
            if (voxelCompute == null) {
                logVoxelizationSkipOnce(VoxelizationSkip.NO_VOXELIZATION_COMPUTE);
                return;
            }

            final int prevProgram = GLStateManager.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            voxelCompute.use();
            deferred.prepareShadowVoxelizationCompute(matrices.modelView());
            try {
                shadowVoxelizer.walkPass(renderLists, renderPass, renderPass.vertexType().getVertexFormat(), camera, occlusionCamera, useBlockFaceCulling(), shadowVoxelSink);
                shadowVoxelSink.flush();
            } finally {
                shadowVoxelSink.discard();
                if (prevProgram != 0) GLStateManager.glUseProgram(prevProgram);
            }
        }

        private enum VoxelizationSkip {
            NO_RENDER_LISTS("pass has no render lists"),
            NO_DEFERRED_PIPELINE("no deferred pipeline"),
            NO_VOXELIZATION_COMPUTE("pack declares no shadow voxelization");

            private final String message;

            VoxelizationSkip(String message) {
                this.message = message;
            }
        }

        private static void logVoxelizationSkipOnce(VoxelizationSkip reason) {
            final int bit = 1 << reason.ordinal();
            if ((loggedVoxelizationSkips & bit) != 0) return;
            loggedVoxelizationSkips |= bit;
            ActiniumRuntime.logger().info("shadow voxelization skipped: {}", reason.message);
        }

        @Override
        public void delete(CommandList commandList) {
            shadowVoxelSink.delete();
            unbindTerrainSampler();
            RenderSystem.destroySampler(terrainSampler);
            terrainSampler = 0;
            terrainSamplerResolved = false;
            terrainSamplerAnisotropy = -1;
            terrainSamplerNearest = false;
            terrainSamplerMipped = false;
            RenderSystem.destroySampler(packTerrainSampler);
            packTerrainSampler = 0;
            packTerrainSamplerResolved = false;
            packTerrainSamplerAnisotropy = -1;
            packTerrainSamplerMipped = false;

            super.delete(commandList);
        }

        /**
         * Min/mag filtering comes from the Texel Sampling option and anisotropic filtering from the Texture
         * Filtering mode, so the sampler is rebuilt whenever either of them changes.
         */
        private void bindTerrainSampler() {
            final int anisotropy = TerrainTextureFiltering.resolvedAnisotropicFiltering();
            final boolean nearest = ActiniumRuntime.options().quality.texelSampling.isNearest();
            final boolean mipped = blockAtlasHasMipmaps();

            if (!terrainSamplerResolved || terrainSamplerAnisotropy != anisotropy || terrainSamplerNearest != nearest
                || terrainSamplerMipped != mipped) {
                if (!terrainSamplerResolved) {
                    terrainSampler = RenderSystem.genSampler();
                    if (terrainSampler == 0) {
                        ActiniumRuntime.logger().warn("Sampler objects unavailable; terrain filtering will fall back to the atlas texture's own parameters");
                    }
                }
                terrainSamplerResolved = true;
                terrainSamplerAnisotropy = anisotropy;
                terrainSamplerNearest = nearest;
                terrainSamplerMipped = mipped;

                configureTerrainSampler(terrainSampler, nearest, mipped, anisotropy);
            }

            if (terrainSampler != 0) {
                RenderSystem.bindSamplerToUnit(BLOCK_TEXTURE_UNIT, terrainSampler);
                terrainSamplerBound = true;
            }
        }

        /**
         * Shader packs filter the block atlas themselves, so their terrain sampler only applies the
         * anisotropic footprint and leaves magnification unfiltered.
         */
        private void bindPackTerrainSampler() {
            final int anisotropy = TerrainTextureFiltering.resolvedAnisotropicFiltering();
            final boolean mipped = blockAtlasHasMipmaps();

            if (!packTerrainSamplerResolved || packTerrainSamplerAnisotropy != anisotropy
                || packTerrainSamplerMipped != mipped) {
                if (!packTerrainSamplerResolved) {
                    packTerrainSampler = RenderSystem.genSampler();
                    if (packTerrainSampler == 0) {
                        ActiniumRuntime.logger().warn("Sampler objects unavailable; shader pack terrain filtering will fall back to the atlas texture's own parameters");
                    }
                }
                packTerrainSamplerResolved = true;
                packTerrainSamplerAnisotropy = anisotropy;
                packTerrainSamplerMipped = mipped;

                configureTerrainSampler(packTerrainSampler, true, mipped, anisotropy);
            }

            if (packTerrainSampler != 0) {
                RenderSystem.bindSamplerToUnit(BLOCK_TEXTURE_UNIT, packTerrainSampler);
                terrainSamplerBound = true;
            }
        }

        /**
         * A bound sampler object overrides the atlas texture's own filter state, so the mipmap filter can only
         * be requested while the atlas actually carries mip levels: sampling a single-level atlas with a
         * mipmap filter would read an incomplete texture and come out black.
         */
        private static boolean blockAtlasHasMipmaps() {
            return Minecraft.getMinecraft().getTextureMapBlocks().getMipmapLevels() > 0;
        }

        private static void configureTerrainSampler(int sampler, boolean nearest, boolean mipped, int anisotropy) {
            if (sampler == 0) {
                return;
            }
            RenderSystem.samplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            RenderSystem.samplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            RenderSystem.samplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER,
                mipped ? (nearest ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_LINEAR_MIPMAP_LINEAR)
                    : (nearest ? GL11.GL_NEAREST : GL11.GL_LINEAR));
            RenderSystem.samplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER,
                nearest ? GL11.GL_NEAREST : GL11.GL_LINEAR);
            if (TerrainTextureFiltering.anisotropySupported()) {
                RenderSystem.samplerParameteri(sampler,
                    EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy);
            }
        }

        private void unbindTerrainSampler() {
            if (terrainSamplerBound) {
                RenderSystem.bindSamplerToUnit(BLOCK_TEXTURE_UNIT, 0);
                terrainSamplerBound = false;
            }
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

