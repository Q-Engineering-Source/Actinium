package com.dhj.actinium.mixin.vintage.core.startup;

import com.dhj.actinium.startup.LoadingCheckpoints;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleReloadableResourceManager.class)
public class MixinResourceReloadCheckpoint {
    @Inject(method = "reloadResources", at = @At("RETURN"))
    private void actinium$finishResourceUploads(CallbackInfo ci) {
        LoadingCheckpoints.post(true);
    }
}
