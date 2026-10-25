package com.dhj.actinium.render;

/**
 * Reports the multiplier a foreign mod is currently applying to the alpha channel of item vertex
 * colours. Actinium renders lit items through a fast path that appends baked quads straight to the
 * vertex buffer and, for stable models, caches the result in a GLSM display list; both shortcuts
 * assume the vertex colours are a pure function of the baked quad data. NeverEnoughAnimation
 * breaks that assumption by scaling the alpha of every item vertex while a GUI opens or closes,
 * so the fast path would either drop the fade or bake the alpha of the compile frame into the
 * display list. Registering an implementation lets the fast path step aside while such scaling is
 * active. Implementations are polled for every item draw on the client render thread, so
 * {@link #getAlphaMultiplier()} must be cheap and free of side effects.
 */
public interface ItemVertexAlphaOverride {

    /**
     * Returns the multiplier currently applied to item vertex alpha, or {@code 1.0F} when this
     * source is not scaling anything right now. Any value other than exactly {@code 1.0F} marks the
     * fast lit item path as unsound for the current frame.
     */
    float getAlphaMultiplier();
}
