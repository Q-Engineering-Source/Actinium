package com.dhj.actinium.gui.rso.compat;

/**
 * Newer-Minecraft style focus navigation event adapted to the 1.12.2 client.
 * Describes how focus should move: by arrow key direction or by tab order.
 */
public sealed interface FocusNavigationEvent {

    record ArrowNavigation(ScreenDirection direction) implements FocusNavigationEvent {
    }

    record TabNavigation(boolean forward) implements FocusNavigationEvent {
    }
}
