package dhj.embeddedt.embeddium.impl.render.chunk.lists;

import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;

import java.util.Iterator;

public interface ChunkRenderListIterable {
    Iterator<ChunkRenderList> iterator(boolean reverse);

    int getNumRegions();

    default Iterator<ChunkRenderList> iterator() {
        return this.iterator(false);
    }

    /**
     * {@return the render list for region index {@code index}, in the order the lists were collected}
     *
     * <p>Indexed access lets a pass walk the region list in its own order (the shadow voxelizer walks
     * forward or reversed depending on the pass) without materialising the lists first.</p>
     */
    ChunkRenderList getRegion(int index);

    /**
     * {@return true if there are sections that need rendering for the given pass}
     */
    default boolean hasPass(TerrainRenderPass pass) {
        return true;
    }
}
