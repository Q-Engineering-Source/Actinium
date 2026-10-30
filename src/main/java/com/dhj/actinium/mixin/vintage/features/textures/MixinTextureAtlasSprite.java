package com.dhj.actinium.mixin.vintage.features.textures;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.dhj.actinium.texture.SpriteExtension;

@Mixin(TextureAtlasSprite.class)
public abstract class MixinTextureAtlasSprite implements SpriteExtension {
    private boolean celeritas$isActive = false;

    @Override
    public void celeritas$markActive() {
        this.celeritas$isActive = true;
    }

    @Override
    public boolean celeritas$shouldUpdate() {
        if (this.celeritas$isActive) {
            this.celeritas$isActive = false;
            return true;
        } else {
            return false;
        }
    }

    @ModifyReturnValue(method = { "getMinU", "getInterpolatedU" }, at = @At("RETURN"))
    private float markActiveWhenGettingCoords(float original) {
        this.celeritas$isActive = true;
        return original;
    }
}
