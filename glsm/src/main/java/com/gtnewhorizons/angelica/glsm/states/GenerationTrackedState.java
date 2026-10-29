package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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

    private final Map<GLContextIdentity, UploadState> statesByContext = new HashMap<>();
    private final int categoryCount;

    protected GenerationTrackedState(int categoryCount) {
        this.categoryCount = categoryCount;
    }

    protected final boolean needsUpload(GLContextIdentity identity, int category, int generation) {
        final UploadState state = currentState(identity);
        return !state.isInitialized(category) || state.generations[category] != generation;
    }

    protected final void markUploaded(GLContextIdentity identity, int category, int generation) {
        final UploadState state = currentState(identity);
        state.generations[category] = generation;
        state.initialized |= 1 << category;
    }

    protected final boolean isInitialized(GLContextIdentity identity, int category) {
        return currentState(identity).isInitialized(category);
    }

    /** Removes upload markers for a destroyed context while preserving markers of shared contexts. */
    public final synchronized void retireContext(GLContextIdentity identity) {
        statesByContext.remove(identity);
    }

    private synchronized UploadState currentState(GLContextIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return statesByContext.computeIfAbsent(identity, ignored -> new UploadState(categoryCount));
    }

    private static final class UploadState {
        private final int[] generations;
        private int initialized;

        private UploadState(int categoryCount) {
            generations = new int[categoryCount];
        }

        private boolean isInitialized(int category) {
            return (initialized & (1 << category)) != 0;
        }
    }
}
