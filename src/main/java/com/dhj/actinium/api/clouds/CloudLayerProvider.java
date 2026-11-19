package com.dhj.actinium.api.clouds;

import java.util.List;

import net.minecraft.client.multiplayer.WorldClient;

public interface CloudLayerProvider {

    List<CloudLayer> getCloudLayers(WorldClient world, int cloudTicks, float partialTicks);
}
