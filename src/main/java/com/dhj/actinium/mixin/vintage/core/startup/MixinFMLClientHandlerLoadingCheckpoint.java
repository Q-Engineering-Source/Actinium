package com.dhj.actinium.mixin.vintage.core.startup;

import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FMLClientHandler.class, remap = false)
public class MixinFMLClientHandlerLoadingCheckpoint {

    // FML pumps window messages at every ProgressManager step; each one is a chance for the
    // backend to blit a finished splash frame while loading keeps the main thread busy.
    @Inject(method = "processWindowMessages", at = @At("RETURN"))
    private void celeritas$fireLoadingCheckpoint(CallbackInfo ci) {
        if (GLSMHooks.LOADING_CHECKPOINT.hasListeners()) {
            GLSMHooks.LOADING_CHECKPOINT.post(GLSMHooks.loadingCheckpointEvent);
        }
    }
}
