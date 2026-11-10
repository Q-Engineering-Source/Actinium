package com.dhj.actinium.world.cloned;

import com.dhj.actinium.world.WorldSlice;
import dhj.embeddedt.embeddium.impl.util.position.SectionPos;

/**
 * Carries a per-task snapshot from {@code WorldSlice.prepare} to the chunk build task: the origin
 * section coordinates, one cloned section per slice slot, and the cache those sections came from.
 * The array is fully populated by prepare on the main thread and only read afterwards, so it can be
 * shared with the worker thread through the task queue; each pending task owns its own array
 * instance. The snapshot volume is not carried here because it is derived from the origin by the
 * consuming WorldSlice, which reuses a single bounding box across tasks.
 *
 * <p>Ownership: the context holds one cache reference per section until it either hands the whole
 * snapshot to a {@link WorldSlice} through
 * {@code WorldSlice#copyData(ChunkRenderContext)} or releases it itself. A task that never starts
 * must call {@link #release()}; after {@code copyData} the slice owns the release duty and this
 * context must not release again.
 */
public class ChunkRenderContext {
    private final ClonedChunkSectionCache sectionCache;
    private final SectionPos sectionCoord;
    private final ClonedChunkSection[] sections;

    private boolean released;
    private boolean ownershipTransferred;

    public ChunkRenderContext(ClonedChunkSectionCache sectionCache, SectionPos sectionCoord, ClonedChunkSection[] sections) {
        this.sectionCache = sectionCache;
        this.sectionCoord = sectionCoord;
        this.sections = sections;
    }

    public ClonedChunkSection[] getSections() {
        return this.sections;
    }

    public SectionPos getOrigin() {
        return this.sectionCoord;
    }

    public ClonedChunkSectionCache getSectionCache() {
        return this.sectionCache;
    }

    /**
     * Releases the snapshot of a task that never started. Idempotent, and a no-op once a
     * {@code WorldSlice} took ownership of the sections.
     */
    public void release() {
        if (this.released || this.ownershipTransferred) {
            return;
        }

        this.released = true;
        this.sectionCache.release(this.sections);
    }

    /**
     * Marks that a {@code WorldSlice} copied the sections and now owns their release. Called by
     * {@code WorldSlice#copyData}; the slice releases them through
     * {@code WorldSlice#releaseContext()}.
     */
    public void transferOwnership() {
        this.ownershipTransferred = true;
    }
}
