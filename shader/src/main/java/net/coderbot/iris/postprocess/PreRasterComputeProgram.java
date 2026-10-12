package net.coderbot.iris.postprocess;

import net.coderbot.iris.gl.program.ComputeProgram;
import net.coderbot.iris.pipeline.transform.PreRasterComputeDispatcher;
import net.coderbot.iris.pipeline.transform.RwImageStoreExtractor.RwExtractMode;
import net.coderbot.iris.uniforms.custom.CustomUniforms;

/**
 * One built pre-raster compute program: the compute pre-pass that a composite program's image writes
 * were extracted into because the SDL GPU backend cannot store images from a raster stage.
 *
 * <p>{@link PreRasterComputeStages} is the only driver of these, and it reaches the GL context through
 * this interface so that registration, dispatch, and teardown can be exercised without a context.</p>
 */
public interface PreRasterComputeProgram {

	/** {@return the GL program id, for logging} */
	int getProgramId();

	/**
	 * Runs the pre-pass once.
	 *
	 * @param mode the extraction mode the pre-pass was built for, which decides how it is dispatched
	 * @param cachedTargetSizeLoc the {@code _vg_target_size} location an earlier dispatch resolved, or
	 *                            {@link PreRasterComputeDispatcher#LOC_UNRESOLVED} when there is none yet
	 * @param width the composite pass's target width
	 * @param height the composite pass's target height
	 * @return the location to hand back on the next dispatch
	 */
	int dispatch(RwExtractMode mode, int cachedTargetSizeLoc, int width, int height);

	/** Releases the underlying GL program. */
	void destroy();

	/**
	 * {@return a pre-pass that runs on the GL context the program was built on}
	 *
	 * @param program the compiled pre-pass
	 * @param uniforms the pipeline's custom uniforms, which the pre-pass reads like the raster program does
	 */
	static PreRasterComputeProgram of(ComputeProgram program, CustomUniforms uniforms) {
		return new GlPreRasterComputeProgram(program, uniforms);
	}
}
