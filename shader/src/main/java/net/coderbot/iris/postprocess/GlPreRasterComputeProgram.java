package net.coderbot.iris.postprocess;

import net.coderbot.iris.gl.program.ComputeProgram;
import net.coderbot.iris.pipeline.transform.PreRasterComputeDispatcher;
import net.coderbot.iris.pipeline.transform.RwImageStoreExtractor.RwExtractMode;
import net.coderbot.iris.uniforms.custom.CustomUniforms;

/**
 * A pre-raster compute program that dispatches through the GL context it was built on.
 *
 * <p>Handed out by {@link PreRasterComputeProgram#of}, so the renderers only ever name the interface.</p>
 */
final class GlPreRasterComputeProgram implements PreRasterComputeProgram {

	private final ComputeProgram program;
	private final CustomUniforms uniforms;

	GlPreRasterComputeProgram(ComputeProgram program, CustomUniforms uniforms) {
		this.program = program;
		this.uniforms = uniforms;
	}

	@Override
	public int getProgramId() {
		return program.getProgramId();
	}

	@Override
	public int dispatch(RwExtractMode mode, int cachedTargetSizeLoc, int width, int height) {
		return PreRasterComputeDispatcher.dispatch(program, mode, cachedTargetSizeLoc, width, height, uniforms);
	}

	@Override
	public void destroy() {
		program.destroy();
	}
}
