package com.dhj.actinium.render.terrain;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.coderbot.iris.celeritas.WorldRendererCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.client.MinecraftForgeClient;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import com.dhj.actinium.compat.ichunutil.PortalChunkRenderMatrices;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import dhj.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.SortedRenderLists;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderFogComponent;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import dhj.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import net.coderbot.iris.pipeline.ShadowRenderer;
import com.dhj.actinium.compat.depthsupdate.DepthsUpdateCompat;
import com.dhj.actinium.runtime.ActiniumRuntime;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.*;

/**
 * Provides an extension to vanilla's {@link net.minecraft.client.renderer.RenderGlobal}.
 */
public class ActiniumWorldRenderer extends SimpleWorldRenderer<WorldClient, VintageRenderSectionManager, BlockRenderLayer, TileEntity, ActiniumWorldRenderer.TileEntityRenderContext>
    implements WorldRendererCompat {
    private static final double MAX_ENTITY_CHECK_VOLUME = 16 * 16 * 16 * 15;
    private boolean portalCamera;
    private ChunkRenderMatrices portalMatrices;

    public record TileEntityRenderContext(Map<Integer, DestroyBlockProgress> damagedBlocks, float partialTicks) {}

    /**
     * @return The ActiniumWorldRenderer based on the current dimension
     */
    public static ActiniumWorldRenderer instance() {
        return SimpleWorldRenderer.Provider.getWorldRenderer(Minecraft.getMinecraft().renderGlobal);
    }

    /**
     * @return The ActiniumWorldRenderer based on the current dimension, or null if none is attached
     */
    public static ActiniumWorldRenderer instanceNullable() {
        return SimpleWorldRenderer.Provider.getWorldRendererNullable(Minecraft.getMinecraft().renderGlobal);
    }

    @Override
    public int getMinimumBuildHeight() {
        return DepthsUpdateCompat.getMinBuildHeight(this.world);
    }

    @Override
    public int getMaximumBuildHeight() {
        return this.world.getHeight();
    }

    @Override
    public int getEffectiveRenderDistance() {
        return Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
    }

    @Override
    protected int getShadowEffectiveRenderDistance() {
        return Math.max(1, this.getEffectiveRenderDistance());
    }

    @Override
    protected ChunkRenderMatrices createChunkRenderMatrices() {
        if (this.renderSectionManager != null && this.renderSectionManager.isInShadowPass()) {
            return new ChunkRenderMatrices(ShadowRenderer.PROJECTION, ShadowRenderer.MODELVIEW);
        }
        if (this.portalCamera) {
            return Objects.requireNonNull(this.portalMatrices, "Portal matrices must be captured before terrain rendering");
        }

        Matrix4fc modelView = RenderingState.INSTANCE.getModelViewMatrix();
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view != null) {
            // Vanilla's model-view is anchored at the player's feet while chunk vertices use that same origin.
            // Restore the eye-height offset so terrain coordinates match the shader gbufferModelView uniform.
            modelView = new Matrix4f(modelView).translate(0f, view.getEyeHeight(), 0f);
        }

        return new ChunkRenderMatrices(RenderingState.INSTANCE.getProjectionMatrix(), modelView);
    }

    @Override
    protected VintageRenderSectionManager createRenderSectionManager(CommandList commandList) {
        return VintageRenderSectionManager.create(chooseVertexType(), this.world, this.getEffectiveRenderDistance(), commandList);
    }

    /**
     * Performs a render pass for the given {@link BlockRenderLayer} and draws all visible chunks for it.
     */
    public void drawChunkLayer(BlockRenderLayer renderLayer, double x, double y, double z) {
        RenderDebugHooksHolder.check("actinium:draw-chunk-layer:" + renderLayer + ":before-super");
        super.drawChunkLayer(renderLayer, x, y, z);
        RenderDebugHooksHolder.check("actinium:draw-chunk-layer:" + renderLayer + ":after-super");

        GLStateManager.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        RenderDebugHooksHolder.check("actinium:draw-chunk-layer:" + renderLayer + ":after-reset-color");
    }

    public void drawChunkLayersDeduplicated(Collection<BlockRenderLayer> renderLayers, double x, double y, double z) {
        ChunkRenderMatrices matrices = createChunkRenderMatrices();
        Set<TerrainRenderPass> passes = new LinkedHashSet<>();

        for (BlockRenderLayer renderLayer : renderLayers) {
            Collection<TerrainRenderPass> layerPasses = this.renderSectionManager.getRenderPassConfiguration().vanillaRenderStages().get(renderLayer);
            if (layerPasses != null) {
                passes.addAll(layerPasses);
            }
        }

        if (!passes.isEmpty()) {
            CameraTransform occlusionCamera = this.getLastViewport().getTransform();
            CameraTransform realCamera = new CameraTransform(x, y, z);
            for (TerrainRenderPass pass : passes) {
                this.renderSectionManager.renderLayer(matrices, pass, occlusionCamera, realCamera);
            }
        }

        GLStateManager.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public void setCurrentViewport(Viewport viewport) {
        this.currentViewport = viewport;
    }

    @Override
    public Viewport getLastViewport() {
        return super.getLastViewport();
    }

    /**
     * Captures the iChun recursive terrain matrices while leaving ordinary and shadow rendering unchanged.
     */

    @Override
    public void markSectionGraphDirty() {
        getRenderSectionManager().markGraphDirty();
    }
    public void setPortalCamera(boolean portalCamera) {
        this.portalCamera = portalCamera;
        this.portalMatrices = portalCamera ? PortalChunkRenderMatrices.capture() : null;
    }

    @Override
    public void setupTerrain(Viewport viewport, CameraState cameraState, int frame, boolean spectator, boolean updateChunksImmediately) {
        super.setupTerrain(viewport, cameraState, frame, spectator, updateChunksImmediately);
    }

    @Override
    public void setupShadowTerrain(Viewport playerViewport, Viewport shadowViewport, CameraState cameraState, int frame, boolean spectator) {
        super.setupShadowTerrain(playerViewport, shadowViewport, cameraState, frame, spectator);
        collectTileEntitiesForShadow();
        RenderDebugHooksHolder.logShadowTerrainLayer(
            "culling",
            "fogOcclusion=false,occlusionCulling=false",
            this.renderSectionManager.getVisibleChunkCount()
        );
    }

    @SuppressWarnings("unchecked")
    private void collectTileEntitiesForShadow() {
        final SortedRenderLists renderLists = this.renderSectionManager.getRenderLists();
        final Iterator<ChunkRenderList> renderListIterator = renderLists.iterator();

        while (renderListIterator.hasNext()) {
            final ChunkRenderList renderList = renderListIterator.next();
            final var renderRegion = renderList.getRegion();
            final var renderSectionIterator = renderList.sectionsWithEntitiesIterator();

            if (renderSectionIterator == null) {
                continue;
            }

            while (renderSectionIterator.hasNext()) {
                final int renderSectionId = renderSectionIterator.nextByteAsInt();
                final var renderSection = renderRegion.getSection(renderSectionId);

                if (renderSection == null) {
                    continue;
                }

                final var context = renderSection.getBuiltContext();
                if (context instanceof MinecraftBuiltRenderSectionData<?, ?> mcData) {
                    ShadowRenderer.visibleTileEntities.addAll((List<TileEntity>) mcData.culledBlockEntities);
                }
            }
        }

        for (var renderSection : this.renderSectionManager.getSectionsWithGlobalEntities()) {
            final var context = renderSection.getBuiltContext();
            if (context instanceof MinecraftBuiltRenderSectionData<?, ?> mcData) {
                ShadowRenderer.globalTileEntities.addAll((List<TileEntity>) mcData.globalBlockEntities);
            }
        }

        RenderDebugHooksHolder.logShadowPassState(
            "collect-block-entities",
            true,
            true,
            true,
            true,
            true,
            this.renderSectionManager.getVisibleChunkCount(),
            -1,
            ShadowRenderer.visibleTileEntities.size() + ShadowRenderer.globalTileEntities.size()
        );
    }

    public static CameraState captureCameraState(Entity viewEntity, double ticks) {
        Objects.requireNonNull(viewEntity, "viewEntity");
        double x = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * ticks;
        double y = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * ticks + (double) viewEntity.getEyeHeight();
        double z = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * ticks;

        float pitch = viewEntity.rotationPitch;
        float yaw = viewEntity.rotationYaw;
        float fogDistance = ChunkShaderFogComponent.FOG_SERVICE.getFogCutoff();

        return new CameraState(x, y, z, pitch, yaw, fogDistance);
    }


    @Override
    protected void renderBlockEntityList(List<TileEntity> list, TileEntityRenderContext tileEntityRenderContext) {
        this.renderBlockEntityListInternal(list, tileEntityRenderContext, false);
    }

    @Override
    protected void renderGlobalBlockEntityList(List<TileEntity> list, TileEntityRenderContext tileEntityRenderContext) {
        this.renderBlockEntityListInternal(list, tileEntityRenderContext, true);
    }

    @SuppressWarnings("unused")
    private void renderBlockEntityListInternal(
        List<TileEntity> list, TileEntityRenderContext tileEntityRenderContext, boolean globalRendererList
    ) {
        int pass = MinecraftForgeClient.getRenderPass();
        float partialTicks = tileEntityRenderContext.partialTicks;

        for (TileEntity tileEntity : list) {
            if(!tileEntity.shouldRenderInPass(pass))
                continue;

            try {
                TileEntityRendererDispatcher.instance.render(tileEntity, partialTicks, -1);
            } catch(RuntimeException e) {
                if(tileEntity.isInvalid()) {
                    ActiniumRuntime.logger().error("Suppressing crash from invalid tile entity", e);
                } else {
                    throw e;
                }
            }
        }
    }

    @Override
    public int renderBlockEntities(TileEntityRenderContext tileEntityRenderContext) {
        int pass = MinecraftForgeClient.getRenderPass();
        // TESRs (e.g. HBM-CE machines) are not disciplined about GL state; guard the batch so
        // leaked depth/blend/texture state cannot reach the translucent pass or the HUD.
        TileEntityGlStateGuard.push();
        try {
            TileEntityRendererDispatcher.instance.preDrawBatch();
            try {
                return super.renderBlockEntities(tileEntityRenderContext);
            } finally {
                // TESRs leak GL state during the render loop; flush the FastTESR batch with
                // the clean entry state (see TileEntityGlStateGuard.restoreForBatch).
                TileEntityGlStateGuard.restoreForBatch();
                TileEntityRendererDispatcher.instance.drawBatch(pass);
            }
        } finally {
            TileEntityGlStateGuard.pop();
        }
    }

    /**
     * Returns whether or not the entity intersects with any visible chunks in the graph.
     * @return True if the entity is visible, otherwise false
     */
    public boolean isEntityVisible(Entity entity) {
        if (!ActiniumRuntime.options().performance.useEntityCulling || this.renderSectionManager.isInShadowPass()) {
            return true;
        }

        // Ensure entities with outlines or nametags are always visible
        if (entity.isGlowing() || entity.getAlwaysRenderNameTagForRender()) {
            return true;
        }

        AxisAlignedBB box = entity.getRenderBoundingBox();
        if (box == null) {
            return true;
        }

        double volume = (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
        if (volume <= 0.0D || volume > MAX_ENTITY_CHECK_VOLUME) {
            return true;
        }

        return this.isBoxVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private ChunkVertexType chooseVertexType() {
        ShaderProvider provider = ShaderProviderHolder.getProvider();
        if (provider != null && provider.isShadersEnabled()) {
            ChunkVertexType shaderVertexType = provider.getVertexType(ChunkMeshFormats.VANILLA_LIKE);
            if (shaderVertexType != null && shaderVertexType != ChunkMeshFormats.VANILLA_LIKE) {
                return shaderVertexType;
            }
        }

        if (!ActiniumRuntime.options().performance.useCompactVertexFormat) {
            return ChunkMeshFormats.VANILLA_LIKE;
        }

        return ChunkMeshFormats.COMPACT;
    }
}
