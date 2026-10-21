package com.gtnewhorizons.angelica.glsm.recording;

/** Tracks StateSet scopes that are recorded but are not live during GL_COMPILE. */
public final class VirtualStateDepth {
    private final int maximumDepth;
    private int depth;

    public VirtualStateDepth(int maximumDepth) {
        if (maximumDepth <= 0) {
            throw new IllegalArgumentException("Virtual StateSet depth limit must be positive");
        }
        this.maximumDepth = maximumDepth;
    }

    /** Opens one recorded scope, failing before its command is appended when full. */
    public void push() {
        if (depth >= maximumDepth) {
            throw new IllegalStateException("Recorded StateSet stack overflow at depth " + depth);
        }
        depth++;
    }

    /** Closes one virtual scope, returning false when the matching push was not recorded. */
    public boolean popIfPending() {
        if (depth == 0) {
            return false;
        }
        depth--;
        return true;
    }

    /** Clears all virtual scopes when compilation is aborted or finishes. */
    public void clear() {
        depth = 0;
    }

    /** Restores the parent's virtual depth after a nested display-list compile finishes. */
    public void restore(int restoredDepth) {
        if (restoredDepth < 0 || restoredDepth > maximumDepth) {
            throw new IllegalArgumentException("Invalid recorded StateSet depth: " + restoredDepth);
        }
        depth = restoredDepth;
    }

    /** Returns the number of internal scopes awaiting a recorded pop. */
    public int depth() {
        return depth;
    }
}
