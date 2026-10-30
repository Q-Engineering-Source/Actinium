package dhj.embeddedt.embeddium.impl.render.chunk;

import dhj.embeddedt.embeddium.impl.gl.tessellation.GlPrimitiveType;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlTessellation;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlVertexArrayTessellation;
import dhj.embeddedt.embeddium.impl.gl.tessellation.TessellationBinding;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dhj.embeddedt.embeddium.impl.gl.array.GlVertexArray;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexAttributeBinding;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import dhj.embeddedt.embeddium.impl.gl.debug.GLDebug;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.DirectMultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.device.IndirectMultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.device.MultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.gl.tessellation.*;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.ChunkPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.BatchAssembler;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.IndividualDrawEmitter;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;
import dhj.embeddedt.embeddium.api.render.chunk.ChunkAnimationProvider;
import dhj.embeddedt.embeddium.api.render.chunk.ChunkAnimationProviderHolder;
import com.mitchej123.lwjgl.GLExtension;
import dhj.embeddedt.embeddium.impl.runtime.EmbeddiumRuntimeOptions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;

public abstract class DefaultChunkRenderer extends ShaderChunkRenderer {
    private static final Logger LOGGER = LogManager.getLogger("EmbeddiumChunkRenderer");
    private static boolean loggedMultiDrawMode;

    /**
     * Resolved multidraw mode; selects the storage format of assembled batches and how they are executed.
     */
    private final MultiDrawMode multiDrawMode;

    /**
     * Scratch batch used only when a chunk animation provider is active: animated sections are diverted
     * per frame, so their region batch is rebuilt instead of served from the per-storage batch cache.
     * Always uses the direct layout so that animated sections can be replayed from it individually.
     */
    private final DirectMultiDrawBatch batch = new DirectMultiDrawBatch(MultiDrawBatch.MAX_COMMAND_COUNT);

    /**
     * Dedicated emitter for sections which are currently animating. Animated sections are drawn
     * one-by-one after the region batch with their own model offset, since a single region
     * transform cannot express per-section translations.
     */
    private final IndividualDrawEmitter individualEmitter = new IndividualDrawEmitter();

    private final Reference2ReferenceMap<ChunkPrimitiveType, SharedQuadIndexBuffer> sharedIndexBuffers;

    private TerrainRenderPass currentRenderPass;
    private GlVertexFormat currentVertexFormat;

    public DefaultChunkRenderer(RenderDevice device, RenderPassConfiguration<?> renderPassConfiguration) {
        super(device, renderPassConfiguration);

        this.multiDrawMode = selectMultiDrawMode(device);
        this.sharedIndexBuffers = new Reference2ReferenceOpenHashMap<>();
    }

    private static MultiDrawMode selectMultiDrawMode(RenderDevice device) {
        String override = System.getProperty("actinium.chunkMultiDrawMode", "").trim();
        MultiDrawMode configuredMode = EmbeddiumRuntimeOptions.chunkMultiDrawMode();
        MultiDrawMode mode = override.isEmpty() ? configuredMode : MultiDrawMode.fromProperty(override);
        boolean gl32 = LWJGL.isOpenGLVersionSupported(3, 2);
        boolean gl43 = LWJGL.isOpenGLVersionSupported(4, 3);
        boolean arbIndirect = LWJGL.isExtensionSupported(GLExtension.ARB_multi_draw_indirect);

        if (mode == MultiDrawMode.INDIRECT && !gl43 && !arbIndirect) {
            LOGGER.warn("Indirect chunk multidraw is not supported, falling back to direct (GL43={}, ARB_multi_draw_indirect={})", gl43, arbIndirect);
            mode = MultiDrawMode.DIRECT;
        }

        if (!loggedMultiDrawMode) {
            loggedMultiDrawMode = true;
            LOGGER.info(
                "Chunk multidraw mode: requested={}, selected={}, directFunction={}, GL32={}, GL43={}, ARB_multi_draw_indirect={}",
                override.isEmpty() ? configuredMode.id() : override,
                mode.id(),
                device.getDeviceFunctions().multidrawFunctions(),
                gl32,
                gl43,
                arbIndirect
            );
        }

        return mode;
    }

    protected boolean useBlockFaceCulling() {
        return true;
    }

