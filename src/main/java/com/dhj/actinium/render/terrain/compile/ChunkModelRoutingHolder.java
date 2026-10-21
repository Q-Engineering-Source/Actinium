package com.dhj.actinium.render.terrain.compile;

/** Supplies the stateless model router to the thin dispatcher mixin. */
public final class ChunkModelRoutingHolder {
    private static final ChunkModelRouting ROUTING = new ChunkModelRoutingImpl();

    private ChunkModelRoutingHolder() {
    }

    /** Returns the router installed for dispatcher model calls. */
    public static ChunkModelRouting get() {
        return ROUTING;
    }
}
