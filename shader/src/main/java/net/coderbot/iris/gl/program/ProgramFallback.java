package net.coderbot.iris.gl.program;

import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import org.jetbrains.annotations.Nullable;

/**
 * The two ways a program is obtained once {@link RetainedPrograms} keeps none for a set of stage sources:
 * restoring the binary a previous run saved, then compiling and linking.
 *
 * <p>This is the second and third tier of {@link ProgramBuilder#acquire}: a kept program needs neither a
 * restore nor a link, a restored binary replaces the link, and only a miss of both does the work a saved
 * binary was meant to save. The interface exists so that order - including the short circuits - is
 * exercisable without a GL context; {@link ProgramFallbackImpl} is the production implementation.</p>
 */
interface ProgramFallback {

	/**
	 * Builds the program-binary cache key for these stage sources.
	 *
	 * @return the key describing exactly the stages that will be linked, or null when saved programs are
	 *     switched off
	 */
	@Nullable
	ProgramBinaryCache.Key cacheKey(RetainedPrograms.Sources sources);

	/**
	 * Restores the program saved under {@code cacheKey}.
	 *
	 * @param cacheKey the key built by {@link #cacheKey}, or null when saved programs are off
	 * @return the restored program, or 0 when nothing usable was saved
	 */
	int restore(String name, @Nullable ProgramBinaryCache.Key cacheKey);

	/**
	 * Compiles and links the stages, and saves the linked program under {@code cacheKey} when there is one.
	 *
	 * @param cacheKey the key built by {@link #cacheKey}, or null when saved programs are off
	 * @return the new program
	 */
	int compile(String name, @Nullable ProgramBinaryCache.Key cacheKey, RetainedPrograms.Sources sources);

	/** Deletes a program whose ownership did not reach a completed wrapper. */
	void deleteProgram(int program);
}
