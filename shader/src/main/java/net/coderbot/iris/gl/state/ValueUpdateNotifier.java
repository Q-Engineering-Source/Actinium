package net.coderbot.iris.gl.state;

/**
 * A notifier that can trigger uniform updates when values change.
 */
public interface ValueUpdateNotifier {
	/** No-op notifier for uniforms that are polled per-frame rather than push-notified */
	ValueUpdateNotifier NONE = listener -> {};

	/**
	 * Registers a stable listener for the active program. A null listener clears all registrations
	 * when that program is deactivated. Re-registering the same listener must not duplicate it.
	 */
	void setListener(Runnable listener);
}
