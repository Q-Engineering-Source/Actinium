package dhj.embeddedt.embeddium.impl.render.culling;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.DrawCommandList;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlPrimitiveType;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlTessellation;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import dhj.embeddedt.embeddium.impl.render.chunk.LocalSectionIndex;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.ChunkPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataUnsafe;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.BatchAssembler;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Culls the sections of one frame on the GPU and draws them with indirect multidraw.
 *
 * <p>A frame's render lists are walked once per pass: every visible section is appended to a staging list of
 * {@code (slot, facing mask, output base)} entries and the pass records which output slots its regions own. One compute
 * dispatch per pass then writes the indirect commands for that range, and drawing a region asks this class for the
 * command range that belongs to the pass currently being rendered.
 *
 * <p>Each pass keeps its own state, its own range of the visible list and its own range of the indirect buffer. The
 * local render configuration draws water and translucent as two sorted passes, so sharing one range between the sorted
 * passes would either drop the second pass's sections or draw the first pass's geometry twice.
 *
 * <p>One terrain renderer owns one instance and prepares the passes of its own render lists, so the culler being used
 * is always the one its renderer holds; there is no process-wide instance to look up.
 */
public final class GpuTerrainCuller {

    private static final Logger LOG = LogManager.getLogger("ActiniumCulling");

    private static final int ENTRY_BYTES = GpuDrivenChunkCuller.VISIBLE_ENTRY_BYTES;
    private static final int SORTED_INDEX_MASK = 0xFFFFFFFF;

    private static final long[] EMPTY_RANGES = new long[0];
    private static final int[] EMPTY_STAMPS = new int[0];

    private final GpuDrivenChunkCuller culler;
    private final SectionMetaBuffer sectionMeta;
    private final SectionMetaBuffer.Sink uploadSectionMetaSink;

    private final Map<TerrainRenderPass, PassState> states = new IdentityHashMap<>();
    private final List<PassState> preparedStates = new ArrayList<>();
    private final GpuCulledMultiDrawBatch batch = new GpuCulledMultiDrawBatch(this);

    private ByteBuffer visibleStaging;
    private ByteBuffer visibleStagingView;
    private int stagingCapacityEntries;

    private int visibleSsboGlId;
    private int indirectSsboGlId;
    private int gpuCapacityEntries;

    private PassState buildPass;
    private PassState current;
    private ChunkRenderListIterable preparedRenderLists;
    private boolean computeActiveThisPass;
    private int totalAppendedEntries;
    private int passOutputBase;
    private ChunkPrimitiveType walkPrimitiveType;

    private int currentDrawStart;
    private int currentDrawCount;
    private boolean indirectBufferBound;

    private ByteBuffer frustumUboBytes;
    private ByteBuffer uboBytes;
    private Matrix4f mvp;

    /**
     * Per-pass slice of the visible list and of the indirect command buffer, plus the region ranges of one walk.
     */
    private static final class PassState {
        private final TerrainRenderPass pass;
        private int entryBase;
        private int entryCount;
        private int indexPointerMask;
        private int maxElementCount;
        private boolean dispatched;
        private boolean prepared;

        private int stamp;
        private long[] regionRanges = EMPTY_RANGES;
        private int[] regionStamps = EMPTY_STAMPS;

        private PassState(TerrainRenderPass pass) {
            this.pass = pass;
        }

        private void reset(int indexPointerMask, int entryBase) {
            this.entryBase = entryBase;
            this.entryCount = 0;
            this.indexPointerMask = indexPointerMask;
            this.maxElementCount = 0;
            this.dispatched = false;
            this.stamp++;
            if (this.stamp == 0) {
                Arrays.fill(this.regionStamps, 0);
                this.stamp = 1;
            }
        }

        private void putRange(int regionId, long packed) {
            if (regionId >= this.regionStamps.length) {
                final int capacity = Math.max(regionId + 1, Math.max(this.regionStamps.length * 2, 64));
                this.regionRanges = Arrays.copyOf(this.regionRanges, capacity);
                this.regionStamps = Arrays.copyOf(this.regionStamps, capacity);
            }
            this.regionRanges[regionId] = packed;
            this.regionStamps[regionId] = this.stamp;
        }

