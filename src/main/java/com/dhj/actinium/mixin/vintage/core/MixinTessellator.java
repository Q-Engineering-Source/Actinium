package com.dhj.actinium.mixin.vintage.core;

import com.gtnewhorizon.gtnhlib.client.renderer.ITessellatorInstance;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizons.angelica.client.rendering.DeferredDrawBatcher;
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
import com.dhj.actinium.render.VanillaBufferBuilderRenderer;
import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;

@Mixin(Tessellator.class)
public class MixinTessellator implements ITessellatorInstance {
    @Shadow
    @Final
    private BufferBuilder buffer;

    @Unique
    private boolean celeritas$gtnhlibCompiling;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void celeritas$coreProfileDraw(CallbackInfo ci) {
        if (VanillaBufferBuilderRenderer.shouldUseVanillaPositionDraw(this.buffer)) {
            return;
        }

        if (DeferredDrawBatcher.capture(this.buffer)) {
            ci.cancel();
            return;
        }

        this.buffer.finishDrawing();
        if (TessellatorManager.shouldInterceptBufferBuilderDraw()) {
            TessellatorManager.interceptBufferBuilderDraw(this.buffer);
            ci.cancel();
            return;
        }

        // An open entity or block entity batch merges the vertices into its retained groups instead of
        // drawing them here; outside a batch the builder keeps its own streaming path.
        if (ModelPartBatcher.isBatchingActive()) {
            ModelPartBatcher.drawTessellator((ITessellatorData) this.buffer);
            ci.cancel();
            return;
        }

        VanillaBufferBuilderRenderer.draw(this.buffer, "Tessellator");
        ci.cancel();
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

