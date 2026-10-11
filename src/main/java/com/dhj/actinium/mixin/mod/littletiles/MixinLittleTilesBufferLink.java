package com.dhj.actinium.mixin.mod.littletiles;

import com.creativemd.littletiles.client.render.cache.BufferLink;
import com.dhj.actinium.compat.littletiles.LittleTilesQuadContextCarrier;
import dhj.embeddedt.embeddium.api.shader.buffer.VanillaQuadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

/** Stores shader contexts beside LittleTiles buffers after concatenation or upload. */
@Mixin(value = BufferLink.class, remap = false)
public abstract class MixinLittleTilesBufferLink implements LittleTilesQuadContextCarrier {
    @Unique
    private List<VanillaQuadContext> actinium$quadContexts = List.of();

    @Override
    public List<VanillaQuadContext> actinium$getQuadContexts() {
        return this.actinium$quadContexts;
    }

    @Override
    public void actinium$setQuadContexts(List<VanillaQuadContext> contexts) {
        this.actinium$quadContexts = List.copyOf(contexts);
    }
}
