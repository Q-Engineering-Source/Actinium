package com.gtnewhorizons.angelica.api.shaders;

import com.dhj.actinium.config.ActiniumConfig;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.block.Block;

/**
 * Stable entry points for mods that expose block entities to an active shader pack.
 */
public final class ShaderPacks {

    private ShaderPacks() {
    }

    public static boolean isInUse() {
        return ActiniumConfig.enableIris && IrisApi.getInstance().isShaderPackInUse();
    }

    public static float getFrameTimeCounter() {
        return SystemTimeUniforms.TIMER.getFrameTimeCounter();
    }

    public static void beginBlockEntity(Block block, int metadata) {
        if (!ActiniumConfig.enableIris) return;
        CapturedRenderingState.INSTANCE.pushCurrentBlockEntity();
        CapturedRenderingState.INSTANCE.setCurrentBlockEntity(block, metadata);
    }

    public static void endBlockEntity() {
        if (!ActiniumConfig.enableIris) return;
        CapturedRenderingState.INSTANCE.popCurrentBlockEntity();
    }
}
