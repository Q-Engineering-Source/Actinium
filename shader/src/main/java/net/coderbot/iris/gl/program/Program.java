package net.coderbot.iris.gl.program;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import net.coderbot.iris.gl.GlResource;
import net.coderbot.iris.gl.blending.DepthColorStorage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

public final class Program extends GlResource {
	private final ProgramUniforms uniforms;
	private final ProgramSamplers samplers;
	private final ProgramImages images;
	/** The stages this program was linked from, or null when it must be deleted with its wrapper. */
	@Nullable
	private final RetainedPrograms.Sources sources;
	private final long ownerShareGroupId;
	private final long ownerConfigurationEpoch;

	Program(int program, @Nullable RetainedPrograms.Sources sources, long ownerShareGroupId,
			long ownerConfigurationEpoch, ProgramUniforms uniforms, ProgramSamplers samplers, ProgramImages images) {
		super(program);

		this.sources = sources;
		this.ownerShareGroupId = ownerShareGroupId;
		this.ownerConfigurationEpoch = ownerConfigurationEpoch;
		this.uniforms = uniforms;
		this.samplers = samplers;
		this.images = images;

		DepthColorStorage.registerOwnedProgram(program);
	}

	public void use() {
		RetainedPrograms.requireCurrentShareGroup(ownerShareGroupId);
		RenderSystem.memoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
		GLStateManager.glUseProgram(getGlId());

		uniforms.update();
		samplers.update();
		images.update();
	}

	public static void unbind() {
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		GLStateManager.glUseProgram(0);
	}

	@Override
    public void destroyInternal() {
		uniforms.removeListeners();
		samplers.removeListeners();
		DepthColorStorage.unregisterOwnedProgram(getGlId());

		if (sources != null) {
			// The program name outlives this wrapper: a later pipeline with the same stages takes it over.
			RetainedPrograms.retain(ownerShareGroupId, sources, getGlId(), ownerConfigurationEpoch);
		} else {
			RetainedPrograms.deleteProgram(ownerShareGroupId, getGlId());
		}
	}

	/**
	 * @return the OpenGL ID of this program.
	 * @deprecated this should be encapsulated eventually
	 */
	@Deprecated
	public int getProgramId() {
		return getGlId();
	}

	public int getActiveImages() {
		return images.getActiveImages();
	}
}
