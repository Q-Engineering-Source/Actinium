package com.gtnewhorizons.angelica.glsm.context;

/** Receives a context retirement after native destruction and registry cleanup have completed. */
@FunctionalInterface
public interface ContextRetirementListener {
    /** Reports the identity whose context-local state must no longer be retained. */
    void onContextRetired(GLContextIdentity identity);
}
