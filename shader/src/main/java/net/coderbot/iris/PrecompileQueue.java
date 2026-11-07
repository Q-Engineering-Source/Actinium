package net.coderbot.iris;

import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs the precompile queue from the render tick instead of the settings screen, so closing the screen or
 * switching away from it does not stop compilation. The screen only starts a run and reads its progress.
 * Every selected folder compiles at most once per rendered frame, with an idle frame between driver calls.
 */
public final class PrecompileQueue {
	public record Job(String packName, @Nullable String folder, String displayName) {}

	private static PrecompileBackend backend = new PrecompileBackendImpl();

	private static final List<Job> jobs = new ArrayList<>();
	private static int index;
	private static boolean running;
	private static boolean skipNextFrame;
	private static boolean anyJobFailed;
	@Nullable
	private static String fullCompilePack;
	@Nullable
	private static String fullCompileSettings;
	@Nullable
	private static Job current;

	private PrecompileQueue() {}

	/** Replaces the shader pack operations the queue drives. Tests use it to run without a pack or GL context. */
	static void useBackend(PrecompileBackend replacement) {
		backend = Objects.requireNonNull(replacement);
	}

	static void useDefaultBackend() {
		backend = new PrecompileBackendImpl();
	}

	/** Queues only the folders the player selected. Starting a run never happens as a cache-setting side effect. */
	public static void start(List<Job> newJobs) {
		stop();
		jobs.addAll(newJobs);
		index = 0;
		running = !jobs.isEmpty();
		current = running ? jobs.get(0) : null;
		if (running) {
			beginSelectedFullCompile(newJobs);
			Iris.logger.info("Precompile queue started with {} folders", jobs.size());
		}
	}

	/** Starts full-cache tracking only when one selected run covers every folder of one pack. */
	private static void beginSelectedFullCompile(List<Job> selected) {
		final String packName = selected.get(0).packName();
		final String settings = backend.settings(packName);
		final List<Iris.PrecompileEntry> available = backend.folders(packName);
		if (settings == null || available == null || available.size() != selected.size()) return;
		for (Job job : selected) {
			if (!packName.equals(job.packName()) || !containsFolder(available, job)) return;
		}

		fullCompilePack = packName;
		fullCompileSettings = settings;
		ProgramBinaryCache.usePackForPrecompile(packName);
		ProgramBinaryCache.beginFullCompile();
	}

	private static boolean containsFolder(List<Iris.PrecompileEntry> available, Job selected) {
		for (Iris.PrecompileEntry entry : available) {
			if (Objects.equals(entry.folder(), selected.folder())) return true;
		}
		return false;
	}

	/** Stops a run the player asked for and restores the active world pipeline afterwards. */
	public static void cancel() {
		if (!stop()) return;
		backend.restore();
	}

	/** Stops the queue without restoring the pipeline, for a reload that is about to replace the pack. */
	public static void abandon() {
		stop();
	}

	private static boolean stop() {
		final boolean wasRunning = running;
		if (wasRunning) {
			Iris.logger.info("Precompile queue stopped after {}/{} folders", index, jobs.size());
		}
		running = false;
		current = null;
		skipNextFrame = false;
		jobs.clear();
		index = 0;
		anyJobFailed = false;
		endFullCompile(false);
		return wasRunning;
	}

	/** Draws the current job before compiling it and leaves one rendered frame between driver calls. */
	public static void tick() {
		if (!running) return;
		if (skipNextFrame) {
			skipNextFrame = false;
			return;
		}

		final Job job = jobs.get(index);
		current = job;
		boolean compiled = false;
		try {
			if (backend.isFolderCompiled(job.packName(), job.folder())) {
				backend.forgetFolder(job.packName(), job.folder());
			}
			compiled = backend.compileFolder(job.packName(), job.folder());
		} finally {
			if (!compiled) anyJobFailed = true;
			index++;
			if (index >= jobs.size()) {
				Iris.logger.info("Precompile queue finished after {} folders", jobs.size());
				running = false;
				current = null;
				endFullCompile(!anyJobFailed);
				backend.restore();
			} else {
				current = jobs.get(index);
				skipNextFrame = true;
			}
		}
	}

	/** Writes a full marker only after every selected folder compiled successfully. */
	private static void endFullCompile(boolean finished) {
		final String packName = fullCompilePack;
		if (packName == null) return;
		final String settings = fullCompileSettings;
		fullCompilePack = null;
		fullCompileSettings = null;
		ProgramBinaryCache.usePackForPrecompile(packName);
		ProgramBinaryCache.finishFullCompile(finished ? settings : null);
	}

	public static boolean isRunning() {
		return running;
	}

	public static int done() {
		return index;
	}

	public static int total() {
		return jobs.size();
	}

	public static float overallProgress() {
		return jobs.isEmpty() ? 0.0F : (float) (index + (running ? 1 : 0)) / jobs.size();
	}

	public static float packProgress(String packName) {
		int packTotal = 0;
		int packFilled = 0;
		for (int i = 0; i < jobs.size(); i++) {
			if (!jobs.get(i).packName().equals(packName)) continue;
			packTotal++;
			if (isFilled(i)) packFilled++;
		}
		return packTotal == 0 ? 0.0F : (float) packFilled / packTotal;
	}

	public static float folderProgress(String packName, @Nullable String folder) {
		for (int i = 0; i < jobs.size(); i++) {
			final Job job = jobs.get(i);
			if (job.packName().equals(packName) && Objects.equals(job.folder(), folder)) {
				return isFilled(i) ? 1.0F : 0.0F;
			}
		}
		return 0.0F;
	}

	private static boolean isFilled(int jobIndex) {
		return jobIndex < index || running && jobIndex == index;
	}

	@Nullable
	public static Job currentJob() {
		return current;
	}
}
