package net.coderbot.iris.gl.program;

/**
 * The single OpenGL call {@link RetainedPrograms} makes: deleting a program object the pool drops.
 *
 * <p>The pool is otherwise plain Java bookkeeping, and deleting a program is the only part that needs a
 * context. Keeping it behind a port - the same split {@code ProgramBinaryOps} uses for the program-binary
 * cache - lets eviction, retirement and the configuration gate be exercised without a GL context.</p>
 */
public interface ProgramDeleter {
	/** Deletes the program object named by {@code program}. A zero name is ignored. */
	void deleteProgram(int program);
}
