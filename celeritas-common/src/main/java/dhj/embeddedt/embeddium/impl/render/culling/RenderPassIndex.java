package dhj.embeddedt.embeddium.impl.render.culling;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;

/**
 * Stable index assignment for {@link TerrainRenderPass} instances, keyed by pass name so that a rebuilt render
 * configuration keeps the slots it published to the section mirror.
 *
 * <p>The index is part of the section key the compute backend hashes sections by, so the budget is small and explicit:
 * the key reserves three bits for it, and {@link #indexOf} rejects a ninth pass name instead of aliasing it onto the
 * sections of another pass.
 */
public final class RenderPassIndex {
    public static final int MAX_PASSES = 8;

    private static final Object2IntOpenHashMap<String> INDICES = new Object2IntOpenHashMap<>(MAX_PASSES);

    static {
        INDICES.defaultReturnValue(-1);
    }

    private static int nextIndex;

    private RenderPassIndex() {
    }

    public static synchronized int indexOf(TerrainRenderPass pass) {
        final String name = pass.name();
        final int existing = INDICES.getInt(name);
        if (existing >= 0) {
            return existing;
        }
        if (nextIndex >= MAX_PASSES) {
            throw new IllegalStateException("RenderPassIndex: more than " + MAX_PASSES
                + " distinct TerrainRenderPass names; raise MAX_PASSES and the pass-bit budget in SectionMetaBuffer.sectionKey. Registered: " + INDICES);
        }
        final int assigned = nextIndex++;
        INDICES.put(name, assigned);
        return assigned;
    }
}
