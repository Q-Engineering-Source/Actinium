package com.dhj.actinium.compat.chunkanimator;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizon.gtnhlib.compat.Mods;
import lumien.chunkanimator.ChunkAnimator;
import lumien.chunkanimator.handler.AnimationHandler;
import net.minecraft.util.math.BlockPos;
import dhj.embeddedt.embeddium.api.render.chunk.ChunkAnimationProvider;
import dhj.embeddedt.embeddium.api.render.chunk.ChunkAnimationProviderHolder;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.lwjgl.util.vector.Vector3f;

import java.util.Objects;

/**
 * Bridges the vanilla-oriented Chunk Animator (mod id {@code chunkanimator}) into the celeritas
 * chunk pipeline. Chunk Animator is an ASM coremod hooking {@code RenderChunk#setOrigin} (record
 * animation start) and {@code ChunkRenderContainer#preRenderChunk} (translate while rendering);
 * neither hook runs under Actinium, so {@link #onSectionAdded} and {@link #getSectionOffset}
 * re-attach the same two calls to the celeritas section lifecycle. The section serves as the
 * animation identity token: {@link AnimationHandler} keys timestamps by object identity through
 * a {@code WeakHashMap}, so disposed sections are reclaimed automatically.
 */
public final class ChunkAnimatorCompat implements ChunkAnimationProvider {
    private final AnimationHandler animationHandler;

    private ChunkAnimatorCompat(AnimationHandler animationHandler) {
        this.animationHandler = Objects.requireNonNull(animationHandler, "animationHandler");
    }

    /**
     * Installs the compatibility provider when Chunk Animator is loaded. Must be called after Chunk
     * Animator's pre-init has run (i.e. from the host mod's init phase), so that its animation
     * handler is already constructed.
     */
    public static void install() {
        if (!Mods.CHUNKANIMATOR) {
            return;
        }

        ChunkAnimator instance = ChunkAnimator.INSTANCE;
        if (instance == null || instance.animationHandler == null) {
            ActiniumRuntime.logger().error(
                "Chunk Animator is loaded but its animation handler is unavailable; chunk animations will not be applied");
            return;
        }

        ChunkAnimationProviderHolder.setProvider(new ChunkAnimatorCompat(instance.animationHandler));
        ActiniumRuntime.logger().info("Chunk Animator compatibility layer enabled");
    }

    @Override
    public void onSectionAdded(RenderSection section) {
        // Mirrors RenderChunk#setOrigin: record the animation start for a section that has just
        // entered the render distance.
        this.animationHandler.setOrigin(section,
            new BlockPos(section.getOriginX(), section.getOriginY(), section.getOriginZ()));
    }

    @Override
    public boolean getSectionOffset(RenderSection section, float[] out) {
        // Mirrors ChunkRenderContainer#preRenderChunk: query the current animation offset and let
        // the renderer draw this section translated by it.
        Vector3f offset = this.animationHandler.getOffset(section,
            new BlockPos(section.getOriginX(), section.getOriginY(), section.getOriginZ()));
        if (offset == null) {
            return false;
        }
        out[0] = offset.x;
        out[1] = offset.y;
        out[2] = offset.z;
        return true;
    }
}
