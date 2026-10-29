package net.coderbot.iris.gl.program;

import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.coderbot.iris.gl.shader.GlShader;
import net.coderbot.iris.gl.shader.ProgramCreator;
import net.coderbot.iris.gl.shader.ShaderType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The production {@link ProgramFallback}: the program-binary cache for a saved binary, then a normal compile
 * and link of the stages.
 *
 * <p>The cache key is built once per program so the entry that is restored is the entry a miss saves: it
 * covers the stage sources and the attribute bindings {@link ProgramCreator} applies, but not the pack
 * settings, which reach the key through the final source text.</p>
 */
final class ProgramFallbackImpl implements ProgramFallback {

	@Override
	@Nullable
	public ProgramBinaryCache.Key cacheKey(RetainedPrograms.Sources sources) {
		final ProgramBinaryCache.Key cacheKey = ProgramCreator.cacheKey();
		if (cacheKey == null) {
			return null;
		}

		if (sources.compute() != null) {
			cacheKey.stage(ShaderType.COMPUTE.id, sources.compute());
			return cacheKey;
		}

		cacheKey.stage(ShaderType.VERTEX.id, sources.vertex());
		if (sources.geometry() != null) cacheKey.stage(ShaderType.GEOMETRY.id, sources.geometry());
		if (sources.tessControl() != null) cacheKey.stage(ShaderType.TESSELATION_CONTROL.id, sources.tessControl());
		if (sources.tessEval() != null) cacheKey.stage(ShaderType.TESSELATION_EVAL.id, sources.tessEval());
		cacheKey.stage(ShaderType.FRAGMENT.id, sources.fragment());

		return cacheKey;
	}

	@Override
	public int restore(String name, @Nullable ProgramBinaryCache.Key cacheKey) {
		if (cacheKey == null) {
			return 0;
		}

		return ProgramCreator.load(name, cacheKey);
	}

	@Override
	public int compile(String name, @Nullable ProgramBinaryCache.Key cacheKey, RetainedPrograms.Sources sources) {
		// Everything from here to the link is the work a kept or restored program replaces, the stage
		// compilations included, so this is what the program cache reports it saved.
		final long buildStartNanos = cacheKey != null ? System.nanoTime() : 0L;

		final List<GlShader> shaders = new ArrayList<>();
		int programId = 0;
		Throwable compileFailure = null;
		try {
			if (sources.compute() != null) {
				shaders.add(buildShader(ShaderType.COMPUTE, name + ".csh", sources.compute()));
			} else {
				shaders.add(buildShader(ShaderType.VERTEX, name + ".vsh", sources.vertex()));
				if (sources.geometry() != null) {
					shaders.add(buildShader(ShaderType.GEOMETRY, name + ".gsh", sources.geometry()));
				}
				if (sources.tessControl() != null) {
					shaders.add(buildShader(ShaderType.TESSELATION_CONTROL, name + ".tcs", sources.tessControl()));
				}
				if (sources.tessEval() != null) {
					shaders.add(buildShader(ShaderType.TESSELATION_EVAL, name + ".tes", sources.tessEval()));
				}
				shaders.add(buildShader(ShaderType.FRAGMENT, name + ".fsh", sources.fragment()));
			}

			programId = ProgramCreator.create(name, cacheKey, shaders.toArray(new GlShader[0]));
		} catch (RuntimeException | Error failure) {
			compileFailure = failure;
			throw failure;
		} finally {
			try {
				destroyShaders(shaders);
			} catch (RuntimeException | Error cleanupFailure) {
				if (compileFailure != null) {
					compileFailure.addSuppressed(cleanupFailure);
				} else {
					if (programId != 0) {
						try {
							deleteProgram(programId);
						} catch (RuntimeException | Error programCleanupFailure) {
							cleanupFailure.addSuppressed(programCleanupFailure);
						}
					}
					throw cleanupFailure;
				}
			}
		}

		if (buildStartNanos != 0L) {
			ProgramBinaryCache.recordMissBuild(System.nanoTime() - buildStartNanos);
		}

		return programId;
	}

	private static void destroyShaders(List<GlShader> shaders) {
		Throwable failure = null;
		for (GlShader shader : shaders) {
			try {
				shader.destroy();
			} catch (RuntimeException | Error exception) {
				if (failure == null) {
					failure = exception;
				} else {
					failure.addSuppressed(exception);
				}
			}
		}
		if (failure instanceof RuntimeException runtimeException) {
			throw runtimeException;
		}
		if (failure instanceof Error error) {
			throw error;
		}
	}

	@Override
	public void deleteProgram(int program) {
		GLStateManager.glDeleteProgram(program);
	}

	private static GlShader buildShader(ShaderType shaderType, String name, @Nullable String source) {
		try {
			return new GlShader(shaderType, name, source);
		} catch (RuntimeException e) {
			throw new RuntimeException("Failed to compile " + shaderType + " shader for program " + name, e);
		}
	}
}
