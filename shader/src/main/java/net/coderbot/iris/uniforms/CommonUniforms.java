package net.coderbot.iris.uniforms;

import net.coderbot.iris.debug.IrisDebugOptions;
import com.gtnewhorizons.angelica.compat.mojang.GameModeUtil;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.states.BlendState;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfo;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfoCache;
import com.gtnewhorizons.angelica.client.rendering.TextureTracker;
import net.coderbot.iris.Iris;
import net.coderbot.iris.compat.dh.DHCompat;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.gl.state.FogMode;
import net.coderbot.iris.gl.state.StateUpdateNotifiers;
import net.coderbot.iris.gl.uniform.DynamicUniformHolder;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.layer.GbufferPrograms;
import net.coderbot.iris.shaderpack.IdMap;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.uniforms.transforms.SmoothedFloat;
import net.coderbot.iris.uniforms.transforms.SmoothedVec2f;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.math.Vec3d;
import org.joml.Math;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector4i;
import com.gtnewhorizon.gtnhlib.client.renderer.postprocessing.PostProcessingBridge;

import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;
import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.PER_TICK;
import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.ONCE;
import static java.lang.Math.PI;

public final class CommonUniforms {
	private static final Minecraft client = Minecraft.getMinecraft();
	private static final Vector2i ZERO_VECTOR_2i = new Vector2i();
	private static final Vector3d ZERO_VECTOR_3d = new Vector3d();
	private static float lastLoggedCloudTime = Float.NaN;

	// Scratch vectors for push-notified suppliers -- GL thread only, never escapes
	private static final Vector2i scratch2i = new Vector2i();
	private static final Vector4i scratch4i = new Vector4i();

	private CommonUniforms() {
		// no construction allowed
	}

    public static void addNonDynamicUniforms(UniformHolder uniforms, IdMap idMap, PackDirectives directives, FrameUpdateNotifier updateNotifier) {
        CameraUniforms.addCameraUniforms(uniforms, updateNotifier);
        ViewportUniforms.addViewportUniforms(uniforms);
        WorldTimeUniforms.addWorldTimeUniforms(uniforms);
        SystemTimeUniforms.addSystemTimeUniforms(uniforms);
		BiomeUniforms.addBiomeUniforms(uniforms);
        new CelestialUniforms(directives.getSunPathRotation()).addCelestialUniforms(uniforms);
        IrisExclusiveUniforms.addIrisExclusiveUniforms(uniforms);
        IdMapUniforms.addIdMapUniforms(updateNotifier, uniforms, idMap, directives.isOldHandLight());
        MatrixUniforms.addMatrixUniforms(uniforms, directives);

        if (IrisDebugOptions.enableHardcodedCustomUniforms()) {
            HardcodedCustomUniforms.addHardcodedCustomUniforms(uniforms, updateNotifier);
        }

        CommonUniforms.generalCommonUniforms(uniforms, updateNotifier, directives);
    }

	// Needs to use a LocationalUniformHolder as we need it for the common uniforms
	public static void addDynamicUniforms(DynamicUniformHolder uniforms, FogMode fogMode) {
		ExternallyManagedUniforms.addExternallyManagedUniforms116(uniforms);
        IdMapUniforms.addEntityIdMapUniforms(uniforms);
		FogUniforms.addFogUniforms(uniforms, fogMode);
		IrisInternalUniforms.addFogUniforms(uniforms, fogMode);

		uniforms.uniform2i("atlasSize", () -> {
			final int glId = GLStateManager.getBoundTextureForServerState(0);

			final AbstractTexture texture = TextureTracker.INSTANCE.getTexture(glId);
			if (texture instanceof TextureMap) {
				final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(glId);
				return scratch2i.set(info.getWidth(), info.getHeight());
			}

			return scratch2i.set(0, 0);
		}, StateUpdateNotifiers.bindTextureNotifier);

		uniforms.uniform1i("gtextureId", () -> GLStateManager.getBoundTextureForServerState(0), StateUpdateNotifiers.bindTextureNotifier);

		uniforms.uniform2i("gtextureSize", () -> {
			final int glId = GLStateManager.getBoundTextureForServerState(0);

			final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(glId);
			return scratch2i.set(info.getWidth(), info.getHeight());

		}, StateUpdateNotifiers.bindTextureNotifier);

		uniforms.uniform4i("blendFunc", () -> {
            if(GLStateManager.getBlendMode().isEnabled()) {
                final BlendState blend = GLStateManager.getBlendState();
                return scratch4i.set(blend.getSrcRgb(), blend.getDstRgb(), blend.getSrcAlpha(), blend.getDstAlpha());
            }
            return scratch4i.set(0, 0, 0, 0);
		}, StateUpdateNotifiers.blendFuncNotifier);

		uniforms.uniform1i("renderStage", () -> GbufferPrograms.getCurrentPhase().ordinal(), StateUpdateNotifiers.phaseChangeNotifier);

        uniforms.uniform4f("entityColor", CapturedRenderingState.INSTANCE::getCurrentEntityColor, CapturedRenderingState.INSTANCE.getEntityColorNotifier());

	}

