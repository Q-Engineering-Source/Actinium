package dhj.embeddedt.embeddium.impl.render.chunk.region;

import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.CompactChunkVertex;

/**
 * Creates a {@link RenderRegion} for a test that owns no region manager and no staging buffer. The production
 * constructor is package-private, so a test outside the region package needs this factory to build the regions whose
 * sections it drives.
 *
 * <p>The arena stride is the one a real region of the compact quad pass reports, so a fixture region describes its
 * geometry arena the way production does even though the fixture never uploads geometry.
 */
public final class TestRegions {
    /**
     * Element size of a region's geometry arena for the compact quad format.
     */
    private static final int ARENA_STRIDE =
            CompactChunkVertex.VERTEX_FORMAT.getStride() * QuadPrimitiveType.TRIANGULATED.getVerticesPerPrimitive();

    private TestRegions() {
    }

    public static RenderRegion create(int x, int y, int z, int id) {
        return new RenderRegion(x, y, z, id, null, ARENA_STRIDE);
    }
}
