package net.coderbot.iris.layer;

import net.coderbot.iris.Iris;
import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.coderbot.iris.gl.shader.ProgramCreator;
import net.coderbot.iris.gl.state.StateUpdateNotifiers;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;

public class GbufferPrograms {
	private static boolean entities;
	private static boolean blockEntities;
	private static boolean outline;
	private static Runnable phaseChangeListener;
	private static final PhaseOperations PHASE_OPERATIONS = new PhaseOperations() {
		@Override
		public OwnedPhase getOwnedPhase() {
			return getOwnedProgramPhase();
		}

		@Override
		public WorldRenderingPhase getPipelinePhase() {
			return getCurrentPhase();
		}

		@Override
		public void beginEntities() {
			GbufferPrograms.beginEntities();
		}

		@Override
		public void endEntities() {
			GbufferPrograms.endEntities();
		}

		@Override
		public void beginBlockEntities() {
			GbufferPrograms.beginBlockEntities();
		}

		@Override
		public void endBlockEntities() {
			GbufferPrograms.endBlockEntities();
		}
	};
	
	static {
		StateUpdateNotifiers.phaseChangeNotifier = listener -> phaseChangeListener = listener;
	}
	
	private static void checkReentrancy() {
		if (entities || blockEntities || outline) {
			throw new IllegalStateException("GbufferPrograms in weird state, tried to call begin function when entities = "
				+ entities + ", blockEntities = " + blockEntities + ", outline = " + outline);
		}
	}

	public static void beginEntities() {
		checkReentrancy();
		setPhase(WorldRenderingPhase.ENTITIES);
		setBlockEntityDefaults();
		entities = true;
	}

	public static void endEntities() {
		if (!entities) {
			throw new IllegalStateException("GbufferPrograms in weird state, tried to call endEntities when entities = false");
		}

		setPhase(WorldRenderingPhase.NONE);
		entities = false;
	}

	public static void beginOutline() {
		checkReentrancy();
		setPhase(WorldRenderingPhase.OUTLINE);
		outline = true;
	}

	public static void endOutline() {
		if (!outline) {
			throw new IllegalStateException("GbufferPrograms in weird state, tried to call endOutline when outline = false");
		}

		setPhase(WorldRenderingPhase.NONE);
		outline = false;
	}

	public static void beginBlockEntities() {
		checkReentrancy();
		setPhase(WorldRenderingPhase.BLOCK_ENTITIES);
		setBlockEntityDefaults();
		blockEntities = true;
	}

	public static void endBlockEntities() {
		if (!blockEntities) {
			throw new IllegalStateException("GbufferPrograms in weird state, tried to call endBlockEntities when blockEntities = false");
		}

		setPhase(WorldRenderingPhase.NONE);
		blockEntities = false;
	}

	/**
	 * Enters the entity phase when the caller is responsible for selecting a render phase.
	 * A block-entity phase owned by an outer renderer is suspended and restored when the scope closes.
	 */
	public static EntityPhase enterEntityPhase() {
		return enterEntityPhase(PHASE_OPERATIONS);
	}

	static EntityPhase enterEntityPhase(PhaseOperations operations) {
		OwnedPhase ownedPhase = operations.getOwnedPhase();
		if (ownedPhase == OwnedPhase.ENTITIES || ownedPhase == OwnedPhase.OUTLINE) {
			return EntityPhase.unchanged();
		}

		if (ownedPhase == OwnedPhase.BLOCK_ENTITIES) {
			operations.endBlockEntities();
			try {
				operations.beginEntities();
			} catch (RuntimeException exception) {
				restoreBlockEntitiesAfterFailedBegin(operations, exception);
				throw exception;
			} catch (Error error) {
				restoreBlockEntitiesAfterFailedBegin(operations, error);
				throw error;
			}
			return EntityPhase.changed(operations, true);
		}

		if (operations.getPipelinePhase() != WorldRenderingPhase.NONE) {
			return EntityPhase.unchanged();
		}

		operations.beginEntities();
		return EntityPhase.changed(operations, false);
	}

	private static OwnedPhase getOwnedProgramPhase() {
		int activePhaseCount = (entities ? 1 : 0) + (blockEntities ? 1 : 0) + (outline ? 1 : 0);
		if (activePhaseCount > 1) {
			throw new IllegalStateException("GbufferPrograms owns more than one render phase");
		}
		if (entities) {
			return OwnedPhase.ENTITIES;
		}
		if (blockEntities) {
			return OwnedPhase.BLOCK_ENTITIES;
		}
		if (outline) {
			return OwnedPhase.OUTLINE;
		}
		return OwnedPhase.NONE;
	}

