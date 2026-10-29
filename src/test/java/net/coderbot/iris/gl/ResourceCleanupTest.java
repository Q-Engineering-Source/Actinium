package net.coderbot.iris.gl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceCleanupTest {
	@Test
	void continuesAfterCleanupFailuresAndKeepsTheOriginalFailure() {
		final IllegalStateException primary = new IllegalStateException("program build failed");
		final RuntimeException firstCleanup = new RuntimeException("shader delete failed");
		final IllegalArgumentException secondCleanup = new IllegalArgumentException("framebuffer delete failed");
		final List<String> order = new ArrayList<>();

		ResourceCleanup.runAll(primary,
			() -> { order.add("program"); throw firstCleanup; },
			() -> order.add("texture"),
			() -> { order.add("framebuffer"); throw secondCleanup; });

		assertEquals(List.of("program", "texture", "framebuffer"), order);
		assertEquals(List.of(firstCleanup, secondCleanup), List.of(primary.getSuppressed()));
	}

	@Test
	void throwsTheFirstCleanupFailureAndSuppressesLaterFailuresWhenThereIsNoPrimary() {
		final RuntimeException firstCleanup = new RuntimeException("program delete failed");
		final Error secondCleanup = new AssertionError("compute delete failed");
		final List<String> order = new ArrayList<>();

		final RuntimeException thrown = assertThrows(RuntimeException.class, () -> ResourceCleanup.runAll(null,
			() -> { order.add("program"); throw firstCleanup; },
			() -> order.add("texture"),
			() -> { order.add("compute"); throw secondCleanup; }));

		assertSame(firstCleanup, thrown);
		assertEquals(List.of(secondCleanup), List.of(thrown.getSuppressed()));
		assertEquals(List.of("program", "texture", "compute"), order);
	}
}
