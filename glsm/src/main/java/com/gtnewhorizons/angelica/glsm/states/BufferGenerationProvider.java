package com.gtnewhorizons.angelica.glsm.states;

/** Supplies buffer-name lifetimes to vertex-array models without querying a GL context. */
public interface BufferGenerationProvider {
    /** Returns or creates the tracked lifetime for a nonzero buffer name. */
    long current(int buffer);

    /** Checks whether a saved name and generation still refer to the same buffer object. */
    boolean isCurrent(int buffer, long generation);
}
