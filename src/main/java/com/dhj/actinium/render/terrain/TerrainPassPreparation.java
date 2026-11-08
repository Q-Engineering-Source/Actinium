package com.dhj.actinium.render.terrain;

/**
 * Tracks whether the terrain passes of the current frame still need their compute culling preparation.
 *
 * <p>The preparation cannot run during the terrain setup: the shadow pass draws afterwards and walks its own render
 * lists, which discards the command ranges prepared for the terrain lists. It also cannot run once per pass: every walk
 * prepares the ranges of all passes of the lists at once. This class holds the mark that lets the first main terrain
 * draw of the frame prepare exactly once, and keeps it across shadow draws and across a preparation that failed.
 */
final class TerrainPassPreparation {
    private boolean pending;

    /**
     * Marks the render lists of the terrain setup that just ran as needing their preparation.
     */
    void markPending() {
        this.pending = true;
    }

    /**
     * Runs {@code preparation} when this draw is the one that prepares the frame.
     *
     * @param shadowPass  whether the draw belongs to the shadow pass, which draws its own render lists
     * @param preparation the preparation of every terrain pass of the frame
     */
    void prepareIfPending(boolean shadowPass, Runnable preparation) {
        if (!this.pending || shadowPass) {
            return;
        }

        preparation.run();
        this.pending = false;
    }
}
