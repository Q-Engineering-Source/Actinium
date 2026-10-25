package com.dhj.actinium.gui.rso.compat;

/**
 * Newer-Minecraft style GUI event listener adapted to the 1.12.2 client.
 * Mirrors the upstream {@code GuiEventListener} contract so Reese's Sodium
 * Options widgets can keep their event handling structure unchanged.
 */
public interface GuiEventListener {

    default boolean isActive() {
        return true;
    }

    default boolean isMouseOver(double mouseX, double mouseY) {
        return false;
    }

    default void setFocused(boolean focused) {
    }

    default boolean isFocused() {
        return false;
    }

    default boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    default boolean mouseReleased(MouseButtonEvent event) {
        return false;
    }

    default boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return false;
    }

    default boolean keyPressed(KeyEvent event) {
        return false;
    }

    default boolean charTyped(CharacterEvent event) {
        return false;
    }

    /**
     * Computes the next focus path for the given navigation event, or null if
     * this listener cannot take focus.
     */
    default ComponentPath nextFocusPath(FocusNavigationEvent navigation) {
        return null;
    }

    /**
     * Returns the rectangle occupied by this listener in GUI coordinates.
     */
    default ScreenRectangle getRectangle() {
        return ScreenRectangle.empty();
    }

    /**
     * Returns the current focus path of this listener, or null when it does
     * not currently hold focus.
     */
    default ComponentPath getCurrentFocusPath() {
        return null;
    }
}
