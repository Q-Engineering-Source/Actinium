package com.dhj.actinium.render.iris;

import net.coderbot.iris.Iris;
import net.coderbot.iris.apiimpl.IrisApiV0Impl;
import net.coderbot.iris.layer.GbufferPrograms;

/**
 * Keeps Iris' outline G-buffer phase balanced around selection-box rendering.
 *
 * <p>ReplayMod cancels {@code RenderGlobal.drawSelectionBox} at HEAD while capturing video, so a
 * separate HEAD/RETURN pair can leave the outline phase active after that cancellation.
 */
public final class SelectionBoxOutlinePhase {
    private SelectionBoxOutlinePhase() {
    }

    /**
     * Runs selection-box rendering inside Iris' outline phase when a shader pack is active and
     * releases that phase even when a Mixin cancels or throws during the wrapped method.
     *
     * @param renderSelectionBox the original selection-box render operation
     */
    public static void runWithOutline(Runnable renderSelectionBox) {
        if (!Iris.enabled || !IrisApiV0Impl.INSTANCE.isShaderPackInUse()) {
            renderSelectionBox.run();
            return;
        }

        GbufferPrograms.beginOutline();
        try {
            renderSelectionBox.run();
        } finally {
            GbufferPrograms.endOutline();
        }
    }
}
