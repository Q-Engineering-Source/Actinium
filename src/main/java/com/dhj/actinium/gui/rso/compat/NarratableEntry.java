package com.dhj.actinium.gui.rso.compat;

import java.util.Collection;
import java.util.List;

/**
 * Newer-Minecraft style narratable entry adapted to the 1.12.2 client.
 * Retained only for source compatibility; see {@link NarrationPriority}.
 */
public interface NarratableEntry {

    default NarrationPriority narrationPriority() {
        return NarrationPriority.NONE;
    }

    /** Writes this entry's narration; a no-op on 1.12.2. */
    default void updateNarration(NarrationElementOutput builder) {
    }

    /** Returns nested narratable entries; a no-op on 1.12.2. */
    default Collection<? extends NarratableEntry> getNarratables() {
        return List.of();
    }
}
