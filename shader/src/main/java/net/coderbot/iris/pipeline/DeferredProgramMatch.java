package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.hooks.BatchStateGuard;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.PendingProgramSelection;
import com.gtnewhorizons.angelica.glsm.recording.CommandRecorder;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * Coalesces program selection while another renderer owns the GL program or a display list is
 * being compiled.
 *
 * <p>A pass change requested mid-batch must not bind a program for every intermediate state:
 * {@code PassOverride.apply} changes the special condition, the translucency declaration and the
 * override phase one after another, and only the final combination selects a program. A pass
 * change requested while a display list is compiled must neither bind nor record anything,
 * because the compiled list replays the state commands it captured. Program transitions of a
 * recorded list are executed with recording paused so the switch itself never enters the list.</p>
 */
final class DeferredProgramMatch {
	/** Display-list recorder operations a program transition has to stay out of. */
	interface DisplayListAccess {
		/** Returns whether a list is being compiled, which must not execute or record a switch. */
		boolean isCompiling();

		/** Returns whether a list is recording, including {@code GL_COMPILE_AND_EXECUTE}. */
		boolean isRecording();

		/** Detaches the active recorder, returning the token {@link #resume} expects. */
		@Nullable
		CommandRecorder pause();

		/** Reattaches a recorder detached by {@link #pause}. */
		void resume(@Nullable CommandRecorder recorder);
	}

	private static final DisplayListAccess DISPLAY_LISTS = new DisplayListAccess() {
		@Override
		public boolean isCompiling() {
			return DisplayListManager.getRecordMode() == DisplayListManager.RecordMode.COMPILE;
		}

		@Override
		public boolean isRecording() {
			return DisplayListManager.isRecording();
		}

		@Override
		public CommandRecorder pause() {
			return DisplayListManager.pauseRecording();
		}

		@Override
		public void resume(CommandRecorder recorder) {
			DisplayListManager.resumeRecording(recorder);
		}
	};

	private final DisplayListAccess displayLists;
	private final PendingProgramSelection owner;
	private final BooleanSupplier deferred;
	private final Runnable match;
	private boolean pending;

	DeferredProgramMatch(DisplayListAccess displayLists, PendingProgramSelection owner, BooleanSupplier deferred, Runnable match) {
		this.displayLists = displayLists;
		this.owner = owner;
		this.deferred = deferred;
		this.match = match;
	}

	/** Creates the selection used by the world pipelines, tied to the live display-list recorder. */
	static DeferredProgramMatch forDisplayLists(PendingProgramSelection owner, BooleanSupplier deferred, Runnable match) {
		return new DeferredProgramMatch(DISPLAY_LISTS, owner, deferred, match);
	}

	/** Runs a match immediately, unless a compiled display list must not bind a program. */
	void matchNow() {
		if (this.displayLists.isCompiling()) {
			return;
		}

		runMatch();
	}

	/**
	 * Requests a match now, or publishes the request in the host slot while a foreign renderer or a
	 * batch owns the program. The slot carries this pipeline's identity so only it resolves the
	 * request, and {@code GLStateManager.endForeignDraw} or the batch end resolve it.
	 */
	void request() {
		if (this.displayLists.isCompiling()) {
			return;
		}

		if (this.deferred.getAsBoolean()) {
			this.pending = true;
			GLSMHooks.pendingProgramSelection = this.owner;
			return;
		}

		runMatch();
	}

	/** Runs the pending match once the foreign renderer or batch that deferred it has finished. */
	void resolve() {
		if (!this.pending || this.deferred.getAsBoolean()) {
			return;
		}

		runMatch();
	}

	/** Drops the pending request and its slot, e.g. when a mod takes over the GL program. */
	void clear() {
		this.pending = false;
		clearSlot();
	}

	/** Returns whether a batch deferred a match that has not run yet. */
	boolean isPending() {
		return this.pending;
	}

	/**
	 * Runs a program transition without appending it to the list being recorded.
	 *
	 * <p>The transition binds a program, which the batch state guard would otherwise answer by
	 * draining the batch that is being built. Suspending the guard keeps the batch intact for the
	 * rest of the pass.</p>
	 */
	void runUnrecorded(Runnable transition) {
		if (this.displayLists.isCompiling()) {
			return;
		}

		BatchStateGuard.suspend();
		try {
			if (!this.displayLists.isRecording()) {
				transition.run();
				return;
			}

			final CommandRecorder recorder = this.displayLists.pause();
			try {
				transition.run();
			} finally {
				this.displayLists.resume(recorder);
			}
		} finally {
			BatchStateGuard.resume();
		}
	}

	private void runMatch() {
		this.pending = false;
		clearSlot();
		this.match.run();
	}

	private void clearSlot() {
		if (GLSMHooks.pendingProgramSelection == this.owner) {
			GLSMHooks.pendingProgramSelection = null;
		}
	}
}
