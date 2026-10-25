package com.dhj.actinium.gui.rso.compat;

import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

/**
 * Newer-Minecraft style text component adapted to the 1.12.2 client.
 * Wraps a 1.12.2 {@link ITextComponent} and exposes the subset of the modern
 * {@code Component} API used by Reese's Sodium Options. Arbitrary RGB colors
 * that the 1.12.2 style system cannot represent are retained separately and
 * applied at draw time by {@link GuiGraphicsExtractor}.
 */
public class Component implements FormattedText {
    private final ITextComponent delegate;
    private Style rgbStyle;

    protected Component(ITextComponent delegate) {
        this.delegate = delegate;
    }

    public static Component translatable(String key, Object... args) {
        return new Component(new TextComponentTranslation(key, args));
    }

    public static Component literal(String text) {
        return new Component(new TextComponentString(text));
    }

    public static Component from(ITextComponent component) {
        return new Component(component);
    }

    public static Component fromEmbeddium(TextComponent component) {
        if (component instanceof TextComponent.Translatable translatable) {
            return new Component(new TextComponentTranslation(translatable.keys().get(0),
                    translatable.args().stream()
                            .map(arg -> arg instanceof TextComponent nested
                                    ? fromEmbeddium(nested).unwrap()
                                    : arg)
                            .toArray()));
        }
        if (component instanceof TextComponent.Literal literal) {
            return new Component(new TextComponentString(literal.text()));
        }
        if (component instanceof TextComponent.Styled styled) {
            return fromEmbeddium(styled.inner());
        }
        return new Component(new TextComponentString(component.toString()));
    }

    public static Component empty() {
        return new Component(new TextComponentString(""));
    }

    public Component copy() {
        Component copy = new Component(this.delegate.createCopy());
        copy.rgbStyle = this.rgbStyle == null ? null : this.rgbStyle.copy();
        return copy;
    }

    /** Returns a mutable copy for chained style/append operations. */
    public MutableComponent mutableCopy() {
        return new MutableComponent(this.delegate.createCopy(), this.rgbStyle == null ? null : this.rgbStyle.copy());
    }

    public Component withStyle(Style style) {
        if (style == null) {
            return this;
        }
        this.delegate.setStyle(this.delegate.getStyle().createShallowCopy());
        if (style.formattingColor() != null) {
            this.delegate.getStyle().setColor(style.formattingColor());
        }
        if (style.isItalic()) {
            this.delegate.getStyle().setItalic(true);
        }
        if (style.isStrikethrough()) {
            this.delegate.getStyle().setStrikethrough(true);
        }
        if (style.isUnderlined()) {
            this.delegate.getStyle().setUnderlined(true);
        }
        if (style.rgbColor() != null) {
            this.rgbStyle = style;
        }
        return this;
    }

    public Component withStyle(TextFormatting... formats) {
        this.delegate.setStyle(this.delegate.getStyle().createShallowCopy());
        for (TextFormatting format : formats) {
            if (format.isColor()) {
                this.delegate.getStyle().setColor(format);
            } else if (format == TextFormatting.ITALIC) {
                this.delegate.getStyle().setItalic(true);
            } else if (format == TextFormatting.STRIKETHROUGH) {
                this.delegate.getStyle().setStrikethrough(true);
            } else if (format == TextFormatting.UNDERLINE) {
                this.delegate.getStyle().setUnderlined(true);
            } else if (format == TextFormatting.BOLD) {
                this.delegate.getStyle().setBold(true);
            }
        }
        return this;
    }

    public MutableComponent append(Component other) {
        this.delegate.appendSibling(other.delegate);
        return new MutableComponent(this.delegate, this.rgbStyle == null ? null : this.rgbStyle.copy());
    }

    public MutableComponent append(String text) {
        return this.append(literal(text));
    }

    @Override
    public String getString() {
        return this.delegate.getUnformattedText();
    }

    /** Returns the formatted text including legacy color codes. */
    public String getFormattedString() {
        return this.delegate.getFormattedText();
    }

    /** Returns the style carrying an arbitrary RGB color, or null. */
    public Style rgbStyle() {
        return this.rgbStyle;
    }

    @Override
    public ITextComponent unwrap() {
        return this.delegate;
    }
}
