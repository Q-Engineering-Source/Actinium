package net.coderbot.iris.gl.program;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.coderbot.iris.gl.sampler.GlSampler;
import net.coderbot.iris.gl.sampler.SamplerBinding;
import net.coderbot.iris.gl.sampler.SamplerHolder;
import net.coderbot.iris.gl.sampler.SamplerLimits;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import net.coderbot.iris.gl.texture.TextureAccess;
import net.coderbot.iris.gl.texture.TextureType;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.shaderpack.PackRenderTargetDirectives;
import org.lwjgl.opengl.GL13;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

public class ProgramSamplers {
	private static ProgramSamplers active;
	private final ImmutableList<SamplerBinding> samplerBindings;
	private List<GlUniform1iCall> initializer;

	private ProgramSamplers(ImmutableList<SamplerBinding> samplerBindings, List<GlUniform1iCall> initializer) {
		this.samplerBindings = samplerBindings;
		this.initializer = initializer;
	}

	public void update() {
		if (active != null && active != this) {
			active.removeListeners();
		}

		active = this;

		if (initializer != null) {
			for (GlUniform1iCall call : initializer) {
				RenderSystem.uniform1i(call.getLocation(), call.getValue());
			}

			initializer = null;
		}

		// We need to keep the active texture intact, since if we mess it up
		// in the middle of RenderType setup, bad things will happen.
		int activeTexture = GLStateManager.getActiveTextureUnit();

		for (SamplerBinding samplerBinding : samplerBindings) {
			samplerBinding.update();
		}

		GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + activeTexture);
	}

	/**
	 * Takes back every push notification this program's samplers registered, whether or not this program is
	 * the active one. Notifiers are shared between programs, so only this program's registrations are
	 * removed; a leftover one would rebind a texture unit of whatever program is bound next.
	 */
	public void removeListeners() {
		if (active == this) {
			active = null;
		}

		for (SamplerBinding binding : samplerBindings) {
			binding.removeListener();
		}
	}

	public static void clearActiveSamplers() {
		if (active != null) {
			active.removeListeners();
		}
	}

	public static Builder builder(int program, Set<Integer> reservedTextureUnits) {
		return new Builder(program, reservedTextureUnits);
	}

	public static CustomTextureSamplerInterceptor customTextureSamplerInterceptor(SamplerHolder samplerHolder, Object2ObjectMap<String, TextureAccess> customTextureIds) {
		return customTextureSamplerInterceptor(samplerHolder, customTextureIds, ImmutableSet.of());
	}

	public static CustomTextureSamplerInterceptor customTextureSamplerInterceptor(SamplerHolder samplerHolder, Object2ObjectMap<String, TextureAccess> customTextureIds, ImmutableSet<Integer> flippedAtLeastOnceSnapshot) {
		return new CustomTextureSamplerInterceptor(samplerHolder, customTextureIds, flippedAtLeastOnceSnapshot);
	}

	public static final class Builder implements SamplerHolder {
		private final int program;
		private final ImmutableSet<Integer> reservedTextureUnits;
		private final ImmutableList.Builder<SamplerBinding> samplers;
		private final List<GlUniform1iCall> calls;
		private int remainingUnits;
		private int nextUnit;

		private Builder(int program, Set<Integer> reservedTextureUnits) {
			this.program = program;
			this.reservedTextureUnits = ImmutableSet.copyOf(reservedTextureUnits);
			this.samplers = ImmutableList.builder();
			this.calls = new ArrayList<>();

			int maxTextureUnits = SamplerLimits.get().getMaxTextureUnits();

			for (int unit : reservedTextureUnits) {
				if (unit >= maxTextureUnits) {
					throw new IllegalStateException("Cannot mark texture unit " + unit + " as reserved because that " +
							"texture unit isn't available on this system! Only " + maxTextureUnits +
							" texture units are available.");
				}
			}

			this.remainingUnits = maxTextureUnits - reservedTextureUnits.size();
			this.nextUnit = 0;

			while (reservedTextureUnits.contains(nextUnit)) {
				nextUnit += 1;
			}

		}

		@Override
		public void addExternalSampler(int textureUnit, String... names) {
			if (!reservedTextureUnits.contains(textureUnit)) {
				throw new IllegalArgumentException("Cannot add an externally-managed sampler for texture unit " +
						textureUnit + " since it isn't in the set of reserved texture units.");
			}

			for (String name : names) {
				int location = RenderSystem.getUniformLocation(program, name);

				if (location == -1) {
					// There's no active sampler with this particular name in the program.
					continue;
				}

				calls.add(new GlUniform1iCall(location, textureUnit));
				IrisGlDebug.logSamplerInitialization(program, "external", name, location, textureUnit);
			}
		}

		@Override
		public boolean hasSampler(String name) {
			return RenderSystem.getUniformLocation(program, name) != -1;
		}

		@Override
		public boolean addDefaultSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
			if (nextUnit != 0) {
				// TODO: Relax this restriction!
				throw new IllegalStateException("Texture unit 0 is already used.");
			}

			return addDynamicSampler(type, texture, sampler, true, notifier, names);
		}

		@Override
		public boolean addDynamicSampler(TextureType type, IntSupplier texture, GlSampler sampler, String... names) {
			return addDynamicSampler(type, texture, sampler, false, null, names);
		}

		@Override
		public boolean addDynamicSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
			return addDynamicSampler(type, texture, sampler, false, notifier, names);
		}

		private boolean addDynamicSampler(TextureType type, IntSupplier texture, GlSampler sampler, boolean used, ValueUpdateNotifier notifier, String... names) {
			for (String name : names) {
				int location = RenderSystem.getUniformLocation(program, name);

				if (location == -1) {
					// There's no active sampler with this particular name in the program.
					continue;
				}

				// Make sure that we aren't out of texture units.
				if (remainingUnits <= 0) {
					throw new IllegalStateException("No more available texture units while activating sampler " + name);
				}

				calls.add(new GlUniform1iCall(location, nextUnit));
				IrisGlDebug.logSamplerInitialization(program, used ? "dynamic-shared" : "dynamic", name, location, nextUnit);

				used = true;
			}

			if (!used) {
				return false;
			}

			samplers.add(new SamplerBinding(type, nextUnit, texture, sampler, notifier));

			remainingUnits -= 1;
			nextUnit += 1;

			while (remainingUnits > 0 && reservedTextureUnits.contains(nextUnit)) {
				nextUnit += 1;
			}

			return true;
		}

		public ProgramSamplers build() {
			return new ProgramSamplers(samplers.build(), calls);
		}
	}

	public static final class CustomTextureSamplerInterceptor implements SamplerHolder {
		private final SamplerHolder samplerHolder;
		private final Object2ObjectMap<String, TextureAccess> customTextureIds;
		private final ImmutableSet<String> deactivatedOverrides;

		private CustomTextureSamplerInterceptor(SamplerHolder samplerHolder, Object2ObjectMap<String, TextureAccess> customTextureIds, ImmutableSet<Integer> flippedAtLeastOnceSnapshot) {
			this.samplerHolder = samplerHolder;
			this.customTextureIds = customTextureIds;

			ImmutableSet.Builder<String> deactivatedOverrides = new ImmutableSet.Builder<>();

			for (int deactivatedOverride : flippedAtLeastOnceSnapshot) {
				deactivatedOverrides.add("colortex" + deactivatedOverride);

				if (deactivatedOverride < PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.size()) {
					deactivatedOverrides.add(PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.get(deactivatedOverride));
				}
			}

			this.deactivatedOverrides = deactivatedOverrides.build();
		}

		private IntSupplier getOverride(IntSupplier existing, String... names) {
			for (String name : names) {
				if (customTextureIds.containsKey(name) && !deactivatedOverrides.contains(name)) {
					return customTextureIds.get(name).getTextureId();
				}
			}

			return existing;
		}

		private boolean hasOverride(String... names) {
			for (String name : names) {
				if (customTextureIds.containsKey(name) && !deactivatedOverrides.contains(name)) {
					return true;
				}
			}

			return false;
		}

		@Override
		public void addExternalSampler(int textureUnit, String... names) {
			IntSupplier override = getOverride(null, names);
			IrisGlDebug.logSamplerIntercept(getProgramId(), "external", textureUnit, hasOverride(names), names);

			if (override != null) {
				if (textureUnit == 0) {
					samplerHolder.addDefaultSampler(override, names);
				} else {
					samplerHolder.addDynamicSampler(override, names);
				}
			} else {
				samplerHolder.addExternalSampler(textureUnit, names);
			}
		}

		@Override
		public boolean hasSampler(String name) {
			return samplerHolder.hasSampler(name);
		}

		@Override
		public boolean addDefaultSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
			texture = getOverride(texture, names);
			IrisGlDebug.logSamplerIntercept(getProgramId(), "default", 0, hasOverride(names), names);
			return samplerHolder.addDefaultSampler(type, texture, notifier, sampler, names);
		}

		@Override
		public boolean addDynamicSampler(TextureType type, IntSupplier texture, GlSampler sampler, String... names) {
			texture = getOverride(texture, names);
			IrisGlDebug.logSamplerIntercept(getProgramId(), "dynamic", -1, hasOverride(names), names);
			return samplerHolder.addDynamicSampler(type, texture, sampler, names);
		}

		@Override
		public boolean addDynamicSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
			texture = getOverride(texture, names);
			IrisGlDebug.logSamplerIntercept(getProgramId(), "dynamic-notified", -1, hasOverride(names), names);
			return samplerHolder.addDynamicSampler(type, texture, notifier, sampler, names);
		}

		private int getProgramId() {
			if (samplerHolder instanceof Builder builder) {
				return builder.program;
			}
			return -1;
		}
	}
}
