package com.dhj.actinium.gui;

import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the text line the shadow replay exclusion editor edits: names are separated by commas or whitespace, the
 * order the player entered is kept, and a malformed name is rejected instead of being written as a class that never
 * matches.
 */
class EntityShadowReplayExcludedClassesScreenTest {
    @Test
    void parseAcceptsCommaAndWhitespaceSeparatedNames() {
        assertEquals(List.of("com.example.One", "com.example.Two", "com.example.Three"),
                EntityShadowReplayExcludedClassesScreen.parseClassNames(
                        " com.example.One, com.example.Two\ncom.example.Three "));
    }

    @Test
    void parseDeduplicatesAndKeepsTheFirstOccurrenceOrder() {
        assertEquals(List.of("com.example.Two", "com.example.One"),
                EntityShadowReplayExcludedClassesScreen.parseClassNames(
                        "com.example.Two com.example.One,com.example.Two"));
    }

    @Test
    void blankInputMeansNoExcludedClass() {
        assertTrue(EntityShadowReplayExcludedClassesScreen.parseClassNames("   ").isEmpty());
        assertTrue(EntityShadowReplayExcludedClassesScreen.parseClassNames("").isEmpty());
    }

    @Test
    void parseKeepsNestedClassNames() {
        assertEquals(List.of("com.example.Outer$Inner", "com.example._Leading", "Single"),
                EntityShadowReplayExcludedClassesScreen.parseClassNames(
                        "com.example.Outer$Inner com.example._Leading Single"));
    }

    @Test
    void parseRejectsMalformedNames() {
        for (String invalid : List.of("com..example.Foo", ".Foo", "Foo.", "com/example/Foo",
                "com.example.Foo-Bar", "com.example.1Foo", "com.example.Foo@1234")) {
            assertThrows(IllegalArgumentException.class,
                    () -> EntityShadowReplayExcludedClassesScreen.parseClassNames(invalid),
                    "expected '" + invalid + "' to be rejected");
        }

        assertThrows(IllegalArgumentException.class,
                () -> EntityShadowReplayExcludedClassesScreen.parseClassNames(null));
    }

    @Test
    void formattingRoundTripsThroughParsing() {
        List<String> classNames = List.of("twilightforest.entity.passive.EntityTFMobileFirefly",
                "gregtech.common.entity.EntityDrone");

        assertEquals(classNames, EntityShadowReplayExcludedClassesScreen.parseClassNames(
                EntityShadowReplayExcludedClassesScreen.formatClassNames(classNames)));
    }

    @Test
    void shippedDefaultsSurviveAnEditRoundTrip() {
        List<String> defaults = new SodiumGameOptions().performance.entityShadowReplayExcludedClasses;

        assertEquals(defaults, EntityShadowReplayExcludedClassesScreen.parseClassNames(
                EntityShadowReplayExcludedClassesScreen.formatClassNames(defaults)));
    }
}
