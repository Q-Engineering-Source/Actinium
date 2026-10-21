package net.coderbot.iris.gl.program;

/** Supplies the GL share-group ownership needed by the linked-program cache. */
interface ProgramContextProvider {
	long currentShareGroupId();

	boolean hasLiveContexts(long shareGroupId);
}
