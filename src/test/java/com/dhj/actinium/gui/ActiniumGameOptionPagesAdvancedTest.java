package com.dhj.actinium.gui;

import com.dhj.actinium.runtime.ActiniumRuntime;
import dhj.embeddedt.embeddium.api.options.OptionIdentifier;
import dhj.embeddedt.embeddium.api.options.control.ExternalButtonControl;
import dhj.embeddedt.embeddium.api.options.structure.Option;
import dhj.embeddedt.embeddium.api.options.structure.OptionGroup;
import dhj.embeddedt.embeddium.api.options.structure.OptionPage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the real ADVANCED page so every option id it registers is checked against the
 * {@link OptionIdentifier} registry. A group id that repeats a member option path makes that
 * registry reject the second type, which aborts the page build instead of the page appearing.
 */
class ActiniumGameOptionPagesAdvancedTest {
    @Test
    void entityBatchingGroupUsesItsOwnIdentifier() {
        OptionGroup group = entityBatchingGroup(ActiniumGameOptionPages.advanced());

        assertEquals(ActiniumRuntime.MODID, group.getId().getModId());

        for (Option<?> option : group.getOptions()) {
            assertNotEquals(option.getId().getPath(), group.getId().getPath(),
                    "group id repeats the option path " + option.getId());
        }
    }

    @Test
    void shadowReplayExclusionEditorIsInTheEntityBatchingGroup() {
        OptionGroup group = entityBatchingGroup(ActiniumGameOptionPages.advanced());

        assertTrue(group.getOptions().stream().map(Option::getControl).anyMatch(ExternalButtonControl.class::isInstance),
                "the entity batching group must expose the exclusion list editor");
    }

    private static OptionGroup entityBatchingGroup(OptionPage page) {
        OptionIdentifier<Boolean> batching = OptionIdentifier.create(ActiniumRuntime.MODID, "entity_batching", boolean.class);

        return page.getGroups().stream()
                .filter(group -> group.getOptions().stream().anyMatch(option -> batching.equals(option.getId())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("advanced page has no entity batching group"));
    }
}
