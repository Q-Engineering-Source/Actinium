package com.dhj.actinium.mixin.mod.littletiles;

import com.creativemd.littletiles.client.render.cache.BufferLink;
import com.creativemd.littletiles.client.render.cache.IRenderDataCache;
import com.creativemd.littletiles.client.render.cache.LayeredRenderBufferCache;
import com.dhj.actinium.compat.littletiles.LittleTilesCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserves context order when LittleTiles concatenates cached vertex data. */
@Mixin(value = LayeredRenderBufferCache.class, remap = false)
public abstract class MixinLittleTilesRenderBufferCache {
    @Inject(method = "combine", at = @At("RETURN"))
    private void actinium$combineQuadContexts(
            int layer,
            IRenderDataCache first,
            IRenderDataCache second,
            CallbackInfoReturnable<BufferLink> cir
    ) {
        LittleTilesCompat.combineCachedQuadContexts(cir.getReturnValue(), first, second);
    }
}
