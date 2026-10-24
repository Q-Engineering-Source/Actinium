package com.dhj.actinium.gui.rso.compat;

import java.util.List;

/**
 * Newer-Minecraft style container of GUI event listeners adapted to the
 * 1.12.2 client. Mirrors the upstream {@code ContainerEventHandler}
 * contract: a parent element that tracks a focused child and forwards
 * interactions to it.
 */
public interface ContainerEventHandler extends GuiEventListener {

    /** Returns the direct children of this container in render order. */
    List<? extends GuiEventListener> children();

    /** Returns whether this container is currently dragging. */
    default boolean isDragging() {
        return false;
    }

    /** Sets whether this container is currently dragging. */
    default void setDragging(boolean dragging) {
    }

    /** Returns the currently focused child, or null. */
    default GuiEventListener getFocused() {
        return null;
    }

    default void setFocused(GuiEventListener focused) {
        GuiEventListener previous = this.getFocused();
        if (previous != null) {
            previous.setFocused(false);
        }
        if (focused != null) {
            focused.setFocused(true);
        }
    }

    @Override
    default boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        GuiEventListener focused = this.getFocused();
        return focused != null && focused.mouseClicked(event, doubleClick);
    }

    @Override
    default boolean mouseReleased(MouseButtonEvent event) {
        GuiEventListener focused = this.getFocused();
        return focused != null && focused.mouseReleased(event);
    }

    @Override
    default boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        GuiEventListener focused = this.getFocused();
        return focused != null && focused.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    default boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        GuiEventListener focused = this.getFocused();
        return focused != null && focused.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    default boolean keyPressed(KeyEvent event) {
        GuiEventListener focused = this.getFocused();
        return focused != null && focused.keyPressed(event);
    }

    @Override
    default boolean charTyped(CharacterEvent event) {
        GuiEventListener focused = this.getFocused();
        return focused != null && focused.charTyped(event);
    }

    /**
     * Returns the focus path of the currently focused leaf, or null when
     * nothing inside this container holds focus.
     */
    default ComponentPath getCurrentFocusPath() {
        GuiEventListener focused = this.getFocused();
        return focused == null ? null : ComponentPath.path(this, focused.getCurrentFocusPath());
    }
}
