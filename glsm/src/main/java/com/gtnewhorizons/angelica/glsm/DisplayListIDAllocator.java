package com.gtnewhorizons.angelica.glsm;

/*
 * Ported from Mesa's util_idalloc (src/util/u_idalloc.c).
 *
 * Original copyright and license:
 *
 * Copyright 2017 Valve Corporation
 * All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sub license, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice (including the
 * next paragraph) shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS AND/OR ITS SUPPLIERS BE LIABLE FOR
 * ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * Original author: Samuel Pitoiset <samuel.pitoiset@gmail.com>
 */

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Tracks allocated positive display list names as disjoint, non-adjacent intervals. */
public class DisplayListIDAllocator {
    private final NavigableMap<Integer, Integer> allocated = new TreeMap<>();

    /** Allocates consecutive names, or returns zero if no sufficiently large range is free. */
    public synchronized int allocRange(int count) {
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");

        long base = 1;
        for (Map.Entry<Integer, Integer> range : allocated.entrySet()) {
            if (base + count <= range.getKey()) break;
            base = (long) range.getValue() + 1;
        }
        final long last = base + count - 1;
        if (last > Integer.MAX_VALUE) return 0;

        addRange((int) base, (int) last);
        return (int) base;
    }

    /** Retains a name installed by compilation, including names already allocated by glGenLists. */
    public synchronized void reserve(int id) {
        if (id > 0 && !isAllocated(id)) addRange(id, id);
    }

    private void addRange(int base, int last) {
        final Map.Entry<Integer, Integer> before = allocated.lowerEntry(base);
        if (before != null && before.getValue() == base - 1) {
            base = before.getKey();
        }
        if (last < Integer.MAX_VALUE) {
            final Integer after = allocated.remove(last + 1);
            if (after != null) last = after;
        }
        allocated.put(base, last);
    }

    /** Releases a positive name for reuse. */
    public void free(int id) {
        freeRange(id, 1);
    }

    /** Releases allocated names in the requested range up to the largest positive ID. */
    public synchronized void freeRange(int base, int count) {
        if (base <= 0 || count <= 0) return;
        final int last = (int) Math.min((long) base + count - 1, Integer.MAX_VALUE);

        final Map.Entry<Integer, Integer> before = allocated.lowerEntry(base);
        if (before != null && before.getValue() >= base) {
            allocated.put(before.getKey(), base - 1);
            if (before.getValue() > last) {
                allocated.put(last + 1, before.getValue());
                return;
            }
        }

        Map.Entry<Integer, Integer> range;
        while ((range = allocated.ceilingEntry(base)) != null && range.getKey() <= last) {
            allocated.remove(range.getKey());
            if (range.getValue() > last) {
                allocated.put(last + 1, range.getValue());
                return;
            }
        }
    }

    /** Reports whether a positive name is reserved or part of a generated range. */
    public synchronized boolean isAllocated(int id) {
        if (id <= 0) return false;
        final Map.Entry<Integer, Integer> range = allocated.floorEntry(id);
        return range != null && range.getValue() >= id;
    }
}
