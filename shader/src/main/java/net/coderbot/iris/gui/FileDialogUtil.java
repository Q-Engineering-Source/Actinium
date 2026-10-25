package net.coderbot.iris.gui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Class used to make interfacing with {@link TinyFileDialogs} easier and asynchronous.
 */
public final class FileDialogUtil {
	private static final ExecutorService FILE_DIALOG_EXECUTOR = Executors.newSingleThreadExecutor();

	private FileDialogUtil() {}

	public enum DialogType {
		SAVE, OPEN
	}
}
