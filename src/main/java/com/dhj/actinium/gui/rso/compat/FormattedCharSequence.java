package com.dhj.actinium.gui.rso.compat;

/**
 * Newer-Minecraft style formatted character sequence adapted to the 1.12.2
 * client. A lightweight carrier of already-formatted text used for tooltip
 * lines and text-field rendering.
 */
public final class FormattedCharSequence {
    private final String text;
    private final Style style;

    private FormattedCharSequence(String text, Style style) {
        this.text = text;
        this.style = style == null ? Style.EMPTY : style;
    }

    public static FormattedCharSequence forward(String text, Style style) {
        return new FormattedCharSequence(text, style);
    }

    public static FormattedCharSequence forward(String text) {
        return new FormattedCharSequence(text, Style.EMPTY);
    }

    public String getString() {
        return this.text;
    }

    public Style style() {
        return this.style;
    }
}
