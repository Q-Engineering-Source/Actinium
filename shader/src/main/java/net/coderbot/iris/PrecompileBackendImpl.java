package net.coderbot.iris;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Drives the production precompile paths in {@link Iris} for {@link PrecompileQueue}. */
final class PrecompileBackendImpl implements PrecompileBackend {
	@Override
	public boolean isFolderCompiled(String packName, @Nullable String folder) {
		return Iris.isPackFolderCompiled(packName, folder);
	}

	@Override
	public void forgetFolder(String packName, @Nullable String folder) {
		Iris.forgetPackFolderCompilation(packName, folder);
	}

	@Override
	public boolean compileFolder(String packName, @Nullable String folder) {
		return Iris.compilePackFolder(packName, folder);
	}

	@Override
	@Nullable
	public List<Iris.PrecompileEntry> folders(String packName) {
		return Iris.packEntries(packName);
	}

	@Override
	public void restore() {
		Iris.restoreAfterPrecompile();
	}

	@Override
	@Nullable
	public String settings(String packName) {
		return Iris.packSettings(packName);
	}
}
