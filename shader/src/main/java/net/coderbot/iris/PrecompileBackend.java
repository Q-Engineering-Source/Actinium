package net.coderbot.iris;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The shader pack operations a precompile run performs, separated from {@link Iris} so a run can be driven
 * without a loaded pack or a GL context. Production uses {@link PrecompileBackendImpl}.
 */
interface PrecompileBackend {
	boolean isFolderCompiled(String packName, @Nullable String folder);

	void forgetFolder(String packName, @Nullable String folder);

	/** @return true when the folder compiled and its marker was written */
	boolean compileFolder(String packName, @Nullable String folder);

	/** @return the folders of the pack, or null when the pack could not be read */
	@Nullable
	List<Iris.PrecompileEntry> folders(String packName);

	/** @return the pack fingerprint, or null when the pack could not be read */
	@Nullable
	String settings(String packName);

	/** Rebuilds the active world pipeline after throwaway precompile pipelines changed render settings. */
	void restore();
}
