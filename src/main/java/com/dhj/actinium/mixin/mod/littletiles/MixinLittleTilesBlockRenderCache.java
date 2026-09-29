package com.dhj.actinium.mixin.mod.littletiles;

import com.creativemd.littletiles.client.render.cache.BlockRenderCache;
import com.creativemd.littletiles.client.render.cache.BufferLink;
import com.creativemd.littletiles.client.render.cache.IRenderDataCache;
import com.creativemd.littletiles.client.render.world.TileEntityRenderManager;
import com.dhj.actinium.compat.littletiles.LittleTilesCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/** Carries shader contexts onto the link created for an uploaded LittleTiles layer. */
@Mixin(value = BlockRenderCache.class, remap = false)
public abstract class MixinLittleTilesBlockRenderCache {
    @Shadow
    public BufferLink link;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void actinium$copyQuadContexts(
            TileEntityRenderManager manager,
            int layer,
            IRenderDataCache cache,
            ByteBuffer buffer,
            CallbackInfo ci
    ) {
        if (this.link != null) {
            LittleTilesCompat.copyCachedQuadContexts(this.link, cache);
        }
    }
}
