package com.gtnewhorizons.angelica.rendering;

import net.minecraft.client.model.ModelBase;
import net.minecraft.entity.EntityLivingBase;

/**
 * Records what the deferred overlay asks of the renderer it captured a layer from.
 *
 * <p>The production replay calls the renderer's main model and its brightness helpers; this stand-in
 * keeps the model and records the two calls, so the test can assert them without a real
 * {@code RenderLivingBase}, whose static initialiser uploads a texture and therefore needs a client.</p>
 */
public class OverlayTestRenderer {

    private final ModelBase model;

    public boolean brightnessSet;
    public boolean brightnessUnset;
    public boolean lastCombineTextures;

    public OverlayTestRenderer(ModelBase model) {
        this.model = model;
    }

    /** The model the replay saves and restores state on. */
    public ModelBase model() {
        return model;
    }

    public boolean setBrightness(EntityLivingBase entity, float partialTicks, boolean combineTextures) {
        brightnessSet = true;
        lastCombineTextures = combineTextures;
        return true;
    }

    public void unsetBrightness() {
        brightnessUnset = true;
    }
}