	public static void generalCommonUniforms(UniformHolder uniforms, FrameUpdateNotifier updateNotifier, PackDirectives directives) {
		ExternallyManagedUniforms.addExternallyManagedUniforms116(uniforms);

		final SmoothedVec2f eyeBrightnessSmooth = new SmoothedVec2f(directives.getEyeBrightnessHalfLife(), directives.getEyeBrightnessHalfLife(), CommonUniforms::getEyeBrightness, updateNotifier);
		// Keep volumetric cloud offsets stable across sleep/time-set jumps without changing raw worldTime.
		final SmoothedFloat worldTimeSmooth = new SmoothedFloat(20, 20, WorldTimeUniforms::getContinuousWorldTime, updateNotifier);

        uniforms
            .uniform1f(ONCE, "darknessFactor", () -> 0.0F) // This is PER_FRAME in modern, it is an effect added by The Warden. We're just setting to 0 because 1.7.10 doesn't have it.
            .uniform1f(ONCE, "darknessLightFactor", () -> 0.0F) // Warden darkness current light factor - 1.7.10 doesn't have it so hardcode to 0
			.uniform1b(PER_FRAME, "hideGUI", () -> client.gameSettings.hideGUI)
			.uniform1i(PER_FRAME, "isEyeInWater", CommonUniforms::isEyeInWater)
			.uniform1f(PER_FRAME, "blindness", CommonUniforms::getBlindness)
			.uniform1f(PER_FRAME, "nightVision", CommonUniforms::getNightVision)
			.uniform1f(PER_FRAME, "iris_worldTimeSmooth", () -> {
				final float rawContinuous = WorldTimeUniforms.getContinuousWorldTime();
				final float smoothContinuous = worldTimeSmooth.getAsFloat();
				final float cloudTime = smoothContinuous - (WorldTimeUniforms.getWorldDay() % 100) * 24000.0F;
				if (IrisGlDebug.isCloudControlDebugEnabled()
					&& (Float.isNaN(lastLoggedCloudTime) || Math.abs(rawContinuous - lastLoggedCloudTime) > 100.0F)) {
					Iris.logger.info(
						"[CloudTime] worldTime={} worldDay={} rawContinuous={} smoothContinuous={} cloudTime={}",
						WorldTimeUniforms.getWorldDayTime(), WorldTimeUniforms.getWorldDay(),
						rawContinuous, smoothContinuous, cloudTime
					);
					lastLoggedCloudTime = rawContinuous;
				}
				return cloudTime;
			})
            .uniform1b(PER_FRAME, "is_sneaking", CommonUniforms::isSneaking)
            .uniform1b(PER_FRAME, "is_sprinting", CommonUniforms::isSprinting)
            .uniform1b(PER_FRAME, "is_hurt", CommonUniforms::isHurt)
            .uniform1b(PER_FRAME, "is_invisible", CommonUniforms::isInvisible)
            .uniform1b(PER_FRAME, "is_burning", CommonUniforms::isBurning)
            .uniform1b(PER_FRAME, "is_on_ground", CommonUniforms::isOnGround)
			// TODO: Do we need to clamp this to avoid fullbright breaking shaders? Or should shaders be able to detect
			//       that the player is trying to turn on fullbright?
			.uniform1f(PER_FRAME, "screenBrightness", () -> client.gameSettings.gammaSetting)
			.uniform1f(ONCE, "pi", () -> PI)
			// just a dummy value for shaders where entityColor isn't supplied through a vertex attribute (and thus is
			// not available) - suppresses warnings. See AttributeShaderTransformer for the actual entityColor code.
            .uniform1f(PER_TICK, "playerMood", CommonUniforms::getPlayerMood)
			.uniform2i(PER_FRAME, "eyeBrightness", CommonUniforms::getEyeBrightness)
			.uniform2i(PER_FRAME, "eyeBrightnessSmooth", () -> {
				final Vector2f smoothed = eyeBrightnessSmooth.get();
				return new Vector2i((int) smoothed.x(),(int) smoothed.y());
			})
			.uniform1f(PER_TICK, "rainStrength", CommonUniforms::getRainStrength)
			.uniform1f(PER_TICK, "wetness", new SmoothedFloat(directives.getWetnessHalfLife(), directives.getDrynessHalfLife(), CommonUniforms::getRainStrength, updateNotifier))
			.uniform3d(PER_FRAME, "skyColor", CommonUniforms::getSkyColor)
			.uniform3d(PER_FRAME, "fogColor", GLStateManager::getFogColor)
			.uniform1f(PER_FRAME, "dhFarPlane", DHCompat::getFarPlane)
			.uniform1f(PER_FRAME, "dhNearPlane", DHCompat::getNearPlane)
			.uniform1i(PER_FRAME, "dhRenderDistance", DHCompat::getRenderDistance);
	}

