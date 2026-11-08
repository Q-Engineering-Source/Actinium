package dhj.embeddedt.embeddium.impl.render.chunk.region;

import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import lombok.Getter;
import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferArena;
import dhj.embeddedt.embeddium.impl.gl.arena.staging.StagingBuffer;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlBuffer;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlTessellation;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.culling.RenderPassIndex;
import dhj.embeddedt.embeddium.impl.common.util.MathUtil;
import dhj.embeddedt.embeddium.impl.util.PositionUtil;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class RenderRegion {
    public static final int REGION_WIDTH = 8;
    public static final int REGION_HEIGHT = 4;
    public static final int REGION_LENGTH = 8;

    public static final int REGION_BLOCK_WIDTH = REGION_WIDTH * 16;
    public static final int REGION_BLOCK_HEIGHT = REGION_HEIGHT * 16;
    public static final int REGION_BLOCK_LENGTH = REGION_LENGTH * 16;

    private static final int REGION_WIDTH_M = RenderRegion.REGION_WIDTH - 1;
    private static final int REGION_HEIGHT_M = RenderRegion.REGION_HEIGHT - 1;
    private static final int REGION_LENGTH_M = RenderRegion.REGION_LENGTH - 1;

    public static final int REGION_WIDTH_SH = Integer.bitCount(REGION_WIDTH_M);
    public static final int REGION_HEIGHT_SH = Integer.bitCount(REGION_HEIGHT_M);
    public static final int REGION_LENGTH_SH = Integer.bitCount(REGION_LENGTH_M);

    public static final int REGION_SIZE = REGION_WIDTH * REGION_HEIGHT * REGION_LENGTH;

    static {
        if(!MathUtil.isPowerOfTwo(REGION_WIDTH) || !MathUtil.isPowerOfTwo(REGION_HEIGHT) || !MathUtil.isPowerOfTwo(REGION_LENGTH)) {
            throw new IllegalStateException("Region width/height/length are not powers of two");
        }
    }

    private static final int MINIMUM_GEOMETRY_ARENA_BYTES = 512 * 1024;
    private static final int MINIMUM_INDEX_ARENA_BYTES = 64 * 1024;

    private final StagingBuffer stagingBuffer;

    /**
     * Element size of this region's geometry arena. Every pass of the region allocates from the one arena in elements
     * of this size, so an element holds a whole number of primitives of every pass.
     */
    private final int commonVertexStride;

    private final int x, y, z;

    @Getter
    private final int id;

    private final RenderSection[] sections = new RenderSection[RenderRegion.REGION_SIZE];
    @Getter
    private final long[] sectionLoadTimes = new long[RenderRegion.REGION_SIZE];
    @Getter
    private long newestSectionLoadTime;

    private int sectionCount;

    private final Map<TerrainRenderPass, SectionRenderDataStorage> sectionRenderData = new Reference2ReferenceOpenHashMap<>();

    private @Nullable DeviceResources resources;

    /**
     * Incremented each time the set of render passes in the region is changed.
     */
    @Getter
    private int passSetUpdateCount = 0;

    /**
     * Incremented each time the built data of any section in this region changes, or a section is added to or
     * removed from the region. Consumers that derive per-region state from section data can compare this against a
     * cached value to skip recomputation.
     */
    @Getter
    private int dataRevision = 0;

    RenderRegion(int x, int y, int z, int id, StagingBuffer stagingBuffer, int commonVertexStride) {
        this.x = x;
        this.y = y;
        this.z = z;

        this.id = id;
        this.stagingBuffer = stagingBuffer;
        this.commonVertexStride = commonVertexStride;
    }

    public static long key(int x, int y, int z) {
        return PositionUtil.packSection(x, y, z);
    }

    public int getChunkX() {
        return this.x << REGION_WIDTH_SH;
    }

    public int getChunkY() {
        return this.y << REGION_HEIGHT_SH;
    }

    public int getChunkZ() {
        return this.z << REGION_LENGTH_SH;
    }

    public int getOriginX() {
        return this.getChunkX() << 4;
    }

    public int getOriginY() {
        return this.getChunkY() << 4;
    }

    public int getOriginZ() {
        return this.getChunkZ() << 4;
    }

    public int getCenterX() {
        return (this.getChunkX() + REGION_WIDTH / 2) << 4;
    }

    public int getCenterY() {
        return (this.getChunkY() + REGION_HEIGHT / 2) << 4;
    }

    public int getCenterZ() {
        return (this.getChunkZ() + REGION_LENGTH / 2) << 4;
    }

    public void delete(CommandList commandList) {
        for (var storage : this.sectionRenderData.values()) {
            storage.delete();
        }

        this.sectionRenderData.clear();

        if (this.resources != null) {
            this.resources.delete(commandList);
            this.resources = null;
        }

        Arrays.fill(this.sections, null);
        Arrays.fill(this.sectionLoadTimes, 0);
    }

    public boolean isEmpty() {
        return this.sectionCount == 0;
    }

    public void onSectionDataChanged() {
        this.dataRevision++;
    }

    public SectionRenderDataStorage getStorage(TerrainRenderPass pass) {
        return this.sectionRenderData.get(pass);
    }

    public SectionRenderDataStorage createStorage(TerrainRenderPass pass, RenderPassConfiguration<?> renderPassConfiguration) {
        var storage = this.sectionRenderData.get(pass);

        if (storage == null) {
            int stride = pass.vertexType().getVertexFormat().getStride();

            if (this.commonVertexStride % stride != 0) {
                throw new IllegalStateException("Pass " + pass + " uses a vertex stride of " + stride
                        + ", which does not divide the region's common vertex stride of " + this.commonVertexStride
                        + "; its vertex format was not declared by the render pass configuration");
            }

            storage = new SectionRenderDataStorage(renderPassConfiguration.getPrimitiveTypeForPass(pass), pass.isSorted(),
                    this.commonVertexStride / stride);
            storage.attachRegion(this, RenderPassIndex.indexOf(pass));
            this.sectionRenderData.put(pass, storage);
            this.passSetUpdateCount++;
        }

        return storage;
    }

    public void removeEmptyStorages() {
        if (this.sectionRenderData.isEmpty()) {
            return;
        }

        boolean anyRemoved = this.sectionRenderData.values().removeIf(s -> {
            if (s.isEmpty()) {
                s.delete();
                return true;
            } else {
                return false;
            }
        });

        if (anyRemoved) {
            this.passSetUpdateCount++;
        }
    }

    public void removeMeshes(int sectionIndex) {
        if (this.sectionRenderData.isEmpty()) {
            return;
        }
        for (var storage : this.sectionRenderData.values()) {
            storage.removeMeshes(sectionIndex);
        }
    }

    public boolean hasSectionsInPass(TerrainRenderPass pass) {
        return this.sectionRenderData.containsKey(pass);
    }

    public Set<TerrainRenderPass> getPasses() {
        return this.sectionRenderData.keySet();
    }

    public void refresh(CommandList commandList) {
        if (this.resources != null) {
            this.resources.deleteTessellations(commandList);
        }

        for (var storage : this.sectionRenderData.values()) {
            storage.onBufferResized();
        }
    }

    /**
     * Drops the cached batches of every pass, because the buffers they draw from are gone or moved.
     */
    private void invalidateCachedBatches() {
        for (var storage : this.sectionRenderData.values()) {
            storage.invalidateCachedBatches();
        }
    }

    public void addSection(RenderSection section) {
        var sectionIndex = section.getSectionIndex();
        var prev = this.sections[sectionIndex];

        if (prev != null) {
            throw new IllegalStateException("Section has already been added to the region");
        }

        this.sections[sectionIndex] = section;
        this.sectionLoadTimes[sectionIndex] = 0;
        this.sectionCount++;
        this.dataRevision++;
    }

    public void removeSection(RenderSection section) {
        var sectionIndex = section.getSectionIndex();
        var prev = this.sections[sectionIndex];

        if (prev == null) {
            throw new IllegalStateException("Section was not loaded within the region");
        } else if (prev != section) {
            throw new IllegalStateException("Tried to remove the wrong section");
        }

        for (var storage : this.sectionRenderData.values()) {
            storage.removeMeshes(sectionIndex);
        }

        this.sections[sectionIndex] = null;
        this.sectionLoadTimes[sectionIndex] = 0;
        this.sectionCount--;
        this.dataRevision++;
    }

    public void updateSectionLoadTime(RenderSection section) {
        long timestamp = System.nanoTime();
        this.sectionLoadTimes[section.getSectionIndex()] = timestamp;
        this.newestSectionLoadTime = timestamp;
    }

    @Nullable
    public RenderSection getSection(int id) {
        return this.sections[id];
    }

    /**
     * {@return the device resources of this region, or {@code null} while the region holds no uploaded geometry} Every
     * pass of the region shares these resources.
     */
    @Nullable
    public DeviceResources getResources() {
        return this.resources;
    }

    public DeviceResources createResources(CommandList commandList) {
        if (this.resources == null) {
            this.resources = new DeviceResources(commandList, this.stagingBuffer, this.commonVertexStride);
        }

        return this.resources;
    }

    public void update(CommandList commandList) {
        var resources = this.resources;

        if (resources == null) {
            return;
        }

        if (resources.shouldDelete()) {
            resources.delete(commandList);
            this.resources = null;
            this.invalidateCachedBatches();
        } else if (resources.deleteIndexArenaIfPossible(commandList)) {
            this.invalidateCachedBatches();
        }
    }

    public static class DeviceResources {
        private final GlBufferArena geometryArena;
        private final StagingBuffer stagingBuffer;
        private GlBufferArena indexArena;

        /**
         * Tessellations of the vertex arrays the region is drawn with, keyed by {@link TerrainRenderPass.TessellationKey}.
         */
        private final Map<TerrainRenderPass.TessellationKey, GlTessellation> tessellations = new Object2ReferenceOpenHashMap<>();

        public DeviceResources(CommandList commandList, StagingBuffer stagingBuffer, int commonVertexStride) {
            this.geometryArena = new GlBufferArena(commandList, commonVertexStride, MINIMUM_GEOMETRY_ARENA_BYTES, stagingBuffer);
            this.stagingBuffer = stagingBuffer;
        }

        /**
         * {@return the tessellation cached for {@code key}, or {@code null} when that vertex array was never built}
         */
        public GlTessellation getTessellation(TerrainRenderPass.TessellationKey key) {
            return this.tessellations.get(key);
        }

        public void updateTessellation(CommandList commandList, TerrainRenderPass.TessellationKey key, GlTessellation tessellation) {
            var previous = this.tessellations.put(key, tessellation);

            if (previous != null) {
                previous.delete(commandList);
            }
        }

        public void deleteTessellations(CommandList commandList) {
            for (var tessellation : this.tessellations.values()) {
                tessellation.delete(commandList);
            }

            this.tessellations.clear();
        }

        public GlBuffer getVertexBuffer() {
            return this.geometryArena.getBufferObject();
        }

        public GlBuffer getIndexBuffer() {
            if (this.indexArena == null) {
                throw new IllegalStateException("Attempted to retrieve index buffer for a non-indexed region");
            }
            return this.indexArena.getBufferObject();
        }

        public void delete(CommandList commandList) {
            this.deleteTessellations(commandList);
            this.geometryArena.delete(commandList);
            if (this.indexArena != null) {
                this.indexArena.delete(commandList);
            }
        }

        public GlBufferArena getGeometryArena() {
            return this.geometryArena;
        }

        public GlBufferArena getIndexArena() {
            return this.indexArena;
        }

        public GlBufferArena getOrCreateIndexArena(CommandList commandList) {
            if (this.indexArena == null) {
                this.indexArena = new GlBufferArena(commandList, 4, MINIMUM_INDEX_ARENA_BYTES, this.stagingBuffer);
            }
            return this.indexArena;
        }

        public boolean shouldDelete() {
            return this.geometryArena.isEmpty();
        }

        /**
         * Releases the index arena when it holds no allocation any more. The tessellations of the sorted passes bind
         * that buffer, so they are dropped with it.
         *
         * @return whether the index arena was released
         */
        public boolean deleteIndexArenaIfPossible(CommandList commandList) {
            if (this.indexArena == null || !this.indexArena.isEmpty()) {
                return false;
            }

            this.deleteTessellations(commandList);
            this.indexArena.delete(commandList);
            this.indexArena = null;

            return true;
        }
    }
}
