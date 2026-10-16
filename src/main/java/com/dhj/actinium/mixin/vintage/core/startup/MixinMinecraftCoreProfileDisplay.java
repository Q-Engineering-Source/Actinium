package com.dhj.actinium.mixin.vintage.core.startup;

import com.dhj.actinium.startup.CoreProfileDisplayCreator;
import net.minecraft.client.Minecraft;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.PixelFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps Minecraft's display startup flow intact while replacing its context creation call. */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftCoreProfileDisplay {
    /**
     * Creates Actinium's core-profile context without bypassing other mixins on {@code createDisplay}.
     *
     * @param format pixel format requested by Minecraft
     * @throws LWJGLException if no supported core-profile context can be created
     */
    @Redirect(
        method = "createDisplay",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/opengl/Display;create(Lorg/lwjgl/opengl/PixelFormat;)V",
            remap = false
        )
    )
    private void celeritas$createCoreProfileDisplay(PixelFormat format) throws LWJGLException {
        CoreProfileDisplayCreator.create(format.withStencilBits(8));
    }
}
