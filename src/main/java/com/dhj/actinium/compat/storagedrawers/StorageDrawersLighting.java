package com.dhj.actinium.compat.storagedrawers;

/** Provides the scoped lighting correction used by the conditional Storage Drawers renderer mixin. */
public interface StorageDrawersLighting {
    /** Shared implementation kept behind this compatibility boundary. */
    StorageDrawersLighting INSTANCE = new StorageDrawersLightingImpl();

    /** Captures the view-space model-view matrix before Storage Drawers applies item-local transforms. */
    void captureRootModelView();

    /** Applies vanilla standard item lighting under the captured root matrix, then restores the active matrix. */
    void enableStandardItemLightingAtRootModelView();
}
