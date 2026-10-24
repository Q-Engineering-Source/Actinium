package com.dhj.actinium.mixin.mod.voxelmap;

import com.dhj.actinium.compat.voxelmap.VoxelMapCompat;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.mamiyaotaru.voxelmap.util.GLShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Keeps VoxelMap's GL state mutations inside the GLSM state cache.
 *
 * <p>The minimap's CPU render path draws the map texture inside a scissor rectangle, but its
 * scissor enable/disable and {@code glScissor} calls go straight to {@code GL11}, bypassing
 * the cache; the core-profile renderer then draws the map quad without the scissor, spreading
 * the map texture (and the black mask around it) across the whole HUD and leaving the blend
 * state polluted for the rest of the frame. The same mixin downgrades the minimap texture's
 * minification filter from mipmap-based to plain linear: the CPU path relies on the legacy
 * {@code GL_GENERATE_MIPMAP} parameter, which core profiles ignore, so the map texture only
 * has level 0 and sampling an incomplete texture returns opaque black.
 */
@Mixin(value = GLShim.class, remap = false)
public abstract class MixinVoxelMapGLShim {
    /** {@code GL_SCISSOR_TEST} - capability that VoxelMap toggles outside the state cache. */
    private static final int GL_SCISSOR_TEST = 3089;
    /** {@code GL_COLOR_BUFFER_BIT} - color buffer clear mask. */
    private static final int GL_COLOR_BUFFER_BIT = 16384;

    @Inject(method = "glClear", at = @At("HEAD"), cancellable = true)
    private static void voxelmap$protectHudCacheAlpha(int mask, CallbackInfo ci) {
        if ((mask & GL_COLOR_BUFFER_BIT) != 0 && GLSMConfig.hudCacheOverride) {
            if (VoxelMapCompat.mapScissorActive) {
                // MixinVoxelMapRenderMap has scoped the clear to the minimap rectangle, so the
                // cached HUD alpha outside it is preserved while the map area is prepared.
                return;
            }
            // StellarCore renders the whole HUD (including the minimap, which is drawn on the
            // RenderGameOverlayEvent.Post) into a cached framebuffer that is later blitted to the
            // screen with an alpha-dependent blend. VoxelMap clears the color buffer (masked to
            // alpha-only) to prepare its circular mask; inside the HUD-cache window that wipes the
            // alpha of every HUD element already drawn, so the cached blit turns them transparent.
            // Skip the clear while the cache window is active unless it has been scoped to the
            // minimap area (see MixinVoxelMapRenderMap).
            ci.cancel();
        }
    }

    @Inject(method = "glEnable", at = @At("HEAD"), cancellable = true)
    private static void voxelmap$enableScissorTracked(int attrib, CallbackInfo ci) {
        if (attrib == GL_SCISSOR_TEST) {
            GLStateManager.glEnable(attrib);
            ci.cancel();
        }
    }

    @Inject(method = "glDisable", at = @At("HEAD"), cancellable = true)
    private static void voxelmap$disableScissorTracked(int attrib, CallbackInfo ci) {
        if (attrib == GL_SCISSOR_TEST) {
            GLStateManager.glDisable(attrib);
            ci.cancel();
        }
    }

    @Inject(method = "glScissor", at = @At("HEAD"), cancellable = true)
    private static void voxelmap$scissorTracked(int x, int y, int width, int height, CallbackInfo ci) {
        GLStateManager.glScissor(x, y, width, height);
        ci.cancel();
    }

    @ModifyArgs(
        method = "glTexParameteri",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL11;glTexParameteri(III)V"
        ),
        remap = false
    )
    private static void voxelmap$forceLinearMinFilter(Args args) {
        if (VoxelMapCompat.needsLinearMinFilter(args.get(1), args.get(2))) {
            args.set(2, VoxelMapCompat.linearMinFilter());
        }
    }
}
