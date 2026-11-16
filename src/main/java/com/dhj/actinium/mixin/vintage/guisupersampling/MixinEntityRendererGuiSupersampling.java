package com.dhj.actinium.mixin.vintage.guisupersampling;

import com.dhj.actinium.render.RenderingState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Marks the world pass so GUI items drawn during it never pick up the GUI supersampling path. */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererGuiSupersampling {

    @WrapMethod(method = "renderWorld(FJ)V")
    private void actinium$markWorldRendering(EntityRenderer instance, float partialTicks, long finishTimeNano,
                                             Operation<Void> original) {
        final boolean previousWorld = RenderingState.isRenderingWorld();
        RenderingState.setRenderingWorld(true);
        try {
            original.call(instance, partialTicks, finishTimeNano);
        } finally {
            RenderingState.setRenderingWorld(previousWorld);
        }
    }
}
