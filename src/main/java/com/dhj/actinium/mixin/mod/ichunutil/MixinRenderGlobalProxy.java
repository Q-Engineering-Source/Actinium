package com.dhj.actinium.mixin.mod.ichunutil;

import com.dhj.actinium.render.ManagedCodeBoundary;
import com.dhj.actinium.render.terrain.ActiniumWorldRenderer;
import me.ichun.mods.ichunutil.common.module.worldportals.client.render.world.RenderGlobalProxy;
import net.minecraft.client.Minecraft;
import dhj.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderGlobalProxy.class, remap = false)
public class MixinRenderGlobalProxy {
    @Inject(method = "loadRenderers", remap = true, at = @At("TAIL"))
    private void actinium$reloadWorldRenderer(CallbackInfo ci) {
        ActiniumWorldRenderer renderer = SimpleWorldRenderer.Provider.getWorldRenderer(this);
        if (!renderer.isRenderingWorld(Minecraft.getMinecraft().world)) {
            return;
        }

        ManagedCodeBoundary.enter();
        try {
            renderer.reload();
        } finally {
            ManagedCodeBoundary.exit();
        }
    }
}
