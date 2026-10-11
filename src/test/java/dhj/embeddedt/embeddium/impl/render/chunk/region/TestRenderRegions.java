package dhj.embeddedt.embeddium.impl.render.chunk.region;

/**
 * Test factory for {@link RenderRegion}.
 *
 * <p>{@code RenderRegion}'s constructor is package-private because regions are only ever built by
 * {@code RenderRegionManager}, so tests that need a real region - with real storages and section
 * mask writes - have to live in this package. Keep the construction here rather than reflecting into
 * the constructor, so the compiler keeps telling us when the constructor shape changes.</p>
 */
public final class TestRenderRegions {

    private TestRenderRegions() {}

    /** Creates a region with no staging buffer; only tests that never upload need this. */
    public static RenderRegion create(int x, int y, int z, int id) {
        return new RenderRegion(x, y, z, id, null);
    }
}
