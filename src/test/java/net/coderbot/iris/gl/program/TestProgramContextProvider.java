package net.coderbot.iris.gl.program;

import java.util.HashSet;
import java.util.Set;

final class TestProgramContextProvider implements ProgramContextProvider {
	private long currentShareGroupId = 1L;
	private final Set<Long> liveShareGroups = new HashSet<>(Set.of(1L));

	@Override
	public long currentShareGroupId() {
		return currentShareGroupId;
	}

	@Override
	public boolean hasLiveContexts(long shareGroupId) {
		return liveShareGroups.contains(shareGroupId);
	}

	void makeCurrent(long shareGroupId) {
		currentShareGroupId = shareGroupId;
		liveShareGroups.add(shareGroupId);
	}

	void retireShareGroup(long shareGroupId) {
		liveShareGroups.remove(shareGroupId);
	}
}
