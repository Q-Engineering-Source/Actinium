package com.dhj.actinium.mixin.mod.littletiles.mixinterface;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes vanilla's AO multipliers so LittleTiles can put them in the shader's AO channel. */
@Mixin(targets = "net.minecraft.client.renderer.BlockModelRenderer$AmbientOcclusionFace")
public interface AccessorAmbientOcclusionFace {
    @Accessor("vertexColorMultiplier")
    float[] actinium$getVertexColorMultiplier();
}
