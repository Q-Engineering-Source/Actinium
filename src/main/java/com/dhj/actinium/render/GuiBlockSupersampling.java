package com.dhj.actinium.render;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.GLStateManager;

/**
 * Applies the configured atlas supersampling while a GUI item is drawn.
 *
 * <p>Supersampling only makes sense for the item atlas: it keeps the sampled footprint inside a sprite's gutter
 * so the neighbouring sprites cannot bleed into a scaled-down item. World draws and drawers that already carry
 * their own atlas handling are excluded.</p>
 */
public final class GuiBlockSupersampling {

    private GuiBlockSupersampling() {}

    /** @return the previous sample count, or {@code 0} when nothing was changed */
    public static int begin() {
        if (!RenderingState.isRenderingGuiItem() || RenderingState.isRenderingWorld()) return 0;
        final int samples = ActiniumRuntime.options().quality.guiBlockSupersampling.samples();
        return samples > 1 ? GLStateManager.setTextureSupersamplingSamples(samples) : 0;
    }

    public static void end(int previous) {
        if (previous != 0) GLStateManager.setTextureSupersamplingSamples(previous);
    }
}
