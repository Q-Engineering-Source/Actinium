package net.coderbot.iris.uniforms;

import com.gtnewhorizons.angelica.compat.mojang.Camera;
import com.gtnewhorizons.angelica.compat.mojang.GameModeUtil;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.gl.uniform.UniformUpdateFrequency;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.effect.EntityLightningBolt;
import org.joml.Math;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector4f;

import java.util.List;
import java.util.Objects;

public class IrisExclusiveUniforms {
	// Reusable vectors to avoid allocations every frame
	private static final Vector3d eyePositionCache = new Vector3d();
	private static final Vector3d relativeEyePositionCache = new Vector3d();
	private static final Vector4f lightningBoltPositionCache = new Vector4f();
	private static final Vector4f ZERO_VECTOR_4f = new Vector4f(0, 0, 0, 0);

	/** Supplies the cloud texture width and scale of the renderer, the inputs of the cloud scroll period. */
	public interface CloudScrollPeriod {
		/** Width in texels of the cloud texture sampled by the pack. */
		int textureWidth();

		/** Horizontal scale of one cloud cell, used to convert its texture period into world ticks. */
		int scale();
	}

	private static final CloudScrollPeriod DEFAULT_CLOUD_SCROLL_PERIOD = new CloudScrollPeriod() {
		@Override
		public int textureWidth() {
			return 256;
		}

		@Override
		public int scale() {
			return 1;
		}
	};

	private static volatile CloudScrollPeriod cloudScrollPeriod = DEFAULT_CLOUD_SCROLL_PERIOD;

	/**
	 * Registers the cloud scroll period of the renderer. The renderer calls this because this module cannot
	 * reference it; until then the vanilla texture width and scale are used.
	 *
	 * @param period cloud texture width and scale of the renderer
	 * @throws NullPointerException     if {@code period} is {@code null}
	 * @throws IllegalArgumentException if the width or the scale is not positive, which would make the cycle
	 *                                  zero and the wrap undefined
	 */
	public static void setCloudScrollPeriod(CloudScrollPeriod period) {
		Objects.requireNonNull(period, "period");
		if (period.textureWidth() <= 0 || period.scale() <= 0) {
			throw new IllegalArgumentException("Cloud scroll period needs a positive texture width and scale: "
				+ period.textureWidth() + " texels at " + period.scale() + "x");
		}
		cloudScrollPeriod = period;
	}

	/**
	 * Returns the number of ticks after which the cloud animation repeats.
	 *
	 * <p>One cycle moves the layer by one texture width: the width in texels times 400 ticks per width at
	 * the 1x cloud scale, scaled by the registered scale.</p>
	 */
	static long cloudScrollCycle() {
		final CloudScrollPeriod period = cloudScrollPeriod;
		return (long) period.textureWidth() * 400L * period.scale();
	}

	/**
	 * Returns the cloud animation time at the given world time.
	 *
	 * <p>The time wraps at one scroll period, which keeps the value small enough to stay exact in single
	 * precision while the pack samples its cloud noise.</p>
	 */
	static float cloudTime(long totalWorldTime, float partialTicks) {
		return (totalWorldTime % cloudScrollCycle() + partialTicks) * 0.03F;
	}

	/** Returns the cloud animation time of a world, zero when no world is loaded. */
	static float cloudTime(WorldClient world, float partialTicks) {
		return world == null ? 0.0F : cloudTime(world.getTotalWorldTime(), partialTicks);
	}

	private static float getCloudTime() {
		final Minecraft mc = Minecraft.getMinecraft();
		return cloudTime(mc.world, mc.getRenderPartialTicks());
	}

