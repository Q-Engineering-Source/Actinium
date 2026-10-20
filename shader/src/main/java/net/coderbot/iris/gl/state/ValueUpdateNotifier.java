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

	/**
	 * Removes exactly the registration of {@code listener}, leaving the registrations other programs made on
	 * the same notifier intact. Calling this for a listener that never registered is a no-op.
	 *
	 * <p>Programs share notifiers - every program with a fog or entity-colour uniform registers on the same
	 * one - and a wrapper can be destroyed while another program is bound. Clearing the whole notifier, which
	 * {@link #setListener(Runnable) setListener(null)} does, would then silence the other programs and let a
	 * later state change write into the wrong program, so a destroyed wrapper takes back only its own
	 * registration.</p>
	 *
	 * <p>The default removes nothing: a notifier that keeps no registry cannot tell one registration from
	 * another, and {@link #NONE} is such a notifier. The multi-program implementation used for the shader
	 * state overrides this.</p>
	 */
	default void removeListener(Runnable listener) {
	}
}
