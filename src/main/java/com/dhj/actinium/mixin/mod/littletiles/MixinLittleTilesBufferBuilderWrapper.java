package com.dhj.actinium.mixin.mod.littletiles;

import com.creativemd.littletiles.client.render.cache.LayeredRenderBufferCache;
import com.dhj.actinium.compat.littletiles.LittleTilesCompat;
import com.dhj.actinium.compat.littletiles.LittleTilesQuadContextCarrier;
import dhj.embeddedt.embeddium.api.shader.buffer.VanillaQuadContext;
import net.minecraft.client.renderer.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Snapshots each source builder's contexts into LittleTiles' reusable cache wrapper. */
@Mixin(value = LayeredRenderBufferCache.BufferBuilderWrapper.class, remap = false)
public abstract class MixinLittleTilesBufferBuilderWrapper implements LittleTilesQuadContextCarrier {
    @Unique
    private List<VanillaQuadContext> actinium$quadContexts = List.of();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void actinium$captureContexts(BufferBuilder builder, CallbackInfo ci) {
        LittleTilesCompat.captureCachedQuadContexts(this, builder);
    }

    @Override
    public List<VanillaQuadContext> actinium$getQuadContexts() {
        return this.actinium$quadContexts;
    }

    @Override
    public void actinium$setQuadContexts(List<VanillaQuadContext> contexts) {
        this.actinium$quadContexts = List.copyOf(contexts);
    }
}
