package com.dhj.actinium.gui.rso.compat;

/**
 * Newer-Minecraft style narration output adapted to the 1.12.2 client.
 * All narration is discarded because the 1.12.2 client has no screen reader;
 * the API surface is kept so widgets can retain their upstream structure.
 */
public final class NarrationElementOutput {

    public void add(NarratedElementType type, Component... contents) {
        // No screen reader on 1.12.2; narration is intentionally dropped.
    }
}
