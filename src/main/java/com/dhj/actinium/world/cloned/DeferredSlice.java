package com.dhj.actinium.world.cloned;

/**
 * A block-access snapshot whose lifetime spans the two halves of a deferred chunk mesh.
 *
 * <p>The snapshot is taken on the render thread, handed to the build thread that encodes the
 * captured geometry, and released afterwards. The release is the point that returns the cloned
 * sections to the section cache, so the owner of a slice has to be explicit about it: a pooled
 * slice is only reusable once {@link #releaseContext()} ran.</p>
 *
 * <p>Implementations must make {@link #releaseContext()} idempotent, because a slice can be
 * released both by the task that owned it and by the pool that drops it.</p>
 */
public interface DeferredSlice extends ActiniumBlockAccess {
    /**
     * Gives back the snapshot held by this slice. Calling this on a slice that no longer holds a
     * snapshot does nothing.
     */
    void releaseContext();
}
