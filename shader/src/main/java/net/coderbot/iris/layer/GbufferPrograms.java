package net.coderbot.iris.layer;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.gl.shader.ProgramCreator;
import net.coderbot.iris.gl.state.StateUpdateNotifiers;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;

public class GbufferPrograms {
	private static boolean entities;
	private static boolean entityLoop;
	private static boolean blockEntities;
	private static boolean outline;
	private static Runnable phaseChangeListener;
	private static WorldRenderingPhase overridePhase;
	private static Boolean declaredTranslucent;
	private static SpecialCondition currentSpecial;
	private static final ObjectArrayList<WorldRenderingPhase> overridePhaseStack = new ObjectArrayList<>();
	private static final ObjectArrayList<SpecialCondition> specialConditionStack = new ObjectArrayList<>();
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

		@Override
		public void setOverridePhase(WorldRenderingPhase phase) {
			final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
			if (pipeline != null) {
				pipeline.setOverridePhase(phase);
			}
		}

		@Override
		public void setDeclaredTranslucency(Boolean translucent) {
			final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
			if (pipeline != null) {
				pipeline.setDeclaredTranslucency(translucent);
			}
		}

		@Override
		public void setSpecialCondition(SpecialCondition condition) {
			final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
			if (pipeline != null) {
				pipeline.setSpecialCondition(condition);
			}
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

	/** Marks the start of the loop that renders every entity, which re-matches the pass per entity. */
	public static void beginEntityLoop() {
		entityLoop = true;
	}

	public static void endEntityLoop() {
		entityLoop = false;
		setPhase(WorldRenderingPhase.NONE);
	}

	public static boolean isEntityLoopActive() {
		return entityLoop;
	}

	/** Gives the pipeline a chance to re-match the pass between two entities. */
	public static void onEntityRenderBoundary() {
		final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline != null) {
			pipeline.onEntityRenderBoundary();
		}
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
		GLSMCurrentAttribs.glVertexAttrib2s(ProgramCreator.MC_ENTITY, (short)-1, (short)-1);
		GLSMCurrentAttribs.glVertexAttrib2f(ProgramCreator.MC_MID_TEX_COORD, 0.5f, 0.5f);
		GLSMCurrentAttribs.glVertexAttrib4f(ProgramCreator.AT_TANGENT, 1.0f, 0.0f, 0.0f, 1.0f);
		GLSMCurrentAttribs.glVertexAttrib4f(ProgramCreator.AT_MIDBLOCK, 0.0f, 0.0f, 0.0f, 0.0f);
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
		setOverridePhase(PHASE_OPERATIONS, phase);
	}

	static void setOverridePhase(PhaseOperations operations, WorldRenderingPhase phase) {
		recordOverridePhase(phase);
		applyOverridePhase(operations, phase);
	}

	/**
	 * Returns the override phase last applied through this class, or null when none is active.
	 */
	public static WorldRenderingPhase getOverridePhase() {
		return overridePhase;
	}

	public static void pushOverridePhase(WorldRenderingPhase phase) {
		pushOverridePhase(PHASE_OPERATIONS, phase);
	}

	static void pushOverridePhase(PhaseOperations operations, WorldRenderingPhase phase) {
		recordPhaseScope(phase, true);
		applyPushOverridePhase(operations, phase);
	}

	private static void applyPushOverridePhase(PhaseOperations operations, WorldRenderingPhase phase) {
		overridePhaseStack.add(overridePhase);
		applyOverridePhase(operations, phase);
	}

	public static void popOverridePhase() {
		popOverridePhase(PHASE_OPERATIONS);
	}

	static void popOverridePhase(PhaseOperations operations) {
		recordPhaseScope(null, false);
		applyPopOverridePhase(operations);
	}

	private static void applyPopOverridePhase(PhaseOperations operations) {
		if (overridePhaseStack.isEmpty()) {
			throw new IllegalStateException("GbufferPrograms override phase stack is empty");
		}
		applyOverridePhase(operations, overridePhaseStack.pop());
	}

	private static void applyOverridePhase(PhaseOperations operations, WorldRenderingPhase phase) {
		overridePhase = phase;
		operations.setOverridePhase(phase);
	}

	/**
	 * Declares the translucency of the following draws, returning the previous declaration.
	 */
	public static Boolean beginTranslucencyDeclaration(Boolean translucent) {
		return beginTranslucencyDeclaration(PHASE_OPERATIONS, translucent);
	}

	static Boolean beginTranslucencyDeclaration(PhaseOperations operations, Boolean translucent) {
		recordTranslucencyScope(translucent, true);
		final Boolean previous = declaredTranslucent;
		applyDeclaredTranslucency(operations, translucent);
		return previous;
	}

	public static void endTranslucencyDeclaration(Boolean previous) {
		endTranslucencyDeclaration(PHASE_OPERATIONS, previous);
	}

	static void endTranslucencyDeclaration(PhaseOperations operations, Boolean previous) {
		recordTranslucencyScope(null, false);
		applyDeclaredTranslucency(operations, previous);
	}

