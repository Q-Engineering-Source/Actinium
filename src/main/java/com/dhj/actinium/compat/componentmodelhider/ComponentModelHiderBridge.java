package com.dhj.actinium.compat.componentmodelhider;

import com.cleanroommc.multiblocked.persistence.MultiblockWorldSavedData;
import net.minecraft.util.math.BlockPos;

/**
 * The hider's own API surface, isolated so {@link ComponentModelHiderCompat} and its call sites
 * link while the mod is absent; only loaded once {@link ComponentModelHiderCompat#IS_LOADED} is
 * true. Exposes exactly the three calls the mesher needs and delegates the predicate to the
 * hider's {@code isModelDisabled}, keeping the gating semantics owned by the mod.
 */
final class ComponentModelHiderBridge {
    private ComponentModelHiderBridge() {
    }

    /**
     * Returns whether {@code pos} is hidden. Consulted only while the hider's build flag is armed by
     * {@link #beginBuild()}; outside a build the mod's own gate reports every position as enabled.
     */
    static boolean isHidden(BlockPos pos) {
        return MultiblockWorldSavedData.isModelDisabled(pos);
    }

    /** Sets the hider's per-thread build flag, arming its {@code BlockModelRenderer} redirects and CodeChickenLib hooks. */
    static void beginBuild() {
        MultiblockWorldSavedData.isBuildingChunk.set(Boolean.TRUE);
    }

    /** Clears the flag set by {@link #beginBuild()}; the thread local defaults to false. */
    static void endBuild() {
        MultiblockWorldSavedData.isBuildingChunk.set(Boolean.FALSE);
    }
}
