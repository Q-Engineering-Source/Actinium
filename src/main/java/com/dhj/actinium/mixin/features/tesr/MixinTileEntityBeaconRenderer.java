package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.tesr.BeaconBeamMeshes;
import net.minecraft.client.renderer.tileentity.TileEntityBeaconRenderer;
import net.minecraft.tileentity.TileEntityBeacon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the beacon beam from cached meshes instead of rebuilding both shells every frame.
 *
 * <p>The beam is two constant cylinders; only the texture matrix and the model transform change
 * per frame. {@link BeaconBeamMeshes} keeps them in the shared TESR mesh cache and falls back to
 * the vanilla renderer while the beam is still fading in.</p>
 */
@Mixin(TileEntityBeaconRenderer.class)
public abstract class MixinTileEntityBeaconRenderer {

    @Inject(
        method = "render(Lnet/minecraft/tileentity/TileEntityBeacon;DDDFIF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void actinium$batchBeam(TileEntityBeacon beacon, double x, double y, double z, float partialTicks,
                                    int destroyStage, float alpha, CallbackInfo ci) {
        if (BeaconBeamMeshes.render(beacon, x, y, z, partialTicks)) {
            ci.cancel();
        }
    }
}
