package me.flashyreese.mods.reeses_sodium_options.client.gui.option;

import com.dhj.actinium.gui.rso.compat.Component;
import dhj.embeddedt.embeddium.api.options.OptionIdentifier;
import dhj.embeddedt.embeddium.api.options.control.CyclingControl;
import dhj.embeddedt.embeddium.api.options.control.ExternalButtonControl;
import dhj.embeddedt.embeddium.api.options.control.SliderControl;
import dhj.embeddedt.embeddium.api.options.control.TickBoxControl;
import dhj.embeddedt.embeddium.api.options.structure.OptionImpact;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import dhj.embeddedt.embeddium.api.options.structure.Option;
import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Row-level view over an embeddium {@link Option}: exposes the upstream
 * pending/applied semantics and control-type dispatch helpers. Rows depend
 * only on this class and {@link RsoModOptions}; no Sodium config model is
 * involved.
 */
public final class RsoOption {
    private final Option<?> delegate;

    public RsoOption(Option<?> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Option must not be null");
    }

    /** Returns the underlying embeddium option (for control type checks). */
    public Option<?> unwrap() {
        return this.delegate;
    }

    /** Returns the stable option id (from the embeddium OptionIdentifier). */
    public String rso$getId() {
        OptionIdentifier<?> id = this.delegate.getId();
        return id == null ? "" : id.toString();
    }

    public ITextComponent getName() {
        return convertText(this.delegate.getName());
    }

    public ITextComponent getTooltip() {
        return convertText(this.delegate.getTooltip());
    }

    public boolean isEnabled() {
        return this.delegate.isAvailable();
    }

    public boolean hasChanged() {
        return this.delegate.hasChanged();
    }

    /** Returns the pending value the user has not applied yet. */
    public Object getPendingValue() {
        return this.delegate.getValue();
    }

    /** Returns the baseline value from the last apply. */
    public Object getAppliedValue() {
        return this.delegate.getAppliedValue();
    }

    /** Replaces the current edit value with a new pending value. */
    public void modifyValue(Object value) {
        this.setValue(value);
    }

    @SuppressWarnings("unchecked")
    private void setValue(Object value) {
        ((Option<Object>) this.delegate).setValue(value);
    }

    /** Discards pending changes, returning to the applied baseline. */
    public void undo() {
        this.delegate.reset();
    }

    /** Restores the declared default value. */
    public void resetToDefault() {
        this.delegate.resetToDefault();
    }

    /** Returns the declared default value. */
    public Object getDefaultValue() {
        return this.delegate.getDefaultValue();
    }

    /** Returns the performance impact label (may be null). */
    public String getImpactName() {
        OptionImpact impact = this.delegate.getImpact();
        return impact == null ? null : impact.name();
    }

    /** Control-type dispatch: whether this is a boolean tick-box. */
    public boolean isTickBox() {
        return this.delegate.getControl() instanceof TickBoxControl;
    }

    /** Control-type dispatch: whether this is an integer slider. */
    public boolean isSlider() {
        return this.delegate.getControl() instanceof SliderControl;
    }

    /** Control-type dispatch: whether this is a cycling control (enum/discrete values). */
    public boolean isCycling() {
        return this.delegate.getControl() instanceof CyclingControl;
    }

    /** Control-type dispatch: whether this opens a separate screen. */
    public boolean isExternalButton() {
        return this.delegate.getControl() instanceof ExternalButtonControl;
    }

    /** Returns the slider lower bound (meaningful only when isSlider). */
    public int sliderMin() {
        return ((SliderControl) this.delegate.getControl()).getMin();
    }

    /** Returns the slider upper bound (meaningful only when isSlider). */
    public int sliderMax() {
        return ((SliderControl) this.delegate.getControl()).getMax();
    }

    /** Returns the slider step (meaningful only when isSlider). */
    public int sliderInterval() {
        return ((SliderControl) this.delegate.getControl()).getInterval();
    }

    /** Returns the formatted slider value text (meaningful only when isSlider). */
    public ITextComponent formatSliderValue(Object value) {
        SliderControl control =
                (SliderControl) this.delegate.getControl();
        return convertText(control.getFormatter().format((Integer) value));
    }

    /** Returns the cycling control label for a value (meaningful only when isCycling). */
    public ITextComponent getElementName(Object value) {
        CyclingControl<Object> control =
                (CyclingControl<Object>) this.delegate.getControl();
        Object[] allowed = control.getAllowedValues();
        TextComponent[] names = control.getNames();
        for (int i = 0; i < allowed.length; i++) {
            if (Objects.equals(allowed[i], value)) {
                return convertText(names[i]);
            }
        }
        return new TextComponentString(value.toString());
    }

    /** Returns whether the cycling control accepts a value (meaningful only when isCycling). */
    public boolean isValueAllowed(Object value) {
        CyclingControl<Object> control =
                (CyclingControl<Object>) this.delegate.getControl();
        for (Object allowed : control.getAllowedValues()) {
            if (Objects.equals(allowed, value)) {
                return true;
            }
        }
        return false;
    }

    /** Returns all values of the cycling control (meaningful only when isCycling). */
    public Object[] getAllowedValues() {
        CyclingControl<Object> control =
                (CyclingControl<Object>) this.delegate.getControl();
        return control.getAllowedValues();
    }

    /** Returns the external button's screen consumer (meaningful only when isExternalButton). */
    public java.util.function.Consumer<net.minecraft.client.gui.GuiScreen> getCurrentScreenConsumer() {
        return ((ExternalButtonControl) this.delegate.getControl())
                .getScreenConsumer();
    }

    @Nullable
    public Component externalButtonLabel() {
        if (!(this.delegate.getControl() instanceof ExternalButtonControl control)) {
            return null;
        }
        TextComponent label = control.getLabelText();
        return label == null ? null : Component.fromEmbeddium(label);
    }

    /** Whether the control should be hidden while the option is disabled (always false here). */
    public boolean shouldHideControl() {
        return false;
    }

    private static ITextComponent convertText(TextComponent component) {
        if (component instanceof TextComponent.Translatable translatable) {
            return new TextComponentTranslation(translatable.keys().get(0),
                    translatable.args().stream()
                            .map(arg -> arg instanceof TextComponent nested ? convertText(nested) : arg)
                            .toArray());
        }
        if (component instanceof TextComponent.Literal literal) {
            return new TextComponentString(literal.text());
        }
        if (component instanceof TextComponent.Styled styled) {
            return convertText(styled.inner());
        }
        return new TextComponentString(component.toString());
    }
}
