package com.dhj.actinium.debug;

import com.dhj.actinium.compat.ForgeEarlyConfigCompat;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.ContextAttribs;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CoreProfileContextAttributesTest {
    @Test
    void createsGl46WithoutTrippingLegacyConstructorLimit() {
        assumeTrue(hasWritableVersionFields(), "Requires field-based LWJGL ContextAttribs");
        ContextAttribs attributes = CoreProfileContextAttributes.create(4, 6, false);

        assertEquals(4, attributes.getMajorVersion());
        assertEquals(6, attributes.getMinorVersion());
        assertTrue(attributes.isProfileCore());
        assertTrue(attributes.isForwardCompatible());
        assertFalse(attributes.isDebug());
    }

    @Test
    void configuresTheOriginalContextInRequiredOrder() {
        RecordingContext attributes = new RecordingContext();

        RecordingContext configured = CoreProfileContextAttributes.configure(
            attributes,
            value -> {
                value.record("core");
                return null;
            },
            value -> {
                value.record("forward");
                return null;
            },
            value -> {
                value.record("debug");
                return null;
            }
        );

        assertSame(attributes, configured);
        assertEquals(List.of("core", "forward", "debug"), attributes.operations());
    }

    @Test
    void configuresImmutableContextsInRequiredOrder() {
        ImmutableRecordingContext attributes = new ImmutableRecordingContext();
        List<ImmutableRecordingContext> mutations = new ArrayList<>();

        ImmutableRecordingContext configured = CoreProfileContextAttributes.configure(
            attributes,
            value -> recordMutation(mutations, value, "core"),
            value -> recordMutation(mutations, value, "forward"),
            value -> recordMutation(mutations, value, "debug")
        );

        assertSame(mutations.get(2), configured);
        assertEquals(List.of("core", "forward", "debug"), configured.operations());
    }

    @Test
    void retainsMutableContextWhenMutationsReturnNull() {
        RecordingContext attributes = new RecordingContext();

        RecordingContext configured = CoreProfileContextAttributes.configure(
            attributes,
            value -> {
                value.record("core");
                return null;
            },
            value -> {
                value.record("forward");
                return null;
            },
            value -> {
                value.record("debug");
                return null;
            }
        );

        assertSame(attributes, configured);
        assertEquals(List.of("core", "forward", "debug"), attributes.operations());
    }

    @Test
    void appliesForgeEarlyCoreProfileForLwjglxx() {
        ForgeEarlyConfigCompat.Snapshot original = ForgeEarlyConfigCompat.snapshot();

        try {
            CoreProfileContextAttributes.applyForgeEarlyCoreProfile(4, 1, true);

            ForgeEarlyConfigCompat.Snapshot configured = ForgeEarlyConfigCompat.snapshot();
            assertEquals(4, configured.major());
            assertEquals(1, configured.minor());
            assertFalse(configured.compatibilityProfile());
            assertTrue(configured.debugContext());
        } finally {
            ForgeEarlyConfigCompat.restore(original);
        }
    }

    @Test
    void restoresCompatProfileForLwjglxxAfterCoreContextCreation() {
        ForgeEarlyConfigCompat.Snapshot original = ForgeEarlyConfigCompat.snapshot();

        try {
            CoreProfileContextAttributes.applyForgeEarlyCoreProfile(4, 6, true);
            assertFalse(ForgeEarlyConfigCompat.snapshot().compatibilityProfile());

            CoreProfileContextAttributes.restoreForgeEarlyCompatProfile(4, 6, false);

            ForgeEarlyConfigCompat.Snapshot restored = ForgeEarlyConfigCompat.snapshot();
            assertEquals(4, restored.major());
            assertEquals(6, restored.minor());
            assertTrue(restored.compatibilityProfile(),
                "The compatibility profile must be restored so Cleanroom can start without Actinium");
            assertFalse(restored.debugContext());
        } finally {
            ForgeEarlyConfigCompat.restore(original);
        }
    }

    private static boolean hasWritableVersionFields() {
        try {
            ContextAttribs.class.getDeclaredField("majorVersion");
            return true;
        } catch (NoSuchFieldException unavailable) {
            return false;
        }
    }

    private static ImmutableRecordingContext recordMutation(
        List<ImmutableRecordingContext> mutations,
        ImmutableRecordingContext attributes,
        String operation
    ) {
        ImmutableRecordingContext mutated = attributes.record(operation);
        mutations.add(mutated);
        return mutated;
    }

    private static final class ImmutableRecordingContext {
        private final List<String> operations;

        private ImmutableRecordingContext() {
            this(List.of());
        }

        private ImmutableRecordingContext(List<String> operations) {
            this.operations = operations;
        }

        private ImmutableRecordingContext record(String operation) {
            List<String> mutated = new ArrayList<>(operations);
            mutated.add(operation);
            return new ImmutableRecordingContext(mutated);
        }

        private List<String> operations() {
            return List.copyOf(operations);
        }
    }

    private static final class RecordingContext {
        private final List<String> operations = new ArrayList<>();

        private void record(String operation) {
            operations.add(operation);
        }

        private List<String> operations() {
            return List.copyOf(operations);
        }
    }
}
