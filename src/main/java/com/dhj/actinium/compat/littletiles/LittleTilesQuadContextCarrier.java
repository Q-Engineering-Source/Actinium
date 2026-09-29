package com.dhj.actinium.compat.littletiles;

import dhj.embeddedt.embeddium.api.shader.buffer.VanillaQuadContext;

import java.util.List;

/** Carries quad contexts alongside LittleTiles' cached raw vertex buffers. */
public interface LittleTilesQuadContextCarrier {
    /** Returns the immutable per-quad context snapshot associated with this cache object. */
    List<VanillaQuadContext> actinium$getQuadContexts();

    /** Replaces the cache object's contexts with an immutable copy of {@code contexts}. */
    void actinium$setQuadContexts(List<VanillaQuadContext> contexts);
}
