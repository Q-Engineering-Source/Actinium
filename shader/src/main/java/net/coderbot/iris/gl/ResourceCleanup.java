package net.coderbot.iris.gl;

import java.util.Arrays;

/** Runs every cleanup action while preserving the failure that caused cleanup. */
public final class ResourceCleanup {
	private ResourceCleanup() {}

	public static void runAll(Throwable primaryFailure, Runnable... actions) {
		runAll(primaryFailure, Arrays.asList(actions));
	}

	public static void runAll(Throwable primaryFailure, Iterable<? extends Runnable> actions) {
		Throwable cleanupFailure = null;
		for (Runnable action : actions) {
			try {
				action.run();
			} catch (RuntimeException | Error failure) {
				if (primaryFailure != null) {
					if (failure != primaryFailure) {
						primaryFailure.addSuppressed(failure);
					}
				} else if (cleanupFailure == null) {
					cleanupFailure = failure;
				} else if (failure != cleanupFailure) {
					cleanupFailure.addSuppressed(failure);
				}
			}
		}

		if (primaryFailure == null) {
			if (cleanupFailure instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (cleanupFailure instanceof Error error) {
				throw error;
			}
		}
	}
}