	public static void setTranslucencyDeclaration(Boolean translucent) {
		setTranslucencyDeclaration(PHASE_OPERATIONS, translucent);
	}

	static void setTranslucencyDeclaration(PhaseOperations operations, Boolean translucent) {
		recordTranslucency(translucent);
		applyDeclaredTranslucency(operations, translucent);
	}

	/**
	 * Returns the active translucency declaration, or null when the live blend state decides.
	 */
	public static Boolean getDeclaredTranslucency() {
		return declaredTranslucent;
	}

	private static void applyDeclaredTranslucency(PhaseOperations operations, Boolean translucent) {
		declaredTranslucent = translucent;
		operations.setDeclaredTranslucency(translucent);
	}

	/**
	 * Returns the active special render condition, or null when no override is active.
	 */
	public static SpecialCondition getSpecialCondition() {
		return currentSpecial;
	}

	/**
	 * Pushes a special render condition, restoring the previous one on teardown.
	 */
	public static void setupSpecialRenderCondition(SpecialCondition override) {
		setupSpecialRenderCondition(PHASE_OPERATIONS, override);
	}

	static void setupSpecialRenderCondition(PhaseOperations operations, SpecialCondition override) {
		recordSpecialCondition(override, true);
		specialConditionStack.add(currentSpecial);
		applySpecialCondition(operations, override);
	}

	public static void teardownSpecialRenderCondition() {
		teardownSpecialRenderCondition(PHASE_OPERATIONS);
	}

	static void teardownSpecialRenderCondition(PhaseOperations operations) {
		recordSpecialCondition(null, false);
		if (specialConditionStack.isEmpty()) {
			throw new IllegalStateException("GbufferPrograms special condition stack is empty");
		}
		applySpecialCondition(operations, specialConditionStack.pop());
	}

	private static void applySpecialCondition(PhaseOperations operations, SpecialCondition condition) {
		currentSpecial = condition;
		operations.setSpecialCondition(condition);
	}

	/**
	 * Moves an active block-entity draw onto the entity pass and returns whether it did.
	 */
	public static boolean beginNestedEntityPhase() {
		return beginNestedEntityPhase(PHASE_OPERATIONS);
	}

	static boolean beginNestedEntityPhase(PhaseOperations operations) {
		recordNestedEntityScope(true);
		if (operations.getPipelinePhase() != WorldRenderingPhase.BLOCK_ENTITIES) {
			return false;
		}
		applyPushOverridePhase(operations, WorldRenderingPhase.ENTITIES);
		return true;
	}

	public static void endNestedEntityPhase(boolean pushed) {
		endNestedEntityPhase(PHASE_OPERATIONS, pushed);
	}

	static void endNestedEntityPhase(PhaseOperations operations, boolean pushed) {
		recordNestedEntityScope(false);
		if (pushed) {
			applyPopOverridePhase(operations);
		}
	}

	private static void recordOverridePhase(WorldRenderingPhase phase) {
		final CapturedRenderingState.StateRecorder recorder = CapturedRenderingState.INSTANCE.getStateRecorder();
		if (recorder != null) {
			recorder.recordOverridePhase(phase);
		}
	}

	private static void recordPhaseScope(WorldRenderingPhase phase, boolean begin) {
		final CapturedRenderingState.StateRecorder recorder = CapturedRenderingState.INSTANCE.getStateRecorder();
		if (recorder != null) {
			recorder.recordPhaseScope(phase, begin);
		}
	}

	private static void recordNestedEntityScope(boolean begin) {
		final CapturedRenderingState.StateRecorder recorder = CapturedRenderingState.INSTANCE.getStateRecorder();
		if (recorder != null) {
			recorder.recordNestedEntityScope(begin);
		}
	}

	private static void recordTranslucency(Boolean translucent) {
		final CapturedRenderingState.StateRecorder recorder = CapturedRenderingState.INSTANCE.getStateRecorder();
		if (recorder != null) {
			recorder.recordTranslucency(translucent);
		}
	}

	private static void recordTranslucencyScope(Boolean translucent, boolean begin) {
		final CapturedRenderingState.StateRecorder recorder = CapturedRenderingState.INSTANCE.getStateRecorder();
		if (recorder != null) {
			recorder.recordTranslucencyScope(translucent, begin);
		}
	}

	private static void recordSpecialCondition(SpecialCondition condition, boolean begin) {
		final CapturedRenderingState.StateRecorder recorder = CapturedRenderingState.INSTANCE.getStateRecorder();
		if (recorder != null) {
			recorder.recordSpecialCondition(condition, begin);
		}
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

		/** Applies an override phase to the pipeline, or clears it when null. */
		void setOverridePhase(WorldRenderingPhase phase);

		/** Declares the translucency of the following draws, or hands the decision back when null. */
		void setDeclaredTranslucency(Boolean translucent);

		/** Applies a special render condition to the pipeline, or clears it when null. */
		void setSpecialCondition(SpecialCondition condition);
	}
}
