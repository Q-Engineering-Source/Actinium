package com.dhj.actinium.render;

import net.coderbot.iris.pipeline.SkyRenderDistance;

import com.dhj.actinium.runtime.ActiniumRuntime;

/**
 * Resolves the vanilla cloud mode Actinium renders at low render distances.
 *
 * <p>Vanilla {@code GameSettings.shouldRenderClouds()} returns {@code 0} below four chunks because the
 * shortened projection clips the cloud layer. Actinium raises the render distance that drives sky, cloud,
 * and celestial geometry to {@link SkyRenderDistance#MINIMUM_RENDER_DISTANCE_CHUNKS}, so the cloud layer
 * stays inside the projection and the player's cloud option stays renderable. The option keeps its vanilla
 * members: {@code 0} off, {@code 1} fast, {@code 2} fancy.</p>
 */
public final class CloudRenderSettings {
    private CloudRenderSettings() {
    }

    /**
     * Returns the cloud mode to render for the player's cloud option.
     *
     * <p>The option is handed through unchanged, so disabling clouds keeps them disabled and an enabled
     * mode still renders below the vanilla cloud render distance.</p>
     *
     * @param configuredClouds the player's cloud option: {@code 0} off, {@code 1} fast, {@code 2} fancy
     * @return the cloud mode to render
     */
    public static int effectiveCloudMode(int configuredClouds) {
        return configuredClouds;
    }

    /**
     * Returns whether the cloud layer is drawn for the player's cloud option.
     *
     * @param configuredClouds the player's cloud option: {@code 0} off, {@code 1} fast, {@code 2} fancy
     * @return {@code true} when the option enables either cloud mode
     */
    public static boolean cloudsVisible(int configuredClouds) {
        return configuredClouds != 0;
    }

    /**
     * Returns the render distance that drives the cloud disc.
     *
     * <p>The cloud disc is built from the render distance, so a dedicated cloud distance lets the layer
     * extend past the terrain without paying for more chunks. Zero in the option means the vanilla
     * render distance.</p>
     *
     * @param vanillaChunks the player's terrain render distance in chunks
     * @return the larger of the terrain render distance and the configured cloud render distance
     */
    public static int cloudRenderDistance(int vanillaChunks) {
        return Math.max(vanillaChunks, ActiniumRuntime.options().quality.cloudRenderDistanceChunks);
    }

    /**
     * Returns the cloud cell scale multiplier.
     *
     * @return the configured multiplier, or {@code 1} when the stored value would collapse the disc
     */
    public static int cloudScale() {
        final int scale = ActiniumRuntime.options().quality.cloudScale;
        return scale > 0 ? scale : 1;
    }
}
