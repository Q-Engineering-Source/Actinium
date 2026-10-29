package com.gtnewhorizons.angelica.glsm.states;

import java.util.HashMap;
import java.util.Map;

/** Tracks buffer-name lifetimes for one share group without querying the current GL context. */
public final class BufferGenerationTracker implements BufferGenerationProvider {
    private static final Map<Long, BufferGenerationTracker> sharedTrackers = new HashMap<>();

    private final long shareGroupId;
    private final Map<Integer, Long> generations = new HashMap<>();
    private long nextGeneration = 1L;

    /** Creates an explicit in-memory tracker, suitable for a standalone vertex-array model. */
    public BufferGenerationTracker(long shareGroupId) {
        if (shareGroupId <= 0L) {
            throw new IllegalArgumentException("Share-group id must be positive: " + shareGroupId);
        }
        this.shareGroupId = shareGroupId;
    }

    /** Returns the process tracker shared by every live context in this OpenGL share group. */
    public static synchronized BufferGenerationTracker shared(long shareGroupId) {
        if (shareGroupId <= 0L) {
            throw new IllegalArgumentException("Share-group id must be positive: " + shareGroupId);
        }
        return sharedTrackers.computeIfAbsent(shareGroupId, BufferGenerationTracker::new);
    }

    /** Drops the production tracker after its final native context in the group retires. */
    public static synchronized void retireShareGroup(long shareGroupId) {
        final BufferGenerationTracker tracker = sharedTrackers.remove(shareGroupId);
        if (tracker != null) {
            tracker.clear();
        }
    }

    /** Registers a newly generated buffer name in this share group. */
    public synchronized long register(int buffer) {
        if (buffer == 0) {
            return 0L;
        }
        final long generation = nextGeneration++;
        generations.put(buffer, generation);
        return generation;
    }

    /** Returns the current lifetime, assigning one for names introduced outside glGenBuffers. */
    @Override
    public synchronized long current(int buffer) {
        if (buffer == 0) {
            return 0L;
        }
        return generations.computeIfAbsent(buffer, ignored -> nextGeneration++);
    }

    /** Invalidates every saved reference when this share-group object is deleted. */
    public synchronized void delete(int buffer) {
        if (buffer != 0) {
            generations.remove(buffer);
        }
    }

    /** Checks whether a saved pointer still names the same live object lifetime. */
    @Override
    public synchronized boolean isCurrent(int buffer, long generation) {
        return buffer == 0 || generation != 0L && generations.getOrDefault(buffer, 0L) == generation;
    }

    private synchronized void clear() {
        generations.clear();
    }

    public long shareGroupId() {
        return shareGroupId;
    }
}
