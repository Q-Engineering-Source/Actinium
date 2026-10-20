package net.coderbot.iris.gl.program;

import com.google.common.collect.ImmutableSet;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import net.coderbot.iris.gl.image.ImageHolder;
import net.coderbot.iris.gl.sampler.GlSampler;
import net.coderbot.iris.gl.sampler.SamplerHolder;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.coderbot.iris.gl.texture.TextureType;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;

public class ProgramBuilder extends ProgramUniforms.Builder implements SamplerHolder, ImageHolder {
	private static final ProgramFallback FALLBACK = new ProgramFallbackImpl();

	private final int program;
	private final ProgramSamplers.Builder samplers;
	private final ProgramImages.Builder images;
	private final RetainedPrograms.Sources sources;
	/** Set when this builder hands its program name to a wrapper; the name has one owner at a time. */
	private boolean built;

	private ProgramBuilder(String name, int program, ImmutableSet<Integer> reservedTextureUnits,
						   RetainedPrograms.Sources sources) {
		super(name, program);

		this.program = program;
		this.samplers = ProgramSamplers.builder(program, reservedTextureUnits);
		this.images = ProgramImages.builder(program);
		this.sources = sources;
	}

	public void bindAttributeLocation(int index, String name) {
		RenderSystem.bindAttributeLocation(program, index, name);
	}

	public static ProgramBuilder begin(String name, @Nullable String vertexSource, @Nullable String geometrySource,
									   @Nullable String fragmentSource, ImmutableSet<Integer> reservedTextureUnits) {
		return begin(name, vertexSource, geometrySource, null, null, fragmentSource, reservedTextureUnits);
	}

	public static ProgramBuilder begin(String name, @Nullable String vertexSource, @Nullable String geometrySource,
									   @Nullable String tessControlSource, @Nullable String tessEvalSource,
									   @Nullable String fragmentSource, ImmutableSet<Integer> reservedTextureUnits) {
		final RetainedPrograms.Sources sources = new RetainedPrograms.Sources(vertexSource, geometrySource,
			tessControlSource, tessEvalSource, fragmentSource, null);

		return new ProgramBuilder(name, acquire(name, sources, FALLBACK), reservedTextureUnits, sources);
	}

	public static ProgramBuilder beginCompute(String name, @Nullable String source, ImmutableSet<Integer> reservedTextureUnits) {
		if (!RenderSystem.supportsCompute()) {
			throw new IllegalStateException("This PC does not support compute shaders, but it's attempting to be used???");
		}

		final RetainedPrograms.Sources sources = new RetainedPrograms.Sources(null, null, null, null, null, source);

		return new ProgramBuilder(name, acquire(name, sources, FALLBACK), reservedTextureUnits, sources);
	}

	/**
	 * Picks where the program for these stage sources comes from: the pool of programs a destroyed pipeline
	 * kept, then the binary a previous run saved to disk, then a compile and link.
	 *
	 * <p>Each tier only runs when the one before it has nothing. A kept program therefore costs neither a
	 * disk read nor a shader object, and a saved binary costs no compile.</p>
	 *
	 * @param fallback the disk and link tiers; production passes {@link ProgramFallbackImpl}
	 * @return the program, never 0
	 */
	static int acquire(String name, RetainedPrograms.Sources sources, ProgramFallback fallback) {
		final int retained = RetainedPrograms.take(sources);
		if (retained != 0) {
			return retained;
		}

		final ProgramBinaryCache.Key cacheKey = fallback.cacheKey(sources);
		final int restored = fallback.restore(name, cacheKey);
		if (restored != 0) {
			return restored;
		}

		return fallback.compile(name, cacheKey, sources);
	}

	public Program build() {
		return new Program(claimProgram(), sources, super.buildUniforms(), this.samplers.build(), this.images.build());
	}

	public ComputeProgram buildCompute() {
		return new ComputeProgram(claimProgram(), sources, super.buildUniforms(), this.samplers.build(), this.images.build());
	}

	/**
	 * Hands the program name to the wrapper that is being built. A builder names exactly one program, and a
	 * program name can have only one owner: once the pool is in play, a second wrapper for the same name would
	 * let two wrappers keep and reuse it independently.
	 */
	private int claimProgram() {
		if (built) {
			throw new IllegalStateException("This program builder already gave its program to a wrapper");
		}

		built = true;
		return program;
	}

	@Override
	public void addExternalSampler(int textureUnit, String... names) {
		samplers.addExternalSampler(textureUnit, names);
	}

	@Override
	public boolean hasSampler(String name) {
		return samplers.hasSampler(name);
	}

	@Override
	public boolean addDefaultSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
		return samplers.addDefaultSampler(type, texture, notifier, sampler, names);
	}

	@Override
	public boolean addDynamicSampler(TextureType type, IntSupplier texture, GlSampler sampler, String... names) {
		return samplers.addDynamicSampler(type, texture, sampler, names);
	}

	@Override
	public boolean addDynamicSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
		return samplers.addDynamicSampler(type, texture, notifier, sampler, names);
	}

	@Override
	public boolean hasImage(String name) {
		return images.hasImage(name);
	}

	@Override
	public void addTextureImage(IntSupplier textureID, InternalTextureFormat internalFormat, String name) {
		images.addTextureImage(textureID, internalFormat, name);
	}
}