        private long getRange(int regionId) {
            if (regionId >= this.regionStamps.length || this.regionStamps[regionId] != this.stamp) {
                return -1L;
            }
            return this.regionRanges[regionId];
        }

        private void releaseRanges() {
            this.regionRanges = EMPTY_RANGES;
            this.regionStamps = EMPTY_STAMPS;
        }
    }

    public GpuTerrainCuller(GpuDrivenChunkCuller culler, SectionMetaBuffer sectionMeta) {
        this.culler = culler;
        this.sectionMeta = sectionMeta;
        this.uploadSectionMetaSink = culler::uploadSectionMeta;
    }

    /**
     * {@return whether the pass being drawn takes its commands from the compute path}
     */
    public boolean isComputeActiveThisPass() {
        return this.computeActiveThisPass;
    }

    /**
     * {@return whether this frame culls and draws through the compute path}
     *
     * <p>The condition is the one {@link SectionRenderDataStorage} publishes section rows under: a context that lost
     * the compute path stops walking at the same moment it stops publishing rows, so the walk never reads a slot the
     * storage has given up. {@code CPU_ONLY} answers without asking the context, which keeps the CPU emitter's
     * per-pass check free of driver queries.
     */
    private static boolean computePathActive() {
        return GpuCulling.mode().computeEnabled() && GpuCulling.isAvailable();
    }

    /**
     * Walks every pass of the render lists and dispatches the culls once, so the per-pass draw calls only have to
     * select their prepared range.
     */
    public void prepareAllPasses(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, CameraTransform occlusionCamera,
                                 CameraTransform camera, boolean useBlockFaceCulling) {
        invalidatePreparedPasses();

        final List<TerrainRenderPass> passes = orderedPasses(renderLists);
        if (passes.isEmpty()) {
            return;
        }

        try {
            walkPasses(matrices, renderLists, passes, occlusionCamera, camera, useBlockFaceCulling);
            dispatchPreparedPasses();
            this.preparedRenderLists = renderLists;
        } catch (RuntimeException | Error failure) {
            invalidatePreparedPasses();
            throw failure;
        }
    }

    /**
     * Prepares the pass about to be drawn. A pass that was already prepared for these render lists only selects its
     * range; any other pass is walked on its own, which is what keeps a renderer usable without
     * {@link #prepareAllPasses}.
     */
    public void beginRenderPass(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass,
                                CameraTransform occlusionCamera, CameraTransform camera, boolean useBlockFaceCulling) {
        if (renderLists == this.preparedRenderLists && renderLists.hasPass(renderPass)) {
            final PassState prepared = this.states.get(renderPass);
            if (prepared != null && selectPrepared(prepared)) {
                return;
            }
        }

        if (!renderLists.hasPass(renderPass)) {
            if (renderLists != this.preparedRenderLists) {
                invalidatePreparedPasses();
            }
            this.current = null;
            return;
        }

        walkSinglePass(matrices, renderLists, renderPass, occlusionCamera, camera, useBlockFaceCulling);
        this.preparedRenderLists = renderLists;
    }

    /**
     * Finishes the pass that was just drawn and releases the indirect buffer it bound.
     */
    public void endPass() {
        if (this.indirectBufferBound) {
            GLSMBuffers.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
            this.indirectBufferBound = false;
        }
        this.currentDrawStart = 0;
        this.currentDrawCount = 0;
        if (this.current != null) {
            this.current.prepared = false;
        }
    }

    /**
     * {@return the command range this region owns in the pass being drawn, or {@code null} when it owns none}
     */
    public GpuCulledMultiDrawBatch batchForRegion(RenderRegion region) {
        if (this.current == null) {
            return null;
        }
        prepareRegion(region);
        if (this.currentDrawCount == 0) {
            return null;
        }
        this.batch.prepare(this.currentDrawStart, this.currentDrawCount, this.current.maxElementCount);
        return this.batch;
    }

