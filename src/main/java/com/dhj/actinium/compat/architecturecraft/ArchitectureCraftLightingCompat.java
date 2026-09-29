package com.dhj.actinium.compat.architecturecraft;

import net.coderbot.iris.block_rendering.BlockRenderingSettings;

/** Selects ArchitectureCraft's shader-pack-specific vertex lighting convention. */
public final class ArchitectureCraftLightingCompat {
    private ArchitectureCraftLightingCompat() {
    }

    /** Returns whether ArchitectureCraft must store its CPU lighting factor in vertex alpha. */
    public static boolean shouldWriteSeparateAo() {
        return ArchitectureCraftCompat.IS_LOADED && BlockRenderingSettings.INSTANCE.shouldUseSeparateAo();
    }

    /** Returns whether the active shader pack disables vanilla directional face shading. */
    public static boolean shouldDisableDirectionalShading() {
        return BlockRenderingSettings.INSTANCE.shouldDisableDirectionalShading();
    }
}
