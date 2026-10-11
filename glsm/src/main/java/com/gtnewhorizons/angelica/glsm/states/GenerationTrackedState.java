package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizons.angelica.glsm.GLContextState;

/**
 * Generation-tracked upload markers for one linked OpenGL program.
 *
 * <p>Uniform values belong to a program object; these markers record which
 * categories were already uploaded at which GLSM state generation, so uploads
 * are skipped while the state is unchanged. Subclasses declare their category
 * constants (bit positions 0..N-1) and expose typed needs/mark pairs that
 * delegate here.
 */
public abstract class GenerationTrackedState {

    private final int[] generations;
    private int initialized;
    /**
     * The GLSM context the markers were written for. Generations are per context, so markers written
     * while another drawable was current say nothing about the one being drawn into now.
     */
    private GLContextState owner;

    /** Rebinds the markers to the context about to be uploaded for, dropping another context's markers. */
    public final void bindOwner(GLContextState context) {
        if (owner == context) return;
        owner = context;
        initialized = 0;
    }

    protected GenerationTrackedState(int categoryCount) {
        this.generations = new int[categoryCount];
    }

    protected final boolean needsUpload(int category, int generation) {
        return !isInitialized(category) || generations[category] != generation;
    }

    protected final void markUploaded(int category, int generation) {
        generations[category] = generation;
        initialized |= 1 << category;
    }

    protected final boolean isInitialized(int category) {
        return (initialized & (1 << category)) != 0;
    }
}
