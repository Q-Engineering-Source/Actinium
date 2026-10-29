package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.recording.CompiledDisplayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/** Owns the allocated names and compiled lists visible to one OpenGL share group. */
final class DisplayListNamespace {
    private final long shareGroupId;
    private final DisplayListIDAllocator allocator = new DisplayListIDAllocator();
    private final Int2ObjectMap<CompiledDisplayList> compiledLists = new Int2ObjectOpenHashMap<>();

    DisplayListNamespace(long shareGroupId) {
        if (shareGroupId <= 0L) {
            throw new IllegalArgumentException("Display-list namespace requires a positive share-group id");
        }
        this.shareGroupId = shareGroupId;
    }

    /** Allocates an unused contiguous range without returning names already defined by glNewList. */
    synchronized int allocateRange(int range) {
        return this.allocator.allocRange(range, this.compiledLists::containsKey);
    }

    synchronized boolean contains(int list) {
        return this.compiledLists.containsKey(list);
    }

    synchronized CompiledDisplayList get(int list) {
        return this.compiledLists.get(list);
    }

    /** Stores a newly compiled list and returns the previous object, if any, for cleanup. */
    synchronized CompiledDisplayList put(int list, CompiledDisplayList compiled) {
        return this.compiledLists.put(list, compiled);
    }

    /** Removes only this namespace's compiled objects and returns their ownership to the caller. */
    synchronized List<CompiledDisplayList> deleteRange(int first, int count) {
        if (first <= 0 || count <= 0) {
            return List.of();
        }

        final List<CompiledDisplayList> removed = new ArrayList<>();
        final long end = Math.min((long) Integer.MAX_VALUE + 1L, (long) first + count);
        for (long value = first; value < end; value++) {
            final CompiledDisplayList compiled = this.compiledLists.remove((int) value);
            if (compiled != null) {
                removed.add(compiled);
            }
        }
        this.allocator.freeRange(first, (int) (end - first));
        return removed;
    }

    /** Detaches all lists after the registry reports the share group's final context retired. */
    synchronized List<CompiledDisplayList> retire() {
        final List<CompiledDisplayList> retired = new ArrayList<>(this.compiledLists.values());
        this.compiledLists.clear();
        return retired;
    }

    long shareGroupId() {
        return this.shareGroupId;
    }
}
