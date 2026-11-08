package dhj.embeddedt.embeddium.impl.render.chunk.lists;

import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;

import java.util.Iterator;
import java.util.Set;

public interface ChunkRenderListIterable {
    Iterator<ChunkRenderList> iterator(boolean reverse);

    int getNumRegions();

    /**
     * {@return the render list at {@code index} in iteration order}
     *
     * @throws IndexOutOfBoundsException when {@code index} is outside {@code [0, getNumRegions())}
     */
    ChunkRenderList getRegion(int index);

    default Iterator<ChunkRenderList> iterator() {
        return this.iterator(false);
    }

    /**
     * {@return true if there are sections that need rendering for the given pass}
     */
    default boolean hasPass(TerrainRenderPass pass) {
        return true;
    }

    /**
     * {@return the render passes these lists hold sections for} The compute cull walk prepares one command range per
     * pass, so a list that under-reports its passes leaves those sections on the CPU path.
     */
    Set<TerrainRenderPass> getPasses();
}
