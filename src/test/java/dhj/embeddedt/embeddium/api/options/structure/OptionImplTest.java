package dhj.embeddedt.embeddium.api.options.structure;

import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how an option's declared GUI side effects are read back.
 */
class OptionImplTest {

    private boolean filteringOff;

    /**
     * A side effect that depends on another option cannot be decided when the option is built, so the set has
     * to be asked for again on every query - the GUI reads it only after applying a change.
     */
    @Test
    void supplierFlagsAreRecomputedOnEveryQuery() {
        final OptionImpl<Object, Integer> option = optionBuilder()
            .setFlags(() -> this.filteringOff
                ? EnumSet.of(OptionFlag.REQUIRES_RENDERER_RELOAD)
                : EnumSet.noneOf(OptionFlag.class))
            .build();

        this.filteringOff = true;
        assertEquals(Set.of(OptionFlag.REQUIRES_RENDERER_RELOAD), Set.copyOf(option.getFlags()));

        this.filteringOff = false;
        assertTrue(option.getFlags().isEmpty());
    }

    @Test
    void declaredFlagsAreReadBack() {
        final OptionImpl<Object, Integer> option = optionBuilder()
            .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD, OptionFlag.REQUIRES_RENDERER_RELOAD)
            .build();

        assertEquals(Set.of(OptionFlag.REQUIRES_ASSET_RELOAD, OptionFlag.REQUIRES_RENDERER_RELOAD),
            Set.copyOf(option.getFlags()));
    }

    @Test
    void anOptionWithoutFlagsHasNone() {
        assertTrue(optionBuilder().build().getFlags().isEmpty());
    }

    private static OptionImpl.Builder<Object, Integer> optionBuilder() {
        return OptionImpl.createBuilder(Integer.class, new OptionStorage<>() {
            @Override
            public Object getData() {
                return new Object();
            }
        })
            .setName(TextComponent.literal("Test"))
            .setTooltip(TextComponent.literal("Test"))
            .setControl(option -> null)
            .setBinding((opts, value) -> {
            }, opts -> 0);
    }
}
