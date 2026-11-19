package com.dhj.actinium.mixin.features.clouds;

import com.dhj.actinium.render.CloudRenderer;
import net.minecraft.world.WorldProvider;
import net.minecraftforge.client.IRenderHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes cloud drawing through the Actinium renderer.
 *
 * <p>Forge asks {@code WorldProvider.getCloudRenderer()} before it runs its own cloud renderer, and
 * any non-null result replaces the vanilla path entirely. Returning the Actinium renderer for every
 * dimension keeps the cloud layer under one implementation; the renderer records the handler the
 * dimension installed and delegates to it unless the dimension is one Actinium can rebuild from known
 * parameters.</p>
 */
@Mixin(WorldProvider.class)
public abstract class MixinWorldProviderClouds {
    @Inject(method = "getCloudRenderer", at = @At("RETURN"), cancellable = true)
    private void actinium$provideCloudRenderer(CallbackInfoReturnable<IRenderHandler> cir) {
        final CloudRenderer clouds = CloudRenderer.getCloudRenderer();
        clouds.setDimensionRenderer(cir.getReturnValue());
        cir.setReturnValue(clouds);
    }
}
