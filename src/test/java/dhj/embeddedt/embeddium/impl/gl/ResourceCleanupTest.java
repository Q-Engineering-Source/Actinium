package dhj.embeddedt.embeddium.impl.gl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceCleanupTest {
    @Test
    void runsEveryActionAndSuppressesCleanupFailuresOnThePrimary() {
        final IllegalStateException primary = new IllegalStateException("chunk shader link failed");
        final RuntimeException firstCleanup = new RuntimeException("vertex shader delete failed");
        final Error secondCleanup = new AssertionError("fragment shader delete failed");
        final List<String> order = new ArrayList<>();

        ResourceCleanup.runAll(primary,
                () -> { order.add("vertex"); throw firstCleanup; },
                () -> order.add("fragment"),
                () -> { order.add("third"); throw secondCleanup; });

        assertEquals(List.of("vertex", "fragment", "third"), order);
        assertEquals(List.of(firstCleanup, secondCleanup), List.of(primary.getSuppressed()));
    }

    @Test
    void throwsCleanupFailureOnlyAfterTryingEveryAction() {
        final RuntimeException firstCleanup = new RuntimeException("shader delete failed");
        final Error secondCleanup = new AssertionError("other shader delete failed");
        final List<String> order = new ArrayList<>();

        final RuntimeException thrown = assertThrows(RuntimeException.class, () -> ResourceCleanup.runAll(null,
                () -> { order.add("first"); throw firstCleanup; },
                () -> order.add("middle"),
                () -> { order.add("last"); throw secondCleanup; }));

        assertSame(firstCleanup, thrown);
        assertEquals(List.of(secondCleanup), List.of(thrown.getSuppressed()));
        assertEquals(List.of("first", "middle", "last"), order);
    }
}