    protected final SharedQuadIndexBuffer getSharedIndexBuffer(ChunkPrimitiveType type, CommandList commandList) {
        var buffer = this.sharedIndexBuffers.get(type);
        if (buffer == null) {
            buffer = new SharedQuadIndexBuffer(commandList, type);
            this.sharedIndexBuffers.put(type, buffer);
        }
        return buffer;
    }

    protected abstract void configureShaderInterface(ChunkShaderInterface shader);

    @Override
    public void render(ChunkRenderMatrices matrices,
                       CommandList commandList,
                       ChunkRenderListIterable renderLists,
                       TerrainRenderPass renderPass,
                       CameraTransform occlusionCamera,
                       CameraTransform camera) {
        if (!renderLists.hasPass(renderPass)) {
            return;
        }

        this.begin(renderPass);

        // If there is no active program, shader compilation probably failed, and we can't render anything.
        if (this.activeProgram != null) {
            long totalStartNanos = RenderDebugHooksHolder.beginRenderGlobalStageTiming();
            long fillNanos = 0L;
            long tessellationNanos = 0L;
            long uniformsNanos = 0L;
            long drawNanos = 0L;
            int regions = 0;
            int batches = 0;
            int commands = 0;
            boolean useBlockFaceCulling = this.useBlockFaceCulling();

            GLDebug.pushGroup(770, renderPass.name() + " terrain pass");

            ChunkShaderInterface shader = this.activeProgram.getInterface();
            shader.setProjectionMatrix(matrices.projection());
            shader.setModelViewMatrix(matrices.modelView());

            var primitiveType = shader.getPrimitiveType();

            Iterator<ChunkRenderList> iterator = renderLists.iterator(renderPass.isReverseOrder());

            this.currentRenderPass = renderPass;
            this.currentVertexFormat = this.renderPassConfiguration.getVertexTypeForPass(this.currentRenderPass).getVertexFormat();

            this.configureShaderInterface(shader);

            long timestamp = System.nanoTime();

            ChunkAnimationProvider animationProvider = ChunkAnimationProviderHolder.getProvider();
            float[] animationOffsetBuffer = animationProvider != null ? new float[3] : null;

            useBlockFaceCulling = useBlockFaceCulling && !renderPass.isSorted();
            var cacheParams = new SectionRenderDataStorage.BatchCacheParams(useBlockFaceCulling);

            while (iterator.hasNext()) {
                ChunkRenderList renderList = iterator.next();

                var region = renderList.getRegion();
                var storage = region.getStorage(renderPass);

                if (storage == null) {
                    continue;
                }

                regions++;
                long fillStartNanos = RenderDebugHooksHolder.beginRenderGlobalStageTiming();

                MultiDrawBatch batch;
                boolean ephemeralBatch = false;
                List<AnimatedSectionDraw> animatedSections = null;

                if (animationProvider != null) {
                    // Animated sections are diverted every frame, so the region batch cannot be cached.
                    List<AnimatedSectionDraw> diverted = new ArrayList<>();
                    animatedSections = diverted;
                    if (this.multiDrawMode == MultiDrawMode.INDIRECT) {
                        // Indirect batches are one-shot: upload() releases the CPU-side command storage,
                        // so each per-frame rebuild needs a fresh batch.
                        batch = new IndirectMultiDrawBatch(MultiDrawBatch.MAX_COMMAND_COUNT);
                        ephemeralBatch = true;
                    } else {
                        batch = this.batch;
                    }
                    BatchAssembler.fillRegion(batch, region, storage, renderList, occlusionCamera, renderPass,
                            useBlockFaceCulling, animationProvider, animationOffsetBuffer,
                            (animatedStorage, sectionIndex, slices, offsetX, offsetY, offsetZ) ->
                                    diverted.add(new AnimatedSectionDraw(animatedStorage, sectionIndex, slices,
                                            offsetX, offsetY, offsetZ)));
                    if (ephemeralBatch) {
                        batch.upload(commandList);
                    }
                } else {
                    var cached = storage.getCachedMultiDrawBatch(cacheParams);

                    if (cached == null || !cached.isValidFor(renderList.getSectionsWithGeometry(), renderList.getSectionsWithGeometryCount(),
                            occlusionCamera.intX, occlusionCamera.intY, occlusionCamera.intZ)) {
                        cached = BatchAssembler.createCachedBatch(region, storage, renderList, occlusionCamera, renderPass,
                                useBlockFaceCulling, commandList, this.multiDrawMode);

                        storage.storeCachedMultiDrawBatch(cacheParams, cached);
                    }

                    batch = cached.getBatch();
                }

                if (fillStartNanos != 0L) {
                    fillNanos += System.nanoTime() - fillStartNanos;
                }

                GlTessellation tessellation = null;
                if (batch != null && !batch.isEmpty()) {
                    batches++;
                    commands += batch.size();

                    if (!renderPass.isSorted()) {
                       getSharedIndexBuffer(renderPassConfiguration.getPrimitiveTypeForPass(renderPass), commandList).ensureCapacity(commandList, batch.getIndexBufferSize());
                    }

                    long tessellationStartNanos = RenderDebugHooksHolder.beginRenderGlobalStageTiming();
                    tessellation = this.prepareTessellation(commandList, region);
                    if (tessellationStartNanos != 0L) {
                        tessellationNanos += System.nanoTime() - tessellationStartNanos;
                    }

                    long uniformsStartNanos = RenderDebugHooksHolder.beginRenderGlobalStageTiming();
                    setModelMatrixUniforms(shader, region, camera);
                    shader.setSectionAges(timestamp, region.getSectionLoadTimes());
                    if (uniformsStartNanos != 0L) {
                        uniformsNanos += System.nanoTime() - uniformsStartNanos;
                    }

                    long drawStartNanos = RenderDebugHooksHolder.beginRenderGlobalStageTiming();
                    if (this.multiDrawMode == MultiDrawMode.INDIVIDUAL) {
                        // INDIVIDUAL batches use the direct storage layout.
                        this.individualEmitter.executeBatch(commandList, tessellation, primitiveType, (DirectMultiDrawBatch) batch);
                    } else {
                        batch.execute(commandList, tessellation, primitiveType);
                    }
                    if (drawStartNanos != 0L) {
                        drawNanos += System.nanoTime() - drawStartNanos;
                    }
                }

                if (ephemeralBatch) {
                    batch.delete();
                }

                // Sections currently playing an animation were excluded from the region batch and
                // must be drawn individually with their per-section offset applied.
                if (animatedSections != null && !animatedSections.isEmpty()) {
                    long animatedStartNanos = RenderDebugHooksHolder.beginRenderGlobalStageTiming();
                    if (tessellation == null) {
                        tessellation = this.prepareTessellation(commandList, region);
                    }
                    setModelMatrixUniforms(shader, region, camera);
                    shader.setSectionAges(timestamp, region.getSectionLoadTimes());
                    drawAnimatedSections(animatedSections, shader, region, camera, commandList, tessellation, primitiveType, renderPass);
                    if (animatedStartNanos != 0L) {
                        drawNanos += System.nanoTime() - animatedStartNanos;
                    }
                }
            }

            this.currentVertexFormat = null;
            this.currentRenderPass = null;

            GLDebug.popGroup();
            if (totalStartNanos != 0L) {
                RenderDebugHooksHolder.recordTerrainRendererTiming(
                    renderPass.name(),
                    System.nanoTime() - totalStartNanos,
                    fillNanos,
                    tessellationNanos,
                    uniformsNanos,
                    drawNanos,
                    regions,
                    batches,
                    commands
                );
            }
        }

        this.end(renderPass);
    }