	private static void restoreBlockEntitiesAfterFailedBegin(PhaseOperations operations, Throwable failure) {
		try {
			operations.beginBlockEntities();
		} catch (RuntimeException | Error restorationFailure) {
			failure.addSuppressed(restorationFailure);
		}
	}

	public static void setBlockEntityDefaults() {
		GLStateManager.glVertexAttrib2s(ProgramCreator.MC_ENTITY, (short)-1, (short)-1);
		GLStateManager.glVertexAttrib2f(ProgramCreator.MC_MID_TEX_COORD, 0.5f, 0.5f);
		GLStateManager.glVertexAttrib4f(ProgramCreator.AT_TANGENT, 1.0f, 0.0f, 0.0f, 1.0f);
		GLStateManager.glVertexAttrib4f(ProgramCreator.AT_MIDBLOCK, 0.0f, 0.0f, 0.0f, 0.0f);
	}

	public static WorldRenderingPhase getCurrentPhase() {
		final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline != null) {
			return pipeline.getPhase();
		} else {
			return WorldRenderingPhase.NONE;
		}
	}

	private static void setPhase(WorldRenderingPhase phase) {
		final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline != null) {
			pipeline.setPhase(phase);
		}
	}

	public static void setOverridePhase(WorldRenderingPhase phase) {
		final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline != null) {
			pipeline.setOverridePhase(phase);
		}
	}

	public static void setupSpecialRenderCondition(SpecialCondition override) {
		Iris.getPipelineManager().getPipeline().ifPresent(p -> p.setSpecialCondition(override));
	}

	public static void teardownSpecialRenderCondition() {
		Iris.getPipelineManager().getPipeline().ifPresent(p -> p.setSpecialCondition(null));
	}
	
	public static void runPhaseChangeNotifier() {
		if (phaseChangeListener != null) {
			phaseChangeListener.run();
		}
	}
	
	public static void init() {
		// Empty initializer to run static
	}

	/**
	 * Owns a temporary entity phase and restores the phase active before entry.
	 */
	public static final class EntityPhase implements AutoCloseable {
		private final PhaseOperations operations;
		private final boolean changedPhase;
		private final boolean restoreBlockEntities;
		private boolean closed;

		private EntityPhase(PhaseOperations operations, boolean changedPhase, boolean restoreBlockEntities) {
			this.operations = operations;
			this.changedPhase = changedPhase;
			this.restoreBlockEntities = restoreBlockEntities;
		}

		private static EntityPhase unchanged() {
			return new EntityPhase(null, false, false);
		}

		private static EntityPhase changed(PhaseOperations operations, boolean restoreBlockEntities) {
			return new EntityPhase(operations, true, restoreBlockEntities);
		}

		/**
		 * Returns whether entering this scope changed the active G-buffer phase.
		 */
		public boolean changedPhase() {
			return this.changedPhase;
		}

		@Override
		public void close() {
			if (!this.changedPhase) {
				return;
			}
			if (this.closed) {
				throw new IllegalStateException("Entity phase scope is already closed");
			}
			this.closed = true;

			try {
				this.operations.endEntities();
			} catch (RuntimeException exception) {
				this.restoreAfterFailedEnd(exception);
				throw exception;
			} catch (Error error) {
				this.restoreAfterFailedEnd(error);
				throw error;
			}

			if (this.restoreBlockEntities) {
				this.operations.beginBlockEntities();
			}
		}

		private void restoreAfterFailedEnd(Throwable failure) {
			if (this.restoreBlockEntities) {
				restoreBlockEntitiesAfterFailedBegin(this.operations, failure);
			}
		}
	}

	enum OwnedPhase {
		NONE,
		ENTITIES,
		BLOCK_ENTITIES,
		OUTLINE
	}

	/**
	 * Supplies render-phase operations so entity nesting can be verified without an OpenGL context.
	 */
	interface PhaseOperations {
		/** Returns the phase currently owned by {@link GbufferPrograms}. */
		OwnedPhase getOwnedPhase();

		/** Returns the phase exposed by the active world pipeline. */
		WorldRenderingPhase getPipelinePhase();

		/** Starts the entity phase. */
		void beginEntities();

		/** Ends the entity phase. */
		void endEntities();

		/** Starts the block-entity phase. */
		void beginBlockEntities();

		/** Ends the block-entity phase. */
		void endBlockEntities();
	}
}
