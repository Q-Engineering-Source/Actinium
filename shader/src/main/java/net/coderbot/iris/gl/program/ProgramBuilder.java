package net.coderbot.iris.gl.program;

import com.google.common.collect.ImmutableSet;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import net.coderbot.iris.gl.image.ImageHolder;
import net.coderbot.iris.gl.sampler.GlSampler;
import net.coderbot.iris.gl.sampler.SamplerHolder;
import net.coderbot.iris.gl.shader.GlShader;
import net.coderbot.iris.gl.shader.ProgramCreator;
import net.coderbot.iris.gl.shader.ShaderType;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.coderbot.iris.gl.texture.TextureType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

public class ProgramBuilder extends ProgramUniforms.Builder implements SamplerHolder, ImageHolder {
	private final int program;
	private final ProgramSamplers.Builder samplers;
	private final ProgramImages.Builder images;

	private ProgramBuilder(String name, int program, ImmutableSet<Integer> reservedTextureUnits) {
		super(name, program);

		this.program = program;
		this.samplers = ProgramSamplers.builder(program, reservedTextureUnits);
		this.images = ProgramImages.builder(program);
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
		// The retained-program tier is looked up here before anything is read from disk: a program the
		// pipeline already linked for the same sources needs neither a restore nor a compile.
		final ProgramBinaryCache.Key cacheKey = ProgramCreator.cacheKey();
		if (cacheKey != null) {
			cacheKey.stage(ShaderType.VERTEX.id, vertexSource);
			if (geometrySource != null) cacheKey.stage(ShaderType.GEOMETRY.id, geometrySource);
			if (tessControlSource != null) cacheKey.stage(ShaderType.TESSELATION_CONTROL.id, tessControlSource);
			if (tessEvalSource != null) cacheKey.stage(ShaderType.TESSELATION_EVAL.id, tessEvalSource);
			cacheKey.stage(ShaderType.FRAGMENT.id, fragmentSource);
		}

		if (cacheKey != null) {
			final int saved = ProgramCreator.load(name, cacheKey);
			if (saved != 0) {
				return new ProgramBuilder(name, saved, reservedTextureUnits);
			}
		}

		// Everything from here to the link is the work a restored binary replaces, the stage compilations
		// included, so this is what the program cache reports it saved.
		final long buildStartNanos = cacheKey != null ? System.nanoTime() : 0L;
		GlShader vertex = buildShader(ShaderType.VERTEX, name + ".vsh", vertexSource);
		GlShader geometry = geometrySource != null ? buildShader(ShaderType.GEOMETRY, name + ".gsh", geometrySource) : null;
		GlShader tessControl = tessControlSource != null ? buildShader(ShaderType.TESSELATION_CONTROL, name + ".tcs", tessControlSource) : null;
		GlShader tessEval = tessEvalSource != null ? buildShader(ShaderType.TESSELATION_EVAL, name + ".tes", tessEvalSource) : null;
		GlShader fragment = buildShader(ShaderType.FRAGMENT, name + ".fsh", fragmentSource);

		List<GlShader> shaders = new ArrayList<>();
		shaders.add(vertex);
		if (geometry != null) shaders.add(geometry);
		if (tessControl != null) shaders.add(tessControl);
		if (tessEval != null) shaders.add(tessEval);
		shaders.add(fragment);

		int programId = ProgramCreator.create(name, cacheKey, shaders.toArray(new GlShader[0]));

		for (GlShader shader : shaders) {
			shader.destroy();
		}

		if (buildStartNanos != 0L) {
			ProgramBinaryCache.recordMissBuild(System.nanoTime() - buildStartNanos);
		}

		return new ProgramBuilder(name, programId, reservedTextureUnits);
	}

	public static ProgramBuilder beginCompute(String name, @Nullable String source, ImmutableSet<Integer> reservedTextureUnits) {
		if (!RenderSystem.supportsCompute()) {
			throw new IllegalStateException("This PC does not support compute shaders, but it's attempting to be used???");
		}

		final ProgramBinaryCache.Key cacheKey = ProgramCreator.cacheKey();
		if (cacheKey != null) {
			cacheKey.stage(ShaderType.COMPUTE.id, source);
		}

		if (cacheKey != null) {
			final int saved = ProgramCreator.load(name, cacheKey);
			if (saved != 0) {
				return new ProgramBuilder(name, saved, reservedTextureUnits);
			}
		}

		final long buildStartNanos = cacheKey != null ? System.nanoTime() : 0L;
		GlShader compute = buildShader(ShaderType.COMPUTE, name + ".csh", source);

		int programId = ProgramCreator.create(name, cacheKey, compute);

		compute.destroy();

		if (buildStartNanos != 0L) {
			ProgramBinaryCache.recordMissBuild(System.nanoTime() - buildStartNanos);
		}

		return new ProgramBuilder(name, programId, reservedTextureUnits);
	}

	public Program build() {
		return new Program(program, super.buildUniforms(), this.samplers.build(), this.images.build());
	}

	public ComputeProgram buildCompute() {
		return new ComputeProgram(program, super.buildUniforms(), this.samplers.build(), this.images.build());
	}

	private static GlShader buildShader(ShaderType shaderType, String name, @Nullable String source) {
		try {
			return new GlShader(shaderType, name, source);
		} catch (RuntimeException e) {
			throw new RuntimeException("Failed to compile " + shaderType + " shader for program " + name, e);
		}
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
