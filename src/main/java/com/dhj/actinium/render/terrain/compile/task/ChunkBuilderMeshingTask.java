package com.dhj.actinium.render.terrain.compile.task;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.ReportedException;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.ForgeHooksClient;
import dhj.embeddedt.embeddium.impl.asm.ProxyClassGenerator;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import dhj.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionVisibilityBuilder;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.util.task.CancellationToken;
import org.joml.Vector3d;
import dhj.embeddedt.embeddium.api.shader.BlockRenderLayer;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import com.dhj.actinium.compat.architecturecraft.ArchitectureCraftCompat;
import com.dhj.actinium.compat.componentmodelhider.ComponentModelHiderCompat;
import com.dhj.actinium.compat.fluidlogged.FluidloggedCompat;
import com.dhj.actinium.compat.snowrealmagic.SnowRealMagicCompat;
import com.dhj.actinium.debug.ChunkMeshInputDiagnostics;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.world.WorldSlice;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import com.dhj.actinium.world.cloned.ChunkRenderContext;
import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.dhj.actinium.render.terrain.compile.ChunkBufferBuilder;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;

import java.util.*;

public class ChunkBuilderMeshingTask extends ChunkBuilderTask<ChunkBuildOutput> {
    private static final ProxyClassGenerator<WorldSlice, ActiniumBlockAccess> WORLD_SLICE_LOCAL_GENERATOR = new ProxyClassGenerator<>(WorldSlice.class, "WorldSliceLocal", ActiniumBlockAccess.class);
    /** Bounded fallback for callers of the compatibility constructor that do not supply a manager-owned budget. */
    private static final ChunkMeshInputDiagnostics.TraceBudget SESSION_TRACE_BUDGET =
        new ChunkMeshInputDiagnostics.TraceBudget();
    private final RenderSection render;
    private final int buildTime;
    private final Vector3d camera;
    private final ChunkRenderContext renderContext;
    private final boolean rasterOcclusion;
    private final ChunkMeshInputDiagnostics.TraceBudget meshInputTraceBudget;

    public ChunkBuilderMeshingTask(RenderSection render, ChunkRenderContext context, int time, Vector3d camera, boolean rasterOcclusion) {
        this(render, context, time, camera, rasterOcclusion, SESSION_TRACE_BUDGET);
    }

    public ChunkBuilderMeshingTask(RenderSection render, ChunkRenderContext context, int time, Vector3d camera,
                                   boolean rasterOcclusion, ChunkMeshInputDiagnostics.TraceBudget meshInputTraceBudget) {
        this.render = render;
        this.buildTime = time;
        this.camera = camera;
        this.renderContext = context;
        this.rasterOcclusion = rasterOcclusion;
        this.meshInputTraceBudget = Objects.requireNonNull(meshInputTraceBudget, "meshInputTraceBudget");
    }

