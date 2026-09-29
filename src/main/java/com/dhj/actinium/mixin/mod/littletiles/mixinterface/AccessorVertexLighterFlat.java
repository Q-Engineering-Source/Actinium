package com.dhj.actinium.mixin.mod.littletiles.mixinterface;

import net.minecraftforge.client.model.pipeline.BlockInfo;
import net.minecraftforge.client.model.pipeline.VertexLighterFlat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VertexLighterFlat.class)
public interface AccessorVertexLighterFlat {
    /** Exposes the source multiplier needed to separate AO from Forge's packed vertex color. */
    @Accessor("blockInfo")
    BlockInfo actinium$getBlockInfo();
}
