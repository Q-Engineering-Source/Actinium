package com.dhj.actinium.mixin.mod.lumenized;

import codechicken.lib.render.shader.ShaderObject;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import gregtech.client.shader.Shaders;
import net.minecraft.client.shader.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * Lumenized composites the bloom onto the main framebuffer with a full-screen quad and no
 * depth handling of its own, relying on the depth test state left by world rendering; in
 * Actinium's render stack that state is not guaranteed to be enabled at this point, so the
 * glow is drawn over everything, visible through walls. Enable the depth test explicitly so
 * geometry in front of the light source occludes the glow.
 */
@Mixin(value = Shaders.class, remap = false)
public abstract class MixinShadersDepthTest {
    @Inject(
        method = "renderFullImageInFBO(Lnet/minecraft/client/shader/Framebuffer;"
            + "Lcodechicken/lib/render/shader/ShaderObject;Ljava/util/function/Consumer;)"
            + "Lnet/minecraft/client/shader/Framebuffer;",
        at = @At("HEAD"),
        remap = false
    )
    private static void actinium$enableDepthForComposite(
        Framebuffer target,
        ShaderObject shader,
        Consumer<Framebuffer> setup,
        CallbackInfoReturnable<Framebuffer> cir
    ) {
        GLStateManager.enableDepthTest();
    }
}
