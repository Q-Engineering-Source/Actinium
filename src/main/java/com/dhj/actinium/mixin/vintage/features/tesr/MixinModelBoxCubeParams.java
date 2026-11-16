package com.dhj.actinium.mixin.vintage.features.tesr;

import com.gtnewhorizons.angelica.glsm.ffp.CubeParams;
import com.gtnewhorizons.angelica.mixins.interfaces.ModelBoxData;
import com.gtnewhorizons.angelica.rendering.tesr.ModelBoxCapture;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.TexturedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelBox.class)
public abstract class MixinModelBoxCubeParams implements ModelBoxData {

    @Shadow
    private TexturedQuad[] quadList;

    @Unique
    private CubeParams actinium$cubeParams;

    @Inject(method = "<init>(Lnet/minecraft/client/model/ModelRenderer;IIFFFIIIF)V", at = @At("RETURN"))
    private void actinium$captureCubeParams(ModelRenderer part, int texU, int texV, float x, float y, float z,
        int sizeX, int sizeY, int sizeZ, float inflate, CallbackInfo ci) {
        if (((Object) this).getClass() != ModelBox.class) return;
        this.actinium$cubeParams = ModelBoxCapture.capture((ModelBox) (Object) this, this.quadList, part, texU, texV,
            sizeX, sizeY, sizeZ, inflate);
    }

    @Override
    public CubeParams angelica$cubeParams() {
        return this.actinium$cubeParams;
    }
}
