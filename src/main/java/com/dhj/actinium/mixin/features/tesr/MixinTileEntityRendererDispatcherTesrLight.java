package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.tesr.TesrLightCache;
import com.gtnewhorizons.angelica.rendering.tesr.TileEntityLight;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shortens the two light lookups every tile entity costs per frame.
 *
 * <p>The dispatcher asks the world for the combined light of the tile entity position once per
 * block entity per frame. The combined light only changes inside a client tick, so the value is
 * cached per position for one tick. Under a shader pack the packed value is then post-processed by
 * {@link TileEntityLight}, which reproduces the modern neighbour-brightness behaviour the fixed
 * function pipeline never had.</p>
 */
@Mixin(TileEntityRendererDispatcher.class)
public abstract class MixinTileEntityRendererDispatcherTesrLight {

    @Shadow
    public World world;

    @ModifyExpressionValue(
        method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getCombinedLight(Lnet/minecraft/util/math/BlockPos;I)I")
    )
    private int actinium$tesrLight(int vanillaLight, @Local(argsOnly = true) TileEntity te, @Local BlockPos pos) {
        if (te == null) return vanillaLight;
        final int cached = TesrLightCache.getLightBrightnessForSkyBlocks(this.world, pos.getX(), pos.getY(), pos.getZ(), 0);
        if (!IrisApi.getInstance().isShaderPackInUse()) return cached;
        return TileEntityLight.packedLight(this.world, pos.getX(), pos.getY(), pos.getZ(), cached);
    }

    @Inject(method = "setWorld", at = @At("HEAD"))
    private void actinium$dropTesrLightmapCache(World world, CallbackInfo ci) {
        TesrLightCache.clear();
    }
}
