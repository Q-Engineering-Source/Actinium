package net.coderbot.iris.pipeline;

import net.coderbot.iris.debug.flight.GlFlightRecording;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import lombok.Getter;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.framebuffer.MinecraftFramebufferHelper;
import net.coderbot.iris.uniforms.SystemTimeUniforms;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;

public class PipelineManager {

	private static final long IDLE_PIPELINE_TIMEOUT_NS = 30_000_000_000L;
	private static final long EVICTION_SCAN_INTERVAL_NS = 1_000_000_000L;

	private final Function<String, WorldRenderingPipeline> pipelineFactory;
	private final Map<String, WorldRenderingPipeline> pipelinesPerDimension = new HashMap<>();
	private final Map<String, Long> pipelineLastUsedNs = new HashMap<>();
	private long lastEvictionScanNs;
	private WorldRenderingPipeline pipeline = new FixedFunctionWorldRenderingPipeline();
	private String lastPreparedDimension = null;
    @Getter
	private int versionCounterForSodiumShaderReload = 0;

	public PipelineManager(Function<String, WorldRenderingPipeline> pipelineFactory) {
		this.pipelineFactory = pipelineFactory;
		updateGlsmHookConsumers();
	}

	/**
	 * Only a DeferredWorldRenderingPipeline consumes the GLSM blend/program events with observable
	 * side effects; while fixed-function rendering is active the per-call snapshot/post work in
	 * GLStateManager is skipped entirely.
	 */
	private void updateGlsmHookConsumers() {
		GLSMHooks.consumersActive = pipeline instanceof DeferredWorldRenderingPipeline;
	}

	public WorldRenderingPipeline preparePipeline(String currentDimension) {
		// Portal-style mods (e.g. BetterPortals) legitimately render another dimension within the
		// same frame, so a dimension flip is not necessarily a world-load event: keep every
		// dimension's pipeline cached and just switch to it. Pipelines of dimensions that stop
		// being rendered (portal gone, dimension left behind) are destroyed once they have been
		// idle for IDLE_PIPELINE_TIMEOUT_NS so their render targets and programs are reclaimed;
		// shaderpack reload / world unload still tears down everything via destroyPipeline().
		long nowNs = System.nanoTime();
		evictIdlePipelines(currentDimension, nowNs);

		if (lastPreparedDimension != null && !lastPreparedDimension.equals(currentDimension)) {
			GlFlightRecording.dimensionChange(lastPreparedDimension, currentDimension);
		}
		lastPreparedDimension = currentDimension;

		if (!pipelinesPerDimension.containsKey(currentDimension)) {
			SystemTimeUniforms.COUNTER.reset();
			SystemTimeUniforms.TIMER.reset();

			Iris.logger.info("Creating pipeline for dimension '{}'", currentDimension);
			GlFlightRecording.beginPipelineCreate(currentDimension);
			pipeline = pipelineFactory.apply(currentDimension);
			MinecraftFramebufferHelper.restoreMainFramebuffer(true);
			pipelinesPerDimension.put(currentDimension, pipeline);
			GlFlightRecording.endPipelineCreate(currentDimension);
		} else {
			pipeline = pipelinesPerDimension.get(currentDimension);
		}
		pipelineLastUsedNs.put(currentDimension, nowNs);

		updateGlsmHookConsumers();
		return pipeline;
	}

	private void evictIdlePipelines(String activeDimension, long nowNs) {
		if (nowNs - lastEvictionScanNs < EVICTION_SCAN_INTERVAL_NS) {
			return;
		}
		lastEvictionScanNs = nowNs;

		Iterator<Entry<String, WorldRenderingPipeline>> iterator = pipelinesPerDimension.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry<String, WorldRenderingPipeline> entry = iterator.next();
			String dimensionName = entry.getKey();
			if (dimensionName.equals(activeDimension)) {
				continue;
			}
			Long lastUsedNs = pipelineLastUsedNs.get(dimensionName);
			if (lastUsedNs != null && nowNs - lastUsedNs <= IDLE_PIPELINE_TIMEOUT_NS) {
				continue;
			}

			GlFlightRecording.beginPipelineDestroy(dimensionName);
			Iris.logger.info("Destroying idle pipeline for dimension '{}'", dimensionName);
			resetTextureState();
			entry.getValue().destroy();
			MinecraftFramebufferHelper.restoreMainFramebuffer(true);
			GlFlightRecording.endPipelineDestroy(dimensionName);
			iterator.remove();
			pipelineLastUsedNs.remove(dimensionName);
		}
	}

	/**
	 * Returns whether the given pipeline is still held by the per-dimension cache. The terrain
	 * shader provider uses this to drop programs whose pipeline has been evicted or destroyed.
	 */
	public boolean isPipelineCached(WorldRenderingPipeline candidate) {
		return pipelinesPerDimension.containsValue(candidate);
	}

	@Nullable
	public WorldRenderingPipeline getPipelineNullable() {
		return pipeline;
	}

	public Optional<WorldRenderingPipeline> getPipeline() {
		return Optional.ofNullable(pipeline);
	}


	/**
	 * Destroys all the current pipelines.
	 *
	 * <p>This method is <b>EXTREMELY DANGEROUS!</b> It is a huge potential source of hard-to-trace inconsistencies
	 * in program state. You must make sure that you <i>immediately</i> re-prepare the pipeline after destroying
	 * it to prevent the program from falling into an inconsistent state.</p>
	 *
	 * <p>In particular, </p>
	 *
	 * @see <a href="https://github.com/IrisShaders/Iris/issues/1330">this GitHub issue</a>
	 */
	public void destroyPipeline() {
		for (Entry<String, WorldRenderingPipeline> entry : pipelinesPerDimension.entrySet()) {
			String dimensionName = entry.getKey();
			WorldRenderingPipeline pipeline = entry.getValue();
			GlFlightRecording.beginPipelineDestroy(dimensionName);
			Iris.logger.info("Destroying pipeline for dimension '{}'", dimensionName);
			resetTextureState();
			pipeline.destroy();
			MinecraftFramebufferHelper.restoreMainFramebuffer(true);
			GlFlightRecording.endPipelineDestroy(dimensionName);
		}

		pipelinesPerDimension.clear();
		pipelineLastUsedNs.clear();
		pipeline = null;
		lastPreparedDimension = null;
		versionCounterForSodiumShaderReload++;
		updateGlsmHookConsumers();

		// The lazy version-counter cleanup in the terrain shader provider only runs while
		// shaders are enabled; delete the chunk programs here so disabling shaders does not
		// strand them (and their references into the destroyed pipeline) until re-enable.
		deleteTerrainShaders(ShaderProviderHolder.getProvider());

		MinecraftFramebufferHelper.restoreMainFramebuffer(true);
	}

	static void deleteTerrainShaders(@Nullable ShaderProvider terrainShaderProvider) {
		if (terrainShaderProvider != null) {
			terrainShaderProvider.deleteShaders();
		}
	}

	private void resetTextureState() {
		// Unbind all textures
		//
		// This is necessary because we don't want destroyed render target textures to remain bound to certain texture
		// units. Vanilla appears to properly rebind all textures as needed, and we do so too, so this does not cause
		// issues elsewhere.
		//
		// Without this code, there will be weird issues when reloading certain shaderpacks.
		for (int i = 0; i < 16; i++) {
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + i);
			GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		}

		// Set the active texture unit to unit 0
		//
		// This seems to be what most code expects. It's a sane default in any case.
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
	}
}
