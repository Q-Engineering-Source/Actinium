package com.dhj.actinium.compat.componentmodelhider;

import com.gtnewhorizon.gtnhlib.compat.Mods;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/**
 * Compatibility for the Component Model Hider ({@code component_model_hider}), whose hiding only
 * works inside a vanilla {@code RenderChunk#rebuildChunk}: its {@code BlockModelRenderer} and
 * CodeChickenLib redirects consult the disabled-position set only while the hider's thread-local
 * build flag is armed, and they also carry the neighbour-face rule. Actinium meshes terrain in
 * {@code ChunkBuilderMeshingTask} and never calls {@code rebuildChunk}, so this class re-applies
 * the per-position skip and the neighbour rule from the Actinium mesher.
 *
 * <p>No hider class is loaded while the mod is absent: foreign references live in
 * {@link ComponentModelHiderBridge}, loaded only when {@link #IS_LOADED} is true.</p>
 */
public final class ComponentModelHiderCompat {
    /** The mod id the hider ships under (CurseForge project 940949, file 4885858). */
    public static final String MODID = "component_model_hider";

    /** Whether the hider is present; read once so call sites decide with a constant. */
    public static final boolean IS_LOADED = Mods.COMPONENT_MODEL_HIDER;

    private ComponentModelHiderCompat() {
    }

    /**
     * Returns whether the block model at {@code pos} is currently hidden. Answers only while
     * {@link #beginBuild()} is in effect on the calling thread, the same gate the hider applies,
     * so overlays outside a chunk build keep rendering hidden positions.
     */
    public static boolean isHidden(BlockPos pos) {
        if (!IS_LOADED) {
            return false;
        }
        return ComponentModelHiderBridge.isHidden(pos);
    }

    /**
     * Returns whether the neighbour of {@code pos} towards {@code dir} is hidden, in which case the
     * face looking at it must still be drawn. The mesher has to apply this rule itself: the hider
     * carries it only inside {@code BlockModelRenderer} redirects, which the mesher bypasses, so
     * without this a hidden block punches a see-through hole into every adjacent block.
     */
    public static boolean isNeighbourHidden(BlockPos pos, EnumFacing dir) {
        if (!IS_LOADED) {
            return false;
        }
        return ComponentModelHiderBridge.isHidden(pos.offset(dir));
    }

    /**
     * Arms the hider's thread-local culling gate for one chunk mesh build on the calling thread,
     * mirroring what the hider's {@code RenderChunkMixin} did around each vanilla
     * {@code renderBlock} call.
     */
    public static void beginBuild() {
        if (IS_LOADED) {
            ComponentModelHiderBridge.beginBuild();
        }
    }

    /** Disarms {@link #beginBuild()}, restoring the state a fresh worker thread starts from. */
    public static void endBuild() {
        if (IS_LOADED) {
            ComponentModelHiderBridge.endBuild();
        }
    }
}
