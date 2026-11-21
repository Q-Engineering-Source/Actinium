package com.dhj.actinium.mixin.features.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.layer.GbufferPrograms;
import net.minecraft.client.renderer.tileentity.TileEntityBeaconRenderer;
import net.minecraft.tileentity.TileEntityBeacon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(TileEntityBeaconRenderer.class)
public class TileEntityBeaconRendererIrisMixin {
    @WrapOperation(
        method = "render(Lnet/minecraft/tileentity/TileEntityBeacon;DDDFIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/tileentity/TileEntityBeaconRenderer;renderBeacon(DDDDDLjava/util/List;D)V"
        )
    )
    private void actinium$drawVanillaBeaconBeam(
        TileEntityBeaconRenderer renderer,
        double x,
        double y,
        double z,
        double partialTicks,
        double textureScale,
        List<TileEntityBeacon.BeamSegment> segments,
        double worldTime,
        Operation<Void> original
    ) {
        // Cached beams can cancel the TESR and set their condition when their render layer draws.
        GbufferPrograms.setupSpecialRenderCondition(SpecialCondition.BEACON_BEAM);
        try {
            original.call(renderer, x, y, z, partialTicks, textureScale, segments, worldTime);
        } finally {
            GbufferPrograms.teardownSpecialRenderCondition();
        }
    }
}
