package com.dhj.actinium.compat.hbm;

import com.dhj.actinium.mixinterface.features.iris.ModelRenderState;
import net.minecraft.client.model.ModelRenderer;

/**
 * Lets HBM collect previously rendered model parts without requiring a vanilla display list.
 */
public final class HbmModelRenderCompat {
    private HbmModelRenderCompat() {
    }

    /**
     * Preserves HBM's rendered-part filter for both vanilla and Actinium's batched model paths.
     */
    public static boolean isReadyForSlicing(boolean vanillaCompiled, ModelRenderer renderer) {
        return vanillaCompiled || ((ModelRenderState) renderer).actinium$hasRenderedModel();
    }
}