    /**
     * Draws sections that are currently animating, one at a time, with the per-section offset
     * added to the region model offset. The region offset is restored afterwards.
     */
    private void drawAnimatedSections(List<AnimatedSectionDraw> animatedSections,
                                      ChunkShaderInterface shader,
                                      RenderRegion region,
                                      CameraTransform camera,
                                      CommandList commandList,
                                      GlTessellation tessellation,
                                      GlPrimitiveType primitiveType,
                                      TerrainRenderPass pass) {
        float baseX = getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX);
        float baseY = getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY);
        float baseZ = getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ);

        for (AnimatedSectionDraw draw : animatedSections) {
            shader.setRegionOffset(baseX + draw.offsetX(), baseY + draw.offsetY(), baseZ + draw.offsetZ());
            this.batch.clear();
            BatchAssembler.fillSection(this.batch, draw.storage(), draw.sectionIndex(), draw.slices());
            if (!pass.isSorted() && !this.batch.isEmpty()) {
                this.getSharedIndexBuffer(this.renderPassConfiguration.getPrimitiveTypeForPass(pass), commandList)
                        .ensureCapacity(commandList, this.batch.getIndexBufferSize());
            }
            this.individualEmitter.executeBatch(commandList, tessellation, primitiveType, this.batch);
        }

        shader.setRegionOffset(baseX, baseY, baseZ);
    }

    private record AnimatedSectionDraw(SectionRenderDataStorage storage, int sectionIndex, int slices,
                                       float offsetX, float offsetY, float offsetZ) {
    }

    private static void setModelMatrixUniforms(ChunkShaderInterface shader, RenderRegion region, CameraTransform camera) {
        float x = getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX);
        float y = getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY);
        float z = getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ);

        shader.setRegionOffset(x, y, z);
    }

    private static float getCameraTranslation(int chunkBlockPos, int cameraBlockPos, float cameraPos) {
        return (chunkBlockPos - cameraBlockPos) - cameraPos;
    }

    private GlTessellation prepareTessellation(CommandList commandList, RenderRegion region) {
        var resources = region.getResources(this.currentVertexFormat);
        var tessellation = this.currentRenderPass.isSorted() ? resources.getIndexedTessellation() : resources.getTessellation();

        if (tessellation == null) {
            tessellation = this.createRegionTessellation(commandList, resources);
            if (this.currentRenderPass.isSorted()) {
                resources.updateIndexedTessellation(commandList, tessellation);
            } else {
                resources.updateTessellation(commandList, tessellation);
            }
        }

        return tessellation;
    }

    private GlVertexAttributeBinding[] generateVertexAttributeBindings() {
        var attributes = this.currentVertexFormat.getAttributes();
        var bindings = new GlVertexAttributeBinding[attributes.size()];
        int i = 0;
        for (var attr : attributes) {
            bindings[i] = new GlVertexAttributeBinding(i, attr);
            i++;
        }
        return bindings;
    }

    protected TessellationBinding[] makeTessellationBindingArray(CommandList commandList, RenderRegion.DeviceResources resources) {
        return new TessellationBinding[] {
                TessellationBinding.forVertexBuffer(resources.getVertexBuffer(), this.generateVertexAttributeBindings()),
                TessellationBinding.forElementBuffer(this.currentRenderPass.isSorted() ? resources.getIndexBuffer() : this.getSharedIndexBuffer(this.renderPassConfiguration.getPrimitiveTypeForPass(this.currentRenderPass), commandList).getBufferObject())
        };
    }

    protected GlTessellation createRegionTessellation(CommandList commandList, RenderRegion.DeviceResources resources) {
        var bindings = makeTessellationBindingArray(commandList, resources);
        GlVertexArrayTessellation tessellation = new GlVertexArrayTessellation(new GlVertexArray(), bindings);
        tessellation.init(commandList);

        return tessellation;
    }

    /**
     * Legacy Celeritas command-buffer fill API — HBM-CE compatibility seam.
     *
     * <p>HBM-CE's {@code MixinDefaultChunkRenderer} (from {@code hbm.mod.mixin.json})
     * applies {@code @Redirect} injections on this class that replace direct reads of
     * the camera transform's integer coordinates with unsafe accessors (see HBM's
     * {@code CeleritasCameraTransformAccess}). One group of those redirects targets
     * {@link #setModelMatrixUniforms} (which this renderer retains); the other group
     * targets a {@code fillCommandBuffer} method that existed in the upstream Celeritas
     * {@code DefaultChunkRenderer} but no longer exists in Actinium's rewritten pipeline
     * (command buffers are now filled by {@code BatchAssembler}). Without this method the
     * whole mixin application aborts with a Critical injection error, which poisons this
     * class and breaks world loading (a subsequent {@code NoClassDefFoundError} on
     * {@code VintageRenderSectionManager$ChunkRenderer} surfaces during
     * {@code Minecraft.loadWorld}).
     *
     * <p>This method is never invoked by Actinium's render path; it exists purely so the
     * third-party mixin finds its injection targets and applies cleanly. The reads below
     * are kept so the redirects have a {@code CameraTransform.intX/intY/intZ} getfield to
     * rewrite, matching the same unsafe camera access they apply to
     * {@code setModelMatrixUniforms}.</p>
     */
    @SuppressWarnings("unused")
    void fillCommandBuffer(CameraTransform camera) {
        int originX = camera.intX;
        int originY = camera.intY;
        int originZ = camera.intZ;
        // Intentionally unused: the values are consumed by HBM-CE's redirected accessors.
    }

    @Override
    public void delete(CommandList commandList) {
        super.delete(commandList);

        this.sharedIndexBuffers.values().forEach(buffer -> buffer.delete(commandList));
        this.batch.delete();
    }
}