	public static void addIrisExclusiveUniforms(UniformHolder uniforms) {
		WorldInfoUniforms.addWorldInfoUniforms(uniforms);

		//All Iris-exclusive uniforms (uniforms which do not exist in either OptiFine or ShadersMod) should be registered here.
		uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "thunderStrength", IrisExclusiveUniforms::getThunderStrength);
		uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "cloudTime", IrisExclusiveUniforms::getCloudTime);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerHealth", IrisExclusiveUniforms::getCurrentHealth);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerHealth", IrisExclusiveUniforms::getMaxHealth);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerHunger", IrisExclusiveUniforms::getCurrentHunger);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerHunger", () -> 20);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerAir", IrisExclusiveUniforms::getCurrentAir);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerAir", IrisExclusiveUniforms::getMaxAir);
		uniforms.uniform1b(UniformUpdateFrequency.PER_FRAME, "firstPersonCamera", IrisExclusiveUniforms::isFirstPersonCamera);
		uniforms.uniform1b(UniformUpdateFrequency.PER_TICK, "isSpectator", IrisExclusiveUniforms::isSpectator);
		uniforms.uniform1b(UniformUpdateFrequency.PER_TICK, "isRightHanded", () -> true); // 1.7.10 doesn't support left-handed mode
		uniforms.uniform3d(UniformUpdateFrequency.PER_FRAME, "eyePosition", IrisExclusiveUniforms::getEyePosition);
		uniforms.uniform3d(UniformUpdateFrequency.PER_FRAME, "relativeEyePosition", IrisExclusiveUniforms::getRelativeEyePosition);
		uniforms.uniform4f(UniformUpdateFrequency.PER_TICK, "lightningBoltPosition", IrisExclusiveUniforms::getLightningBoltPosition);
	}

	private static float getThunderStrength() {
		// Note: Ensure this is in the range of 0 to 1 - some custom servers send out of range values.
		return Math.clamp(0.0F, 1.0F, Minecraft.getMinecraft().world.thunderingStrength);
	}

	private static float getCurrentHealth() {
		if (Minecraft.getMinecraft().player == null || !Minecraft.getMinecraft().playerController.gameIsSurvivalOrAdventure()) {
			return -1;
		}

		return Minecraft.getMinecraft().player.getHealth() / Minecraft.getMinecraft().player.getMaxHealth();
	}

	private static float getCurrentHunger() {
		if (Minecraft.getMinecraft().player == null || !Minecraft.getMinecraft().playerController.gameIsSurvivalOrAdventure()) {
			return -1;
		}

		return Minecraft.getMinecraft().player.getFoodStats().getFoodLevel() / 20f;
	}

	private static float getCurrentAir() {
		if (Minecraft.getMinecraft().player == null || !Minecraft.getMinecraft().playerController.gameIsSurvivalOrAdventure()) {
			return -1;
		}

		return (float) Minecraft.getMinecraft().player.getAir() / (float) Minecraft.getMinecraft().player.getAir();
	}

	private static float getMaxAir() {
		if (Minecraft.getMinecraft().player == null || !Minecraft.getMinecraft().playerController.gameIsSurvivalOrAdventure()) {
			return -1;
		}

		return 300.0F;
	}

	private static float getMaxHealth() {
		if (Minecraft.getMinecraft().player == null || !Minecraft.getMinecraft().playerController.gameIsSurvivalOrAdventure()) {
			return -1;
		}

		return Minecraft.getMinecraft().player.getMaxHealth();
	}

	private static boolean isFirstPersonCamera() {
		// If camera type is not explicitly third-person, assume it's first-person.
        return !Camera.INSTANCE.isThirdPerson();
	}

	private static boolean isSpectator() {
		return GameModeUtil.isSpectator();
	}

	private static Vector3d getEyePosition() {
        final EntityLivingBase eye = (EntityLivingBase) Minecraft.getMinecraft().getRenderViewEntity();
        return eyePositionCache.set(eye.posX, eye.posY + eye.getEyeHeight(), eye.posZ);
	}

	private static Vector3d getRelativeEyePosition() {
		final Vector3dc cameraPos = CameraUniforms.getUnshiftedCameraPosition();
		final Vector3d eyePos = getEyePosition();
		return relativeEyePositionCache.set(cameraPos).sub(eyePos);
	}

	private static Vector4f getLightningBoltPosition() {
		if (Minecraft.getMinecraft().world != null) {
			final List<Entity> weatherEffects = Minecraft.getMinecraft().world.weatherEffects;
			for (Entity entity : weatherEffects) {
				if (entity instanceof EntityLightningBolt bolt) {
                    final Vector3dc cameraPos = CameraUniforms.getUnshiftedCameraPosition();
					return lightningBoltPositionCache.set(
						(float)(bolt.posX - cameraPos.x()),
						(float)(bolt.posY - cameraPos.y()),
						(float)(bolt.posZ - cameraPos.z()),
						1.0f
					);
				}
			}
		}
		return ZERO_VECTOR_4f;
	}

	public static class WorldInfoUniforms {
		public static void addWorldInfoUniforms(UniformHolder uniforms) {
			final WorldClient level = Minecraft.getMinecraft().world;
			uniforms.uniform1i(UniformUpdateFrequency.PER_FRAME, "bedrockLevel", () -> 0);
            uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "cloudHeight", () -> {
                if (level != null && level.provider != null) {
                    return level.provider.getCloudHeight();
                } else {
                    return 192.0;
                }
            });
			uniforms.uniform1i(UniformUpdateFrequency.PER_FRAME, "heightLimit", () -> {
                if (level != null && level.provider != null) {
                    return level.provider.getHeight();
				} else {
					return 256;
				}
			});
			uniforms.uniform1b(UniformUpdateFrequency.PER_FRAME, "hasCeiling", () -> {
				if (level != null && level.provider != null) {
					return !level.provider.hasSkyLight();
				} else {
					return false;
				}
			});
			uniforms.uniform1b(UniformUpdateFrequency.PER_FRAME, "hasSkylight", () -> {
				if (level != null && level.provider != null) {
					return level.provider.hasSkyLight();
				} else {
					return true;
				}
			});
			uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "ambientLight", () -> {
				if (level != null && level.provider != null) {
                    return level.provider.getLightBrightnessTable()[0];
				} else {
					return 0f;
				}
			});

		}
	}
}