    private boolean selectPrepared(PassState state) {
        if (!state.prepared || !this.computeActiveThisPass) {
            return false;
        }
        this.current = state;
        this.currentDrawStart = 0;
        this.currentDrawCount = 0;
        return true;
    }

    private void invalidatePreparedPasses() {
        this.preparedRenderLists = null;
        this.preparedStates.clear();
        for (PassState state : this.states.values()) {
            state.prepared = false;
        }
        this.computeActiveThisPass = false;
    }

    private PassState passState(TerrainRenderPass pass) {
        return this.states.computeIfAbsent(pass, PassState::new);
    }

    private static int indexPointerMask(TerrainRenderPass pass) {
        return pass.isSorted() ? SORTED_INDEX_MASK : 0;
    }

    /**
     * {@return the passes of the render lists, unsorted first so a solid pass's shared index buffer is walked before
     * the passes that keep their own index ranges}
     */
    private static List<TerrainRenderPass> orderedPasses(ChunkRenderListIterable renderLists) {
        final List<TerrainRenderPass> passes = new ArrayList<>(renderLists.getPasses());
        passes.sort(Comparator.comparing(TerrainRenderPass::isSorted).thenComparing(TerrainRenderPass::name));
        return passes;
    }

    private void walkPasses(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, List<TerrainRenderPass> passes,
                            CameraTransform occlusionCamera, CameraTransform camera, boolean useBlockFaceCulling) {
        this.computeActiveThisPass = computePathActive();
        this.totalAppendedEntries = 0;
        this.passOutputBase = 0;
        this.walkPrimitiveType = null;
        this.buildPass = passState(passes.get(0));
        this.current = this.buildPass;

        if (!this.computeActiveThisPass) {
            return;
        }

        prepareFrustumUbo(matrices, camera);
        syncSectionMetaIfDirty();

        for (TerrainRenderPass pass : passes) {
            final PassState state = passState(pass);
            state.reset(indexPointerMask(pass), this.totalAppendedEntries);
            this.buildPass = state;
            this.current = state;

            walkPass(renderLists, pass, occlusionCamera, useBlockFaceCulling);

            state.entryCount = this.totalAppendedEntries - state.entryBase;
            state.prepared = true;
            this.preparedStates.add(state);
        }

        this.buildPass = passState(passes.get(0));
        this.current = this.buildPass;
        patchPrimitiveRatio();
    }

    private void walkSinglePass(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass,
                                CameraTransform occlusionCamera, CameraTransform camera, boolean useBlockFaceCulling) {
        invalidatePreparedPasses();
        this.computeActiveThisPass = computePathActive();
        this.totalAppendedEntries = 0;
        this.passOutputBase = 0;
        this.walkPrimitiveType = null;

        final PassState state = passState(renderPass);
        state.reset(indexPointerMask(renderPass), 0);
        this.buildPass = state;
        this.current = state;

        if (!this.computeActiveThisPass) {
            return;
        }

        prepareFrustumUbo(matrices, camera);
        syncSectionMetaIfDirty();
        walkPass(renderLists, renderPass, occlusionCamera, useBlockFaceCulling);

        state.entryCount = this.totalAppendedEntries - state.entryBase;
        state.prepared = true;
        this.preparedStates.add(state);
        patchPrimitiveRatio();
    }

    private void patchPrimitiveRatio() {
        if (this.walkPrimitiveType == null) {
            return;
        }
        FrustumExtractor.patchPrimitiveRatio(this.walkPrimitiveType.getVerticesPerPrimitive(),
            this.walkPrimitiveType.getIndexBufferElementsPerPrimitive(), this.uboBytes);
    }

