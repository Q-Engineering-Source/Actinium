package net.coderbot.iris.uniforms;

import net.coderbot.iris.debug.IrisDebugOptions;
import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.PER_TICK;

import java.util.Objects;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

public final class WorldTimeUniforms {
	private static long cachedWorldClock;
	private static boolean hasWorldClockSnapshot;

	private WorldTimeUniforms() {
	}

	/**
	 * Makes world time uniforms available to the given program
	 */
	public static void addWorldTimeUniforms(UniformHolder uniforms) {
		uniforms
			.uniform1i(PER_TICK, "worldTime", WorldTimeUniforms::getWorldDayTime)
			.uniform1i(PER_TICK, "worldDay", WorldTimeUniforms::getWorldDay)
			.uniform1i(PER_TICK, "moonPhase", () -> getWorld().getMoonPhase());
	}

	/**
	 * Captures one world clock value for the current frame, so uniforms that need
	 * the daytime and day count stay consistent even when a tick or time packet
	 * lands between two supplier calls.
	 */
	public static void snapshot() {
		final WorldClient world = getWorld();
		cachedWorldClock = IrisDebugOptions.useTotalWorldTime()
			? world.getTotalWorldTime()
			: world.getWorldTime();
		hasWorldClockSnapshot = true;
	}

	public static long getWorldClock() {
		if (!hasWorldClockSnapshot) {
			snapshot();
		}
		return cachedWorldClock;
	}

	static int getWorldDayTime() {
		return getWorldDayTime(getWorldClock());
	}

	static int getWorldDay() {
		return getWorldDay(getWorldClock());
	}

	static int getWorldDayTime(long worldClock) {
		return (int) (worldClock % 24000L);
	}

	static int getWorldDay(long worldClock) {
		return (int) (worldClock / 24000L);
	}

	static float getContinuousWorldTime() {
		return getContinuousWorldTime(getWorldClock());
	}

	static float getContinuousWorldTime(long worldClock) {
		return getWorldDayTime(worldClock) + (getWorldDay(worldClock) % 100) * 24000.0F;
	}

	private static WorldClient getWorld() {
		return Objects.requireNonNull(Minecraft.getMinecraft().world);
	}
}
