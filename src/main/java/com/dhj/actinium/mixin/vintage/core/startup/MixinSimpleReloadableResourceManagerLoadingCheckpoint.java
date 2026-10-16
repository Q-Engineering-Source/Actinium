package com.dhj.actinium.mixin.vintage.core.startup;

import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(SimpleReloadableResourceManager.class)
public class MixinSimpleReloadableResourceManagerLoadingCheckpoint {

    // A reload invalidates GPU-resident content on the loading screen, so this checkpoint asks the
    // backend for a synchronized flush rather than a plain frame blit.
    @Inject(method = "reloadResources", at = @At("RETURN"))
    private void celeritas$fireSyncCheckpointOnReload(List<IResourcePack> packs, CallbackInfo ci) {
        if (GLSMHooks.LOADING_CHECKPOINT.hasListeners()) {
            GLSMHooks.loadingCheckpointEvent.requiresSync = true;
            GLSMHooks.LOADING_CHECKPOINT.post(GLSMHooks.loadingCheckpointEvent);
            GLSMHooks.loadingCheckpointEvent.requiresSync = false;
        }
    }
}