    private void prepareFrustumUbo(ChunkRenderMatrices matrices, CameraTransform camera) {
        if (this.uboBytes == null) {
            this.uboBytes = FrustumExtractor.allocateUboByteBuffer();
        }
        if (this.mvp == null) {
            this.mvp = new Matrix4f();
        }
        FrustumExtractor.writeFrustum(matrices.projection(), matrices.modelView(), camera, this.mvp, this.uboBytes);
        // The shadow pass renders the section set its own frustum already selected, so the plane test must not cull
        // it a second time against the player camera.
        FrustumExtractor.patchBypassFrustum(ShaderProviderHolder.isShadowPass(), this.uboBytes);
        this.frustumUboBytes = this.uboBytes;
    }

    private void syncSectionMetaIfDirty() {
        this.culler.ensureReady();
        this.sectionMeta.syncIfDirty(this.uploadSectionMetaSink);
    }

    private void walkPass(ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform occlusionCamera,
                          boolean useBlockFaceCulling) {
        if (renderPass.isSorted()) {
            walkSortedPass(renderLists, renderPass);
        } else {
            walkMergedPass(renderLists, renderPass, occlusionCamera, useBlockFaceCulling);
        }
    }

    /**
     * Walks an unsorted pass: the section's facing runs are merged into the fewest possible commands, which is what the
     * shared index buffer starts at element zero for.
     */
    private void walkMergedPass(ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform occlusionCamera,
                                boolean useBlockFaceCulling) {
        final boolean reverse = renderPass.isReverseOrder();
        final int step = reverse ? -1 : 1;
        final long stride = SectionRenderDataUnsafe.Strategy.COMPACT.getStride();
        final int cameraX = occlusionCamera.intX;
        final int cameraY = occlusionCamera.intY;
        final int cameraZ = occlusionCamera.intZ;

        final int regionCount = renderLists.getNumRegions();
        for (int regionIndex = 0; regionIndex < regionCount; regionIndex++) {
            final ChunkRenderList list = renderLists.getRegion(reverse ? regionCount - 1 - regionIndex : regionIndex);
            final RenderRegion region = list.getRegion();
            final SectionRenderDataStorage storage = region.getStorage(renderPass);
            if (storage == null) {
                continue;
            }
            final byte[] sections = list.getSectionsWithGeometry();
            final int sectionCount = list.getSectionsWithGeometryCount();
            if (sectionCount == 0) {
                continue;
            }
            final ChunkPrimitiveType primitiveType = storage.getPrimitiveType();
            trackPrimitiveType(primitiveType);
            final int[] slotCache = storage.getSlotCache();
            if (slotCache == null) {
                continue;
            }

            reserveSections(sectionCount);

            final long rowBase = storage.getRowBasePointer();
            final int regionOriginX = region.getChunkX();
            final int regionOriginY = region.getChunkY();
            final int regionOriginZ = region.getChunkZ();
            final int uniformMask = useBlockFaceCulling
                ? BatchAssembler.uniformCullMask(region, occlusionCamera)
                : ModelQuadFacing.ALL;

            final int regionDrawStart = this.passOutputBase;
            int regionDrawCount = 0;
            int regionMaxElements = 0;

            int cursor = reverse ? sectionCount - 1 : 0;

            for (int index = 0; index < sectionCount; index++, cursor += step) {
                final int sectionIndex = sections[cursor] & 0xFF;

                int slices = uniformMask != BatchAssembler.MASK_NOT_UNIFORM
                    ? uniformMask
                    : BatchAssembler.getVisibleFaces(cameraX, cameraY, cameraZ,
                        regionOriginX + LocalSectionIndex.unpackX(sectionIndex),
                        regionOriginY + LocalSectionIndex.unpackY(sectionIndex),
                        regionOriginZ + LocalSectionIndex.unpackZ(sectionIndex));
                slices &= storage.getSliceMask(sectionIndex);
                if (slices == 0) {
                    continue;
                }

                final int slot = slotCache[sectionIndex];
                if (slot < 0) {
                    continue;
                }

                final long meshData = rowBase + (sectionIndex * stride);
                final long runs = BatchAssembler.packRuns(slices & 0x7F);
                final int sectionDraws = BatchAssembler.runCount(runs);
                appendSection(slot, slices, this.passOutputBase);
                this.passOutputBase += sectionDraws;
                regionDrawCount += sectionDraws;

                int maxElements = 0;
                for (int run = 0; run < sectionDraws; run++) {
                    final int startVertex = SectionRenderDataUnsafe.Strategy.COMPACT.getVertexOffset(meshData, BatchAssembler.runFirst(runs, run));
                    final int endVertex = SectionRenderDataUnsafe.Strategy.COMPACT.getRunVertexEnd(meshData, BatchAssembler.runLast(runs, run), primitiveType);
                    final int elementCount = SectionRenderDataUnsafe.elementsForVertices(endVertex - startVertex, primitiveType);
                    if (elementCount > maxElements) {
                        maxElements = elementCount;
                    }
                }
                if (maxElements > regionMaxElements) {
                    regionMaxElements = maxElements;
                }
            }

            if (regionDrawCount > 0) {
                recordRegion(region, regionDrawStart, regionDrawCount, regionMaxElements);
            }
        }
    }

