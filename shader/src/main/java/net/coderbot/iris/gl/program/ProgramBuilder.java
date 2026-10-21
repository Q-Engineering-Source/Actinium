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

public class ProgramBuilder extends ProgramUniforms.Builder implements SamplerHolder, ImageHolder, AutoCloseable {
	private static final ProgramFallback FALLBACK = new ProgramFallbackImpl();

	private final int program;
	private final ProgramSamplers.Builder samplers;
	private final ProgramImages.Builder images;
	private final RetainedPrograms.Sources sources;
	private final ProgramOwnership ownership;
	private final long ownerShareGroupId;
	private final long ownerConfigurationEpoch;
	/** Set when a complete wrapper has been attached to the builder's owned program. */
	private boolean wrapperBuilt;

	private ProgramBuilder(String name, int program, ImmutableSet<Integer> reservedTextureUnits,
						   RetainedPrograms.Sources sources, ProgramOwnership ownership,
						   long ownerShareGroupId, long ownerConfigurationEpoch) {
		super(name, program);

		this.program = program;
		this.samplers = ProgramSamplers.builder(program, reservedTextureUnits);
		this.images = ProgramImages.builder(program);
		this.sources = sources;
		this.ownership = ownership;
		this.ownerShareGroupId = ownerShareGroupId;
		this.ownerConfigurationEpoch = ownerConfigurationEpoch;
	}

	public void bindAttributeLocation(int index, String name) {
		RetainedPrograms.requireCurrentShareGroup(ownerShareGroupId);
		RenderSystem.bindAttributeLocation(program, index, name);
	}

	public static ProgramBuilder begin(String name, @Nullable String vertexSource, @Nullable String geometrySource,
									   @Nullable String fragmentSource, ImmutableSet<Integer> reservedTextureUnits) {
		return begin(name, vertexSource, geometrySource, null, null, fragmentSource, reservedTextureUnits);
	}

	public static ProgramBuilder begin(String name, @Nullable String vertexSource, @Nullable String geometrySource,
									   @Nullable String tessControlSource, @Nullable String tessEvalSource,
									   @Nullable String fragmentSource, ImmutableSet<Integer> reservedTextureUnits) {
		return begin(name, vertexSource, geometrySource, tessControlSource, tessEvalSource, fragmentSource,
			reservedTextureUnits, FALLBACK);
	}

	static ProgramBuilder begin(String name, @Nullable String vertexSource, @Nullable String geometrySource,
								 @Nullable String tessControlSource, @Nullable String tessEvalSource,
								 @Nullable String fragmentSource, ImmutableSet<Integer> reservedTextureUnits,
								 ProgramFallback fallback) {
		final RetainedPrograms.Sources sources = new RetainedPrograms.Sources(vertexSource, geometrySource,
			tessControlSource, tessEvalSource, fragmentSource, null);
		return create(name, sources, reservedTextureUnits, fallback);
	}

	public static ProgramBuilder beginCompute(String name, @Nullable String source, ImmutableSet<Integer> reservedTextureUnits) {
		return beginCompute(name, source, reservedTextureUnits, FALLBACK);
	}

	static ProgramBuilder beginCompute(String name, @Nullable String source, ImmutableSet<Integer> reservedTextureUnits,
									   ProgramFallback fallback) {
		if (!RenderSystem.supportsCompute()) {
			throw new IllegalStateException("This PC does not support compute shaders, but it's attempting to be used???");
		}

		final RetainedPrograms.Sources sources = new RetainedPrograms.Sources(null, null, null, null, null, source);

		return create(name, sources, reservedTextureUnits, fallback);
	}

	private static ProgramBuilder create(String name, RetainedPrograms.Sources sources,
									 ImmutableSet<Integer> reservedTextureUnits, ProgramFallback fallback) {
		final long ownerShareGroupId = RetainedPrograms.currentShareGroupId();
		final long ownerConfigurationEpoch = RetainedPrograms.configurationEpoch();
		final int program = acquire(name, sources, fallback, ownerShareGroupId);
		final ProgramOwnership ownership = new ProgramOwnership(program, ownerShareGroupId);
		try {
			return new ProgramBuilder(name, program, reservedTextureUnits, sources, ownership,
				ownerShareGroupId, ownerConfigurationEpoch);
		} catch (RuntimeException | Error failure) {
			try {
				ownership.close();
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	/**
	 * Picks where the program for these stage sources comes from: a retained program, its disk binary, then a
	 * compile and link. During a full compile, a retained program is saved to disk before it is reused.
	 *
	 * <p>Each tier only runs when the one before it has nothing. A kept program therefore costs neither a
	 * disk read nor a shader object, and a saved binary costs no compile.</p>
	 *
	 * @param fallback the disk and link tiers; production passes {@link ProgramFallbackImpl}
	 * @return the program, never 0
	 */
	static int acquire(String name, RetainedPrograms.Sources sources, ProgramFallback fallback) {
		return acquire(name, sources, fallback, RetainedPrograms.currentShareGroupId());
	}

	private static int acquire(String name, RetainedPrograms.Sources sources, ProgramFallback fallback,
								long ownerShareGroupId) {
		final int retained = RetainedPrograms.take(ownerShareGroupId, sources);
		final ProgramBinaryCache.Key cacheKey = retained != 0 && !ProgramBinaryCache.isFullCompileRunning()
			? null : fallback.cacheKey(sources);
		if (retained != 0) {
			if (cacheKey == null || ProgramBinaryCache.saveRetained(cacheKey, retained)) {
				return retained;
			}
			fallback.deleteProgram(retained);
		}

		final int restored = fallback.restore(name, cacheKey);
		if (restored != 0) {
			return restored;
		}

		return fallback.compile(name, cacheKey, sources);
	}

	public Program build() {
		ensureWrapperNotBuilt();
		final ProgramUniforms uniforms = super.buildUniforms();
		final ProgramSamplers programSamplers = this.samplers.build();
		final ProgramImages programImages = this.images.build();
		final Program wrapper = new Program(program, sources, ownerShareGroupId, ownerConfigurationEpoch,
			uniforms, programSamplers, programImages);
		ownership.setWrapper(wrapper);
		wrapperBuilt = true;
		return wrapper;
	}

	public ComputeProgram buildCompute() {
		ensureWrapperNotBuilt();
		final ProgramUniforms uniforms = super.buildUniforms();
		final ProgramSamplers programSamplers = this.samplers.build();
		final ProgramImages programImages = this.images.build();
		final ComputeProgram wrapper = new ComputeProgram(program, sources, ownerShareGroupId, ownerConfigurationEpoch,
			uniforms, programSamplers, programImages);
		ownership.setWrapper(wrapper);
		wrapperBuilt = true;
		return wrapper;
	}

	private void ensureWrapperNotBuilt() {
		if (wrapperBuilt) {
			throw new IllegalStateException("This program builder already built a wrapper");
		}
		RetainedPrograms.requireCurrentShareGroup(ownerShareGroupId);
		ownership.program();
	}

	/** Transfers a completed wrapper to its pass, pipeline or renderer. */
	public void commit() {
		if (!wrapperBuilt) {
			throw new IllegalStateException("A program builder cannot transfer ownership before building its wrapper");
		}
		ownership.transfer();
	}

	/** Deletes an unbuilt program or returns a completed wrapper's program to the retained pool. */
	@Override
	public void close() {
		ownership.close();
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
