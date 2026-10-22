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
import com.dhj.actinium.debug.ChunkMeshInputDiagnostics;
import com.dhj.actinium.world.WorldSlice;
import com.dhj.actinium.world.cloned.ChunkRenderContext;
import com.dhj.actinium.world.cloned.ClonedChunkSectionCache;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.dhj.actinium.render.terrain.compile.task.ChunkBuilderMeshingTask;
import com.dhj.actinium.render.terrain.sprite.SpriteUtil;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.List;

public class VintageRenderSectionManager extends RenderSectionManager {
    private final WorldClient world;
    /** Bounded mesh diagnostics for this render-section-manager lifetime, without retaining the world itself. */
    private final ChunkMeshInputDiagnostics.TraceBudget meshInputTraceBudget =
        new ChunkMeshInputDiagnostics.TraceBudget();
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

        return new ChunkBuilderMeshingTask(render, context, frame, this.cameraPosition,
            this.useRasterOcclusionCulling(), this.meshInputTraceBudget);
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
        public void delete(CommandList commandList) {
            unbindTerrainSampler();
            RenderSystem.destroySampler(this.terrainSampler);
            this.terrainSampler = 0;
            this.terrainSamplerResolved = false;
            this.terrainSamplerAnisotropy = -1;
            this.terrainSamplerNearest = false;
            this.terrainSamplerMipped = false;
            RenderSystem.destroySampler(this.packTerrainSampler);
            this.packTerrainSampler = 0;
            this.packTerrainSamplerResolved = false;
            this.packTerrainSamplerAnisotropy = -1;
            this.packTerrainSamplerMipped = false;
            super.delete(commandList);
        }

        /** Applies the selected terrain texel filter and anisotropic level to the regular Celeritas shader. */
        private void bindTerrainSampler() {
            final int anisotropy = TerrainTextureFiltering.resolvedAnisotropicFiltering();
            final boolean nearest = ActiniumRuntime.options().quality.texelSampling.isNearest();
            final boolean mipped = blockAtlasHasMipmaps();

            if (!this.terrainSamplerResolved || this.terrainSamplerAnisotropy != anisotropy
                || this.terrainSamplerNearest != nearest || this.terrainSamplerMipped != mipped) {
                if (!this.terrainSamplerResolved) {
                    this.terrainSampler = RenderSystem.genSampler();
                    if (this.terrainSampler == 0) {
                        ActiniumRuntime.logger().warn("Sampler objects unavailable; terrain filtering will fall back to the atlas texture's own parameters");
                    }
                }
                this.terrainSamplerResolved = true;
                this.terrainSamplerAnisotropy = anisotropy;
                this.terrainSamplerNearest = nearest;
                this.terrainSamplerMipped = mipped;
                configureTerrainSampler(this.terrainSampler, nearest, mipped, anisotropy);
            }

            if (this.terrainSampler != 0) {
                RenderSystem.bindSamplerToUnit(BLOCK_TEXTURE_UNIT, this.terrainSampler);
                this.terrainSamplerBound = true;
            }
        }

        /** Lets shader packs keep their own texel filtering while honoring the configured anisotropic footprint. */
        private void bindPackTerrainSampler() {
            final int anisotropy = TerrainTextureFiltering.resolvedAnisotropicFiltering();
            final boolean mipped = blockAtlasHasMipmaps();

            if (!this.packTerrainSamplerResolved || this.packTerrainSamplerAnisotropy != anisotropy
                || this.packTerrainSamplerMipped != mipped) {
                if (!this.packTerrainSamplerResolved) {
                    this.packTerrainSampler = RenderSystem.genSampler();
                    if (this.packTerrainSampler == 0) {
                        ActiniumRuntime.logger().warn("Sampler objects unavailable; shader-pack terrain filtering will fall back to the atlas texture's own parameters");
                    }
                }
                this.packTerrainSamplerResolved = true;
                this.packTerrainSamplerAnisotropy = anisotropy;
                this.packTerrainSamplerMipped = mipped;
                configureTerrainSampler(this.packTerrainSampler, true, mipped, anisotropy);
            }

            if (this.packTerrainSampler != 0) {
                RenderSystem.bindSamplerToUnit(BLOCK_TEXTURE_UNIT, this.packTerrainSampler);
                this.terrainSamplerBound = true;
            }
        }

        /** A sampler requiring mip filters must not be bound to a single-level atlas. */
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
                RenderSystem.samplerParameterf(sampler,
                    EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, (float) anisotropy);
            }
        }

        private void unbindTerrainSampler() {
            if (this.terrainSamplerBound) {
                RenderSystem.bindSamplerToUnit(BLOCK_TEXTURE_UNIT, 0);
                this.terrainSamplerBound = false;
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

