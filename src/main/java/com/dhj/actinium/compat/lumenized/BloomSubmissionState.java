package com.dhj.actinium.compat.lumenized;

import it.unimi.dsi.fastutil.booleans.BooleanArrayList;

/** Tracks whether each Lumenized bloom ticket group contains renderable work. */
public final class BloomSubmissionState {
    private static final ThreadLocal<Frames> FRAMES = ThreadLocal.withInitial(Frames::new);

    private BloomSubmissionState() {
    }

    public static void beginGroup() {
        final Frames frames = FRAMES.get();
        if (frames.depth == frames.groups.size()) {
            frames.groups.add(false);
        } else {
            frames.groups.set(frames.depth, false);
        }
        frames.depth++;
        frames.lastGroupHasContent = false;
    }

    public static void markRenderable() {
        final Frames frames = FRAMES.get();
        if (frames.depth > 0) {
            frames.groups.set(frames.depth - 1, true);
        }
    }

    public static void endGroup() {
        final Frames frames = FRAMES.get();
        if (frames.depth > 0) {
            frames.lastGroupHasContent = frames.groups.getBoolean(--frames.depth);
        }
    }

    public static boolean lastGroupHasContent() {
        return FRAMES.get().lastGroupHasContent;
    }

    private static final class Frames {
        private final BooleanArrayList groups = new BooleanArrayList(2);
        private int depth;
        private boolean lastGroupHasContent = true;
    }
}
