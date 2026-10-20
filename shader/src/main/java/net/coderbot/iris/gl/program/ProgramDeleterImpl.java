package net.coderbot.iris.gl.program;

import com.gtnewhorizons.angelica.glsm.GLStateManager;

/**
 * The production {@link ProgramDeleter}: every deletion lands on {@link GLStateManager#glDeleteProgram},
 * so the state manager keeps its program cache and its program-delete hooks in step.
 */
final class ProgramDeleterImpl implements ProgramDeleter {

	@Override
	public void deleteProgram(int program) {
		GLStateManager.glDeleteProgram(program);
	}
}
