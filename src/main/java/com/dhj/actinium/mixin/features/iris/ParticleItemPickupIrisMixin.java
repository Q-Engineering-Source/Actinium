package com.dhj.actinium.mixin.features.iris;

import com.gtnewhorizons.angelica.rendering.ParticleRunSplitter;
import net.coderbot.iris.apiimpl.IrisApiV0Impl;
import net.coderbot.iris.layer.GbufferPrograms;
import net.minecraft.client.particle.ParticleItemPickup;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ParticleItemPickup.class)
public class ParticleItemPickupIrisMixin {
    @Redirect(
        method = "renderParticle",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/RenderManager;renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V")
    )
    private void actinium$renderPickupItemAsEntity(
        RenderManager renderManager,
        Entity entity,
        double x,
        double y,
        double z,
        float yaw,
        float partialTicks,
        boolean debugBoundingBox
    ) {
        if (!IrisApiV0Impl.INSTANCE.isShaderPackInUse()) {
            renderManager.renderEntity(entity, x, y, z, yaw, partialTicks, debugBoundingBox);
            return;
        }

        final boolean reopen = ParticleRunSplitter.beforeNestedDraw();
        GbufferPrograms.beginEntities();
        try {
            renderManager.renderEntity(entity, x, y, z, yaw, partialTicks, debugBoundingBox);
        } finally {
            GbufferPrograms.endEntities();
            ParticleRunSplitter.afterNestedDraw(reopen);
        }
    }
}
