// This file is based on code from Sodium by JellySquid, licensed under the LGPLv3 license.

package net.coderbot.iris.gl.shader;

import com.gtnewhorizons.angelica.glsm.GLDebug;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.KHRDebug;

import java.util.function.IntConsumer;

public class ProgramCreator {

	private static final Logger LOGGER = LogManager.getLogger("ProgramCreator");

	public static final int MC_ENTITY = 11;
	public static final int MC_MID_TEX_COORD = 12;
	public static final int AT_TANGENT = 13;
	public static final int AT_MIDBLOCK = 14;

	// TODO: This is *really* hardcoded, we need to refactor this to support external calls to glBindAttribLocation
	// One table, used both for the key and for the bindings, so a saved binary can never come back with a
	// different attribute layout than the program it is restored as.
	private static final String[] ATTRIBUTE_NAMES = { "mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock" };
	private static final int[] ATTRIBUTE_LOCATIONS = { MC_ENTITY, MC_MID_TEX_COORD, AT_TANGENT, AT_MIDBLOCK };

	/**
	 * {@return a key identifying a program by the attribute bindings {@link #create} applies, or null
	 * when saved programs are not in use}
	 */
	@Nullable
	public static ProgramBinaryCache.Key cacheKey() {
		if (!ProgramBinaryCache.isEnabled()) return null;
		final ProgramBinaryCache.Key key = ProgramBinaryCache.key();
		for (int i = 0; i < ATTRIBUTE_NAMES.length; i++) {
			key.attribute(ATTRIBUTE_NAMES[i], ATTRIBUTE_LOCATIONS[i]);
		}
		return key;
	}

	/**
	 * Restores the program stored under {@code cacheKey}.
	 *
	 * @return a program restored from disk, or 0 when none was stored under {@code cacheKey}
	 */
	public static int load(String name, ProgramBinaryCache.Key cacheKey) {
		final int program = ProgramBinaryCache.load(cacheKey);
		return nameLoadedProgram(program,
			loadedProgram -> GLDebug.nameObject(KHRDebug.GL_PROGRAM, loadedProgram, name),
			GLStateManager::glDeleteProgram);
	}

	/** Names a restored program and releases it if naming fails before ownership can be returned. */
	static int nameLoadedProgram(int program, IntConsumer nameObject, IntConsumer deleteProgram) {
		if (program == 0) {
			return 0;
		}

		try {
			nameObject.accept(program);
		} catch (RuntimeException | Error failure) {
			try {
				deleteProgram.accept(program);
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}

		return program;
	}

	public static int create(String name, GlShader... shaders) {
		return create(name, null, shaders);
	}

	public static int create(String name, @Nullable ProgramBinaryCache.Key cacheKey, GlShader... shaders) {
		return create(name, cacheKey, ATTRIBUTE_NAMES, ATTRIBUTE_LOCATIONS, shaders);
	}

	/** Links shader stages with caller-owned attribute bindings that do not use Iris's cached layout. */
	public static int createWithAttributeBindings(String name, String[] attributeNames, int[] attributeLocations,
												 GlShader... shaders) {
		return create(name, null, attributeNames, attributeLocations, shaders);
	}

	private static int create(String name, @Nullable ProgramBinaryCache.Key cacheKey, String[] attributeNames,
						  int[] attributeLocations, GlShader... shaders) {
		if (attributeNames.length != attributeLocations.length) {
			throw new IllegalArgumentException("Attribute names and locations must have the same length");
		}
		int program = GLStateManager.glCreateProgram();

		try {
			for (int i = 0; i < attributeNames.length; i++) {
				RenderSystem.bindAttributeLocation(program, attributeLocations[i], attributeNames[i]);
			}

			for (GlShader shader : shaders) {
				GLStateManager.glAttachShader(program, shader.getHandle());
			}

			GLStateManager.glLinkProgram(program);

			GLDebug.nameObject(KHRDebug.GL_PROGRAM, program, name);

			//Always detach shaders according to https://www.khronos.org/opengl/wiki/Shader_Compilation#Cleanup
			for (GlShader shader : shaders) {
				RenderSystem.detachShader(program, shader.getHandle());
			}

			String log = RenderSystem.getProgramInfoLog(program);

			if (!log.isEmpty()) {
				LOGGER.warn("Program link log for " + name + ": " + log);
			}

			int result = GLStateManager.glGetProgrami(program, GL20.GL_LINK_STATUS);

			if (result != GL11.GL_TRUE) {
				throw new RuntimeException("Shader program linking failed, see log for details");
			}

			if (cacheKey != null) {
				ProgramBinaryCache.save(cacheKey, program);
			}

			return program;
		} catch (RuntimeException | Error failure) {
			// The caller never sees this name, so nothing else can delete what was created here.
			try {
				GLStateManager.glDeleteProgram(program);
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}
}
