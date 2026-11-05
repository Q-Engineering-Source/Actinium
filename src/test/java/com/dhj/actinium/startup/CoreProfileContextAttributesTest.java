package com.dhj.actinium.startup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CoreProfileContextAttributesTest {
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
