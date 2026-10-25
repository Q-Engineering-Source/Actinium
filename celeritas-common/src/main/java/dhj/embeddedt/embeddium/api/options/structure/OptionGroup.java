package dhj.embeddedt.embeddium.api.options.structure;

import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import dhj.embeddedt.embeddium.api.OptionGroupConstructionEvent;
import dhj.embeddedt.embeddium.api.options.OptionIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class OptionGroup {
    public static final OptionIdentifier<Void> DEFAULT_ID = OptionIdentifier.create("celeritas", "empty");

    private final List<Option<?>> options;

    public final OptionIdentifier<Void> id;

    /** Actinium extension: optional localized group header label. */
    private final TextComponent name;

    private OptionGroup(OptionIdentifier<Void> id, List<Option<?>> options,
                        TextComponent name) {
        this.id = id;
        this.options = options;
        this.name = name;
    }

    /** Returns the optional localized group header label (Actinium extension), or null. */
    public TextComponent getName() {
        return this.name;
    }

    public OptionIdentifier<Void> getId() {
        return id;
    }

    public static Builder createBuilder() {
        return new Builder();
    }

    public List<Option<?>> getOptions() {
        return this.options;
    }

    public static class Builder {
        private final List<Option<?>> options = new ArrayList<>();

        private OptionIdentifier<Void> id;

        private TextComponent name;

        public Builder setId(OptionIdentifier<Void> id) {
            this.id = id;

            return this;
        }

        /** Actinium extension: sets the localized group header label. */
        public Builder setName(TextComponent name) {
            this.name = name;

            return this;
        }

        public Builder add(Option<?> option) {
            this.options.add(option);

            return this;
        }

        public Builder addConditionally(boolean shouldAdd, Supplier<Option<?>> option) {
            if (shouldAdd) {
                add(option.get());
            }

            return this;
        }

        public OptionGroup build() {
            if (this.id == null) {
                this.id = OptionGroup.DEFAULT_ID;
                // FIXME Actually enforce IDs on groups
            }

            OptionGroupConstructionEvent.BUS.post(new OptionGroupConstructionEvent(this.id, this.options));

            return new OptionGroup(this.id, List.copyOf(this.options), this.name);
        }
    }
}
