package com.dhj.actinium.mixin.vintage.core;

import com.gtnewhorizon.gtnhlib.client.renderer.ITessellatorInstance;
import dhj.embeddedt.embeddium.api.shader.buffer.BufferBuilderExtension;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.dhj.actinium.render.TessellatorDrawRouter;
import com.gtnewhorizons.angelica.glsm.ITessellatorData;

@Mixin(Tessellator.class)
public class MixinTessellator implements ITessellatorInstance {
    @Shadow
    @Final
    private BufferBuilder buffer;

    @Unique
    private boolean celeritas$gtnhlibCompiling;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void celeritas$coreProfileDraw(CallbackInfo ci) {
        if (TessellatorDrawRouter.draw(this.buffer, (ITessellatorData) this.buffer)) {
            ci.cancel();
        }
    }

    @Override
    public void discard() {
        if (this.buffer instanceof BufferBuilderExtension extension) {
            extension.actinium$discard();
            return;
        }

        this.buffer.reset();
    }

    @Override
    public boolean gtnhlib$isCompiling() {
        return this.celeritas$gtnhlibCompiling;
    }

    @Override
    public void gtnhlib$setCompiling(boolean compiling) {
        this.celeritas$gtnhlibCompiling = compiling;
    }
}