    @Override
    public ChunkBuildOutput execute(ChunkBuildContext context, CancellationToken cancellationToken) {
        VintageChunkBuildContext buildContext = (VintageChunkBuildContext)context;
        MinecraftBuiltRenderSectionData<TextureAtlasSprite, TileEntity> renderData = new MinecraftBuiltRenderSectionData<>();
        SectionVisibilityBuilder occluder = new SectionVisibilityBuilder();

        ChunkBuildBuffers buffers = buildContext.buffers;
        buffers.init(renderData, this.render.getSectionIndex());

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        int maxX = minX + 16;
        int maxY = minY + 16;
        int maxZ = minZ + 16;

        // Initialise with minX/minY/minZ so initial getBlockState crash context is correct
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos(minX, minY, minZ);

        buildContext.getWorldSlice().copyData(this.renderContext);

        var slice = WORLD_SLICE_LOCAL_GENERATOR.generateWrapper(buildContext.getWorldSlice());

        var dispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
        ShaderProvider provider = ShaderProviderHolder.getProvider();
        Map<net.minecraft.block.Block, BlockRenderLayer> blockTypeIds =
                provider != null && provider.isShadersEnabled() ? provider.getBlockTypeIds() : null;

        buildContext.setupTranslation(minX, minY, minZ);

        // The Component Model Hider skips hidden blocks from inside the vanilla chunk rebuild, which
        // this mesher replaces. Arm its culling hooks for the whole build and skip the hidden blocks
        // below (see ComponentModelHiderCompat).
        ComponentModelHiderCompat.beginBuild();

        try {
            for (int y = minY; y < maxY; y++) {
                if (cancellationToken.isCancelled()) {
                    return null;
                }

                for (int z = minZ; z < maxZ; z++) {
                    for (int x = minX; x < maxX; x++) {
                        blockPos.setPos(x, y, z);

                        IBlockState blockState = slice.getBlockState(blockPos);
                        var block = blockState.getBlock();

                        if (block == Blocks.AIR) {
                            continue;
                        }

                        // A hidden block emits no geometry in vanilla either: the hider's redirect
                        // replaces the renderBlock call and nothing else. The occlusion marking below
                        // deliberately still covers it, because vanilla's VisGraph#setOpaqueCube call
                        // sat outside the redirected call and kept a hidden block occluding.
                        boolean hidden = ComponentModelHiderCompat.isHidden(blockPos);

                        if (this.rasterOcclusion) {
                            occluder.markRenderable(x, y, z);
                        }

                        if (block.hasTileEntity(blockState)) {
                            TileEntity tileEntity = slice.getTileEntity(blockPos);
                            if (tileEntity != null) {
                                TileEntitySpecialRenderer<TileEntity> tesr = TileEntityRendererDispatcher.instance.getRenderer(tileEntity);

                                if (tesr != null) {
                                    (tesr.isGlobalRenderer(tileEntity) ? renderData.globalBlockEntities : renderData.culledBlockEntities).add(tileEntity);
                                }
                            }
                        }

                        buildContext.getBlockRenderer().resetSharedState();

                        if (!hidden) {
                            BlockRenderLayer shaderLayerOverride = blockTypeIds != null ? blockTypeIds.get(block) : null;

                            if (shaderLayerOverride != null) {
                                net.minecraft.util.BlockRenderLayer layer = shaderLayerOverride.toVanillaLayer();
                                ForgeHooksClient.setRenderLayer(layer);
                                block.canRenderInLayer(blockState, layer);
                                boolean allowFastRenderer = blockState.getRenderType() == EnumBlockRenderType.MODEL
                                        && ActiniumRuntime.options().performance.useFastBlockRenderer
                                        && !SnowRealMagicCompat.shouldForceVanillaRender(block)
                                        && !ArchitectureCraftCompat.shouldForceVanillaRender(block);
                                ChunkBufferBuilder buffer = buildContext.getChunkBufferForLayer(layer);
                                buffer.actinium$beginInvocation(allowFastRenderer, false, false, blockState);
                                buildContext.beginVanillaBlockRender(buffer, blockPos, blockState);
                                try {
                                    dispatcher.renderBlock(blockState, blockPos, slice, buffer);
                                } finally {
                                    try {
                                        buildContext.endVanillaRender(buffer);
                                    } finally {
                                        buffer.actinium$endInvocation();
                                    }
                                }
                            } else {
                                for (net.minecraft.util.BlockRenderLayer layer : VintageChunkBuildContext.LAYERS) {
                                    if (block.canRenderInLayer(blockState, layer)) {
                                        ForgeHooksClient.setRenderLayer(layer);
                                        boolean allowFastRenderer = blockState.getRenderType() == EnumBlockRenderType.MODEL
                                                && ActiniumRuntime.options().performance.useFastBlockRenderer
                                                && !SnowRealMagicCompat.shouldForceVanillaRender(block)
                                                && !ArchitectureCraftCompat.shouldForceVanillaRender(block);
                                        ChunkBufferBuilder buffer = buildContext.getChunkBufferForLayer(layer);
                                        buffer.actinium$beginInvocation(allowFastRenderer, true, false, blockState);
                                        buildContext.beginVanillaBlockRender(buffer, blockPos, blockState);
                                        try {
                                            dispatcher.renderBlock(blockState, blockPos, slice, buffer);
                                        } finally {
                                            try {
                                                buildContext.endVanillaRender(buffer);
                                            } finally {
                                                buffer.actinium$endInvocation();
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (FluidloggedCompat.IS_LOADED) {
                            FluidloggedCompat.renderFluidState(slice, blockPos, blockState, buildContext, dispatcher);
                        }

                        if (blockState.isOpaqueCube()) {
                            occluder.markOpaque(x, y, z);
                        }
                    }
                }
            }
        } catch (ReportedException ex) {
            // Propagate existing crashes (add context)
            throw fillCrashInfo(ex.getCrashReport(), slice, blockPos);
        } catch (Throwable ex) {
            // Create a new crash report for other exceptions (e.g. thrown in getQuads)
            throw fillCrashInfo(CrashReport.makeCrashReport(ex, "Encountered exception while building chunk meshes"), slice, blockPos);
        } finally {
            ComponentModelHiderCompat.endBuild();
        }

        buildContext.convertVanillaDataToCeleritasData(buffers);

        Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes = BuiltSectionMeshParts.groupFromBuildBuffers(buffers,(float)camera.x - minX, (float)camera.y - minY, (float)camera.z - minZ);

        if (!meshes.isEmpty()) {
            renderData.hasBlockGeometry = true;
        }

        if (this.rasterOcclusion) {
            renderData.occluderBoxes = occluder.computeOccluderBoxes();
        }
        renderData.visibilityData = occluder.computeVisibilityEncoding();

        long meshInputTraceId = 0L;
        try {
            if (GLSMPerfDebug.isEnabled()) {
                long outputNativeBytes = ChunkBuildOutput.calculateNativeMemoryBytes(meshes);
                meshInputTraceId = ChunkMeshInputDiagnostics.reserveTraceId(outputNativeBytes, this.meshInputTraceBudget);
                if (meshInputTraceId != 0L) {
                    ChunkMeshInputDiagnostics.reportBuildOutput(meshInputTraceId, this.render,
                        buildContext.getWorldSlice(), meshes, outputNativeBytes, this.buildTime);
                }
            }
        } catch (RuntimeException | Error diagnosticFailure) {
            ChunkMeshInputDiagnostics.reportOutputMeasurementFailure(this.render, diagnosticFailure);
        }

        return new ChunkBuildOutput(this.render, renderData, meshes, this.buildTime, meshInputTraceId);
    }

    private ReportedException fillCrashInfo(CrashReport report, IBlockAccess slice, BlockPos pos) {
        CrashReportCategory crashReportSection = report.makeCategory("Block being rendered");

        IBlockState state = null;
        try {
            state = slice.getBlockState(pos);
        } catch (Exception ignored) {}
        CrashReportCategory.addBlockInfo(crashReportSection, pos, state);

        crashReportSection.addCrashSection("Chunk section", this.render);

        return new ReportedException(report);
    }

}