    private static boolean isOnGround() {
        return client.player != null && client.player.onGround;
    }

    private static boolean isHurt() {
        // Do not use isHurt, that's not what we want!
        return (client.player != null &&  client.player.hurtTime > 0);
    }

	private static boolean isInvisible() {
        return (client.player != null &&  client.player.isInvisible());
    }

    private static boolean isBurning() {
        return client.player != null && client.player.fire > 0 && !client.player.isImmuneToFire();
    }

    private static boolean isSneaking() {
        return (client.player != null && client.player.isSneaking());
    }

    private static boolean isSprinting() {
        return (client.player != null && client.player.isSprinting());
    }

	private static Vector3d getSkyColor() {
        Entity cameraEntity = client.getRenderViewEntity();
        if (client.world == null || cameraEntity == null) {
			return ZERO_VECTOR_3d;
		}
        final Vec3d skyColor = client.world.getSkyColor(cameraEntity, CapturedRenderingState.INSTANCE.getTickDelta());
        return new Vector3d(skyColor.x, skyColor.y, skyColor.z);
	}

	static float getBlindness() {
        final Entity cameraEntity = client.getRenderViewEntity();

        if (cameraEntity instanceof EntityPlayer livingEntity && livingEntity.isPotionActive(MobEffects.BLINDNESS)) {
            final PotionEffect blindness = livingEntity.getActivePotionEffect(MobEffects.BLINDNESS);

			if (blindness != null) {
				// Guessing that this is what OF uses, based on how vanilla calculates the fog value in BackgroundRenderer
				// TODO: Add this to ShaderDoc
				return Math.clamp(0.0F, 1.0F, blindness.getDuration() / 20.0F);
			}
		}

		return 0.0F;
	}

	private static float getPlayerMood() {
        // TODO: What should this be?
        return 0.0F;
	}

	static float getRainStrength() {
        if (client.world == null) {
			return 0f;
		}

		// Note: Ensure this is in the range of 0 to 1 - some custom servers send out of range values.
        return Math.clamp(0.0F, 1.0F, client.world.getRainStrength(CapturedRenderingState.INSTANCE.getTickDelta()));

	}

	private static Vector2i getEyeBrightness() {
        Entity cameraEntity = client.getRenderViewEntity();
        if (cameraEntity == null || client.world == null) {
			return ZERO_VECTOR_2i;
		}
        // This is what ShadersMod did in 1.7.10
        final int eyeBrightness = cameraEntity.getBrightnessForRender();
        return new Vector2i((eyeBrightness & 0xffff), (eyeBrightness >> 16));

	}

	private static float getNightVision() {
        Entity cameraEntity = client.getRenderViewEntity();

        if (cameraEntity instanceof EntityPlayer entityPlayer) {
            if (!entityPlayer.isPotionActive(MobEffects.NIGHT_VISION)) {
                return 0.0F;
            }
            float nightVisionStrength = PostProcessingBridge.getNightVisionBrightness(entityPlayer, CapturedRenderingState.INSTANCE.getTickDelta());

			try {
				if (nightVisionStrength > 0) {
					// Just protecting against potential weird mod behavior
					return Math.clamp(0.0F, 1.0F, nightVisionStrength);
				}
			} catch (NullPointerException e) {
				return 0.0F;
			}
		}

		return 0.0F;
	}

	static int isEyeInWater() {
		if (client.world == null) {
			return 0;
		}

		Material material = ActiveRenderInfo.getBlockStateAtEntityViewpoint(
			client.world,
			client.getRenderViewEntity(),
			CapturedRenderingState.INSTANCE.getTickDelta()
		).getMaterial();
		if (material == Material.WATER) {
			return 1;
		}
		if (material == Material.LAVA && !GameModeUtil.isSpectator()) {
			return 2;
		}
		return 0;
	}

	static {
		GbufferPrograms.init();
	}
}
