package com.dhj.actinium.world.cloned;

import dhj.embeddedt.embeddium.impl.util.PositionUtil;
import dhj.embeddedt.embeddium.impl.util.position.SectionPos;
import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * Owns the {@link ClonedChunkSection} snapshots of one world: an LRU cache by section position, the
 * reference counts of the tasks using them, and a free queue of sections that are no longer cached
 * so their storage can be reused by the next position.
 *
 * <p>Ownership: {@link #acquire(int, int, int)} returns a section with one reference added, and the
 * caller must release it through {@link #release(ClonedChunkSection[])} once the consuming slice or
 * task is done. {@link #invalidate(int, int, int)} drops a position from the cache; a section that a
 * task still holds is only parked in the free queue after that task releases it.
 */
public class ClonedChunkSectionCache {
    private static final int MAX_CACHE_SIZE = 2048; /* number of entries */
    private static final long MAX_CACHE_DURATION = TimeUnit.SECONDS.toNanos(5); /* number of nanoseconds */
    private static final int MAX_FREE = 64; /* number of recycled sections kept for reuse */

    private final World world;

    private final Long2ReferenceLinkedOpenHashMap<ClonedChunkSection> positionToEntry = new Long2ReferenceLinkedOpenHashMap<>();

    private final ArrayDeque<ClonedChunkSection> free = new ArrayDeque<>();

    private long time; // updated once per frame to be the elapsed time since application start

    public ClonedChunkSectionCache(World world) {
        this.world = world;
        this.time = getMonotonicTimeSource();
    }

    public synchronized void cleanup() {
        this.time = getMonotonicTimeSource();

        while (!this.positionToEntry.isEmpty()) {
            ClonedChunkSection oldest = this.positionToEntry.get(this.positionToEntry.firstLongKey());

            if (oldest == null || this.time <= oldest.getLastUsedTimestamp() + MAX_CACHE_DURATION) {
                break;
            }

            evict(this.positionToEntry.removeFirst());
        }
    }

    @NotNull
    public synchronized ClonedChunkSection acquire(int x, int y, int z) {
        long pos = PositionUtil.packSection(x, y, z);
        ClonedChunkSection section = this.positionToEntry.getAndMoveToLast(pos);

        if (section == null) {
            while (this.positionToEntry.size() >= MAX_CACHE_SIZE) {
                evict(this.positionToEntry.removeFirst());
            }

            section = this.createSection(pos, x, y, z);
        }

        section.refs++;
        section.setLastUsedTimestamp(this.time);

        return section;
    }

    @NotNull
    private ClonedChunkSection createSection(long key, int x, int y, int z) {
        ClonedChunkSection section = this.free.pollLast();

        if (section == null) {
            section = new ClonedChunkSection(this.world);
        }

        section.init(new SectionPos(x, y, z));
        section.cached = true;
        this.positionToEntry.putAndMoveToLast(key, section);

        return section;
    }

    public synchronized void invalidate(int x, int y, int z) {
        ClonedChunkSection section = this.positionToEntry.remove(PositionUtil.packSection(x, y, z));

        if (section != null) {
            evict(section);
        }
    }

    /**
     * Releases one reference per section of {@code sections}. Null entries are skipped so a partially
     * filled snapshot array can be released as-is.
     *
     * @throws IllegalStateException when a section is released more often than it was acquired
     */
    public synchronized void release(ClonedChunkSection[] sections) {
        for (ClonedChunkSection section : sections) {
            if (section == null) {
                continue;
            }

            section.refs--;

            if (section.refs < 0) {
                throw new IllegalStateException("Released a ClonedChunkSection more often than acquired: "
                        + section.getPosition());
            }

            recycleIfIdle(section);
        }
    }

    private void evict(ClonedChunkSection section) {
        section.cached = false;
        recycleIfIdle(section);
    }

    private void recycleIfIdle(ClonedChunkSection section) {
        if (!section.cached && section.refs == 0 && this.free.size() < MAX_FREE) {
            section.reset();
            this.free.addLast(section);
        }
    }

    private static long getMonotonicTimeSource() {
        // Should be monotonic in JDK 17 on sane platforms...
        return System.nanoTime();
    }
}
