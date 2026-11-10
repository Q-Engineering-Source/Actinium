package dhj.embeddedt.embeddium.impl.render.chunk.lists;

import dhj.embeddedt.embeddium.impl.render.chunk.LocalSectionIndex;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.util.iterator.ByteArrayIterator;
import dhj.embeddedt.embeddium.impl.util.iterator.ByteIterator;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import org.jetbrains.annotations.Nullable;

public class ChunkRenderList {
    private final RenderRegion region;

    private final byte[] sectionsWithGeometry = new byte[RenderRegion.REGION_SIZE];
    private int sectionsWithGeometryCount = 0;

    private final byte[] sectionsWithSprites = new byte[RenderRegion.REGION_SIZE];
    private int sectionsWithSpritesCount = 0;

    private final byte[] sectionsWithEntities = new byte[RenderRegion.REGION_SIZE];
    private int sectionsWithEntitiesCount = 0;

    private final byte[] sectionsNeedingDynamicSort = new byte[RenderRegion.REGION_SIZE];
    private int sectionsNeedingDynamicSortCount = 0;

    private int size;

    public ChunkRenderList(RenderRegion region) {
        this.region = region;
    }

    /**
     * Forgets every section of the previous search while keeping the region binding, so the list can
     * be filled again without keeping stale sections. The section arrays are not cleared: the counts
     * bound every read, and the next {@link #add(int, int)} overwrites the entries from index 0.
     */
    public void reset() {
        this.size = 0;
        this.sectionsWithGeometryCount = 0;
        this.sectionsWithSpritesCount = 0;
        this.sectionsWithEntitiesCount = 0;
        this.sectionsNeedingDynamicSortCount = 0;
    }

    public void add(RenderSection render) {
        this.add(render.getSectionIndex(), render.getVisualsServiceFlags());
    }

    /**
     * Adds a section using the metadata captured by the visibility traversal.
     * The collector uses this overload so it does not reread mutable section state after the async search.
     */
    public void add(int sectionIndex, int flags) {
        if (this.size >= RenderRegion.REGION_SIZE) {
            throw new ArrayIndexOutOfBoundsException("Render list is full");
        }

        this.size++;

        this.sectionsWithGeometry[this.sectionsWithGeometryCount] = (byte) sectionIndex;
        this.sectionsWithGeometryCount += (flags >>> RenderVisualsService.HAS_BLOCK_GEOMETRY) & 1;

        this.sectionsWithSprites[this.sectionsWithSpritesCount] = (byte) sectionIndex;
        this.sectionsWithSpritesCount += (flags >>> RenderVisualsService.HAS_SPRITES) & 1;

        this.sectionsWithEntities[this.sectionsWithEntitiesCount] = (byte) sectionIndex;
        this.sectionsWithEntitiesCount += (flags >>> RenderVisualsService.HAS_BLOCK_ENTITIES) & 1;

        this.sectionsNeedingDynamicSort[this.sectionsNeedingDynamicSortCount] = (byte) sectionIndex;
        this.sectionsNeedingDynamicSortCount += (flags >>> RenderVisualsService.NEEDS_DYNAMIC_SORT) & 1;
    }

    /**
     * Returns the backing array containing local section indices with geometry. Only the first
     * {@link #getSectionsWithGeometryCount()} entries are valid.
     */
    public byte[] getSectionsWithGeometry() {
        return this.sectionsWithGeometry;
    }

    /**
     * Returns a forward iterator over the local section indices whose translucent geometry has to be resorted as
     * the camera crosses a cut plane, or null when none of them do.
     */
    public @Nullable ByteIterator sectionsNeedingDynamicSortIterator() {
        if (this.sectionsNeedingDynamicSortCount == 0) {
            return null;
        }

        return new ByteArrayIterator(this.sectionsNeedingDynamicSort, this.sectionsNeedingDynamicSortCount);
    }

    /**
     * Returns a forward iterator over local section indices with geometry.
     */
    public @Nullable ByteIterator sectionsWithGeometryIterator() {
        if (this.sectionsWithGeometryCount == 0) {
            return null;
        }

        return new ByteArrayIterator(this.sectionsWithGeometry, this.sectionsWithGeometryCount);
    }

    public @Nullable ByteIterator sectionsWithSpritesIterator() {
        if (this.sectionsWithSpritesCount == 0) {
            return null;
        }

        return new ByteArrayIterator(this.sectionsWithSprites, this.sectionsWithSpritesCount);
    }

    public @Nullable ByteIterator sectionsWithEntitiesIterator() {
        if (this.sectionsWithEntitiesCount == 0) {
            return null;
        }

        return new ByteArrayIterator(this.sectionsWithEntities, this.sectionsWithEntitiesCount);
    }

    public int getSectionsWithGeometryCount() {
        return this.sectionsWithGeometryCount;
    }

    public int getSectionsWithSpritesCount() {
        return this.sectionsWithSpritesCount;
    }

    public int getSectionsWithEntitiesCount() {
        return this.sectionsWithEntitiesCount;
    }

    public RenderRegion getRegion() {
        return this.region;
    }

    public int size() {
        return this.size;
    }

    @Override
    public String toString() {
        var iterator = this.sectionsWithGeometryIterator();
        if (iterator == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int originX = this.region.getChunkX();
        int originY = this.region.getChunkY();
        int originZ = this.region.getChunkZ();
        while (iterator.hasNext()) {
            int sectionIndex = iterator.nextByteAsInt();
            int chunkX = originX + LocalSectionIndex.unpackX(sectionIndex);
            int chunkY = originY + LocalSectionIndex.unpackY(sectionIndex);
            int chunkZ = originZ + LocalSectionIndex.unpackZ(sectionIndex);
            sb.append("(").append(chunkX).append(", ").append(chunkY).append(", ").append(chunkZ).append(")");
            if (iterator.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
