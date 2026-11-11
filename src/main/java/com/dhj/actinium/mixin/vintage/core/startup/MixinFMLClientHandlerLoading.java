package com.dhj.actinium.mixin.vintage.core.startup;

import com.dhj.actinium.startup.LoadingCheckpoints;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FMLClientHandler.class, remap = false)
public class MixinFMLClientHandlerLoading {
    @Inject(method = "processWindowMessages", at = @At("RETURN"))
    private void actinium$presentLoadingFrame(CallbackInfo ci) {
        LoadingCheckpoints.post(false);
    }
}
