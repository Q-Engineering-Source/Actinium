package net.coderbot.iris.gl;

public abstract class GlResource {
	private final int id;
	private boolean isValid;

	protected GlResource(int id) {
		this.id = id;
		isValid = true;
	}

	/**
	 * Releases this resource exactly once. Destroying it again is a no-op, because a resource name can have
	 * more than one owner-facing path to this call - a shared program is reachable through every pass that
	 * uses it, and a program handed to the retained pool must not be deleted by a second destroy.
	 */
	public final void destroy() {
		if (!isValid) {
			return;
		}

		destroyInternal();
		isValid = false;
	}

	protected abstract void destroyInternal();

	protected void assertValid() {
		if (!isValid) {
			throw new IllegalStateException("Tried to use a destroyed GlResource");
		}
	}

	protected int getGlId() {
		assertValid();

		return id;
	}
}