    /**
     * Walks a sorted pass: every facing keeps its own index range in the region's sorted index buffer, so each visible
     * facing becomes its own command.
     */
    private void walkSortedPass(ChunkRenderListIterable renderLists, TerrainRenderPass renderPass) {
        final boolean reverse = renderPass.isReverseOrder();
        final int step = reverse ? -1 : 1;
        final long stride = SectionRenderDataUnsafe.Strategy.FULL.getStride();

        final int regionCount = renderLists.getNumRegions();
        for (int regionIndex = 0; regionIndex < regionCount; regionIndex++) {
            final ChunkRenderList list = renderLists.getRegion(reverse ? regionCount - 1 - regionIndex : regionIndex);
            final RenderRegion region = list.getRegion();
            final SectionRenderDataStorage storage = region.getStorage(renderPass);
            if (storage == null) {
                continue;
            }
            final byte[] sections = list.getSectionsWithGeometry();
            final int sectionCount = list.getSectionsWithGeometryCount();
            if (sectionCount == 0) {
                continue;
            }
            final ChunkPrimitiveType primitiveType = storage.getPrimitiveType();
            trackPrimitiveType(primitiveType);
            final int[] slotCache = storage.getSlotCache();
            if (slotCache == null) {
                continue;
            }

            reserveSections(sectionCount);

            final long rowBase = storage.getRowBasePointer();

            final int regionDrawStart = this.passOutputBase;
            int regionDrawCount = 0;
            int regionMaxElements = 0;

            int cursor = reverse ? sectionCount - 1 : 0;

            for (int index = 0; index < sectionCount; index++, cursor += step) {
                final int sectionIndex = sections[cursor] & 0xFF;

                final int slices = ModelQuadFacing.ALL & storage.getSliceMask(sectionIndex);
                if (slices == 0) {
                    continue;
                }

                final int slot = slotCache[sectionIndex];
                if (slot < 0) {
                    continue;
                }

                final long meshData = rowBase + (sectionIndex * stride);
                final int sectionDraws = Integer.bitCount(slices);
                appendSection(slot, slices, this.passOutputBase);
                this.passOutputBase += sectionDraws;
                regionDrawCount += sectionDraws;

                int maxElements = 0;
                for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
                    final int elementCount = SectionRenderDataUnsafe.Strategy.FULL.getElementCount(meshData, facing, primitiveType);
                    if (elementCount > maxElements) {
                        maxElements = elementCount;
                    }
                }
                if (maxElements > regionMaxElements) {
                    regionMaxElements = maxElements;
                }
            }

            if (regionDrawCount > 0) {
                recordRegion(region, regionDrawStart, regionDrawCount, regionMaxElements);
            }
        }
    }

    private void trackPrimitiveType(ChunkPrimitiveType primitiveType) {
        if (this.walkPrimitiveType == null) {
            this.walkPrimitiveType = primitiveType;
        } else if (this.walkPrimitiveType.getVerticesPerPrimitive() != primitiveType.getVerticesPerPrimitive()
            || this.walkPrimitiveType.getIndexBufferElementsPerPrimitive() != primitiveType.getIndexBufferElementsPerPrimitive()) {
            throw new IllegalStateException("One cull dispatch spans passes with differing primitive ratios: "
                + this.walkPrimitiveType + " vs " + primitiveType);
        }
    }

    private void reserveSections(int count) {
        if (count > 0) {
            ensureStagingCapacity(this.totalAppendedEntries + count);
        }
    }

    private void appendSection(int slot, int facingMask, int outputBase) {
        if ((outputBase & ~0x00FFFFFF) != 0) {
            throw new IllegalStateException("packed output base overflow: " + outputBase + " (max " + 0x00FFFFFF + ")");
        }
        if (this.totalAppendedEntries >= this.stagingCapacityEntries) {
            ensureStagingCapacity(this.totalAppendedEntries + 1);
        }
        final int offset = this.totalAppendedEntries * ENTRY_BYTES;
        this.visibleStaging.putInt(offset, slot);
        this.visibleStaging.putInt(offset + 4, (outputBase << 8) | (facingMask & 0xFF));
        this.totalAppendedEntries++;
        this.buildPass.entryCount = this.totalAppendedEntries - this.buildPass.entryBase;
    }

    private void recordRegion(RenderRegion region, int drawStart, int drawCount, int maxElementCount) {
        if (drawCount <= 0) {
            return;
        }
        this.buildPass.putRange(region.getId(), (((long) drawStart) << 32) | (drawCount & 0xFFFFFFFFL));
        if (maxElementCount > this.buildPass.maxElementCount) {
            this.buildPass.maxElementCount = maxElementCount;
        }
    }

    private void prepareRegion(RenderRegion region) {
        final long packed = this.current.getRange(region.getId());
        if (packed < 0L) {
            this.currentDrawStart = 0;
            this.currentDrawCount = 0;
        } else {
            this.currentDrawStart = (int) (packed >>> 32);
            this.currentDrawCount = (int) (packed & 0xFFFFFFFFL);
        }
    }

    private void ensureStagingCapacity(int needed) {
        if (this.visibleStaging != null && needed <= this.stagingCapacityEntries) {
            return;
        }
        final int newCapacity = Math.max(needed, Math.max(this.stagingCapacityEntries * 2, 64));
        final ByteBuffer next = MemoryUtilities.memAlloc(newCapacity * ENTRY_BYTES).order(ByteOrder.nativeOrder());
        if (this.visibleStaging != null) {
            this.visibleStaging.position(0).limit(this.totalAppendedEntries * ENTRY_BYTES);
            next.put(this.visibleStaging);
            MemoryUtilities.memFree(this.visibleStaging);
        }
        next.position(0);
        next.limit(newCapacity * ENTRY_BYTES);
        this.visibleStaging = next;
        this.visibleStagingView = this.visibleStaging.duplicate().order(ByteOrder.nativeOrder());
        this.stagingCapacityEntries = newCapacity;
    }

    private void ensureGpuBuffers(int maxVisible) {
        if (this.visibleSsboGlId == 0) {
            this.visibleSsboGlId = GLSMBuffers.glGenBuffers();
        }
        if (this.indirectSsboGlId == 0) {
            this.indirectSsboGlId = GLSMBuffers.glGenBuffers();
        }
        if (maxVisible <= this.gpuCapacityEntries) {
            return;
        }
        final int newCapacity = Math.max(maxVisible, Math.max(this.gpuCapacityEntries * 2, 64));
        GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.visibleSsboGlId);
        GLSMBuffers.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) newCapacity * ENTRY_BYTES, GL15.GL_STREAM_DRAW);
        GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.indirectSsboGlId);
        GLSMBuffers.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,
            (long) newCapacity * GpuDrivenChunkCuller.FACINGS_PER_SECTION * GpuDrivenChunkCuller.INDIRECT_COMMAND_BYTES,
            GL15.GL_STREAM_DRAW);
        this.gpuCapacityEntries = newCapacity;
    }

    private static boolean needsDispatch(PassState state) {
        return !state.dispatched && state.entryCount > 0;
    }

    /**
     * Dispatches every prepared pass that has entries, in one batch that borrows the compute program and the indexed
     * buffer bindings for the duration of the dispatches.
     */
    private void dispatchPreparedPasses() {
        if (!this.computeActiveThisPass || this.totalAppendedEntries == 0) {
            return;
        }

        boolean pending = false;
        for (PassState state : this.preparedStates) {
            if (needsDispatch(state)) {
                pending = true;
                break;
            }
        }
        if (!pending) {
            return;
        }
        if (!this.culler.ensureReady()) {
            return;
        }
        if (this.frustumUboBytes == null) {
            LOG.warn("Compute cull dispatch requested before a frustum was written; skipping the cull");
            return;
        }

        ensureGpuBuffers(this.totalAppendedEntries);
        this.visibleStagingView.position(0).limit(this.totalAppendedEntries * ENTRY_BYTES);
        GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.visibleSsboGlId);
        GLSMBuffers.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, this.visibleStagingView);

        this.culler.beginDispatches(this.visibleSsboGlId, this.indirectSsboGlId);
        BackendManager.RENDER_BACKEND.beginComputeDispatchBatch();
        try {
            for (PassState state : this.preparedStates) {
                dispatchPass(state);
            }
        } finally {
            this.culler.endDispatches();
            BackendManager.RENDER_BACKEND.endComputeDispatchBatch();
        }
    }

    private void dispatchPass(PassState state) {
        if (!needsDispatch(state)) {
            return;
        }
        FrustumExtractor.patchControl(state.entryCount, state.indexPointerMask, this.frustumUboBytes);
        FrustumExtractor.patchBatchEntryBase(state.entryBase, this.frustumUboBytes);
        this.culler.uploadFrustum(this.frustumUboBytes);
        this.culler.dispatch(state.entryCount);
        state.dispatched = true;
    }

    /**
     * Draws one region's range of the indirect commands of the pass being rendered, dispatching first when this is the
     * first draw of a prepared pass.
     */
    void drawRegionRange(CommandList commandList, GlTessellation tessellation, GlPrimitiveType primitiveType, int drawStart, int drawCount) {
        if (drawCount == 0 || this.current == null) {
            return;
        }
        if (!this.current.dispatched) {
            dispatchPreparedPasses();
        }
        if (!this.current.dispatched) {
            return;
        }

        if (!this.indirectBufferBound) {
            GLSMBuffers.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.indirectSsboGlId);
            this.indirectBufferBound = true;
        }

        final long offsetBytes = (long) drawStart * GpuDrivenChunkCuller.INDIRECT_COMMAND_BYTES;
        try (DrawCommandList ignored = commandList.beginTessellating(tessellation)) {
            GLSMDraws.glMultiDrawElementsIndirect(primitiveType.getId(), GL11.GL_UNSIGNED_INT, offsetBytes, drawCount, 0);
        }
    }

    /**
     * Releases every buffer and staging block of this culler.
     */
    public void delete() {
        if (this.indirectBufferBound) {
            GLSMBuffers.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
            this.indirectBufferBound = false;
        }
        if (this.visibleStaging != null) {
            MemoryUtilities.memFree(this.visibleStaging);
            this.visibleStaging = null;
            this.visibleStagingView = null;
            this.stagingCapacityEntries = 0;
        }
        if (this.visibleSsboGlId != 0) {
            GLSMBuffers.glDeleteBuffers(this.visibleSsboGlId);
            this.visibleSsboGlId = 0;
        }
        if (this.indirectSsboGlId != 0) {
            GLSMBuffers.glDeleteBuffers(this.indirectSsboGlId);
            this.indirectSsboGlId = 0;
        }
        this.gpuCapacityEntries = 0;
        this.frustumUboBytes = null;
        this.uboBytes = null;
        for (PassState state : this.states.values()) {
            state.releaseRanges();
        }
        this.states.clear();
        this.batch.delete();
        invalidatePreparedPasses();
        this.current = null;
        this.buildPass = null;
    }
}
