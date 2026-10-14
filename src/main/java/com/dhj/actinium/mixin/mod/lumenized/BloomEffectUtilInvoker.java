package com.dhj.actinium.mixin.mod.lumenized;

import gregtech.client.utils.BloomEffectUtil;
import gregtech.client.utils.EffectRenderContext;
import net.minecraft.client.renderer.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Pseudo
@Mixin(value = BloomEffectUtil.class, remap = false)
public interface BloomEffectUtilInvoker {
    @Invoker(value = "draw", remap = false)
    static void actinium$invokeDraw(
        final BufferBuilder buffer, final EffectRenderContext context, final List<?> tickets
    ) {
        throw new AssertionError();
    }
}
