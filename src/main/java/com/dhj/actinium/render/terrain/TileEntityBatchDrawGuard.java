package com.dhj.actinium.render.terrain;

import java.util.Objects;

/**
 * Protects Forge's shared tile entity batch from a second draw after nested world renderers flush it.
 */
public final class TileEntityBatchDrawGuard {
    private TileEntityBatchDrawGuard() {
    }

    /**
     * Draws a batch only while its BufferBuilder is still building, preserving failures from a real draw.
     */
    public static boolean drawIfBuilding(boolean building, Runnable draw) {
        Objects.requireNonNull(draw, "draw");
        if (!building) {
            return false;
        }

        draw.run();
        return true;
    }
}
