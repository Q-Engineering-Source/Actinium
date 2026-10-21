package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;

/** Checks whether a context owns the active display-list compilation or one of its nested parents. */
final class DisplayListCompilationOwnership {
    private DisplayListCompilationOwnership() {
    }

    static boolean includes(GLContextIdentity activeIdentity, Iterable<GLContextIdentity> parentIdentities,
                            GLContextIdentity candidate) {
        if (candidate == null) {
            return false;
        }
        if (candidate.equals(activeIdentity)) {
            return true;
        }
        for (GLContextIdentity parentIdentity : parentIdentities) {
            if (candidate.equals(parentIdentity)) {
                return true;
            }
        }
        return false;
    }

    static boolean mayNest(GLContextIdentity activeIdentity, GLContextIdentity currentIdentity) {
        return activeIdentity != null && activeIdentity.equals(currentIdentity);
    }
}
