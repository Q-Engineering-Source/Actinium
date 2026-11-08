package dhj.embeddedt.embeddium.impl.render.chunk.region;

/**
 * Creates a {@link RenderRegion} for a test without a region manager. The production constructor stays reachable only
 * from the region package, and a test that drives the renderer directly owns no staging buffer and no region manager.
 */
public final class RenderRegionTestFactory {

    private RenderRegionTestFactory() {
    }

    public static RenderRegion create(int chunkX, int chunkY, int chunkZ, int id) {
        return new RenderRegion(chunkX, chunkY, chunkZ, id, null);
    }
}
