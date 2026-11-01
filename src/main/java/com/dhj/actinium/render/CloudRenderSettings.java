package com.dhj.actinium.render;

import net.coderbot.iris.pipeline.SkyRenderDistance;

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
}
