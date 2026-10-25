package com.dhj.actinium.gui.rso.compat;

import net.minecraft.util.text.TextFormatting;

/**
 * Newer-Minecraft style text style adapted to the 1.12.2 client.
 * Supports the legacy formatting codes plus arbitrary RGB colors, which the
 * 1.12.2 font renderer accepts directly through its draw color parameter.
 */
public final class Style {
    public static final Style EMPTY = new Style();

    private TextFormatting formattingColor;
    private Integer rgbColor;
    private Boolean italic;
    private Boolean strikethrough;
    private Boolean underlined;

    private Style() {
    }

    public Style withColor(TextFormatting color) {
        Style style = this.copy();
        style.formattingColor = color;
        style.rgbColor = null;
        return style;
    }

    public Style withColor(int argb) {
        Style style = this.copy();
        style.formattingColor = null;
        style.rgbColor = argb;
        return style;
    }

    public Style withItalic(boolean italic) {
        Style style = this.copy();
        style.italic = italic;
        return style;
    }

    public Style withStrikethrough(boolean strikethrough) {
        Style style = this.copy();
        style.strikethrough = strikethrough;
        return style;
    }

    public Style withUnderlined(boolean underlined) {
        Style style = this.copy();
        style.underlined = underlined;
        return style;
    }

    /** Returns the arbitrary RGB color, or null when the style uses legacy codes only. */
    public Integer rgbColor() {
        return this.rgbColor;
    }

    /** Returns the legacy formatting color, or null. */
    public TextFormatting formattingColor() {
        return this.formattingColor;
    }

    public boolean isItalic() {
        return Boolean.TRUE.equals(this.italic);
    }

    public boolean isStrikethrough() {
        return Boolean.TRUE.equals(this.strikethrough);
    }

    public boolean isUnderlined() {
        return Boolean.TRUE.equals(this.underlined);
    }

    public Style copy() {
        Style style = new Style();
        style.formattingColor = this.formattingColor;
        style.rgbColor = this.rgbColor;
        style.italic = this.italic;
        style.strikethrough = this.strikethrough;
        style.underlined = this.underlined;
        return style;
    }

    /** Returns the legacy color as a formatting prefix, or an empty string. */
    String legacyPrefix() {
        if (this.formattingColor != null) {
            return this.formattingColor.toString();
        }
        return "";
    }

    /** Returns the style flags as legacy formatting suffixes, or an empty string. */
    String legacyFlags() {
        StringBuilder builder = new StringBuilder();
        if (this.isItalic()) {
            builder.append(TextFormatting.ITALIC);
        }
        if (this.isStrikethrough()) {
            builder.append(TextFormatting.STRIKETHROUGH);
        }
        if (this.isUnderlined()) {
            builder.append(TextFormatting.UNDERLINE);
        }
        return builder.toString();
    }
}
