package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.ffp.Instancing;

/**
 * The part of a shader-pack pipeline the TESR instancing path drives.
 *
 * <p>Batched TESR draws bind an instanced variant of the active pass program, draw from a shared
 * template or unit cube, and then need the pass itself bound again. The host records and replays
 * those draws, so it talks to the pipeline through this interface instead of the concrete
 * deferred pipeline class.</p>
 */
public interface TesrInstancingPipeline {

    /** Rebinds the pass program and framebuffer the pipeline considers active. */
    void rebindCurrentPass();

    /** @return whether the active pass has an instanced variant of the given kind */
    boolean hasInstancedVariant(Instancing kind);

    /** Binds the active pass's instanced variant of the given kind. */
    void bindInstancedVariant(Instancing kind);
}
