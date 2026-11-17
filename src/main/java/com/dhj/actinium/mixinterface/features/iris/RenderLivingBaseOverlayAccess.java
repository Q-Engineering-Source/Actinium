package com.dhj.actinium.mixinterface.features.iris;

import net.minecraft.client.model.ModelBase;
import net.minecraft.entity.EntityLivingBase;

/**
 * Exposes the renderer state the deferred overlay replay has to restore.
 *
 * <p>{@code setBrightness} and {@code unsetBrightness} are {@code protected} on
 * {@link net.minecraft.client.renderer.entity.RenderLivingBase}, and the replay runs outside
 * {@code renderLayers}, so it cannot rely on the loop having set the brightness texture
 * environment. The main model accessor keeps the replay on the renderer's own model instead of a
 * cached reference.</p>
 */
public interface RenderLivingBaseOverlayAccess {

    /** The renderer's main model, whose animation state the replay restores before the layer runs. */
    ModelBase actinium$mainModel();

    /**
     * Applies the brightness and hurt-flash texture environment for the entity.
     *
     * @return whether the environment was applied and therefore has to be unset again
     */
    boolean actinium$setBrightness(EntityLivingBase entity, float partialTicks, boolean combineTextures);

    /** Restores the texture environment {@link #actinium$setBrightness} replaced. */
    void actinium$unsetBrightness();
}
