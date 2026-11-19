package com.gtnewhorizons.angelica.glsm.hooks;

/**
 * A shader pipeline that deferred a program selection and has to run it later.
 *
 * <p>The host compares the slot's identity with itself, so a pipeline only resolves requests it
 * published and never another pipeline's. A request is published while a foreign renderer or an
 * active batch owns the program, and resolved once that scope ends.</p>
 */
public interface PendingProgramSelection {

    /** Runs the deferred program selection, if one is still pending and nothing owns the program. */
    void resolvePendingProgram();
}
