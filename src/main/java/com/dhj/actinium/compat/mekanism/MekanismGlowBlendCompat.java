package com.dhj.actinium.compat.mekanism;

/** Bridges Mekanism glow passes to Iris's shaderpack blend state. */
public interface MekanismGlowBlendCompat {
    /** Shared implementation used by the conditional Mekanism mixin. */
    MekanismGlowBlendCompat INSTANCE = new MekanismGlowBlendCompatImpl();

    /** Applies Mekanism's deferred alpha blend request for the duration of a glow pass. */
    void begin();

    /** Restores the shaderpack blend state after a Mekanism glow pass. */
    void end();
}
