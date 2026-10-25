package com.dhj.actinium.mixinterface.features.iris;

/**
 * Exposes model rendering history when batching bypasses vanilla display list compilation.
 */
public interface ModelRenderState {
    /**
     * Reports whether Actinium has rendered this part, including empty parents of child parts.
     * Geometry consumers need this history without treating a missing vanilla display list as compiled.
     */
    boolean actinium$hasRenderedModel();
}
