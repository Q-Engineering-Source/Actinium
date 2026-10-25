package com.dhj.actinium.gui.rso.compat;

/**
 * Newer-Minecraft style screen rectangle adapted to the 1.12.2 client.
 * Immutable GUI-space rectangle used for focus navigation bounds.
 */
public record ScreenRectangle(int x, int y, int width, int height) {

    public static ScreenRectangle empty() {
        return new ScreenRectangle(0, 0, 0, 0);
    }

    /** Returns the right edge (exclusive). */
    public int right() {
        return this.x + this.width;
    }

    /** Returns the bottom edge (exclusive). */
    public int bottom() {
        return this.y + this.height;
    }
}
