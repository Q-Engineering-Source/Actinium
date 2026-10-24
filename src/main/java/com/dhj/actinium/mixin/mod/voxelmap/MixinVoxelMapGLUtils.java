package com.dhj.actinium.mixin.mod.voxelmap;

import com.dhj.actinium.compat.voxelmap.VoxelMapCompat;
import com.mamiyaotaru.voxelmap.util.GLUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-routes the VoxelMap minimap onto the plain CPU-texture render path: VoxelMap probes
 * {@code GLUtils.hasAlphaBits} / {@code GLUtils.fboEnabled} once during its class
 * initialization, and under Actinium's core-profile window (no alpha bits reported by the
 * LWJGL2 compatibility layer) it would select the legacy {@code EXT_framebuffer_object}
 * path, which renders black on the core profile. Overriding the flags right after
 * initialization makes it use the same direct texture path as on ordinary vanilla systems.
 */
@Mixin(value = GLUtils.class, remap = false)
public abstract class MixinVoxelMapGLUtils {
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void voxelmap$forceCpuMinimapPath(CallbackInfo ci) {
        VoxelMapCompat.forceCpuMinimapPath();
    }
}
