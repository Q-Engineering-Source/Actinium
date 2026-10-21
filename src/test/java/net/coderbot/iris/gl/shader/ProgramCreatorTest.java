package net.coderbot.iris.gl.shader;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgramCreatorTest {
	@Test
	void releasesAProgramWhenNamingTheRestoredProgramFails() {
		final RuntimeException namingFailure = new RuntimeException("debug label failed");
		final List<Integer> deleted = new ArrayList<>();

		final RuntimeException thrown = assertThrows(RuntimeException.class, () -> ProgramCreator.nameLoadedProgram(41,
			program -> { throw namingFailure; }, deleted::add));

		assertSame(namingFailure, thrown);
		assertEquals(List.of(41), deleted);
	}

	@Test
	void preservesNamingFailureWhenProgramDeletionAlsoFails() {
		final RuntimeException namingFailure = new RuntimeException("debug label failed");
		final IllegalStateException deletionFailure = new IllegalStateException("delete failed");

		final RuntimeException thrown = assertThrows(RuntimeException.class, () -> ProgramCreator.nameLoadedProgram(43,
			program -> { throw namingFailure; }, program -> { throw deletionFailure; }));

		assertSame(namingFailure, thrown);
		assertEquals(List.of(deletionFailure), List.of(thrown.getSuppressed()));
	}

	@Test
	void doesNotNameOrDeleteWhenThereIsNoRestoredProgram() {
		final List<String> calls = new ArrayList<>();

		assertEquals(0, ProgramCreator.nameLoadedProgram(0,
			program -> calls.add("name"), program -> calls.add("delete")));

		assertEquals(List.of(), calls);
	}
}
