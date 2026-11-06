package net.coderbot.iris;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs the precompile queue from the render tick instead of the settings screen, so closing the screen or
 * switching away from it does not stop compilation. The screen only starts a run and reads its progress.
 */
public final class PrecompileQueue {
	public record Job(String packName, @Nullable String folder, String displayName) {}

	private static final List<Job> jobs = new ArrayList<>();
	private static int index;
	private static boolean running;
	private static boolean skipNextFrame;
	@Nullable
	private static Job current;

	private PrecompileQueue() {}

	public static void start(List<Job> newJobs) {
		jobs.clear();
		jobs.addAll(newJobs);
		index = 0;
		running = !jobs.isEmpty();
		current = running ? jobs.get(0) : null;
		skipNextFrame = false;
		if (running) {
			Iris.logger.info("Precompile queue started with {} folders", jobs.size());
		}
	}

	public static void cancel() {
		if (!running) return;
		Iris.logger.info("Precompile queue cancelled after {}/{} folders", index, jobs.size());
		running = false;
		current = null;
		skipNextFrame = false;
		Iris.restoreAfterPrecompile();
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
		try {
			if (Iris.isPackFolderCompiled(job.packName(), job.folder())) {
				Iris.forgetPackFolderCompilation(job.packName(), job.folder());
			}
			Iris.compilePackFolder(job.packName(), job.folder());
		} finally {
			index++;
			if (index >= jobs.size()) {
				Iris.logger.info("Precompile queue finished after {} folders", jobs.size());
				running = false;
				current = null;
				Iris.restoreAfterPrecompile();
			} else {
				current = jobs.get(index);
				skipNextFrame = true;
			}
		}
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
