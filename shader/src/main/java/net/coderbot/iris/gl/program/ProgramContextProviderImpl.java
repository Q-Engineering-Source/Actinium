package net.coderbot.iris.gl.program;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.context.GLContextRegistryProvider;

/** Reads share-group ownership from GLSM without making the GL module depend on Iris. */
final class ProgramContextProviderImpl implements ProgramContextProvider {
	@Override
	public long currentShareGroupId() {
		final GLContextIdentity identity = GLStateManager.currentContextIdentity();
		return identity == null ? 0L : identity.shareGroupId();
	}

	@Override
	public boolean hasLiveContexts(long shareGroupId) {
		return !GLContextRegistryProvider.get().liveContextsInShareGroup(shareGroupId).isEmpty();
	}
}
