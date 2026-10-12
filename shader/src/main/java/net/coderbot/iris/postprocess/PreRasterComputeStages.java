package net.coderbot.iris.postprocess;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.coderbot.iris.pipeline.transform.PreRasterComputeDispatcher;
import net.coderbot.iris.pipeline.transform.RwImageStoreExtractor;
import net.coderbot.iris.pipeline.transform.RwImageStoreExtractor.RwExtractMode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the compute pre-passes that the SDL GPU backend forces composite programs to split out.
 *
 * <p>That backend cannot store to images from a raster stage, so {@code ShaderTransformer} strips the
 * image writes of a composite vertex or fragment shader and hands the stripped program the extracted
 * compute source as {@code PatchShaderType.COMPUTE}. Upstream runs each extracted pre-pass as a
 * pre-raster pass of the composite program it came from; this holds one built pre-pass per composite
 * pass that has one, hands out a slot for it, and dispatches it.</p>
 *
 * <p>A pass is registered while the renderer builds its programs, dispatched on every frame just
 * before that pass draws (the render targets it writes are bound as images, so the pass's own viewport
 * and matrices are the ones it needs), and released when the renderer is destroyed. The programs are
 * built by the renderer through {@link Factory}, because the sampler and image wiring differs between
 * the world composite and the shadow composite; the slot bookkeeping and the {@code _vg_target_size}
 * location cache stay here.</p>
 *
 * <p>Nothing registers on the GL backend: it stores to images directly and the extraction never runs,
 * so {@code PatchShaderType.COMPUTE} is absent and the pipeline behaves exactly as before.</p>
 */
public final class PreRasterComputeStages {

	/** The slot of a pass that has no pre-raster compute, i.e. what {@link #register} returns for one. */
	public static final int NONE = -1;

	/** One entry per registered pre-pass, indexed by the slot handed out at registration. */
	private final List<PreRasterComputeProgram> programs = new ArrayList<>();
	private final List<RwExtractMode> modes = new ArrayList<>();
	private final IntArrayList targetSizeLocations = new IntArrayList();

	/**
	 * Registers the pre-pass extracted for one composite pass.
	 *
	 * @param name the composite program's name, used to name the built pre-pass
	 * @param computeSource the source {@code ShaderTransformer} extracted, or null when the program had
	 *                      no stripped image writes
	 * @param factory builds the pre-pass through the renderer's own sampler and image wiring
	 * @return the slot to dispatch with, or {@link #NONE} when there is nothing to run
	 */
	public int register(String name, @Nullable String computeSource, Factory factory) {
		if (computeSource == null) {
			return NONE;
		}

		// Only the two composite sentinels are ours: CHUNK and VERTEX_REPLAY extractions are terrain
		// voxelization and per-draw vertex replay, which other parts of the pipeline drive.
		final RwExtractMode mode = RwImageStoreExtractor.parseSentinel(computeSource);
		if (mode != RwExtractMode.COMPOSITE_VSH && mode != RwExtractMode.COMPOSITE_FSH) {
			return NONE;
		}

		programs.add(factory.create(name, computeSource));
		modes.add(mode);
		targetSizeLocations.add(PreRasterComputeDispatcher.LOC_UNRESOLVED);
		return programs.size() - 1;
	}

	/**
	 * Dispatches one registered pre-pass.
	 *
	 * @param slot a slot returned by {@link #register}, or {@link #NONE} for a pass without one
	 * @param width the composite pass's target width
	 * @param height the composite pass's target height
	 */
	public void dispatch(int slot, int width, int height) {
		if (slot < 0 || slot >= programs.size()) {
			throw new IllegalStateException("Pre-raster compute slot " + slot + " is not registered (" + programs.size() + " registered)");
		}

		targetSizeLocations.set(slot, programs.get(slot).dispatch(modes.get(slot), targetSizeLocations.getInt(slot), width, height));
	}

	/** Releases every registered pre-pass and forgets its slot. */
	public void destroy() {
		for (PreRasterComputeProgram program : programs) {
			program.destroy();
		}
		programs.clear();
		modes.clear();
		targetSizeLocations.clear();
	}

	/** Builds one pre-pass, using the renderer's sampler and image wiring. */
	@FunctionalInterface
	public interface Factory {

		/**
		 * @param name the composite program's name
		 * @param computeSource the extracted compute source, including its {@code _vg_mode} sentinel
		 * @return the built pre-pass, which this class owns from then on
		 */
		PreRasterComputeProgram create(String name, String computeSource);
	}
}
