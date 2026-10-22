package com.dhj.actinium.mixin.vintage.core;

import com.dhj.actinium.texture.TextureDebugLabels;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureManager.class)
public class MixinTextureManagerDebugLabels {
    @Inject(method = "loadTexture", at = @At("RETURN"))
    private void celeritas$nameTextureObject(ResourceLocation resource, ITextureObject texture, CallbackInfoReturnable<Boolean> cir) {
        TextureDebugLabels.nameTextureObject(resource, texture);
    }
}
