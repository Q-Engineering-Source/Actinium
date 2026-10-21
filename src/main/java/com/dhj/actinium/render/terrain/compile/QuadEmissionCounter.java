package com.dhj.actinium.render.terrain.compile;

/** Records successful quad uploads so dispatcher callers receive vanilla's rendered result. */
public final class QuadEmissionCounter {
    private int emittedQuads;

    /** Starts accounting for a new block invocation. */
    public void reset() {
        this.emittedQuads = 0;
    }

    /** Records one quad after its vertex data has been accepted by a chunk vertex buffer. */
    public void recordEmission() {
        this.emittedQuads++;
    }

    /** Returns whether at least one quad reached a chunk vertex buffer. */
    public boolean hasEmittedQuads() {
        return this.emittedQuads > 0;
    }
}
