package com.gtnewhorizons.angelica.api.clouds;

import java.util.List;

import net.minecraft.client.multiplayer.WorldClient;

/**
 * Source of the cloud layers drawn for a world.
 *
 * <p>A dimension render handler or a world provider that implements this interface replaces the vanilla
 * cloud layer with the layers it returns, which lets planets, orbiting stations or custom dimensions bring
 * their own clouds without patching the renderer.
 */
public interface CloudLayerProvider {

    /**
     * Returns the layers to draw this frame.
     *
     * @param world client world the clouds are drawn in, for providers that vary their layers by world
     * @param cloudTicks tick counter the world advances for cloud animation, used to scroll the layers
     * @param partialTicks fraction of the current tick, used to interpolate the scroll offsets
     * @return the layers to draw, or an empty list to draw no clouds at all
     */
    List<CloudLayer> getCloudLayers(WorldClient world, int cloudTicks, float partialTicks);
}
