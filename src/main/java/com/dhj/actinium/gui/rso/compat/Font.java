package com.dhj.actinium.gui.rso.compat;

import net.minecraft.client.gui.FontRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.12.2 adaptation of the newer Minecraft {@code Font} API used by
 * Reese's Sodium Options. Delegates width and wrapping queries to the
 * vanilla {@link FontRenderer}.
 */
public final class Font {
    private final FontRenderer delegate;

    /** Height of a single text line in pixels (upstream field spelling). */
    public final int lineHeight;

    public Font(FontRenderer delegate) {
        this.delegate = delegate;
        this.lineHeight = delegate.FONT_HEIGHT;
    }

    public int width(String text) {
        return this.delegate.getStringWidth(text);
    }

    public FontRenderer getDelegate() {
        return this.delegate;
    }

    public int width(Component text) {
        return this.delegate.getStringWidth(text.getString());
    }

    public int width(FormattedText text) {
        return this.delegate.getStringWidth(text.getString());
    }

    public int width(FormattedCharSequence text) {
        return this.delegate.getStringWidth(text.getString());
    }

    /** Trims the text so it fits the given width, returning the head. */
    public String plainSubstrByWidth(String text, int maxWidth) {
        return this.delegate.trimStringToWidth(text, maxWidth);
    }

    /** Trims the text so it fits the given width, optionally keeping the tail. */
    public String plainSubstrByWidth(String text, int maxWidth, boolean tail) {
        return this.delegate.trimStringToWidth(text, maxWidth, tail);
    }

    public List<FormattedCharSequence> split(Component text, int maxWidth) {
        List<String> lines = this.delegate.listFormattedStringToWidth(text.getString(), maxWidth);
        List<FormattedCharSequence> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(FormattedCharSequence.forward(line));
        }
        return result;
    }
}
