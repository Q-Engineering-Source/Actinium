package com.dhj.actinium.gui.rso.compat;

/**
 * Newer-Minecraft style keyboard event adapted to the 1.12.2 client.
 * Carries the raw key, scancode and modifier mask so widgets can query
 * selection, copy/paste and shift/control state. Key codes use GLFW
 * semantics to match upstream Reese's Sodium Options; the owning screen
 * translates 1.12.2 LWJGL2 codes via {@link #fromLwjgl2(int, boolean, boolean, boolean)}.
 */
public record KeyEvent(int key, int scancode, int modifiers) {

    // GLFW key codes (upstream constants)
    public static final int KEY_ESCAPE = 256;
    public static final int KEY_ENTER = 257;
    public static final int KEY_TAB = 258;
    public static final int KEY_BACKSPACE = 259;
    public static final int KEY_INSERT = 260;
    public static final int KEY_DELETE = 261;
    public static final int KEY_RIGHT = 262;
    public static final int KEY_LEFT = 263;
    public static final int KEY_DOWN = 264;
    public static final int KEY_UP = 265;
    public static final int KEY_PAGE_UP = 266;
    public static final int KEY_PAGE_DOWN = 267;
    public static final int KEY_HOME = 268;
    public static final int KEY_END = 269;
    public static final int KEY_KP_ENTER = 335;
    public static final int KEY_A = 65;
    public static final int KEY_C = 67;
    public static final int KEY_F = 70;
    public static final int KEY_P = 80;
    public static final int KEY_V = 86;
    public static final int KEY_X = 88;
    public static final int KEY_Z = 90;

    private static final int MODIFIER_SHIFT = 1;
    private static final int MODIFIER_CONTROL = 2;
    private static final int MODIFIER_ALT = 4;

    /**
     * Translates a 1.12.2 LWJGL2 key code to a GLFW key code, so the rest of
     * the RSO widget tree can keep upstream GLFW semantics.
     */
    public static int toGlfw(int lwjgl2Key) {
        return switch (lwjgl2Key) {
            case 1 -> KEY_ESCAPE;              // KEY_ESCAPE
            case 28 -> KEY_ENTER;              // KEY_RETURN
            case 156 -> KEY_KP_ENTER;          // KEY_NUMPADENTER
            case 15 -> KEY_TAB;                // KEY_TAB
            case 14 -> KEY_BACKSPACE;          // KEY_BACK
            case 211 -> KEY_DELETE;            // KEY_DELETE
            case 205 -> KEY_RIGHT;             // KEY_RIGHT
            case 203 -> KEY_LEFT;              // KEY_LEFT
            case 208 -> KEY_DOWN;              // KEY_DOWN
            case 200 -> KEY_UP;                // KEY_UP
            case 201 -> KEY_PAGE_UP;           // KEY_PRIOR
            case 209 -> KEY_PAGE_DOWN;         // KEY_NEXT
            case 199 -> KEY_HOME;              // KEY_HOME
            case 207 -> KEY_END;               // KEY_END
            case 30 -> KEY_A;                  // KEY_A
            case 46 -> KEY_C;                  // KEY_C
            case 33 -> KEY_F;                  // KEY_F
            case 25 -> KEY_P;                  // KEY_P
            case 47 -> KEY_V;                  // KEY_V
            case 45 -> KEY_X;                  // KEY_X
            case 44 -> KEY_Z;                  // KEY_Z
            default -> lwjgl2Key + 256;        // best-effort identity mapping for unlisted keys
        };
    }

    public static KeyEvent fromLwjgl2(int lwjgl2Key, boolean shift, boolean control, boolean alt) {
        int modifiers = (shift ? MODIFIER_SHIFT : 0) | (control ? MODIFIER_CONTROL : 0) | (alt ? MODIFIER_ALT : 0);
        return new KeyEvent(toGlfw(lwjgl2Key), lwjgl2Key, modifiers);
    }

    public boolean hasShiftDown() {
        return (this.modifiers & MODIFIER_SHIFT) != 0;
    }

    public boolean hasControlDown() {
        return (this.modifiers & MODIFIER_CONTROL) != 0;
    }

    public boolean hasAltDown() {
        return (this.modifiers & MODIFIER_ALT) != 0;
    }

    /** Returns whether this event is the activation/selection key (Enter). */
    public boolean isSelection() {
        return this.key == KEY_ENTER || this.key == KEY_KP_ENTER;
    }

    public boolean isSelectAll() {
        return this.key == KEY_A && this.hasControlDown();
    }

    public boolean isCopy() {
        return this.key == KEY_C && this.hasControlDown();
    }

    public boolean isPaste() {
        return this.key == KEY_V && this.hasControlDown();
    }

    public boolean isCut() {
        return this.key == KEY_X && this.hasControlDown();
    }

    public boolean isLeft() {
        return this.key == KEY_LEFT;
    }

    public boolean isRight() {
        return this.key == KEY_RIGHT;
    }

    public boolean isUp() {
        return this.key == KEY_UP;
    }

    public boolean isDown() {
        return this.key == KEY_DOWN;
    }

    public boolean isArrow() {
        return this.isLeft() || this.isRight() || this.isUp() || this.isDown();
    }

    public boolean isEscape() {
        return this.key == KEY_ESCAPE;
    }

    public boolean isTab() {
        return this.key == KEY_TAB;
    }

    public boolean isHome() {
        return this.key == KEY_HOME;
    }

    public boolean isEnd() {
        return this.key == KEY_END;
    }

    public boolean isPageUp() {
        return this.key == KEY_PAGE_UP;
    }

    public boolean isPageDown() {
        return this.key == KEY_PAGE_DOWN;
    }

    /** Returns whether this event is the F key (search shortcut target). */
    public boolean isF() {
        return this.key == KEY_F;
    }

    /** Returns whether this event is the P key (vanilla video settings shortcut target). */
    public boolean isP() {
        return this.key == KEY_P;
    }

    /** Returns whether this event is the Z key (undo shortcut target). */
    public boolean isZ() {
        return this.key == KEY_Z;
    }

    /** Returns whether this event is Enter (either main or keypad). */
    public boolean isEnter() {
        return this.key == KEY_ENTER || this.key == KEY_KP_ENTER;
    }
}
