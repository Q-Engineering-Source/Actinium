package com.dhj.actinium.mixin.features.iris;

import com.gtnewhorizons.angelica.rendering.ParticleItemPickupRenderer;
import net.coderbot.iris.apiimpl.IrisApiV0Impl;
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
        ParticleItemPickupRenderer.render(
            () -> renderManager.renderEntity(entity, x, y, z, yaw, partialTicks, debugBoundingBox),
            IrisApiV0Impl.INSTANCE.isShaderPackInUse()
        );
    }
}
