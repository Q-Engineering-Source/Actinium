package com.dhj.actinium.compat.gvclib;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.coderbot.iris.pipeline.HandRenderer;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;

/**
 * Supplies world lightmap coordinates to GVCLib's custom first-person model meshes.
 *
 * <p>GVCLib's first-person gun and arm meshes never write per-vertex light
 * ({@code objmodel.Tessellator2#setBrightness} has no callers in the mod), so every draw
 * falls back to GLSM's current lightmap coordinate. Without intervention that coordinate is
 * whatever the previous renderer left behind — the last entity drawn, a fullbright muzzle
 * flash, or a GUI's fixed 240/240 — which made the held gun's brightness jump between stale
 * values (issue #198 follow-up). Vanilla held items avoid this by calling
 * {@code ItemRenderer#setLightmap()} every frame; this compat does the same for GVCLib by
 * pinning the current lightmap coordinate to the player's packed light at the start of each
 * first-person model scope.</p>
 */
public final class GvclibHandLightingCompat {
    private static int firstPersonModelLightmapScopeDepth;
    private static boolean firstPersonModelLightmapManagedByCompat;

    private GvclibHandLightingCompat() {
    }

    /** Keeps GVCLib's shader event draw inside Iris's hand pass and drops the later vanilla duplicate. */
    public static boolean shouldRenderFirstPersonGun() {
        return shouldRenderFirstPersonGun(
            isShaderPackInUse(),
            isIrisHandPassActive()
        );
    }

    /** Reports whether the first-person model is currently being drawn through the Iris shader pass. */
    public static boolean isShaderPackInUse() {
        return IrisApi.getInstance().isShaderPackInUse();
    }

    /** Reports whether Iris is currently executing its hand pass. */
    public static boolean isIrisHandPassActive() {
        return HandRenderer.INSTANCE.isActive();
    }

    public static boolean shouldRenderFirstPersonGun(boolean shaderPackInUse, boolean irisHandPassActive) {
        return !shaderPackInUse || irisHandPassActive;
    }

    /** Identifies the model groups GVCLib uses for the player's first-person arms. */
    public static boolean isFirstPersonArmPart(String partName) {
        return "leftarm".equalsIgnoreCase(partName) || "rightarm".equalsIgnoreCase(partName);
    }

    /**
     * Pins the current lightmap coordinate to the player's packed light and, when Iris is
     * absent, enables vanilla's lightmap for the duration of the scope.
     */
    public static void beginFirstPersonModelRender() {
        Minecraft minecraft = Minecraft.getMinecraft();
        int packedLight = minecraft.player.getBrightnessForRender();
        boolean manageLightmap = !isShaderPackInUse();
        if (!beginFirstPersonModelLightmapScope(manageLightmap)) {
            return;
        }
        GLStateManager.setLightmapTextureCoords(
            OpenGlHelper.lightmapTexUnit,
            packedLight & 0xFFFF,
            packedLight >>> 16
        );
        if (manageLightmap) {
            minecraft.entityRenderer.enableLightmap();
        }
    }

    /** Ends the model scope and restores the lightmap state owned by the vanilla path. */
    public static void endFirstPersonModelRender() {
        if (endFirstPersonModelLightmapScope()) {
            Minecraft.getMinecraft().entityRenderer.disableLightmap();
        }
    }

    /**
     * Begins a nested-safe first-person model scope and reports whether this call is the
     * outermost one, which owns the per-frame lightmap coordinate and any vanilla lightmap
     * state.
     */
    static boolean beginFirstPersonModelLightmapScope(boolean manageLightmap) {
        boolean outermost = firstPersonModelLightmapScopeDepth == 0;
        if (outermost) {
            firstPersonModelLightmapManagedByCompat = manageLightmap;
        }
        firstPersonModelLightmapScopeDepth++;
        return outermost;
    }

    /** Ends a lightmap scope and reports whether the outer scope enabled vanilla's lightmap. */
    static boolean endFirstPersonModelLightmapScope() {
        if (firstPersonModelLightmapScopeDepth == 0) {
            throw new IllegalStateException("GVCLib model lightmap scope ended without a matching begin");
        }

        firstPersonModelLightmapScopeDepth--;
        if (firstPersonModelLightmapScopeDepth != 0) {
            return false;
        }

        boolean disableLightmap = firstPersonModelLightmapManagedByCompat;
        firstPersonModelLightmapManagedByCompat = false;
        return disableLightmap;
    }

    /** Reports whether GVCLib is currently emitting first-person model vertices. */
    public static boolean isFirstPersonModelLightmapScopeActive() {
        return firstPersonModelLightmapScopeDepth > 0;
    }
}
