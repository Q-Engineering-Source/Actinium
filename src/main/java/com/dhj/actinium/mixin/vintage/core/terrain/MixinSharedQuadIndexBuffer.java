package com.dhj.actinium.mixin.vintage.core.terrain;

import com.dhj.actinium.render.terrain.TerrainDrawStats;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.render.chunk.SharedQuadIndexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SharedQuadIndexBuffer.class, remap = false)
public abstract class MixinSharedQuadIndexBuffer {

    @Inject(method = "grow", at = @At("HEAD"))
    private void actinium$onGrow(CommandList commandList, int primitiveCount, CallbackInfo ci) {
        TerrainDrawStats.recordIndexBufferGrowth();
    }
}
