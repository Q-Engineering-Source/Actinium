package net.coderbot.iris.uniforms;

import java.util.function.LongSupplier;

/**
 * Supplies the tick period of the cloud texture scroll.
 *
 * <p>Iris reports the cloud animation phase through the {@code cloudTime} uniform, and the period has to
 * match the cloud renderer's texture width and cell scale; otherwise the phase wraps at a different tick
 * than the cloud layer and the texture jumps. The renderer host registers the supplier, and the default
 * keeps the vanilla 256 px cloud texture at scale 1 so the uniform is usable before the first cloud
 * frame.</p>
 */
public final class CloudAnimationCycle {
	private static final long VANILLA_CYCLE = 256L * 400L;
	private static volatile LongSupplier ticks = () -> VANILLA_CYCLE;

	private CloudAnimationCycle() {
	}

	public static void setTicks(LongSupplier supplier) {
		ticks = supplier;
	}

	public static long ticks() {
		return ticks.getAsLong();
	}
}
