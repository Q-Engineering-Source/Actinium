package dhj.embeddedt.embeddium.impl.render.chunk.region;

/**
 * Creates a {@link RenderRegion} for a test without a region manager. The production constructor stays reachable only
 * from the region package, and a test that drives the renderer directly owns no staging buffer and no region manager.
 */
public final class RenderRegionTestFactory {

    private RenderRegionTestFactory() {
    }

    /**
     * @param commonVertexStride element size of the region's geometry arena; pass the vertex stride of the pass whose
     *                           geometry the fixture builds itself, which makes one arena element one vertex
     */
    public static RenderRegion create(int chunkX, int chunkY, int chunkZ, int id, int commonVertexStride) {
        return new RenderRegion(chunkX, chunkY, chunkZ, id, null, commonVertexStride);
    }
}
