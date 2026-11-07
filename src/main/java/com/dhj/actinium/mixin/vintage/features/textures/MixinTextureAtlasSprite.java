package com.dhj.actinium.mixin.vintage.features.textures;

import com.dhj.actinium.texture.PBRAnimationMarks;
import com.dhj.actinium.texture.SpriteAnimationDryRun;
import com.dhj.actinium.texture.SpriteExtension;
import com.gtnewhorizons.angelica.mixins.interfaces.IPatchedTextureAtlasSprite;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TextureAtlasSprite.class)
public abstract class MixinTextureAtlasSprite implements SpriteExtension, IPatchedTextureAtlasSprite {
    private boolean celeritas$isActive = false;

    @Shadow
    public abstract void updateAnimation();

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

    @Override
    public void angelica$markNeedsAnimationUpdate() {
        this.celeritas$isActive = true;
    }

    @Override
    public boolean angelica$needsAnimationUpdate() {
        if (this.celeritas$isActive) {
            this.celeritas$isActive = false;
            // PBR sprites are never sampled through sprite UVs, so they inherit the visibility their
            // base sprite just consumed.
            PBRAnimationMarks.markFromBaseSprite((TextureAtlasSprite) (Object) this);
            return true;
        }
        return false;
    }

    @Override
    public void angelica$unmarkNeedsAnimationUpdate() {
        this.celeritas$isActive = false;
    }

    @Override
    public boolean angelica$tickAnimation(boolean onlyVisible) {
        if (!onlyVisible || angelica$needsAnimationUpdate()) {
            updateAnimation();
            return true;
        }

        SpriteAnimationDryRun.advance((TextureAtlasSprite) (Object) this);
        return false;
    }

    @ModifyReturnValue(method = { "getMinU", "getInterpolatedU" }, at = @At("RETURN"))
    private float markActiveWhenGettingCoords(float original) {
        this.celeritas$isActive = true;
        return original;
    }
}
