package com.dhj.actinium.render.terrain.compile.task;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
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
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobResult;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import dhj.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionVisibilityBuilder;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.util.task.CancellationToken;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import dhj.embeddedt.embeddium.api.shader.BlockRenderLayer;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import dhj.embeddedt.embeddium.api.shader.buffer.BufferBuilderExtension;
import dhj.embeddedt.embeddium.api.shader.buffer.VanillaQuadContext;
import com.dhj.actinium.compat.architecturecraft.ArchitectureCraftCompat;
import com.dhj.actinium.compat.componentmodelhider.ComponentModelHiderCompat;
import com.dhj.actinium.compat.farmersfuturedelight.FarmersFutureDelightCompat;
import com.dhj.actinium.compat.fluidlogged.FluidloggedCompat;
import com.dhj.actinium.compat.snowrealmagic.SnowRealMagicCompat;
import com.dhj.actinium.render.terrain.deferred.DeferredBlock;
import com.dhj.actinium.render.terrain.deferred.DeferredMeshScheduler;
import com.dhj.actinium.render.terrain.deferred.DeferredSectionMesh;
import com.dhj.actinium.render.terrain.deferred.DeferredSectionMesh.DeferredCapture;
import com.dhj.actinium.rendering.BlockRenderSafetyRegistry;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.world.WorldSlice;
import com.dhj.actinium.world.cloned.DeferredSlice;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import com.dhj.actinium.world.cloned.ChunkRenderContext;
import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds one section mesh.
 *
 * <p>A block keeps its build-thread path unless the player listed it in
 * {@code performance.deferred_block_ids}, which moves its geometry to a render-thread capture: such a
 * block needs render state that only exists on the render thread. The two halves of that handoff are
 * this class' {@link #captureDeferred(DeferredSectionMesh)} and
 * {@link #finishDeferred(DeferredSectionMesh, ChunkBuildContext)}; the scan below decides which
 * blocks travel that way and renders everything else on the build thread.</p>
 */
public class ChunkBuilderMeshingTask extends ChunkBuilderTask<ChunkBuildOutput> implements DeferredSectionMesh.Handler {
    private static final ProxyClassGenerator<WorldSlice, ActiniumBlockAccess> WORLD_SLICE_LOCAL_GENERATOR = new ProxyClassGenerator<>(WorldSlice.class, "WorldSliceLocal", ActiniumBlockAccess.class);

    /** Byte length of one vertex of the vanilla block format, used to bound a capture to the block that produced it. */
    private static final int VANILLA_BLOCK_VERTEX_SIZE = DefaultVertexFormats.BLOCK.getSize();

    private static final int VERTICES_PER_QUAD = 4;

    /** Pass mask of a deferred block whose layers the render thread decides, one bit per vanilla layer. */
    private static final int ALL_RENDER_LAYERS_MASK = (1 << VintageChunkBuildContext.LAYERS.length) - 1;

    private final RenderSection render;
    private final int buildTime;
    private final Vector3d camera;
    private final ChunkRenderContext renderContext;
    private final boolean rasterOcclusion;

    /**
     * Context the render-thread capture renders with. It belongs to the section manager and is never
     * the context a build thread runs its tasks on, so a capture cannot share state with the build
     * that handed the mesh over or with the next build on that thread.
     */
    private final VintageChunkBuildContext captureContext;

    private DeferredMeshScheduler scheduler;
    private boolean important;
    private Runnable retryTask;

    /** Cancellation token of the build that handed a mesh over, or {@code null} before the handoff. */
    @Nullable
    private CancellationToken handoffToken;

    /** Blocks of this section that need the render thread, in the order the scan found them. */
    private final List<DeferredRender> deferredRenders = new ArrayList<>();

    /** Vertex count of every render layer before one captured fluid render; reused per block. */
    private final int[] captureStartVertices = new int[VintageChunkBuildContext.LAYERS.length];

    /** Render data of the build this task produced; kept until a handed-off mesh is finished. */
    @Nullable
    private MinecraftBuiltRenderSectionData<TextureAtlasSprite, TileEntity> renderData;

    public ChunkBuilderMeshingTask(RenderSection render, ChunkRenderContext context, int time, Vector3d camera,
                                   boolean rasterOcclusion, VintageChunkBuildContext captureContext) {
        this.render = render;
        this.buildTime = time;
        this.camera = camera;
        this.renderContext = context;
        this.rasterOcclusion = rasterOcclusion;
        this.captureContext = captureContext;
    }

    /**
     * Binds the deferred meshing pipeline to this build.
     *
     * @param scheduler scheduler that owns the pooled buffers and slices of the handoff
     * @param important whether the section update that created this task was important
     */
    public void bindScheduler(DeferredMeshScheduler scheduler, boolean important, Runnable retryTask) {
        this.scheduler = scheduler;
        this.important = important;
        this.retryTask = retryTask;
    }

    /**
     * Releases the section snapshot of a build that was cancelled before it could start. Idempotent,
     * and a no-op once {@code copyData} handed the snapshot to the build context's slice, which is
     * why a started build does not need this call.
     */
    @Override
    public void releaseUnstartedContext() {
        this.renderContext.release();
    }

    @Override
    public void retryLater(DeferredSectionMesh mesh) {
        mesh.token.setCancelled();
        this.retryTask.run();
    }

    @Override
    public ChunkBuildOutput execute(ChunkBuildContext context, CancellationToken cancellationToken) {
        long start = System.nanoTime();
        VintageChunkBuildContext buildContext = (VintageChunkBuildContext)context;
        MinecraftBuiltRenderSectionData<TextureAtlasSprite, TileEntity> renderData = new MinecraftBuiltRenderSectionData<>();
        this.renderData = renderData;
        this.deferredRenders.clear();

        SectionVisibilityBuilder occluder = new SectionVisibilityBuilder();

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        int maxX = minX + 16;
        int maxY = minY + 16;
        int maxZ = minZ + 16;

        // Initialise with minX/minY/minZ so initial getBlockState crash context is correct
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos(minX, minY, minZ);

        WorldSlice worldSlice = buildContext.getWorldSlice();
        worldSlice.copyData(this.renderContext);

        var slice = WORLD_SLICE_LOCAL_GENERATOR.generateWrapper(worldSlice);

        var dispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
        ShaderProvider provider = ShaderProviderHolder.getProvider();
        Map<Block, BlockRenderLayer> blockTypeIds =
                provider != null && provider.isShadersEnabled() ? provider.getBlockTypeIds() : null;

        // A build thread scans the section first so it knows which blocks have to wait for the render
        // thread. Without such a block the build keeps its original path and never touches the pool.
        boolean handoff = this.prepareHandoff(buildContext, worldSlice, minX, minY, minZ);
        long[] deferredMask = buildContext.getDeferredMask();
        ChunkBuildBuffers buffers = buildContext.buffers;
        ChunkBuildBuffers handoffBuffers = null;

        if (handoff) {
            handoffBuffers = Objects.requireNonNull(this.scheduler, "Deferred meshing scheduler").acquireBuffers();
            buildContext.setHandoffBuffers(handoffBuffers);
            buffers = handoffBuffers;
        }

        try {
            buffers.init(renderData, this.render.getSectionIndex());

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

                            // A block travels to the render thread only when this build runs on a build
                            // thread, the snapshot carries it and the player listed it as deferred.
                            boolean canRenderOffThread = !handoff || !isDeferred(deferredMask, x, y, z);

                            if (!hidden) {
                                BlockRenderLayer shaderLayerOverride = blockTypeIds != null ? blockTypeIds.get(block) : null;
                                int deferredPasses = 0;
                                Material deferredMaterial = null;

                                if (!canRenderOffThread) {
                                    // Nothing of this block may run on the build thread: the pass mask
                                    // defers every layer and the capture lets the render thread decide
                                    // which of them actually has geometry.
                                    if (shaderLayerOverride != null) {
                                        net.minecraft.util.BlockRenderLayer layer = shaderLayerOverride.toVanillaLayer();
                                        deferredPasses = 1 << layer.ordinal();
                                        deferredMaterial = DeferredBlock.shaderLayerMaterial(
                                                buffers.getRenderPassConfiguration(), shaderLayerOverride);
                                    } else {
                                        deferredPasses = ALL_RENDER_LAYERS_MASK;
                                    }
                                } else if (shaderLayerOverride != null) {
                                    // A shader pack moved this block onto another layer, so it takes the
                                    // fast renderer directly and keeps render pass optimization off.
                                    net.minecraft.util.BlockRenderLayer layer = shaderLayerOverride.toVanillaLayer();
                                    this.renderBlockLayer(block, blockState, blockPos, slice, layer, true, buildContext);
                                } else {
                                    for (net.minecraft.util.BlockRenderLayer layer : VintageChunkBuildContext.LAYERS) {
                                        this.renderBlockLayer(block, blockState, blockPos, slice, layer, false, buildContext);
                                    }
                                }

                                if (deferredPasses != 0) {
                                    this.deferredRenders.add(new DeferredRender(
                                            new DeferredBlock(x, y, z, block, deferredPasses, deferredMaterial),
                                            blockState));
                                }
                            }

                            // A Fluidlogged fluid is rendered on the same thread as its block: it reaches
                            // the vanilla dispatcher through the block's own buffer.
                            if (FluidloggedCompat.IS_LOADED && canRenderOffThread) {
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

            // The blocks that stayed on this thread are encoded here in both paths: a handoff keeps
            // their geometry in the pooled buffers the render-thread capture and the finisher complete.
            buildContext.convertVanillaDataToCeleritasData(buffers);

            if (this.rasterOcclusion) {
                renderData.occluderBoxes = occluder.computeOccluderBoxes();
            }
            renderData.visibilityData = occluder.computeVisibilityEncoding();

            if (handoff) {
                DeferredSectionMesh mesh = this.createHandoffMesh(buildContext, buffers, cancellationToken,
                        System.nanoTime() - start);
                // From here the scheduler owns the buffers, the snapshot and the mesh.
                handoffBuffers = null;
                Objects.requireNonNull(this.scheduler, "Deferred meshing scheduler").submit(mesh);
                return null;
            }

            return this.finalizeSection(buffers, renderData);
        } finally {
            buildContext.setHandoffBuffers(null);

            if (handoffBuffers != null) {
                Objects.requireNonNull(this.scheduler, "Deferred meshing scheduler").releaseBuffers(handoffBuffers);
            }
        }
    }

    /** {@return whether this build must hand its deferred blocks to the render thread} */
    private boolean prepareHandoff(VintageChunkBuildContext buildContext, WorldSlice worldSlice,
                                   int minX, int minY, int minZ) {
        if (this.scheduler == null || Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            return false;
        }

        if (!BlockRenderSafetyRegistry.hasDeferredBlocks()) {
            return false;
        }

        return prescanDeferred(worldSlice, minX, minY, minZ, buildContext.getDeferredMask());
    }

    /**
     * Renders one render layer of a block.
     *
     * <p>This is the single place the vanilla layer decision and the two render paths live in, so the
     * render-thread capture of a deferred block produces exactly the geometry the build thread would
     * have produced.</p>
     *
     * @param shaderLayerOverride whether a shader pack replaced the block's vanilla layers
     * @return whether the block rendered anything in {@code layer}
     */
    private boolean renderBlockLayer(Block block, IBlockState blockState, BlockPos blockPos,
                                     ActiniumBlockAccess slice, net.minecraft.util.BlockRenderLayer layer,
                                     boolean shaderLayerOverride, VintageChunkBuildContext buildContext) {
        BlockRendererDispatcher dispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();

        if (shaderLayerOverride) {
            ForgeHooksClient.setRenderLayer(layer);
            block.canRenderInLayer(blockState, layer);

            if (blockState.getRenderType() == EnumBlockRenderType.MODEL && ActiniumRuntime.options().performance.useFastBlockRenderer
                    && !SnowRealMagicCompat.shouldForceVanillaRender(block)
                    && !ArchitectureCraftCompat.shouldForceVanillaRender(block)) {
                buildContext.getBlockRenderer().renderBlock(blockState, blockPos, slice, layer, false);
                return true;
            }

            var buffer = buildContext.getBufferForLayer(layer);
            buildContext.beginVanillaBlockRender(buffer, blockPos, blockState, layer);
            try {
                dispatcher.renderBlock(blockState, blockPos, slice, buffer);
            } finally {
                buildContext.endVanillaRender(buffer);
            }
            return true;
        }

        if (!block.canRenderInLayer(blockState, layer)
                && !FarmersFutureDelightCompat.shouldForceTranslucentLayer(blockState, layer)) {
            return false;
        }

        ForgeHooksClient.setRenderLayer(layer);
        // The dispatcher hook sends model blocks to the fast renderer. Snow Real Magic,
        // ArchitectureCraft and Fluidlogged blocks are marked on the buffer by
        // beginVanillaBlockRender and keep the vanilla path.
        var buffer = buildContext.getBufferForLayer(layer);
        buildContext.beginVanillaBlockRender(buffer, blockPos, blockState, layer);
        try {
            dispatcher.renderBlock(blockState, blockPos, slice, buffer);
        } finally {
            buildContext.endVanillaRender(buffer);
        }
        return true;
    }

    /** Writes the blocks that need the render thread into the section's pass mask. */
    private static boolean prescanDeferred(WorldSlice worldSlice, int minX, int minY, int minZ, long[] mask) {
        Arrays.fill(mask, 0L);

        boolean anyDeferred = false;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Block block = worldSlice.getBlockState(minX + x, minY + y, minZ + z).getBlock();

                    if (block == Blocks.AIR || BlockRenderSafetyRegistry.canRenderOffThread(block)) {
                        continue;
                    }

                    int bit = (y << 8) | (z << 4) | x;
                    mask[bit >>> 6] |= 1L << bit;
                    anyDeferred = true;
                }
            }
        }

        return anyDeferred;
    }

    private static boolean isDeferred(long[] mask, int x, int y, int z) {
        int bit = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        return (mask[bit >>> 6] & (1L << bit)) != 0;
    }

    /** Moves the section snapshot of a build thread's context into a mesh and returns that mesh. */
    private DeferredSectionMesh createHandoffMesh(VintageChunkBuildContext buildContext, ChunkBuildBuffers buffers,
                                                  CancellationToken cancellationToken, long workerNanos) {
        DeferredMeshScheduler scheduler = Objects.requireNonNull(this.scheduler, "Deferred meshing scheduler");
        DeferredSlice snapshot = buildContext.swapWorldSlice(scheduler.acquireSlice());
        long priority = (long) this.render.getSquaredDistanceFromBlockCenter(
                (int) Math.floor(this.camera.x), (int) Math.floor(this.camera.y), (int) Math.floor(this.camera.z));

        this.handoffToken = cancellationToken;

        return new DeferredSectionMesh(this, cancellationToken, buffers, snapshot, this.important, priority, workerNanos);
    }

    /** {@return whether this mesh must be dropped instead of being captured or finished} */
    @Override
    public boolean isObsolete() {
        CancellationToken token = Objects.requireNonNull(this.handoffToken, "Handoff token");
        return token.isCancelled() || this.render.isDisposed() || this.render.getLastBuiltFrame() > this.buildTime;
    }

    /**
     * Renders the deferred blocks of {@code mesh} into its capture. Runs on the render thread with the
     * capture context, which renders from the same snapshot the build thread scanned.
     */
    @Override
    @Nullable
    public ChunkJobResult<ChunkBuildOutput> captureDeferred(DeferredSectionMesh mesh) {
        long start = System.nanoTime();
        DeferredSlice slice = mesh.slice;
        DeferredCapture capture = Objects.requireNonNull(mesh.capture, "Deferred capture");
        VintageChunkBuildContext buildContext = this.captureContext;

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos(minX, minY, minZ);
        DeferredSlice previousSlice = buildContext.swapWorldSlice(slice);

        try {
            buildContext.setupTranslation(minX, minY, minZ);
            buildContext.setHandoffBuffers(mesh.buffers);

            // The hider's gate is thread-local and the capture runs on another thread than the scan,
            // so it has to be armed around the deferred renders as well.
            ComponentModelHiderCompat.beginBuild();

            try {
                for (int index = 0; index < this.deferredRenders.size(); index++) {
                    DeferredRender deferred = this.deferredRenders.get(index);
                    DeferredBlock block = deferred.block();
                    blockPos.setPos(block.x(), block.y(), block.z());
                    buildContext.getBlockRenderer().resetSharedState();

                    for (int pass = 0; pass < VintageChunkBuildContext.LAYERS.length; pass++) {
                        if ((block.passMask() & (1 << pass)) == 0) {
                            continue;
                        }

                        net.minecraft.util.BlockRenderLayer layer = VintageChunkBuildContext.LAYERS[pass];
                        int startVertex = buildContext.getBufferForLayer(layer).getVertexCount();

                        this.renderBlockLayer(block.block(), deferred.state(), blockPos, slice, layer,
                                block.isShaderPackOverride(), buildContext);
                        this.captureLayer(capture, buildContext, block, layer, startVertex);
                    }

                    if (FluidloggedCompat.IS_LOADED) {
                        this.captureFluidRender(capture, buildContext, slice, blockPos, deferred.state());
                    }
                }
            } finally {
                ComponentModelHiderCompat.endBuild();
            }

            mesh.mainNanos = System.nanoTime() - start;
            return null;
        } catch (ReportedException ex) {
            return new ChunkJobResult.Failure<>(fillCrashInfo(ex.getCrashReport(), slice, blockPos));
        } catch (Throwable ex) {
            return new ChunkJobResult.Failure<>(fillCrashInfo(
                    CrashReport.makeCrashReport(ex, "Encountered exception while capturing deferred chunk meshes"),
                    slice, blockPos));
        } finally {
            buildContext.setHandoffBuffers(null);
            buildContext.discardVanillaBuffers();
            buildContext.swapWorldSlice(previousSlice);
        }
    }

    /** Stores the vertices one captured block render appended to {@code layer} in the mesh's capture. */
    private void captureLayer(DeferredCapture capture, VintageChunkBuildContext buildContext,
                              @Nullable DeferredBlock block, net.minecraft.util.BlockRenderLayer layer,
                              int startVertex) {
        BufferBuilder buffer = buildContext.getBufferForLayer(layer);
        int vertexCount = buffer.getVertexCount() - startVertex;
        List<VanillaQuadContext> quadContexts = consumeQuadContexts(buffer);

        if (vertexCount == 0) {
            if (!quadContexts.isEmpty()) {
                throw new IllegalStateException("Captured no " + layer + " vertices but " + quadContexts.size() + " quad contexts");
            }
            return;
        }

        Material layerMaterial = buildContext.getRenderBuffers().getRenderPassConfiguration()
                .getMaterialForRenderType(layer);
        capture.add(DeferredBlock.captureMaterial(block, layerMaterial), layer,
                vanillaBytes(buffer, startVertex, vertexCount), quadContexts);
    }

    /** Captures the Fluidlogged fluid of one deferred block, which renders through the same buffers. */
    private void captureFluidRender(DeferredCapture capture, VintageChunkBuildContext buildContext, DeferredSlice slice,
                                    BlockPos pos, IBlockState state) {
        int[] startVertices = this.captureStartVertices;

        for (int layer = 0; layer < startVertices.length; layer++) {
            startVertices[layer] = buildContext.getBufferForLayer(VintageChunkBuildContext.LAYERS[layer]).getVertexCount();
        }

        FluidloggedCompat.renderFluidState(slice, pos, state, buildContext,
                Minecraft.getMinecraft().getBlockRendererDispatcher());

        for (int layer = 0; layer < startVertices.length; layer++) {
            this.captureLayer(capture, buildContext, null, VintageChunkBuildContext.LAYERS[layer],
                    startVertices[layer]);
        }
    }

    private static List<VanillaQuadContext> consumeQuadContexts(BufferBuilder buffer) {
        return buffer instanceof BufferBuilderExtension extension
                ? extension.actinium$consumeQuadContexts()
                : List.of();
    }

    /** {@return a direct view of the vertices {@code [startVertex, startVertex + vertexCount)} of {@code buffer}} */
    private static ByteBuffer vanillaBytes(BufferBuilder buffer, int startVertex, int vertexCount) {
        int offset = startVertex * VANILLA_BLOCK_VERTEX_SIZE;
        int end = offset + vertexCount * VANILLA_BLOCK_VERTEX_SIZE;

        return buffer.getByteBuffer().duplicate().position(offset).limit(end).slice();
    }

    /**
     * Encodes the capture of {@code mesh} into the finished mesh. Runs on a build thread again, so the
     * captured vertices are re-encoded from the bounded views the capture stored.
     */
    @Override
    @Nullable
    public ChunkBuildOutput finishDeferred(DeferredSectionMesh mesh, ChunkBuildContext context) {
        VintageChunkBuildContext buildContext = (VintageChunkBuildContext) context;
        MinecraftBuiltRenderSectionData<TextureAtlasSprite, TileEntity> renderData =
                Objects.requireNonNull(this.renderData, "Render data of the handed-off build");
        ChunkBuildBuffers buffers = mesh.buffers;
        DeferredCapture capture = Objects.requireNonNull(mesh.capture, "Deferred capture");

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos(minX, minY, minZ);
        DeferredSlice slice = mesh.slice;
        DeferredSlice previousSlice = buildContext.swapWorldSlice(slice);

        try {
            buildContext.setupTranslation(minX, minY, minZ);

            for (int index = 0; index < capture.size(); index++) {
                DeferredCapture.Entry entry = capture.entry(index);
                requireCompleteEntry(entry);
                buildContext.copyBlockData(capture.view(entry), buffers, entry.material(), entry.layer(), entry.quadContexts());
            }

            return this.finalizeSection(buffers, renderData);
        } catch (ReportedException ex) {
            throw fillCrashInfo(ex.getCrashReport(), slice, blockPos);
        } catch (Throwable ex) {
            throw fillCrashInfo(CrashReport.makeCrashReport(ex, "Encountered exception while finishing deferred chunk meshes"),
                    slice, blockPos);
        } finally {
            buildContext.swapWorldSlice(previousSlice);
        }
    }

    /** Rejects a capture whose vertex bytes and shader quad metadata disagree, which would mislabel geometry. */
    private static void requireCompleteEntry(DeferredCapture.Entry entry) {
        int quadBytes = VANILLA_BLOCK_VERTEX_SIZE * VERTICES_PER_QUAD;
        int quads = entry.length() / quadBytes;

        if (entry.length() % quadBytes != 0 || entry.quadContexts().size() != quads) {
            throw new IllegalStateException("Captured " + entry.length() + " bytes of " + entry.layer()
                    + " geometry with " + entry.quadContexts().size() + " quad contexts instead of " + quads
                    + " quads of " + quadBytes + " bytes");
        }
    }

    private ChunkBuildOutput finalizeSection(ChunkBuildBuffers buffers,
                                             MinecraftBuiltRenderSectionData<TextureAtlasSprite, TileEntity> renderData) {
        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes = BuiltSectionMeshParts.groupFromBuildBuffers(buffers,(float)camera.x - minX, (float)camera.y - minY, (float)camera.z - minZ);

        if (!meshes.isEmpty()) {
            renderData.hasBlockGeometry = true;
        }

        return new ChunkBuildOutput(this.render, renderData, meshes, this.buildTime);
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

    /**
     * One block of this section that the render thread has to draw.
     *
     * @param block position, block, metadata and deferred passes reported by the scan
     * @param state the snapshot state the scan found, which the capture renders
     */
    private record DeferredRender(DeferredBlock block, IBlockState state) {
    }
}
