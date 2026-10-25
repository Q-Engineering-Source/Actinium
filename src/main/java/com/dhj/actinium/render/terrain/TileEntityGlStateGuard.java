package com.dhj.actinium.render.terrain;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import org.lwjgl.opengl.GL11;

/**
 * Saves and restores the full GLSM-tracked GL state around tile-entity (TESR) batches.
 *
 * <p>Vanilla relies on tile-entity renderers leaving GL state as they found it, but modded TESRs
 * (notably HBM-CE machines) leak depth mask/func, blend and texture state, and Actinium renders
 * block entities as a dedicated stage inside the chunk render pass with no state normalization
 * afterward, so a leak lands directly in the translucent terrain pass and the HUD. GLSM already
 * maintains a complete tracked-state stack ({@link GLStateManager#glPushAttrib(int)}); this guard
 * wires it into the TESR path. Matrices are not covered (push/pop attrib semantics); the vanilla
 * dispatcher already wraps each tile entity in its own matrix push/pop.</p>
 */
public final class TileEntityGlStateGuard {
    private TileEntityGlStateGuard() {
    }

    /**
     * Saves all GLSM-tracked GL state before a tile-entity batch.
     */
    public static void push() {
        GLStateManager.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
    }

    /**
     * Restores the state captured by the matching {@link #push()}.
     */
    public static void pop() {
        GLStateManager.glPopAttrib();
    }

    /**
     * Restores the state captured by the latest {@link #push()} and immediately re-saves it.
     *
     * <p>Used between the tile-entity render loop and the FastTESR batch flush: the batch draw
     * relies on ambient state (texture units, alpha test, depth func/mask), so it must run with
     * the clean entry state, and the re-push keeps the enclosing {@link #pop()} balanced.</p>
     */
    public static void restoreForBatch() {
        pop();
        push();
    }
}
