package net.coderbot.iris.gl.uniform;

import net.coderbot.iris.gl.state.ValueUpdateNotifier;

/**
 * One uniform of a program together with the value it is fed from.
 *
 * <p>{@link #update()} writes the current value and, when the uniform is push-notified, registers the
 * update on a {@link ValueUpdateNotifier}. The registration is one stable listener per uniform, so repeated
 * updates cannot duplicate it and {@link #removeListener()} can take exactly that registration back without
 * touching the listeners other programs registered on the same notifier.</p>
 */
public abstract class Uniform {
	protected final int location;
	protected final ValueUpdateNotifier notifier;
	/** The stable listener of this uniform's push notification; one object for the uniform's lifetime. */
	private final Runnable updateListener = this::updateValue;

	Uniform(int location) {
		this(location, null);
	}

	Uniform(int location, ValueUpdateNotifier notifier) {
		this.location = location;
		this.notifier = notifier;
	}

	/** Writes the current value and registers this uniform for push notifications when it has a notifier. */
	public final void update() {
		updateValue();

		if (notifier != null) {
			notifier.setListener(updateListener);
		}
	}

	/**
	 * Takes this uniform's push notification back from its notifier, leaving other programs' registrations
	 * alone. Safe to call when the uniform never registered.
	 */
	public final void removeListener() {
		if (notifier != null) {
			notifier.removeListener(updateListener);
		}
	}

	/** Writes the current value to this uniform's location, unless it is unchanged since the last write. */
	protected abstract void updateValue();

	public final int getLocation() {
		return location;
	}

	public final ValueUpdateNotifier getNotifier() {
		return notifier;
	}
}
