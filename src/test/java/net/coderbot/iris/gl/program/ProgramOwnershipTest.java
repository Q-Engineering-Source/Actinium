package net.coderbot.iris.gl.program;

import net.coderbot.iris.gl.GlResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgramOwnershipTest {
	private TestProgramContextProvider contextProvider;
	private List<Integer> deletedPrograms;

	@BeforeEach
	void configureOwnershipTest() {
		contextProvider = new TestProgramContextProvider();
		deletedPrograms = new ArrayList<>();
		RetainedPrograms.setContextProviderForTesting(contextProvider);
		RetainedPrograms.setDeleterForTesting(deletedPrograms::add);
		RetainedPrograms.discardAll();
		RetainedPrograms.useConfiguration(null);
		deletedPrograms.clear();
	}

	@AfterEach
	void resetOwnershipTest() {
		RetainedPrograms.useConfiguration(null);
		RetainedPrograms.discardAll();
		RetainedPrograms.setDeleterForTesting(null);
		RetainedPrograms.setContextProviderForTesting(null);
	}

	@Test
	void aFailureBeforeWrapperAssemblyDeletesTheProgram() {
		assertThrows(IllegalStateException.class, () -> {
			try (ProgramOwnership ownership = new ProgramOwnership(17, 1L)) {
				throw new IllegalStateException("sampler setup failed");
			}
		});

		assertEquals(List.of(17), deletedPrograms);
	}

	@Test
	void aCompletedButUncommittedWrapperReturnsItsProgramToTheWrapperOwner() {
		final RecordingResource wrapper = new RecordingResource(23);

		assertThrows(IllegalStateException.class, () -> {
			try (ProgramOwnership ownership = new ProgramOwnership(23, 1L)) {
				ownership.setWrapper(wrapper);
				throw new IllegalStateException("pass wiring failed after wrapper assembly");
			}
		});

		assertEquals(1, wrapper.destroyCalls);
		assertEquals(List.of(), deletedPrograms);
	}

	@Test
	void aCommittedWrapperRemainsOwnedByItsCaller() {
		final RecordingResource wrapper = new RecordingResource(31);

		try (ProgramOwnership ownership = new ProgramOwnership(31, 1L)) {
			ownership.setWrapper(wrapper);
			ownership.transfer();
		}

		assertEquals(0, wrapper.destroyCalls);
		assertEquals(List.of(), deletedPrograms);
	}

	@Test
	void anUnbuiltProgramClosedFromAnotherShareGroupIsQueuedForItsOwner() {
		contextProvider.makeCurrent(2L);
		assertThrows(IllegalStateException.class, () -> {
			try (ProgramOwnership ownership = new ProgramOwnership(37, 1L)) {
				throw new IllegalStateException("builder closed on another current share group");
			}
		});
		assertEquals(List.of(), deletedPrograms);

		contextProvider.makeCurrent(1L);
		assertEquals(0, RetainedPrograms.take(new RetainedPrograms.Sources("v", null, null, null, "f", null)));
		assertEquals(List.of(37), deletedPrograms);
	}

	private static final class RecordingResource extends GlResource {
		private int destroyCalls;

		private RecordingResource(int id) {
			super(id);
		}

		@Override
		protected void destroyInternal() {
			destroyCalls++;
		}
	}
}
