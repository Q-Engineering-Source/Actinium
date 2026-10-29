package net.coderbot.iris.compat.dh;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.coderbot.iris.gl.shader.GlShader;
import net.coderbot.iris.gl.shader.ProgramCreator;
import net.coderbot.iris.gl.shader.ShaderType;

import java.util.ArrayList;
import java.util.List;

/** Builds Distant Horizons programs and releases every shader object on both success and failure. */
final class IrisDhProgramCompiler {
	private IrisDhProgramCompiler() {}

	static int create(String name, String vertex, String tessControl, String tessEval, String geometry, String fragment,
				  String[] attributeNames, int[] attributeLocations) {
		final List<GlShader> shaders = new ArrayList<>();
		int program = 0;
		Throwable linkFailure = null;
		try {
			shaders.add(new GlShader(ShaderType.VERTEX, name + ".vsh", vertex));
			if (tessControl != null) {
				shaders.add(new GlShader(ShaderType.TESSELATION_CONTROL, name + ".tcs", tessControl));
			}
			if (tessEval != null) {
				shaders.add(new GlShader(ShaderType.TESSELATION_EVAL, name + ".tes", tessEval));
			}
			if (geometry != null) {
				shaders.add(new GlShader(ShaderType.GEOMETRY, name + ".gsh", geometry));
			}
			shaders.add(new GlShader(ShaderType.FRAGMENT, name + ".fsh", fragment));
			program = ProgramCreator.createWithAttributeBindings(name, attributeNames, attributeLocations,
				shaders.toArray(new GlShader[0]));
			return program;
		} catch (RuntimeException | Error failure) {
			linkFailure = failure;
			throw failure;
		} finally {
			final Throwable shaderCleanupFailure = destroyShaders(shaders);
			if (shaderCleanupFailure != null) {
				if (linkFailure != null) {
					linkFailure.addSuppressed(shaderCleanupFailure);
				} else {
					if (program != 0) {
						try {
							GLStateManager.glDeleteProgram(program);
						} catch (RuntimeException | Error programCleanupFailure) {
							shaderCleanupFailure.addSuppressed(programCleanupFailure);
						}
					}
					if (shaderCleanupFailure instanceof RuntimeException runtimeException) {
						throw runtimeException;
					}
					throw (Error) shaderCleanupFailure;
				}
			}
		}
	}

	private static Throwable destroyShaders(List<GlShader> shaders) {
		Throwable failure = null;
		for (GlShader shader : shaders) {
			try {
				shader.destroy();
			} catch (RuntimeException | Error cleanupFailure) {
				if (failure == null) {
					failure = cleanupFailure;
				} else {
					failure.addSuppressed(cleanupFailure);
				}
			}
		}
		return failure;
	}
}
