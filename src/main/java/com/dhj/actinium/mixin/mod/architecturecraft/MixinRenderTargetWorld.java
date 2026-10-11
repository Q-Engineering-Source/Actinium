package com.dhj.actinium.mixin.mod.architecturecraft;

import com.dhj.actinium.compat.architecturecraft.ArchitectureCraftLightingCompat;
import com.elytradev.architecture.client.render.target.RenderTargetWorld;
import com.dhj.actinium.mixin.mod.architecturecraft.mixinterface.AccessorRenderTargetBase;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Stores ArchitectureCraft's computed lighting factor in alpha when requested by the shader pack. */
@Mixin(value = RenderTargetWorld.class, remap = false)
public abstract class MixinRenderTargetWorld {
    @Shadow
    protected float vr;

    @Shadow
    protected float vg;

    @Shadow
    protected float vb;

    @Shadow
    protected float va;

    @Shadow
    protected boolean ao;

    @WrapMethod(method = "setLight(FI)V")
    private void actinium$writeSeparateAo(float shadow, int packedLight, Operation<Void> original) {
        boolean separateAo = ArchitectureCraftLightingCompat.shouldWriteSeparateAo();
        boolean disableDirectional = separateAo && ArchitectureCraftLightingCompat.shouldDisableDirectionalShading();
        float aoFactor = shadow;
        if (disableDirectional) {
            if (this.ao) {
                float faceShade = ((AccessorRenderTargetBase) (Object) this).actinium$getShade();
                aoFactor = faceShade > 0.000001F ? shadow / faceShade : 1.0F;
            } else {
                aoFactor = 1.0F;
            }
            aoFactor = Math.max(0.0F, Math.min(1.0F, aoFactor));
        }

        original.call(separateAo ? 1.0F : shadow, packedLight);
        if (separateAo) {
            this.va *= aoFactor;
        }
    }
}
