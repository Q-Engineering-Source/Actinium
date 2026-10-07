package com.dhj.actinium.mixin.mod.gvclib;

import com.dhj.actinium.compat.gvclib.GvclibHandLightingCompat;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.lwjgl.opengl.GL13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Pins the client texture unit to GL_TEXTURE0 while a scoped GVCLib first-person model draws.
 *
 * <p>{@code objmodel.Tessellator2#draw()} issues {@code glTexCoordPointer} for the base UVs
 * without first selecting a client texture unit, so it silently targets whatever unit the
 * previous renderer left active. A leftover non-zero unit corrupts the model's texture
 * coordinates (issue #198).</p>
 */
@Pseudo
@Mixin(targets = "objmodel.Tessellator2", remap = false)
public abstract class MixinGVCTessellatorClientUnitGuard {
    @WrapMethod(method = "draw()I")
    private int actinium$preserveClientTextureUnit(Operation<Integer> original) {
        if (!GvclibHandLightingCompat.isFirstPersonModelLightmapScopeActive()) {
            return original.call();
        }

        int previousClientTextureUnit = GLStateManager.getClientActiveTextureUnit();
        GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE0);
        try {
            return original.call();
        } finally {
            GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE0 + previousClientTextureUnit);
        }
    }
}
