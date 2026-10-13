package net.coderbot.iris.debug;

import net.coderbot.iris.debug.flight.GlFlightRecording;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandType;
import net.coderbot.iris.Iris;
import net.coderbot.iris.compat.dh.DHCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.coderbot.iris.rendertarget.RenderTarget;
import net.coderbot.iris.rendertarget.RenderTargets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexAttribute;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class IrisGlDebug {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumGLDebug");
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(4);
    private static final Set<String> LOGGED_CELERITAS_PROGRAMS = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_CELERITAS_STATES = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_FULLSCREEN_PROGRAMS = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_FULLSCREEN_STATES = ConcurrentHashMap.newKeySet();
	private static final Map<String, Integer> FRAMEBUFFER_SAMPLE_COUNTS = new ConcurrentHashMap<>();
	private static final Map<String, Integer> TEXTURE_SAMPLE_COUNTS = new ConcurrentHashMap<>();
	private static final Map<String, Integer> MATERIAL_SAMPLE_COUNTS = new ConcurrentHashMap<>();
	private static final Map<String, Integer> ENTITY_PHASE_SAMPLE_COUNTS = new ConcurrentHashMap<>();
	private static final Map<String, Integer> SAMPLER_INIT_COUNTS = new ConcurrentHashMap<>();
	private static final Map<String, Integer> GLSM_EVENT_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> CLOUD_CONTROL_SAMPLE_COUNTS = new ConcurrentHashMap<>();
	private static final Map<String, WorldPassStageTiming> WORLD_PASS_STAGE_TIMINGS = new ConcurrentHashMap<>();
	private static final Map<String, WorldPassStageTiming> RENDER_GLOBAL_STAGE_TIMINGS = new ConcurrentHashMap<>();
	private static final Map<String, RenderGlobalCounterTiming> RENDER_GLOBAL_COUNTER_TIMINGS = new ConcurrentHashMap<>();
	private static final Map<String, TerrainRendererTiming> TERRAIN_RENDERER_TIMINGS = new ConcurrentHashMap<>();
	private static final Map<String, Long> WHITE_SCREEN_PROBE_TIMES = new ConcurrentHashMap<>();
	private static final Map<String, Integer> WHITE_SCREEN_PROBE_COUNTS = new ConcurrentHashMap<>();
	private static final boolean ENABLE_TEXTURE_UNIT_LOGS = Boolean.getBoolean("actinium.debug.textureUnitLogs");
	private static final boolean ENABLE_PORTAL_RENDER_LOGS = Boolean.getBoolean("actinium.debug.portalRenderLogs");
	private static long lastShadowEntityLogTime;
	private static long lastShadowPassLogTime;
    private static long compositeTimingWindowStart;
    private static int compositeTimingFrames;
    private static int compositeTimingGpuFrames;
    private static long compositeTimingCpuTotal;
    private static long compositeTimingCpuCenterDepth;
    private static long compositeTimingCpuComposite;
    private static long compositeTimingCpuFinal;
    private static long compositeTimingCpuMax;
    private static long compositeTimingGpuTotal;
    private static long compositeTimingGpuComposite;
    private static long compositeTimingGpuFinal;
    private static long compositeTimingGpuMax;
    private static long frameOutputTimingWindowStart;
    private static int frameOutputTimingFrames;
    private static int frameOutputTimingGpuFrames;
    private static long frameOutputTimingCpuTotal;
    private static long frameOutputTimingCpuMax;
    private static long frameOutputTimingGpuTotal;
    private static long frameOutputTimingGpuMax;
    private static long frameRenderTimingWindowStart;
    private static int frameRenderTimingFrames;
    private static int frameRenderTimingGpuFrames;
    private static long frameRenderTimingCpuTotal;
	private static long frameRenderTimingCpuMax;
	private static long frameRenderTimingGpuTotal;
	private static long frameRenderTimingGpuMax;
	private static long gameLoopTimingWindowStart;
	private static int gameLoopTimingFrames;
	private static final Map<String, WorldPassStageTiming> GAME_LOOP_STAGE_TIMINGS = new ConcurrentHashMap<>();
	private static long worldPassTimingWindowStart;
    private static long worldPassStageStartNanos;
    private static String worldPassCurrentStage;
    private static int worldPassCurrentPass;
    private static long renderGlobalTimingWindowStart;
    private static long renderGlobalCounterTimingWindowStart;
    private static long terrainRendererTimingWindowStart;
    private static String lastStage = "startup";
    private static String framebufferSamplePhase;
    private static long lastLogTime;
    private static int debugFramebuffer;

    private IrisGlDebug() {
    }

    public static boolean isEnabled() {
		String override = System.getProperty("actinium.glDebug");
		if (override != null) {
			return Boolean.parseBoolean(override);
		}

        try {
            return IrisDebugOptions.enableActiniumGlDebug();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean isCloudControlDebugEnabled() {
        String override = System.getProperty("actinium.cloudControlDebug");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }

        try {
            return IrisDebugOptions.enableCloudControlDebug();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void logDebugInfo(String message, Object... params) {
		if (shouldCaptureGlState()) {
			LOGGER.info(message, params);
		}
	}

	public static boolean shouldLogGlsmEvent(String label, int maxCount) {
		if (!shouldCaptureGlState()) {
			return false;
		}

		int count = GLSM_EVENT_COUNTS.merge(label, 1, Integer::sum);
		return count <= maxCount;
	}

	public static boolean shouldLogTextureUnitEvents() {
		return ENABLE_TEXTURE_UNIT_LOGS;
	}

	public static boolean shouldLogPortalRenderEvents() {
		return ENABLE_PORTAL_RENDER_LOGS;
	}

	public static void beginWorldPassTiming(int pass) {
		if (!shouldCapturePerfTiming()) {
			worldPassCurrentStage = null;
			return;
		}

		worldPassCurrentPass = pass;
		worldPassCurrentStage = "start";
		worldPassStageStartNanos = System.nanoTime();
	}

	public static void recordWorldPassStage(String nextStage) {
		if (!shouldCapturePerfTiming() || worldPassCurrentStage == null) {
			return;
		}

		long nowNanos = System.nanoTime();
		recordWorldPassStageTiming(worldPassCurrentStage, nowNanos - worldPassStageStartNanos);
		worldPassCurrentStage = nextStage;
		worldPassStageStartNanos = nowNanos;
	}

	public static void finishWorldPassTiming() {
		if (!shouldCapturePerfTiming() || worldPassCurrentStage == null) {
			return;
		}

		long nowNanos = System.nanoTime();
		recordWorldPassStageTiming(worldPassCurrentStage, nowNanos - worldPassStageStartNanos);
		worldPassCurrentStage = null;
		logWorldPassStageTimingIfReady(nowNanos);
	}

	private static void recordWorldPassStageTiming(String stage, long nanos) {
		String key = "pass" + worldPassCurrentPass + ":" + stage;
		WORLD_PASS_STAGE_TIMINGS.computeIfAbsent(key, ignored -> new WorldPassStageTiming()).add(nanos);
	}

	private static void logWorldPassStageTimingIfReady(long nowNanos) {
		long nowMillis = System.currentTimeMillis();
		if (worldPassTimingWindowStart == 0L) {
			worldPassTimingWindowStart = nowMillis;
		}

		if (nowMillis - worldPassTimingWindowStart < 1000L || WORLD_PASS_STAGE_TIMINGS.isEmpty()) {
			return;
		}

		String timings = WORLD_PASS_STAGE_TIMINGS.entrySet().stream()
			.sorted(Comparator.comparingLong((Map.Entry<String, WorldPassStageTiming> entry) -> entry.getValue().totalNanos).reversed())
			.map(entry -> entry.getKey() + "[avg=" + formatNanos(entry.getValue().averageNanos()) + ",max=" + formatNanos(entry.getValue().maxNanos) + ",n=" + entry.getValue().samples + "]")
			.collect(Collectors.joining(" "));

		LOGGER.info("world-pass-stage-timing {}", timings);
		WORLD_PASS_STAGE_TIMINGS.clear();
		worldPassTimingWindowStart = nowMillis;
	}

	public static long beginRenderGlobalStageTiming() {
		return shouldCapturePerfTiming() ? System.nanoTime() : 0L;
	}

	public static void recordRenderGlobalStageTiming(String stage, int pass, long startNanos) {
		if (!shouldCapturePerfTiming() || startNanos == 0L) {
			return;
		}

		long nowNanos = System.nanoTime();
		String key = "pass" + pass + ":" + stage;
		RENDER_GLOBAL_STAGE_TIMINGS.computeIfAbsent(key, ignored -> new WorldPassStageTiming()).add(nowNanos - startNanos);
		logRenderGlobalStageTimingIfReady();
	}

	private static void logRenderGlobalStageTimingIfReady() {
		long nowMillis = System.currentTimeMillis();
		if (renderGlobalTimingWindowStart == 0L) {
			renderGlobalTimingWindowStart = nowMillis;
		}

		if (nowMillis - renderGlobalTimingWindowStart < 1000L || RENDER_GLOBAL_STAGE_TIMINGS.isEmpty()) {
			return;
		}

		String timings = RENDER_GLOBAL_STAGE_TIMINGS.entrySet().stream()
			.sorted(Comparator.comparingLong((Map.Entry<String, WorldPassStageTiming> entry) -> entry.getValue().totalNanos).reversed())
			.map(entry -> entry.getKey() + "[avg=" + formatNanos(entry.getValue().averageNanos()) + ",max=" + formatNanos(entry.getValue().maxNanos) + ",n=" + entry.getValue().samples + "]")
			.collect(Collectors.joining(" "));

		LOGGER.info("render-global-stage-timing {}", timings);
		RENDER_GLOBAL_STAGE_TIMINGS.clear();
		renderGlobalTimingWindowStart = nowMillis;
	}

	public static void recordRenderGlobalCounterTiming(
		String stage,
		int pass,
		long nanos,
		int checked,
		int visible,
		int rendered,
		int outlined,
		int multipass
	) {
		if (!shouldCapturePerfTiming()) {
			return;
		}

		String key = "pass" + pass + ":" + stage;
		RENDER_GLOBAL_COUNTER_TIMINGS.computeIfAbsent(key, ignored -> new RenderGlobalCounterTiming())
			.add(nanos, checked, visible, rendered, outlined, multipass);
		logRenderGlobalCounterTimingIfReady();
	}

	private static void logRenderGlobalCounterTimingIfReady() {
		long nowMillis = System.currentTimeMillis();
		if (renderGlobalCounterTimingWindowStart == 0L) {
			renderGlobalCounterTimingWindowStart = nowMillis;
		}

		if (nowMillis - renderGlobalCounterTimingWindowStart < 1000L || RENDER_GLOBAL_COUNTER_TIMINGS.isEmpty()) {
			return;
		}

		String timings = RENDER_GLOBAL_COUNTER_TIMINGS.entrySet().stream()
			.sorted(Comparator.comparingLong((Map.Entry<String, RenderGlobalCounterTiming> entry) -> entry.getValue().totalNanos).reversed())
			.map(entry -> entry.getKey()
				+ "[avg=" + formatNanos(entry.getValue().averageNanos())
				+ ",max=" + formatNanos(entry.getValue().maxNanos)
				+ ",n=" + entry.getValue().samples
				+ ",checked=" + entry.getValue().averageChecked()
				+ ",visible=" + entry.getValue().averageVisible()
				+ ",rendered=" + entry.getValue().averageRendered()
				+ ",outlined=" + entry.getValue().averageOutlined()
				+ ",multipass=" + entry.getValue().averageMultipass()
				+ "]")
			.collect(Collectors.joining(" "));

		LOGGER.info("render-global-counter-timing {}", timings);
		RENDER_GLOBAL_COUNTER_TIMINGS.clear();
		renderGlobalCounterTimingWindowStart = nowMillis;
	}

	public static void recordTerrainRendererTiming(
		String passName,
		long totalNanos,
		long fillNanos,
		long tessellationNanos,
		long uniformsNanos,
		long drawNanos,
		int regions,
		int batches,
		int commands
	) {
		if (!shouldCapturePerfTiming()) {
			return;
		}

		TERRAIN_RENDERER_TIMINGS.computeIfAbsent(passName, ignored -> new TerrainRendererTiming())
			.add(totalNanos, fillNanos, tessellationNanos, uniformsNanos, drawNanos, regions, batches, commands);
		logTerrainRendererTimingIfReady();
	}

	private static void logTerrainRendererTimingIfReady() {
		long nowMillis = System.currentTimeMillis();
		if (terrainRendererTimingWindowStart == 0L) {
			terrainRendererTimingWindowStart = nowMillis;
		}

		if (nowMillis - terrainRendererTimingWindowStart < 1000L || TERRAIN_RENDERER_TIMINGS.isEmpty()) {
			return;
		}

		String timings = TERRAIN_RENDERER_TIMINGS.entrySet().stream()
			.sorted(Comparator.comparingLong((Map.Entry<String, TerrainRendererTiming> entry) -> entry.getValue().totalNanos).reversed())
			.map(entry -> {
				TerrainRendererTiming value = entry.getValue();
				return entry.getKey()
					+ "[avg=" + formatNanos(value.averageNanos(value.totalNanos))
					+ ",fill=" + formatNanos(value.averageNanos(value.fillNanos))
					+ ",tess=" + formatNanos(value.averageNanos(value.tessellationNanos))
					+ ",uniforms=" + formatNanos(value.averageNanos(value.uniformsNanos))
					+ ",draw=" + formatNanos(value.averageNanos(value.drawNanos))
					+ ",max=" + formatNanos(value.maxNanos)
					+ ",n=" + value.samples
					+ ",regions=" + value.averageRegions()
					+ ",batches=" + value.averageBatches()
					+ ",commands=" + value.averageCommands()
					+ "]";
			})
			.collect(Collectors.joining(" "));

		LOGGER.info("terrain-renderer-timing {}", timings);
		TERRAIN_RENDERER_TIMINGS.clear();
		terrainRendererTimingWindowStart = nowMillis;
	}

	private static final class WorldPassStageTiming {
		private int samples;
		private long totalNanos;
		private long maxNanos;

		private void add(long nanos) {
			this.samples++;
			this.totalNanos += nanos;
			this.maxNanos = Math.max(this.maxNanos, nanos);
		}

		private long averageNanos() {
			return this.samples == 0 ? 0L : this.totalNanos / this.samples;
		}
	}

	private static final class RenderGlobalCounterTiming {
		private int samples;
		private long totalNanos;
		private long maxNanos;
		private long checked;
		private long visible;
		private long rendered;
		private long outlined;
		private long multipass;

		private void add(long nanos, int checked, int visible, int rendered, int outlined, int multipass) {
			this.samples++;
			this.totalNanos += nanos;
			this.maxNanos = Math.max(this.maxNanos, nanos);
			this.checked += checked;
			this.visible += visible;
			this.rendered += rendered;
			this.outlined += outlined;
			this.multipass += multipass;
		}

		private long averageNanos() {
			return this.samples == 0 ? 0L : this.totalNanos / this.samples;
		}

		private long averageChecked() {
			return this.samples == 0 ? 0L : this.checked / this.samples;
		}

		private long averageVisible() {
			return this.samples == 0 ? 0L : this.visible / this.samples;
		}

		private long averageRendered() {
			return this.samples == 0 ? 0L : this.rendered / this.samples;
		}

		private long averageOutlined() {
			return this.samples == 0 ? 0L : this.outlined / this.samples;
		}

		private long averageMultipass() {
			return this.samples == 0 ? 0L : this.multipass / this.samples;
		}
	}

	public static void logCompositeOutputTiming(
		long cpuTotalNanos,
		long cpuCenterDepthNanos,
		long cpuCompositeNanos,
		long cpuFinalNanos,
		long gpuTotalNanos,
		long gpuCompositeNanos,
		long gpuFinalNanos
	) {
		if (!shouldCapturePerfTiming()) {
			return;
		}

		long now = System.currentTimeMillis();
		if (compositeTimingWindowStart == 0L) {
			compositeTimingWindowStart = now;
		}

		compositeTimingFrames++;
		compositeTimingCpuTotal += cpuTotalNanos;
		compositeTimingCpuCenterDepth += cpuCenterDepthNanos;
		compositeTimingCpuComposite += cpuCompositeNanos;
		compositeTimingCpuFinal += cpuFinalNanos;
		compositeTimingCpuMax = Math.max(compositeTimingCpuMax, cpuTotalNanos);

		if (gpuTotalNanos > 0L) {
			compositeTimingGpuFrames++;
			compositeTimingGpuTotal += gpuTotalNanos;
			compositeTimingGpuComposite += Math.max(0L, gpuCompositeNanos);
			compositeTimingGpuFinal += Math.max(0L, gpuFinalNanos);
			compositeTimingGpuMax = Math.max(compositeTimingGpuMax, gpuTotalNanos);
		}

		if (now - compositeTimingWindowStart < 1000L) {
			return;
		}

		int frames = Math.max(1, compositeTimingFrames);
		int gpuFrames = Math.max(1, compositeTimingGpuFrames);
		LOGGER.info(
			"composite-output-timing frames={} gpuFrames={} cpuMs[avg={},max={},center={},composite={},final={}] gpuMs[avg={},max={},composite={},final={}]",
			compositeTimingFrames,
			compositeTimingGpuFrames,
			formatNanos(compositeTimingCpuTotal / frames),
			formatNanos(compositeTimingCpuMax),
			formatNanos(compositeTimingCpuCenterDepth / frames),
			formatNanos(compositeTimingCpuComposite / frames),
			formatNanos(compositeTimingCpuFinal / frames),
			compositeTimingGpuFrames == 0 ? "pending" : formatNanos(compositeTimingGpuTotal / gpuFrames),
			compositeTimingGpuFrames == 0 ? "pending" : formatNanos(compositeTimingGpuMax),
			compositeTimingGpuFrames == 0 ? "pending" : formatNanos(compositeTimingGpuComposite / gpuFrames),
			compositeTimingGpuFrames == 0 ? "pending" : formatNanos(compositeTimingGpuFinal / gpuFrames)
		);

		compositeTimingWindowStart = now;
		compositeTimingFrames = 0;
		compositeTimingGpuFrames = 0;
		compositeTimingCpuTotal = 0L;
		compositeTimingCpuCenterDepth = 0L;
		compositeTimingCpuComposite = 0L;
		compositeTimingCpuFinal = 0L;
		compositeTimingCpuMax = 0L;
		compositeTimingGpuTotal = 0L;
		compositeTimingGpuComposite = 0L;
		compositeTimingGpuFinal = 0L;
		compositeTimingGpuMax = 0L;
	}

	public static void logFrameOutputTiming(long cpuNanos, long gpuNanos) {
		if (!shouldCapturePerfTiming()) {
			return;
		}

		long now = System.currentTimeMillis();
		if (frameOutputTimingWindowStart == 0L) {
			frameOutputTimingWindowStart = now;
		}

		frameOutputTimingFrames++;
		frameOutputTimingCpuTotal += cpuNanos;
		frameOutputTimingCpuMax = Math.max(frameOutputTimingCpuMax, cpuNanos);

		if (gpuNanos > 0L) {
			frameOutputTimingGpuFrames++;
			frameOutputTimingGpuTotal += gpuNanos;
			frameOutputTimingGpuMax = Math.max(frameOutputTimingGpuMax, gpuNanos);
		}

		if (now - frameOutputTimingWindowStart < 1000L) {
			return;
		}

		int frames = Math.max(1, frameOutputTimingFrames);
		int gpuFrames = Math.max(1, frameOutputTimingGpuFrames);
		LOGGER.info(
			"frame-output-timing frames={} gpuFrames={} cpuMs[avg={},max={}] gpuMs[avg={},max={}]",
			frameOutputTimingFrames,
			frameOutputTimingGpuFrames,
			formatNanos(frameOutputTimingCpuTotal / frames),
			formatNanos(frameOutputTimingCpuMax),
			frameOutputTimingGpuFrames == 0 ? "pending" : formatNanos(frameOutputTimingGpuTotal / gpuFrames),
			frameOutputTimingGpuFrames == 0 ? "pending" : formatNanos(frameOutputTimingGpuMax)
		);

		frameOutputTimingWindowStart = now;
		frameOutputTimingFrames = 0;
		frameOutputTimingGpuFrames = 0;
		frameOutputTimingCpuTotal = 0L;
		frameOutputTimingCpuMax = 0L;
		frameOutputTimingGpuTotal = 0L;
		frameOutputTimingGpuMax = 0L;
	}

	public static void logFrameRenderTiming(long cpuNanos, long gpuNanos) {
		if (!shouldCapturePerfTiming()) {
			return;
		}

		long now = System.currentTimeMillis();
		if (frameRenderTimingWindowStart == 0L) {
			frameRenderTimingWindowStart = now;
		}

		frameRenderTimingFrames++;
		frameRenderTimingCpuTotal += cpuNanos;
		frameRenderTimingCpuMax = Math.max(frameRenderTimingCpuMax, cpuNanos);

		if (gpuNanos > 0L) {
			frameRenderTimingGpuFrames++;
			frameRenderTimingGpuTotal += gpuNanos;
			frameRenderTimingGpuMax = Math.max(frameRenderTimingGpuMax, gpuNanos);
		}

		if (now - frameRenderTimingWindowStart < 1000L) {
			return;
		}

		int frames = Math.max(1, frameRenderTimingFrames);
		int gpuFrames = Math.max(1, frameRenderTimingGpuFrames);
		LOGGER.info(
			"frame-render-timing frames={} gpuFrames={} cpuMs[avg={},max={}] gpuMs[avg={},max={}]",
			frameRenderTimingFrames,
			frameRenderTimingGpuFrames,
			formatNanos(frameRenderTimingCpuTotal / frames),
			formatNanos(frameRenderTimingCpuMax),
			frameRenderTimingGpuFrames == 0 ? "pending" : formatNanos(frameRenderTimingGpuTotal / gpuFrames),
			frameRenderTimingGpuFrames == 0 ? "pending" : formatNanos(frameRenderTimingGpuMax)
		);

		frameRenderTimingWindowStart = now;
		frameRenderTimingFrames = 0;
		frameRenderTimingGpuFrames = 0;
		frameRenderTimingCpuTotal = 0L;
		frameRenderTimingCpuMax = 0L;
		frameRenderTimingGpuTotal = 0L;
		frameRenderTimingGpuMax = 0L;
	}

	private static final class TerrainRendererTiming {
		private int samples;
		private long totalNanos;
		private long fillNanos;
		private long tessellationNanos;
		private long uniformsNanos;
		private long drawNanos;
		private long maxNanos;
		private long regions;
		private long batches;
		private long commands;

		private void add(long totalNanos, long fillNanos, long tessellationNanos, long uniformsNanos, long drawNanos, int regions, int batches, int commands) {
			this.samples++;
			this.totalNanos += totalNanos;
			this.fillNanos += fillNanos;
			this.tessellationNanos += tessellationNanos;
			this.uniformsNanos += uniformsNanos;
			this.drawNanos += drawNanos;
			this.maxNanos = Math.max(this.maxNanos, totalNanos);
			this.regions += regions;
			this.batches += batches;
			this.commands += commands;
		}

		private long averageNanos(long nanos) {
			return this.samples == 0 ? 0L : nanos / this.samples;
		}

		private long averageRegions() {
			return this.samples == 0 ? 0L : this.regions / this.samples;
		}

		private long averageBatches() {
			return this.samples == 0 ? 0L : this.batches / this.samples;
		}

		private long averageCommands() {
			return this.samples == 0 ? 0L : this.commands / this.samples;
		}
	}

	public static long beginGameLoopStageTiming() {
		return shouldCapturePerfTiming() ? System.nanoTime() : 0L;
	}

	public static void recordGameLoopStageTiming(String stage, long startNanos) {
		if (!shouldCapturePerfTiming() || startNanos == 0L) {
			return;
		}

		GAME_LOOP_STAGE_TIMINGS.computeIfAbsent(stage, ignored -> new WorldPassStageTiming())
			.add(System.nanoTime() - startNanos);
		logGameLoopStageTimingIfReady();
	}

	public static void incrementGameLoopFrameCount() {
		if (shouldCapturePerfTiming()) {
			gameLoopTimingFrames++;
		}
	}

	private static void logGameLoopStageTimingIfReady() {
		long nowMillis = System.currentTimeMillis();
		if (gameLoopTimingWindowStart == 0L) {
			gameLoopTimingWindowStart = nowMillis;
		}

		if (nowMillis - gameLoopTimingWindowStart < 1000L || GAME_LOOP_STAGE_TIMINGS.isEmpty()) {
			return;
		}

		String timings = GAME_LOOP_STAGE_TIMINGS.entrySet().stream()
			.sorted(Comparator.comparingLong((Map.Entry<String, WorldPassStageTiming> entry) -> entry.getValue().totalNanos).reversed())
			.map(entry -> entry.getKey() + "[avg=" + formatNanos(entry.getValue().averageNanos()) + ",max=" + formatNanos(entry.getValue().maxNanos) + ",n=" + entry.getValue().samples + "]")
			.collect(Collectors.joining(" "));

		LOGGER.info("game-loop-stage-timing frames={} {}", gameLoopTimingFrames, timings);
		GAME_LOOP_STAGE_TIMINGS.clear();
		gameLoopTimingFrames = 0;
		gameLoopTimingWindowStart = nowMillis;
	}

	public static void checkDrawError(String stage, String source, int drawMode, int vertexFlags, int stride, int vertexCount, String format, int vao, int vbo) {
		if (!shouldCaptureGlState()) {
			return;
		}

		int error = GL11.glGetError();
		if (error == 0) {
			return;
		}

		String label = "draw-error:" + stage + ":" + source + ":" + error + ":" + drawMode + ":" + vertexFlags + ":" + stride;
		int count = GLSM_EVENT_COUNTS.merge(label, 1, Integer::sum);
		if (count > 8) {
			return;
		}

		VIEWPORT.clear();
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
		LOGGER.error(
			"draw-error stage={} source={} count={} error={} mode={} flags=0x{} stride={} vertices={} vao={} vbo={} currentVao={} currentVbo={} currentEbo={} program={} fb={} drawBuffer={} readBuffer={} viewport=[{},{},{},{}] format={}",
			stage,
			source,
			count,
			error,
			drawMode,
			Integer.toHexString(vertexFlags),
			stride,
			vertexCount,
			vao,
			vbo,
			GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
			GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
			GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING),
			GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
			GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
			GL11.glGetInteger(GL11.GL_DRAW_BUFFER),
			GL11.glGetInteger(GL11.GL_READ_BUFFER),
			VIEWPORT.get(0),
			VIEWPORT.get(1),
			VIEWPORT.get(2),
			VIEWPORT.get(3),
			format
		);
	}

	public static boolean shouldCaptureGlState() {
		if (!isEnabled() || !Iris.isWorldReadyForShaderpackLoad()) {
			return false;
		}

		return true;
	}

	public static boolean shouldCapturePerfTiming() {
		if (!(isPerfDebugEnabled() || isEnabled()) || !Iris.isWorldReadyForShaderpackLoad()) {
			return false;
		}

		return true;
	}

	public static boolean shouldCaptureGpuPerfTiming() {
		return isGpuPerfDebugEnabled() && shouldCapturePerfTiming();
	}

	public static boolean shouldCheckPreRenderGlErrors() {
		String override = System.getProperty("actinium.frameGlErrorCheck");
		if (override != null) {
			return Boolean.parseBoolean(override);
		}

		try {
			return IrisDebugOptions.enableFrameGlErrorCheck();
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	public static boolean shouldCheckPostRenderGlErrors() {
		String override = System.getProperty("actinium.postRenderGlErrorCheck");
		if (override != null) {
			return Boolean.parseBoolean(override);
		}

		try {
			return IrisDebugOptions.enablePostRenderGlErrorCheck();
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private static boolean isPerfDebugEnabled() {
		String override = System.getProperty("actinium.perfDebug");
		if (override != null) {
			return Boolean.parseBoolean(override);
		}

		try {
			return IrisDebugOptions.enableActiniumPerfDebug();
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private static boolean isGpuPerfDebugEnabled() {
		String override = System.getProperty("actinium.gpuPerfDebug");
		if (override != null) {
			return Boolean.parseBoolean(override);
		}

		try {
			return IrisDebugOptions.enableActiniumGpuPerfDebug();
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	public static void logSamplerInitialization(int program, String mode, String name, int location, int assignedUnit) {
		if (!shouldCaptureGlState()) {
            return;
        }

		String label = "sampler-init:" + program + ":" + mode + ":" + name + ":" + assignedUnit;
		int count = SAMPLER_INIT_COUNTS.merge(label, 1, Integer::sum);
		if (count > 4) {
			return;
		}

		logDebugInfo(
			"sampler-init program={} mode={} name={} count={} location={} unit={}",
			program,
			mode,
			name,
			count,
			location,
			assignedUnit
		);
	}

	public static void logSamplerIntercept(int program, String mode, int requestedUnit, boolean override, String... names) {
		if (!shouldCaptureGlState()) {
            return;
        }

		String joinedNames = String.join(",", names);
		String label = "sampler-intercept:" + program + ":" + mode + ":" + requestedUnit + ":" + override + ":" + joinedNames;
		int count = SAMPLER_INIT_COUNTS.merge(label, 1, Integer::sum);
		if (count > 4) {
			return;
		}

		logDebugInfo(
			"sampler-intercept program={} mode={} requestedUnit={} override={} count={} names=[{}]",
			program,
			mode,
			requestedUnit,
			override,
			count,
			joinedNames
		);
	}

	public static void logPipelineInputs(String stage, String phase, String availability, boolean shadow, boolean mainBound, boolean fullscreen, boolean postChain) {
		if (!shouldCaptureGlState()) {
            return;
        }

		String label = "pipeline-inputs:" + stage + ":" + phase + ":" + availability + ":" + shadow + ":" + mainBound + ":" + fullscreen + ":" + postChain;
		int count = SAMPLER_INIT_COUNTS.merge(label, 1, Integer::sum);
		if (count > 8) {
			return;
		}

		logDebugInfo(
			"pipeline-inputs stage={} phase={} availability={} count={} shadow={} mainBound={} fullscreen={} postChain={}",
			stage,
			phase,
			availability,
			count,
			shadow,
			mainBound,
			fullscreen,
			postChain
		);
	}

	public static void logProgramSamplerState(String stage, int program, String availability, String phase) {
		if (!shouldCaptureGlState()) {
            return;
        }

		String label = "program-sampler-state:" + stage + ":" + program + ":" + availability + ":" + phase;
		int count = SAMPLER_INIT_COUNTS.merge(label, 1, Integer::sum);
		if (count > 4) {
			return;
		}

		logDebugInfo(
			"program-sampler-state stage={} program={} phase={} availability={} count={} values[tex={}, lightmap={}, gaux4={}, shadowtex0={}, shadowtex1={}, shadowcolor0={}]",
			stage,
			program,
			phase,
			availability,
			count,
			getSamplerUniform(program, "tex"),
			getSamplerUniform(program, "lightmap"),
			getSamplerUniform(program, "gaux4"),
			getSamplerUniform(program, "shadowtex0"),
			getSamplerUniform(program, "shadowtex1"),
			getSamplerUniform(program, "shadowcolor0")
		);
	}

    public static void markStage(String stage) {
        lastStage = stage;
        GlFlightRecording.markStage(stage);
        if (GlFlightRecording.isEnabled()
            && (stage.endsWith(":end") || stage.endsWith(":done") || stage.endsWith(":return"))) {
            GLStateManager.gpuCheckpoint(GpuCommandType.PASS_END);
        }
    }

    public static void beginFramebufferSamplePhase(String phase) {
        framebufferSamplePhase = phase;
    }

    public static void endFramebufferSamplePhase() {
        framebufferSamplePhase = null;
    }

    public static String replaceFramebufferSamplePhase(String phase) {
        String previous = framebufferSamplePhase;
        framebufferSamplePhase = phase;
        return previous;
    }

    public static void restoreFramebufferSamplePhase(String phase) {
        framebufferSamplePhase = phase;
    }

    public static void logMinecraftGlError(String message, int error) {
        if (error == 0) {
            return;
        }

        if (!shouldCaptureGlState()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastLogTime < 1000L) {
            return;
        }
        lastLogTime = now;

        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);

        Minecraft mc = Minecraft.getMinecraft();
        Framebuffer main = mc.getFramebuffer();
        int mainFbo = main != null ? main.framebufferObject : -1;
        int mainTex = main != null ? main.framebufferTexture : -1;
        int mainWidth = main != null ? main.framebufferWidth : -1;
        int mainHeight = main != null ? main.framebufferHeight : -1;

        LOGGER.error(
            "message={} error={} lastStage={} fb={} readFb={} drawFb={} drawBuffer={} readBuffer={} program={} vao={} viewport=[{},{},{},{}] mainFbo={} mainTex={} mainSize={}x{} display={}x{}",
            message,
            error,
            lastStage,
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL11.GL_DRAW_BUFFER),
            GL11.glGetInteger(GL11.GL_READ_BUFFER),
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
            VIEWPORT.get(0),
            VIEWPORT.get(1),
            VIEWPORT.get(2),
            VIEWPORT.get(3),
            mainFbo,
            mainTex,
            mainWidth,
            mainHeight,
            mc.displayWidth,
            mc.displayHeight
        );
    }

    public static void check(String stage) {
        if (!shouldCaptureGlState()) {
            return;
        }

        markStage(stage);
        int error = GL11.glGetError();
        if (error != 0) {
            logGlState(stage, error);
        }
    }

    public static void logWhiteScreenProbe(String label) {
        if (!shouldCaptureGlState()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = WHITE_SCREEN_PROBE_TIMES.get(label);
        if (previous != null && now - previous < 1000L) {
            return;
        }
        WHITE_SCREEN_PROBE_TIMES.put(label, now);

        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int width = Math.max(1, VIEWPORT.get(2));
        int height = Math.max(1, VIEWPORT.get(3));

        int[][] pixels = new int[5][4];
        readPixelUnsignedByte(width / 2, height / 2, pixels[0]);
        readPixelUnsignedByte(width / 4, height / 4, pixels[1]);
        readPixelUnsignedByte((width * 3) / 4, height / 4, pixels[2]);
        readPixelUnsignedByte(width / 4, (height * 3) / 4, pixels[3]);
        readPixelUnsignedByte((width * 3) / 4, (height * 3) / 4, pixels[4]);

        int minR = 255;
        int minG = 255;
        int minB = 255;
        int maxR = 0;
        int maxG = 0;
        int maxB = 0;
        for (int[] pixel : pixels) {
            minR = Math.min(minR, pixel[0]);
            minG = Math.min(minG, pixel[1]);
            minB = Math.min(minB, pixel[2]);
            maxR = Math.max(maxR, pixel[0]);
            maxG = Math.max(maxG, pixel[1]);
            maxB = Math.max(maxB, pixel[2]);
        }

        boolean white = minR >= 245 && minG >= 245 && minB >= 245;
        boolean solid = maxR - minR <= 3 && maxG - minG <= 3 && maxB - minB <= 3;
        int count = WHITE_SCREEN_PROBE_COUNTS.merge(label, 1, Integer::sum);

        Minecraft mc = Minecraft.getMinecraft();
        Framebuffer main = mc.getFramebuffer();
        int mainFbo = main != null ? main.framebufferObject : -1;
        int mainTex = main != null ? main.framebufferTexture : -1;
        int mainWidth = main != null ? main.framebufferWidth : -1;
        int mainHeight = main != null ? main.framebufferHeight : -1;

        LOGGER.info(
            "white-screen-probe label={} count={} white={} solid={} fb={} readFb={} drawFb={} drawBuffer={} readBuffer={} program={} vao={} activeTex={} tex2D={} depthTest={} depthMask={} blend={} viewport=[{},{},{},{}] mainFbo={} mainTex={} mainSize={}x{} display={}x{} pixels=[({}, {}, {}, {}) ({}, {}, {}, {}) ({}, {}, {}, {}) ({}, {}, {}, {}) ({}, {}, {}, {})]",
            label,
            count,
            white,
            solid,
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL11.GL_DRAW_BUFFER),
            GL11.glGetInteger(GL11.GL_READ_BUFFER),
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
            GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),
            GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
            GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
            GL11.glIsEnabled(GL11.GL_BLEND),
            VIEWPORT.get(0),
            VIEWPORT.get(1),
            VIEWPORT.get(2),
            VIEWPORT.get(3),
            mainFbo,
            mainTex,
            mainWidth,
            mainHeight,
            mc.displayWidth,
            mc.displayHeight,
            pixels[0][0], pixels[0][1], pixels[0][2], pixels[0][3],
            pixels[1][0], pixels[1][1], pixels[1][2], pixels[1][3],
            pixels[2][0], pixels[2][1], pixels[2][2], pixels[2][3],
            pixels[3][0], pixels[3][1], pixels[3][2], pixels[3][3],
            pixels[4][0], pixels[4][1], pixels[4][2], pixels[4][3]
        );
    }

    public static void logCeleritasProgram(String passName, int program, Collection<GlVertexAttribute> attributes) {
        if (!shouldCaptureGlState()) {
            return;
        }

        if (!LOGGED_CELERITAS_PROGRAMS.add(passName + ":" + program)) {
            return;
        }

        String attributeLocations = attributes.stream()
            .map(attribute -> attribute.getName() + "=" + GL20.glGetAttribLocation(program, attribute.getName()))
            .collect(Collectors.joining(", "));

        logDebugInfo(
            "celeritas-program pass={} program={} attrs=[{}] uniforms=[tex={}, lightmap={}, colortex0={}, gaux4={}, shadowtex0={}, shadowtex1={}, shadowcolor0={}, iris_ModelViewMatrix={}, iris_ProjectionMatrix={}, iris_TextureMatrix={}, iris_LightmapTextureMatrix={}, iris_NormalMatrix={}, u_RegionOffset={}, frameMod={}, frameCounter={}, cameraPosition={}, previousCameraPosition={}, sunPosition={}, moonPosition={}, viewWidth={}, viewHeight={}, pixelSizeX={}, pixelSizeY={}, worldTime={}, gbufferModelView={}, gbufferProjection={}, gbufferPreviousModelView={}, gbufferPreviousProjection={}]",
            passName,
            program,
            attributeLocations,
            GL20.glGetUniformLocation(program, "tex"),
            GL20.glGetUniformLocation(program, "lightmap"),
            GL20.glGetUniformLocation(program, "colortex0"),
            GL20.glGetUniformLocation(program, "gaux4"),
            GL20.glGetUniformLocation(program, "shadowtex0"),
            GL20.glGetUniformLocation(program, "shadowtex1"),
            GL20.glGetUniformLocation(program, "shadowcolor0"),
            GL20.glGetUniformLocation(program, "iris_ModelViewMatrix"),
            GL20.glGetUniformLocation(program, "iris_ProjectionMatrix"),
            GL20.glGetUniformLocation(program, "iris_TextureMatrix"),
            GL20.glGetUniformLocation(program, "iris_LightmapTextureMatrix"),
            GL20.glGetUniformLocation(program, "iris_NormalMatrix"),
            GL20.glGetUniformLocation(program, "u_RegionOffset"),
            GL20.glGetUniformLocation(program, "frameMod"),
            GL20.glGetUniformLocation(program, "frameCounter"),
            GL20.glGetUniformLocation(program, "cameraPosition"),
            GL20.glGetUniformLocation(program, "previousCameraPosition"),
            GL20.glGetUniformLocation(program, "sunPosition"),
            GL20.glGetUniformLocation(program, "moonPosition"),
            GL20.glGetUniformLocation(program, "viewWidth"),
            GL20.glGetUniformLocation(program, "viewHeight"),
            GL20.glGetUniformLocation(program, "pixelSizeX"),
            GL20.glGetUniformLocation(program, "pixelSizeY"),
            GL20.glGetUniformLocation(program, "worldTime"),
            GL20.glGetUniformLocation(program, "gbufferModelView"),
            GL20.glGetUniformLocation(program, "gbufferProjection"),
            GL20.glGetUniformLocation(program, "gbufferPreviousModelView"),
            GL20.glGetUniformLocation(program, "gbufferPreviousProjection")
        );
    }

    public static void logCeleritasTerrainState(String passName, int program, boolean hasAlphaTestOverride, float expectedAlphaReference) {
        if (!shouldCaptureGlState()) {
            return;
        }

        if (!LOGGED_CELERITAS_STATES.add(passName + ":" + program)) {
            return;
        }

        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);

        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
        int texSampler = getSamplerUniform(program, "tex");
        int lightmapSampler = getSamplerUniform(program, "lightmap");
        int shadow0Sampler = getSamplerUniform(program, "shadowtex0");
        int shadow1Sampler = getSamplerUniform(program, "shadowtex1");
        int shadowColorSampler = getSamplerUniform(program, "shadowcolor0");
        int texture0 = getBoundTexture2D(0);
        int texture1 = getBoundTexture2D(1);
        int texture2 = getBoundTexture2D(2);
        int texture3 = getBoundTexture2D(3);
        int frameMod = getIntUniform(program, "frameMod");
        int frameCounter = getIntUniform(program, "frameCounter");
        float viewWidth = getFloatUniform(program, "viewWidth");
        float viewHeight = getFloatUniform(program, "viewHeight");
        float pixelSizeX = getFloatUniform(program, "pixelSizeX");
        float pixelSizeY = getFloatUniform(program, "pixelSizeY");
        float actualAlphaTestReference = getFloatUniform(program, "iris_currentAlphaTest");
        String textureMatrix = getMatrixUniform(program, "iris_TextureMatrix");
        String lightmapTextureMatrix = getMatrixUniform(program, "iris_LightmapTextureMatrix");

        logDebugInfo(
            "celeritas-state pass={} program={} currentProgram={} fb={} readFb={} drawFb={} drawBuffer={} readBuffer={} viewport=[{},{},{},{}] activeTex={} samplers[tex={}, lightmap={}, shadow0={}, shadow1={}, shadowColor0={}] textures[0={}, 1={}, 2={}, 3={}] values[frameMod={}, frameCounter={}, viewWidth={}, viewHeight={}, pixelSizeX={}, pixelSizeY={}, hasAlphaTestOverride={}, expectedAlphaReference={}, actualAlphaTestReference={}] matrices[tex={}, lightmap={}]",
            passName,
            program,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL11.GL_DRAW_BUFFER),
            GL11.glGetInteger(GL11.GL_READ_BUFFER),
            VIEWPORT.get(0),
            VIEWPORT.get(1),
            VIEWPORT.get(2),
            VIEWPORT.get(3),
            activeTexture,
            texSampler,
            lightmapSampler,
            shadow0Sampler,
            shadow1Sampler,
            shadowColorSampler,
            texture0,
            texture1,
            texture2,
            texture3,
            frameMod,
            frameCounter,
            viewWidth,
            viewHeight,
            pixelSizeX,
            pixelSizeY,
            hasAlphaTestOverride,
            expectedAlphaReference,
            actualAlphaTestReference,
            textureMatrix,
            lightmapTextureMatrix
        );

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + activeTexture);
    }

    public static void logTerrainMaterialSample(String source, int blockId, int renderType, int lightValue, int localX, int localY, int localZ, float u, float v) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = source + ":" + blockId + ":" + renderType;
        int count = MATERIAL_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        logDebugInfo(
            "terrain-material source={} count={} blockId={} renderType={} lightValue={} local=[{},{},{}] uv=[{},{}]",
            source,
            count,
            blockId,
            renderType,
            lightValue,
            localX,
            localY,
            localZ,
            u,
            v
        );
    }

    public static void logShadowPassState(String stage, boolean terrain, boolean translucent, boolean entities, boolean player, boolean blockEntities, int visibleChunks, int renderedEntities, int renderedBlockEntities) {
        if (!shouldCaptureGlState()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastShadowPassLogTime < 1000L && !"after-render".equals(stage)) {
            return;
        }
        lastShadowPassLogTime = now;

        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);

        logDebugInfo(
            "shadow-pass stage={} terrain={} translucent={} entities={} player={} blockEntities={} visibleChunks={} renderedEntities={} renderedBlockEntities={} program={} fb={} viewport=[{},{},{},{}]",
            stage,
            terrain,
            translucent,
            entities,
            player,
            blockEntities,
            visibleChunks,
            renderedEntities,
            renderedBlockEntities,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            VIEWPORT.get(0),
            VIEWPORT.get(1),
            VIEWPORT.get(2),
            VIEWPORT.get(3)
        );
    }

    public static void logShadowTerrainLayer(String stage, String passName, int visibleChunks) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "shadow-terrain-layer:" + stage + ":" + passName + ":" + visibleChunks;
        int count = FRAMEBUFFER_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 4) {
            return;
        }

        logDebugInfo(
            "shadow-terrain-layer stage={} pass={} visibleChunks={} program={} fb={} viewport=[{},{},{},{}]",
            stage,
            passName,
            visibleChunks,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight()
        );
    }

    public static void logShadowEntityState(String stage, double cameraX, double cameraY, double cameraZ, double renderPosX, double renderPosY, double renderPosZ, double viewerPosX, double viewerPosY, double viewerPosZ, int entityCount) {
        if (!shouldCaptureGlState()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastShadowEntityLogTime < 1000L) {
            return;
        }
        lastShadowEntityLogTime = now;

        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);

        logDebugInfo(
            "shadow-entity-state stage={} camera=[{},{},{}] renderPos=[{},{},{}] viewerPos=[{},{},{}] entities={} modelviewDepth={} projectionDepth={} fb={} viewport=[{},{},{},{}]",
            stage,
            cameraX,
            cameraY,
            cameraZ,
            renderPosX,
            renderPosY,
            renderPosZ,
            viewerPosX,
            viewerPosY,
            viewerPosZ,
            entityCount,
            GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH),
            GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            VIEWPORT.get(0),
            VIEWPORT.get(1),
            VIEWPORT.get(2),
            VIEWPORT.get(3)
        );
    }

    public static void logShadowEntityDraw(String entityType, double x, double y, double z, float yaw, float partialTicks) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = entityType + ":" + GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 6) {
            return;
        }

        logDebugInfo(
            "shadow-entity-draw entity={} count={} relative=[{},{},{}] yaw={} partialTicks={} program={} fb={} modelview={} projection={}",
            entityType,
            count,
            x,
            y,
            z,
            yaw,
            partialTicks,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            getMatrixModeMatrix(GL11.GL_MODELVIEW_MATRIX),
            getMatrixModeMatrix(GL11.GL_PROJECTION_MATRIX)
        );
    }

    public static void logEntityCullSample(String reason, String entityType, int pass) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "entity-cull:" + reason + ":" + entityType + ":" + pass;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 4) {
            return;
        }

        logDebugInfo(
            "entity-cull reason={} entity={} pass={} count={} program={} fb={} viewport=[{},{},{},{}]",
            reason,
            entityType,
            pass,
            count,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight()
        );
    }

    public static void logEntityLoopSummary(String stage, int pass, int gathered, int rendered, int shadowSkipped, int frustumSkipped, int celeritasSkipped, int blockSkipped, boolean irisEntities, String phase) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "entity-loop:" + stage + ":" + pass + ":" + gathered + ":" + rendered + ":" + shadowSkipped + ":" + frustumSkipped + ":" + celeritasSkipped + ":" + blockSkipped;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 6) {
            return;
        }

        logDebugInfo(
            "entity-loop stage={} pass={} count={} gathered={} rendered={} skipped[shadow={}, frustum={}, celeritas={}, block={}] irisEntities={} phase={} program={} fb={} viewport=[{},{},{},{}]",
            stage,
            pass,
            count,
            gathered,
            rendered,
            shadowSkipped,
            frustumSkipped,
            celeritasSkipped,
            blockSkipped,
            irisEntities,
            phase,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight()
        );
    }

    public static void logEntityPhase(String entityType, String previousPhase, boolean beganEntityPhase, String uniformModelView, String uniformProjection) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = entityType + ":" + previousPhase + ":" + beganEntityPhase;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        logDebugInfo(
            "entity-phase entity={} count={} previousPhase={} beganEntityPhase={} program={} fb={} viewport=[{},{},{},{}] uniforms[modelView={}, projection={}]",
            entityType,
            count,
            previousPhase,
            beganEntityPhase,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight(),
            uniformModelView,
            uniformProjection
        );
    }

    public static void logEntityRenderCall(String stage, String entityType, String rendererType, String phase, int entityId) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "entity-render:" + stage + ":" + entityType + ":" + rendererType;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        logDebugInfo(
            "entity-render stage={} entity={} renderer={} phase={} entityId={} count={} program={} fb={} viewport=[{},{},{},{}]",
            stage,
            entityType,
            rendererType,
            phase,
            entityId,
            count,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight()
        );
    }

    public static void logWorldPassState(String stage, String phase, String subject) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "world-pass-state:" + stage + ":" + phase + ":" + subject;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        final var blend = GLStateManager.getBlendState();
        final var alpha = GLStateManager.getAlphaState();
        final var depth = GLStateManager.getDepthState();
        final var alphaTest = GLStateManager.getAlphaTest();
        final var colorMask = GLStateManager.getColorMask();

        logDebugInfo(
            "world-pass-state stage={} subject={} phase={} count={} program={} fb={} viewport=[{},{},{},{}] blend[enabled={}, srcRgb={}, dstRgb={}, srcAlpha={}, dstAlpha={}] alphaTest[enabled={}, func={}, ref={}] depth[enabled={}, func={}, mask={}] colorMask=[{},{},{},{}] cullEnabled={}",
            stage,
            subject,
            phase,
            count,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight(),
            blend.isEnabled(),
            blend.getSrcRgb(),
            blend.getDstRgb(),
            blend.getSrcAlpha(),
            blend.getDstAlpha(),
            alphaTest.isEnabled(),
            alpha.getFunction(),
            alpha.getReference(),
            depth.isEnabled(),
            depth.getFunc(),
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
            colorMask.red,
            colorMask.green,
            colorMask.blue,
            colorMask.alpha,
            GL11.glIsEnabled(GL11.GL_CULL_FACE)
        );
    }

    public static void logActiveTextureBindings(String stage, String phase, String subject) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "texture-bindings:" + stage + ":" + phase + ":" + subject;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
        int texUnit = getSamplerUniform(program, "tex");
        int lightmapUnit = getSamplerUniform(program, "lightmap");

        logDebugInfo(
            "texture-bindings stage={} subject={} phase={} count={} program={} activeTex={} samplers[tex={}, lightmap={}] textures[0={}, 1={}, 2={}, 3={}]",
            stage,
            subject,
            phase,
            count,
            program,
            activeTexture,
            texUnit,
            lightmapUnit,
            getBoundTexture2D(0),
            getBoundTexture2D(1),
            getBoundTexture2D(2),
            getBoundTexture2D(3)
        );
    }

    public static void logPipelineSkip(String stage, String phase, boolean shadow, boolean mainBound, boolean renderingWorld, boolean fullscreen, boolean postChain) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "pipeline-skip:" + stage + ":" + phase + ":" + shadow + ":" + mainBound + ":" + renderingWorld + ":" + fullscreen + ":" + postChain;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        logDebugInfo(
            "pipeline-skip stage={} count={} phase={} shadow={} mainBound={} world={} fullscreen={} postChain={} currentProgram={} fb={} viewport=[{},{},{},{}]",
            stage,
            count,
            phase,
            shadow,
            mainBound,
            renderingWorld,
            fullscreen,
            postChain,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight()
        );
    }

    public static void logPassBind(String stage, String phase, int previousProgram, int nextProgram) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "pass-bind:" + stage + ":" + phase + ":" + previousProgram + ":" + nextProgram;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        logDebugInfo(
            "pass-bind stage={} count={} phase={} previousProgram={} nextProgram={} currentProgram={} fb={} viewport=[{},{},{},{}]",
            stage,
            count,
            phase,
            previousProgram,
            nextProgram,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight()
        );
    }

    public static void logPhaseChange(String stage, String previousPhase, String nextPhase, boolean shadow, boolean mainBound, boolean renderingWorld, boolean fullscreen, boolean postChain, String inputs) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "phase-change:" + stage + ":" + previousPhase + ":" + nextPhase + ":" + shadow + ":" + mainBound + ":" + fullscreen + ":" + postChain + ":" + inputs;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        logDebugInfo(
            "phase-change stage={} count={} previousPhase={} nextPhase={} inputs={} shadow={} mainBound={} world={} fullscreen={} postChain={} currentProgram={} fb={} viewport=[{},{},{},{}]",
            stage,
            count,
            previousPhase,
            nextPhase,
            inputs,
            shadow,
            mainBound,
            renderingWorld,
            fullscreen,
            postChain,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight()
        );
    }

    public static void logProgramOverrideDecision(String stage, String phase, int oldProgram, int newProgram, int activePassProgram, boolean shouldOverrideShaders, boolean renderingLevel, boolean ownedProgram, boolean unlockedDepthColor, boolean invokedOverride) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "program-override:" + stage + ":" + phase + ":" + oldProgram + ":" + newProgram + ":" + activePassProgram + ":" + shouldOverrideShaders + ":" + renderingLevel + ":" + ownedProgram + ":" + unlockedDepthColor + ":" + invokedOverride;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 12) {
            return;
        }

        logDebugInfo(
            "program-override stage={} count={} phase={} oldProgram={} newProgram={} activePassProgram={} shouldOverride={} renderingLevel={} ownedProgram={} unlockedDepthColor={} invokedOverride={} currentProgram={} fb={} viewport=[{},{},{},{}]",
            stage,
            count,
            phase,
            oldProgram,
            newProgram,
            activePassProgram,
            shouldOverrideShaders,
            renderingLevel,
            ownedProgram,
            unlockedDepthColor,
            invokedOverride,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight()
        );
    }

    public static void logModProgramOverride(String stage, String phase, String inputs, boolean shadow, boolean mainBound, boolean renderingWorld, boolean fullscreen, boolean postChain, int previousProgram, int activePassProgram) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "mod-program-override:" + stage + ":" + phase + ":" + previousProgram + ":" + activePassProgram + ":" + shadow + ":" + mainBound + ":" + fullscreen + ":" + postChain + ":" + inputs;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        logDebugInfo(
            "mod-program-override stage={} count={} phase={} inputs={} previousProgram={} activePassProgram={} shadow={} mainBound={} world={} fullscreen={} postChain={} currentProgram={} fb={} viewport=[{},{},{},{}]",
            stage,
            count,
            phase,
            inputs,
            previousProgram,
            activePassProgram,
            shadow,
            mainBound,
            renderingWorld,
            fullscreen,
            postChain,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight()
        );
    }

    public static void logCurrentFramebufferAttachments(String stage, String phase, int maxAttachments) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = "fb-attachments:" + stage + ":" + phase;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        StringBuilder attachments = new StringBuilder();
        for (int i = 0; i < maxAttachments; i++) {
            int objectType = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0 + i,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
            );
            int objectName = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0 + i,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
            );
            attachments.append(" att").append(i)
                .append("[type=").append(objectType)
                .append(", tex=").append(objectName)
                .append("]");
        }

        int depthType = GL30.glGetFramebufferAttachmentParameteri(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
        );
        int depthName = GL30.glGetFramebufferAttachmentParameteri(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
        );

        logDebugInfo(
            "fb-attachments stage={} phase={} count={} fb={} drawBuffer={} readBuffer={}{} depth[type={}, tex={}]",
            stage,
            phase,
            count,
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL11.GL_DRAW_BUFFER),
            GL11.glGetInteger(GL11.GL_READ_BUFFER),
            attachments,
            depthType,
            depthName
        );
    }

    public static void logPipelineMatch(String stage, String phase, String condition, boolean shadow, boolean mainBound, boolean renderingWorld, boolean fullscreen, boolean postChain, int program) {
        if (!shouldCaptureGlState()) {
            return;
        }

        String label = stage + ":" + phase + ":" + condition + ":" + shadow + ":" + mainBound + ":" + program;
        int count = ENTITY_PHASE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 8) {
            return;
        }

        final var blend = GLStateManager.getBlendState();

        logDebugInfo(
            "pipeline-match stage={} count={} phase={} condition={} shadow={} mainBound={} world={} fullscreen={} postChain={} program={} currentProgram={} fb={} viewport=[{},{},{},{}] blendEnabled={} blend=[{},{},{},{}]",
            stage,
            count,
            phase,
            condition,
            shadow,
            mainBound,
            renderingWorld,
            fullscreen,
            postChain,
            program,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            currentViewportX(),
            currentViewportY(),
            currentViewportWidth(),
            currentViewportHeight(),
            GLStateManager.getBlendMode().isEnabled(),
            blend.getSrcRgb(),
            blend.getDstRgb(),
            blend.getSrcAlpha(),
            blend.getDstAlpha()
        );
    }

    private static int currentViewportX() {
        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        return VIEWPORT.get(0);
    }

    private static int currentViewportY() {
        return VIEWPORT.get(1);
    }

    private static int currentViewportWidth() {
        return VIEWPORT.get(2);
    }

    private static int currentViewportHeight() {
        return VIEWPORT.get(3);
    }

    private static String formatNanos(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    public static void logFullscreenProgram(String stageName, String sourceName, int program, int[] drawBuffers) {
        if (!shouldCaptureGlState()) {
            return;
        }

        if (!LOGGED_FULLSCREEN_PROGRAMS.add(stageName + ":" + sourceName + ":" + program)) {
            return;
        }

        logDebugInfo(
            "fullscreen-program stage={} source={} program={} drawBuffers={} uniforms=[colortex1={}, colortex3={}, colortex12={}, colortex14={}, depthtex1={}, dhDepthTex={}, dhDepthTex1={}, frameMod={}, cameraPosition={}, previousCameraPosition={}, gbufferProjection={}, gbufferProjectionInverse={}, dhProjection={}, dhProjectionInverse={}, gbufferModelViewInverse={}, gbufferPreviousModelView={}, gbufferPreviousProjection={}, dhNearPlane={}, dhFarPlane={}, dhRenderDistance={}, pixelSizeX={}, pixelSizeY={}]",
            stageName,
            sourceName,
            program,
            java.util.Arrays.toString(drawBuffers),
            GL20.glGetUniformLocation(program, "colortex1"),
            GL20.glGetUniformLocation(program, "colortex3"),
            GL20.glGetUniformLocation(program, "colortex12"),
            GL20.glGetUniformLocation(program, "colortex14"),
            GL20.glGetUniformLocation(program, "depthtex1"),
            GL20.glGetUniformLocation(program, "dhDepthTex"),
            GL20.glGetUniformLocation(program, "dhDepthTex1"),
            GL20.glGetUniformLocation(program, "frameMod"),
            GL20.glGetUniformLocation(program, "cameraPosition"),
            GL20.glGetUniformLocation(program, "previousCameraPosition"),
            GL20.glGetUniformLocation(program, "gbufferProjection"),
            GL20.glGetUniformLocation(program, "gbufferProjectionInverse"),
            GL20.glGetUniformLocation(program, "dhProjection"),
            GL20.glGetUniformLocation(program, "dhProjectionInverse"),
            GL20.glGetUniformLocation(program, "gbufferModelViewInverse"),
            GL20.glGetUniformLocation(program, "gbufferPreviousModelView"),
            GL20.glGetUniformLocation(program, "gbufferPreviousProjection"),
            GL20.glGetUniformLocation(program, "dhNearPlane"),
            GL20.glGetUniformLocation(program, "dhFarPlane"),
            GL20.glGetUniformLocation(program, "dhRenderDistance"),
            GL20.glGetUniformLocation(program, "pixelSizeX"),
            GL20.glGetUniformLocation(program, "pixelSizeY")
        );
    }

    public static void logFullscreenPassState(String stageName, String sourceName, int program, int[] drawBuffers, java.util.Set<Integer> readsFromAlt, RenderTargets renderTargets) {
        if (!shouldCaptureGlState()) {
            return;
        }

        if (!LOGGED_FULLSCREEN_STATES.add(stageName + ":" + sourceName + ":" + program)) {
            return;
        }

        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);

        RenderTarget history = renderTargets.getRenderTargetCount() > 3 ? renderTargets.get(3) : null;
        int historyRead = history == null ? -1 : (readsFromAlt.contains(3) ? history.getAltTexture() : history.getMainTexture());
        int historyWrite = history == null ? -1 : (readsFromAlt.contains(3) ? history.getMainTexture() : history.getAltTexture());

        logDebugInfo(
            "fullscreen-state stage={} source={} program={} currentProgram={} drawBuffers={} readsFromAlt={} fb={} drawBuffer={} viewport=[{},{},{},{}] colortex3[main={}, alt={}, sampled={}, written={}] samplers[colortex1={}, colortex3={}, colortex12={}, colortex14={}, depthtex1={}, dhDepthTex={}, dhDepthTex1={}] values[frameMod={}, pixelSizeX={}, pixelSizeY={}, dhNearPlane={}, dhFarPlane={}, dhRenderDistance={}] dhState[loaded={}, renderingEnabled={}]",
            stageName,
            sourceName,
            program,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            java.util.Arrays.toString(drawBuffers),
            readsFromAlt,
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL11.GL_DRAW_BUFFER),
            VIEWPORT.get(0),
            VIEWPORT.get(1),
            VIEWPORT.get(2),
            VIEWPORT.get(3),
            history == null ? -1 : history.getMainTexture(),
            history == null ? -1 : history.getAltTexture(),
            historyRead,
            historyWrite,
            getSamplerUniform(program, "colortex1"),
            getSamplerUniform(program, "colortex3"),
            getSamplerUniform(program, "colortex12"),
            getSamplerUniform(program, "colortex14"),
            getSamplerUniform(program, "depthtex1"),
            getSamplerUniform(program, "dhDepthTex"),
            getSamplerUniform(program, "dhDepthTex1"),
            getIntUniform(program, "frameMod"),
            getFloatUniform(program, "pixelSizeX"),
            getFloatUniform(program, "pixelSizeY"),
            getFloatUniform(program, "dhNearPlane"),
            getFloatUniform(program, "dhFarPlane"),
            getIntUniform(program, "dhRenderDistance"),
            DHCompat.isDistantHorizonsLoaded(),
            DHCompat.hasRenderingEnabled()
        );
    }

    public static void logFullscreenSamplerSamples(String stageName, String sourceName, int program) {
        if (!shouldCaptureGlState()) {
            return;
        }

        if (framebufferSamplePhase == null) {
            return;
        }

        String label = framebufferSamplePhase + ":" + stageName + ":" + sourceName + ":" + program;
        int count = TEXTURE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 4) {
            return;
        }

        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        StringBuilder samples = new StringBuilder();
        appendSamplerTexture(samples, program, "colortex0");
        appendSamplerTexture(samples, program, "colortex1");
        appendSamplerTexture(samples, program, "colortex2");
        appendSamplerTexture(samples, program, "colortex3");
        appendSamplerTexture(samples, program, "gcolor");
        appendSamplerTexture(samples, program, "gaux1");
        appendSamplerTexture(samples, program, "gaux2");
        appendSamplerTexture(samples, program, "gaux3");
        appendSamplerTexture(samples, program, "gaux4");
        appendSamplerBinding(samples, program, "colortex12");
        appendSamplerBinding(samples, program, "colortex14");
        appendSamplerBinding(samples, program, "depthtex0");
        appendSamplerBinding(samples, program, "depthtex1");
        appendSamplerBinding(samples, program, "depthtex2");
        appendSamplerBinding(samples, program, "dhDepthTex");
        appendSamplerBinding(samples, program, "dhDepthTex1");

        logDebugInfo(
            "fullscreen-sampler-sample label={} count={} fb={} readFb={} drawFb={} viewport=[{},{},{},{}] values[dhNearPlane={}, dhFarPlane={}, dhRenderDistance={}] dhState[loaded={}, renderingEnabled={}]{}",
            label,
            count,
            previousFramebuffer,
            previousReadFramebuffer,
            previousDrawFramebuffer,
            VIEWPORT.get(0),
            VIEWPORT.get(1),
            VIEWPORT.get(2),
            VIEWPORT.get(3),
            getFloatUniform(program, "dhNearPlane"),
            getFloatUniform(program, "dhFarPlane"),
            getIntUniform(program, "dhRenderDistance"),
            DHCompat.isDistantHorizonsLoaded(),
            DHCompat.hasRenderingEnabled(),
            samples
        );

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
        GL13.glActiveTexture(previousActiveTexture);
    }

    public static void logCurrentFramebufferSamples(String label, int localColorAttachments) {
		if (!shouldCaptureGlState()) {
			return;
		}

        if (framebufferSamplePhase == null) {
            return;
        }

        String phasedLabel = framebufferSamplePhase + ":" + label;
        int count = FRAMEBUFFER_SAMPLE_COUNTS.merge(phasedLabel, 1, Integer::sum);
        if (count > 4 || localColorAttachments <= 0) {
            return;
        }

        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int width = Math.max(1, VIEWPORT.get(2));
        int height = Math.max(1, VIEWPORT.get(3));
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        StringBuilder samples = new StringBuilder();
        int attachmentCount = previousReadFramebuffer == 0 ? 1 : localColorAttachments;
        for (int attachment = 0; attachment < attachmentCount; attachment++) {
            GL11.glReadBuffer(previousReadFramebuffer == 0 ? previousReadBuffer : GL30.GL_COLOR_ATTACHMENT0 + attachment);
            samples.append(" att").append(attachment).append("[");
            appendPixel(samples, width / 2, height / 2);
            samples.append(" ");
            appendPixel(samples, width / 4, height / 4);
            samples.append(" ");
            appendPixel(samples, (width * 3) / 4, height / 4);
            samples.append(" ");
            appendPixel(samples, width / 4, (height * 3) / 4);
            samples.append(" ");
            appendPixel(samples, (width * 3) / 4, (height * 3) / 4);
            samples.append("]");
        }

        logDebugInfo(
            "framebuffer-sample label={} count={} fb={} readFb={} drawFb={} viewport=[{},{},{},{}]{}",
            phasedLabel,
            count,
            previousFramebuffer,
            previousReadFramebuffer,
            previousDrawFramebuffer,
            VIEWPORT.get(0),
            VIEWPORT.get(1),
            VIEWPORT.get(2),
            VIEWPORT.get(3),
            samples
        );

        GL11.glReadBuffer(previousReadBuffer);
    }

    public static void logCompositeChainPixels(String stage, String subject, RenderTargets renderTargets) {
        if (!isCloudControlDebugEnabled() || renderTargets == null) {
            return;
        }

        int requiredTargets = 15;
        if (renderTargets.getRenderTargetCount() <= requiredTargets) {
            return;
        }

        String label = "chain:" + stage + ":" + subject;
        int count = CLOUD_CONTROL_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 6) {
            return;
        }

        RenderTarget target3 = renderTargets.get(3);
        RenderTarget target5 = renderTargets.get(5);
        RenderTarget target7 = renderTargets.get(7);
        RenderTarget target14 = renderTargets.get(14);
        if (target3 == null || target5 == null || target7 == null || target14 == null) {
            return;
        }

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        StringBuilder builder = new StringBuilder();
        appendUpperScreenTargetPixels(builder, "colortex3.main", target3.getMainTexture(), target3.getWidth(), target3.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex3.alt", target3.getAltTexture(), target3.getWidth(), target3.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex5.main", target5.getMainTexture(), target5.getWidth(), target5.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex5.alt", target5.getAltTexture(), target5.getWidth(), target5.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex7.main", target7.getMainTexture(), target7.getWidth(), target7.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex7.alt", target7.getAltTexture(), target7.getWidth(), target7.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex14.main", target14.getMainTexture(), target14.getWidth(), target14.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex14.alt", target14.getAltTexture(), target14.getWidth(), target14.getHeight());

        LOGGER.info("composite-chain-sample label={} count={}{}", label, count, builder);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
        GLStateManager.glActiveTexture(previousActiveTexture);
    }

    public static void logCompositeDepthPixels(String stage, String subject, RenderTargets renderTargets, int dhDepthTex, int dhDepthTex1) {
        if (!isCloudControlDebugEnabled() || renderTargets == null || renderTargets.getRenderTargetCount() <= 12) {
            return;
        }

        String label = "depth:" + stage + ":" + subject;
        int count = TEXTURE_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 6) {
            return;
        }

        RenderTarget target12 = renderTargets.get(12);
        if (target12 == null) {
            return;
        }

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        StringBuilder builder = new StringBuilder();
        appendUpperScreenTargetPixels(builder, "colortex12.main", target12.getMainTexture(), target12.getWidth(), target12.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex12.alt", target12.getAltTexture(), target12.getWidth(), target12.getHeight());
        appendUpperScreenTargetPixels(builder, "dhDepthTex", dhDepthTex, target12.getWidth(), target12.getHeight());
        appendUpperScreenTargetPixels(builder, "dhDepthTex1", dhDepthTex1, target12.getWidth(), target12.getHeight());

        LOGGER.info("composite-depth-sample label={} count={}{}", label, count, builder);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
        GLStateManager.glActiveTexture(previousActiveTexture);
    }

    public static void logCloudTerrainChainPixels(
        String stage,
        String subject,
        RenderTargets renderTargets,
        int dhDepthTex,
        int dhDepthTex1
    ) {
        if (!shouldCaptureGlState() || renderTargets == null || renderTargets.getRenderTargetCount() <= 14) {
            return;
        }
        if (!"composite3".equals(subject) && !"FINAL".equals(stage)) {
            return;
        }

        String label = "cloud-terrain:" + stage + ":" + subject;
        int count = CLOUD_CONTROL_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 4) {
            return;
        }

        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int width = Math.max(1, VIEWPORT.get(2));
        int height = Math.max(1, VIEWPORT.get(3));

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        RenderTarget target3 = renderTargets.get(3);
        RenderTarget target10 = renderTargets.getRenderTargetCount() > 10 ? renderTargets.get(10) : null;
        DepthPixels depth0 = readDepthTexture(renderTargets.getDepthTexture());
        DepthPixels depth1 = readDepthTexture(renderTargets.getDepthTextureNoTranslucents().getTextureId());
        DepthPixels dhDepth0 = readDepthTexture(dhDepthTex);
        DepthPixels dhDepth1 = readDepthTexture(dhDepthTex1);

        StringBuilder builder = new StringBuilder();
        int currentProgram = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        builder.append(" far=").append(getFloatUniform(currentProgram, "far"));
        builder.append(" viewWidth=").append(getFloatUniform(currentProgram, "viewWidth"));
        builder.append(" viewHeight=").append(getFloatUniform(currentProgram, "viewHeight"));
        builder.append(" dhLoaded=").append(DHCompat.isDistantHorizonsLoaded());
        builder.append(" dhRendering=").append(DHCompat.hasRenderingEnabled());
        builder.append(" dhNear=").append(getFloatUniform(currentProgram, "dhNearPlane"));
        builder.append(" dhFar=").append(getFloatUniform(currentProgram, "dhFarPlane"));
        builder.append(" dhRenderDistance=").append(getIntUniform(currentProgram, "dhRenderDistance"));
        builder.append(" viewport=").append(width).append("x").append(height);
        builder.append(" depth0Size=").append(depth0 != null ? depth0.width + "x" + depth0.height + ",format=0x" + Integer.toHexString(depth0.internalFormat) : "missing");
        builder.append(" dhDepth0Size=").append(dhDepth0 != null ? dhDepth0.width + "x" + dhDepth0.height + ",format=0x" + Integer.toHexString(dhDepth0.internalFormat) : "missing");

        float[][] points = {
            {0.5f, 0.25f},
            {0.5f, 0.45f},
            {0.5f, 0.50f},
            {0.5f, 0.55f},
            {0.5f, 0.75f},
            {0.25f, 0.50f},
            {0.75f, 0.50f}
        };

        for (float[] point : points) {
            int x = (int) (point[0] * width);
            int y = (int) (point[1] * height);

            builder.append("\n point=").append(point[0]).append(",").append(point[1])
                .append(" viewport=(").append(x).append(",").append(y).append(")");
            appendDepthPixel(builder, "depthtex0", depth0, x, y);
            appendDepthPixel(builder, "depthtex1", depth1, x, y);
            appendDepthPixel(builder, "dhDepthTex", dhDepth0, x, y);
            appendDepthPixel(builder, "dhDepthTex1", dhDepth1, x, y);
            appendTargetPixel(builder, "colortex3.main", target3.getMainTexture(), x, y);
            appendTargetPixel(builder, "colortex3.alt", target3.getAltTexture(), x, y);
            if (target10 != null) {
                appendTargetPixel(builder, "colortex10.main", target10.getMainTexture(), x, y);
                appendTargetPixel(builder, "colortex10.alt", target10.getAltTexture(), x, y);
            }
            appendCurrentFramebufferPixel(builder, "fbo", x, y);
            appendCurrentDepthPixel(builder, "fboDepth", x, y);
        }

        logDebugInfo("cloud-terrain-chain label={} count={}{}", label, count, builder);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
        GLStateManager.glActiveTexture(previousActiveTexture);
    }

    public static void logCloudControlPixels(String stage, String subject, RenderTargets renderTargets) {
        if (!isCloudControlDebugEnabled() || renderTargets == null || renderTargets.getRenderTargetCount() <= 14) {
            return;
        }

        String label = stage + ":" + subject;
        int count = CLOUD_CONTROL_SAMPLE_COUNTS.merge(label, 1, Integer::sum);
        if (count > 6) {
            return;
        }

        RenderTarget target0 = renderTargets.get(0);
        RenderTarget target4 = renderTargets.get(4);
        RenderTarget target14 = renderTargets.get(14);
        if (target0 == null || target4 == null || target14 == null) {
            return;
        }

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        StringBuilder builder = new StringBuilder();
        appendTargetPixel(builder, "colortex4.main", target4.getMainTexture(), 0, 37);
        appendTargetPixel(builder, "colortex4.main", target4.getMainTexture(), 1, 37);
        appendTargetPixel(builder, "colortex4.main", target4.getMainTexture(), 6, 37);
        appendTargetPixel(builder, "colortex4.main", target4.getMainTexture(), 1, 1);
        appendTargetPixel(builder, "colortex4.main", target4.getMainTexture(), 2, 1);
        appendTargetPixel(builder, "colortex4.main", target4.getMainTexture(), 3, 1);
        appendTargetPixel(builder, "colortex4.alt", target4.getAltTexture(), 0, 37);
        appendTargetPixel(builder, "colortex4.alt", target4.getAltTexture(), 1, 37);
        appendTargetPixel(builder, "colortex4.alt", target4.getAltTexture(), 6, 37);
        appendTargetPixel(builder, "colortex4.alt", target4.getAltTexture(), 1, 1);
        appendTargetPixel(builder, "colortex4.alt", target4.getAltTexture(), 2, 1);
        appendTargetPixel(builder, "colortex4.alt", target4.getAltTexture(), 3, 1);
        appendUpperScreenTargetPixels(builder, "colortex0.main", target0.getMainTexture(), target0.getWidth(), target0.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex0.alt", target0.getAltTexture(), target0.getWidth(), target0.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex14.main", target14.getMainTexture(), target14.getWidth(), target14.getHeight());
        appendUpperScreenTargetPixels(builder, "colortex14.alt", target14.getAltTexture(), target14.getWidth(), target14.getHeight());

        LOGGER.info("cloud-control-sample label={} count={}{}", label, count, builder);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
        GLStateManager.glActiveTexture(previousActiveTexture);
    }

    private static void appendUpperScreenTargetPixels(StringBuilder builder, String name, int texture, int width, int height) {
        int centerX = Math.max(0, width / 2);
        int quarterX = Math.max(0, width / 4);
        int threeQuarterX = Math.max(0, (width * 3) / 4);
        int upperY = Math.max(0, (height * 3) / 4);
        int topY = Math.max(0, (height * 7) / 8);

        appendTargetPixel(builder, name, texture, centerX, upperY);
        appendTargetPixel(builder, name, texture, quarterX, upperY);
        appendTargetPixel(builder, name, texture, threeQuarterX, upperY);
        appendTargetPixel(builder, name, texture, centerX, topY);
    }

    public static void logFramebufferOutputState(
        String label,
        int framebufferTexture,
        int framebufferWidth,
        int framebufferHeight,
        int framebufferTextureWidth,
        int framebufferTextureHeight,
        int outputWidth,
        int outputHeight,
        boolean disableBlend
    ) {
        if (!shouldCaptureGlState()) {
            return;
        }

        long now = System.currentTimeMillis();
        String phasedLabel = "framebuffer-output-state:" + label;
        Long previous = WHITE_SCREEN_PROBE_TIMES.get(phasedLabel);
        if (previous != null && now - previous < 1000L) {
            return;
        }
        WHITE_SCREEN_PROBE_TIMES.put(phasedLabel, now);

        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        StringBuilder framebufferPixels = new StringBuilder();
        int readWidth = Math.max(1, VIEWPORT.get(2));
        int readHeight = Math.max(1, VIEWPORT.get(3));
        framebufferPixels.append("[");
        appendPixel(framebufferPixels, readWidth / 2, readHeight / 2);
        framebufferPixels.append(" ");
        appendPixel(framebufferPixels, readWidth / 4, readHeight / 4);
        framebufferPixels.append(" ");
        appendPixel(framebufferPixels, (readWidth * 3) / 4, readHeight / 4);
        framebufferPixels.append(" ");
        appendPixel(framebufferPixels, readWidth / 4, (readHeight * 3) / 4);
        framebufferPixels.append(" ");
        appendPixel(framebufferPixels, (readWidth * 3) / 4, (readHeight * 3) / 4);
        framebufferPixels.append("]");

        StringBuilder texturePixels = new StringBuilder();
        if (framebufferTexture > 0) {
            appendTexturePixels(texturePixels, framebufferTexture);
        } else {
            texturePixels.append(",missing");
        }

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
        GL13.glActiveTexture(previousActiveTexture);

        ByteBuffer colorMask = BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
        float uMax = framebufferTextureWidth <= 0 ? -1.0F : (float) framebufferWidth / (float) framebufferTextureWidth;
        float vMax = framebufferTextureHeight <= 0 ? -1.0F : (float) framebufferHeight / (float) framebufferTextureHeight;
        final var blend = GLStateManager.getBlendState();
        LOGGER.info(
            "framebuffer-output-state label={} fb={} readFb={} drawFb={} drawBuffer={} readBuffer={} program={} vao={} activeTex={} tex2D={} texUnits[0={},1={},2={},3={}] tex2DEnabled[0={},1={},2={},3={}] depthTest={} depthMask={} blend={} blendFunc=[{},{},{},{}] colorMask=[{},{},{},{}] viewport=[{},{},{},{}] framebufferTex={} fbSize={}x{} texSize={}x{} output={}x{} uvMax=[{},{}] disableBlend={} framebufferPixels={} texture{} matrices[projection={}, modelview={}, texture={}]",
            label,
            previousFramebuffer,
            previousReadFramebuffer,
            previousDrawFramebuffer,
            GL11.glGetInteger(GL11.GL_DRAW_BUFFER),
            previousReadBuffer,
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
            previousActiveTexture,
            GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
            getBoundTexture2D(0),
            getBoundTexture2D(1),
            getBoundTexture2D(2),
            getBoundTexture2D(3),
            GLStateManager.getTextures().getTextureUnitStates(0).isEnabled(),
            GLStateManager.getTextures().getTextureUnitStates(1).isEnabled(),
            GLStateManager.getTextures().getTextureUnitStates(2).isEnabled(),
            GLStateManager.getTextures().getTextureUnitStates(3).isEnabled(),
            GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
            GL11.glIsEnabled(GL11.GL_BLEND),
            blend.getSrcRgb(),
            blend.getDstRgb(),
            blend.getSrcAlpha(),
            blend.getDstAlpha(),
            colorMask.get(0) != 0,
            colorMask.get(1) != 0,
            colorMask.get(2) != 0,
            colorMask.get(3) != 0,
            VIEWPORT.get(0),
            VIEWPORT.get(1),
            VIEWPORT.get(2),
            VIEWPORT.get(3),
            framebufferTexture,
            framebufferWidth,
            framebufferHeight,
            framebufferTextureWidth,
            framebufferTextureHeight,
            outputWidth,
            outputHeight,
            uMax,
            vMax,
            disableBlend,
            framebufferPixels,
            texturePixels,
            getMatrixModeMatrix(GL11.GL_PROJECTION_MATRIX),
            getMatrixModeMatrix(GL11.GL_MODELVIEW_MATRIX),
            getMatrixModeMatrix(GL11.GL_TEXTURE_MATRIX)
        );
    }

    private static int getSamplerUniform(int program, String name) {
        if (program <= 0) {
            return -1;
        }

        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0) {
            return -1;
        }

        return GL20.glGetUniformi(program, location);
    }

    private static int getIntUniform(int program, String name) {
        if (program <= 0) {
            return -1;
        }

        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0) {
            return -1;
        }

        return GL20.glGetUniformi(program, location);
    }

    private static float getFloatUniform(int program, String name) {
        if (program <= 0) {
            return -1.0F;
        }

        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0) {
            return -1.0F;
        }

        return GL20.glGetUniformf(program, location);
    }

    private static String getMatrixUniform(int program, String name) {
        if (program <= 0) {
            return "missing";
        }

        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0) {
            return "missing";
        }

        FloatBuffer values = BufferUtils.createFloatBuffer(16);
        GL20.glGetUniformfv(program, location, values);
        return String.format(
            java.util.Locale.ROOT,
            "[%.6f,%.6f,%.6f,%.6f|%.6f,%.6f,%.6f,%.6f|%.6f,%.6f,%.6f,%.6f|%.6f,%.6f,%.6f,%.6f]",
            values.get(0),
            values.get(1),
            values.get(2),
            values.get(3),
            values.get(4),
            values.get(5),
            values.get(6),
            values.get(7),
            values.get(8),
            values.get(9),
            values.get(10),
            values.get(11),
            values.get(12),
            values.get(13),
            values.get(14),
            values.get(15)
        );
    }

    private static String getMatrixModeMatrix(int pname) {
        FloatBuffer values = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(pname, values);
        return String.format(
            java.util.Locale.ROOT,
            "[%.4f,%.4f,%.4f,%.4f|%.4f,%.4f,%.4f,%.4f|%.4f,%.4f,%.4f,%.4f|%.4f,%.4f,%.4f,%.4f]",
            values.get(0),
            values.get(1),
            values.get(2),
            values.get(3),
            values.get(4),
            values.get(5),
            values.get(6),
            values.get(7),
            values.get(8),
            values.get(9),
            values.get(10),
            values.get(11),
            values.get(12),
            values.get(13),
            values.get(14),
            values.get(15)
        );
    }

    private static int getBoundTexture2D(int unit) {
        int previous = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(previous);
        GLStateManager.glActiveTexture(previous);
        return texture;
    }

    private static void appendSamplerTexture(StringBuilder builder, int program, String samplerName) {
        int unit = getSamplerUniform(program, samplerName);
        if (unit < 0) {
            return;
        }

        int texture = getBoundTexture2D(unit);
        builder.append(" ").append(samplerName).append("[unit=").append(unit).append(",tex=").append(texture);

        if (texture > 0) {
            appendTexturePixels(builder, texture);
        }

        builder.append("]");
    }

    private static void appendSamplerBinding(StringBuilder builder, int program, String samplerName) {
        int unit = getSamplerUniform(program, samplerName);
        if (unit < 0) {
            return;
        }

        builder.append(" ").append(samplerName)
            .append("[unit=").append(unit)
            .append(",tex=").append(getBoundTexture2D(unit))
            .append("]");
    }

    private static void appendTexturePixels(StringBuilder builder, int texture) {
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);

        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0) {
            builder.append(",size=").append(width).append("x").append(height);
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL11.glReadBuffer(previousReadBuffer);
            GLStateManager.glActiveTexture(previousActiveTexture);
            return;
        }

        if (debugFramebuffer == 0) {
            debugFramebuffer = GL30.glGenFramebuffers();
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, debugFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        builder.append(",size=").append(width).append("x").append(height);
        if (status == GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            builder.append(",pixels[");
            appendPixel(builder, width / 2, height / 2);
            builder.append(" ");
            appendPixel(builder, width / 4, height / 4);
            builder.append(" ");
            appendPixel(builder, (width * 3) / 4, height / 4);
            builder.append(" ");
            appendPixel(builder, width / 4, (height * 3) / 4);
            builder.append(" ");
            appendPixel(builder, (width * 3) / 4, (height * 3) / 4);
            builder.append("]");
        } else {
            builder.append(",fboStatus=").append(status);
        }

        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
        GLStateManager.glActiveTexture(previousActiveTexture);
    }

    private static void appendTargetPixel(StringBuilder builder, String name, int texture, int x, int y) {
        builder.append(" ").append(name).append("@(").append(x).append(",").append(y).append(")=");
        if (texture <= 0) {
            builder.append("missing");
            return;
        }

        float[] pixel = readTexturePixel(texture, x, y);
        if (pixel == null) {
            builder.append("unavailable");
            return;
        }

        builder.append("(")
            .append(formatFloat(pixel[0])).append(",")
            .append(formatFloat(pixel[1])).append(",")
            .append(formatFloat(pixel[2])).append(",")
            .append(formatFloat(pixel[3])).append(")");
    }

    private static DepthPixels readDepthTexture(int texture) {
        if (texture <= 0) {
            return null;
        }

        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);

        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, texture);

        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int internalFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        DepthPixels result = null;
        if (width > 0 && height > 0) {
            result = new DepthPixels(texture, width, height, internalFormat);
        }

        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
        GLStateManager.glActiveTexture(previousActiveTexture);
        return result;
    }

    private static void appendDepthPixel(StringBuilder builder, String name, DepthPixels depth, int x, int y) {
        builder.append(" ").append(name).append("@(").append(x).append(",").append(y).append(")=");
        if (depth == null) {
            builder.append("missing");
            return;
        }

        int sampleX = Math.max(0, Math.min(x, depth.width - 1));
        int sampleY = Math.max(0, Math.min(y, depth.height - 1));
        float value = readDepthTexturePixel(depth.texture, sampleX, sampleY);
        builder.append("(").append(formatFloat(value)).append(")");
    }

    private static float readDepthTexturePixel(int texture, int x, int y) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);

        if (debugFramebuffer == 0) {
            debugFramebuffer = GL30.glGenFramebuffers();
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, debugFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        float value = 1.0f;
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status == GL30.GL_FRAMEBUFFER_COMPLETE) {
            FloatBuffer pixel = BufferUtils.createFloatBuffer(1);
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixel);
            value = pixel.get(0);
        }

        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
        return value;
    }

    private static void appendCurrentFramebufferPixel(StringBuilder builder, String name, int x, int y) {
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int framebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glReadBuffer(framebuffer == 0 ? GL11.GL_BACK : GL30.GL_COLOR_ATTACHMENT0);

        float[] pixel = new float[4];
        readPixel(x, y, pixel);
        builder.append(" ").append(name).append("@(").append(x).append(",").append(y).append(")=(")
            .append(formatFloat(pixel[0])).append(",")
            .append(formatFloat(pixel[1])).append(",")
            .append(formatFloat(pixel[2])).append(",")
            .append(formatFloat(pixel[3])).append(")");

        GL11.glReadBuffer(previousReadBuffer);
    }

    private static void appendCurrentDepthPixel(StringBuilder builder, String name, int x, int y) {
        builder.append(" ").append(name).append("@(").append(x).append(",").append(y).append(")=(");

        int depthAttachmentType = GL30.glGetFramebufferAttachmentParameteri(
            GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (depthAttachmentType == GL11.GL_NONE) {
            builder.append("no-depth-attachment)");
            return;
        }

        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        GL11.glReadBuffer(GL11.GL_NONE);

        FloatBuffer pixel = BufferUtils.createFloatBuffer(1);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixel);
        builder.append(formatFloat(pixel.get(0))).append(")");

        GL11.glReadBuffer(previousReadBuffer);
    }

    private static float[] readTexturePixel(int texture, int x, int y) {
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);

        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0) {
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL11.glReadBuffer(previousReadBuffer);
            GLStateManager.glActiveTexture(previousActiveTexture);
            return null;
        }

        if (debugFramebuffer == 0) {
            debugFramebuffer = GL30.glGenFramebuffers();
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, debugFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        float[] pixel = null;
        if (status == GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            pixel = new float[4];
            readPixel(Math.max(0, Math.min(x, width - 1)), Math.max(0, Math.min(y, height - 1)), pixel);
        }

        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
        GLStateManager.glActiveTexture(previousActiveTexture);
        return pixel;
    }

    private static void appendPixel(StringBuilder builder, int x, int y) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(Math.max(0, x), Math.max(0, y), 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        builder.append("(")
            .append(Byte.toUnsignedInt(pixel.get(0))).append(",")
            .append(Byte.toUnsignedInt(pixel.get(1))).append(",")
            .append(Byte.toUnsignedInt(pixel.get(2))).append(",")
            .append(Byte.toUnsignedInt(pixel.get(3))).append(")");
    }

    private static void readPixel(int x, int y, float[] out) {
        FloatBuffer pixel = BufferUtils.createFloatBuffer(4);
        GL11.glReadPixels(Math.max(0, x), Math.max(0, y), 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
        out[0] = pixel.get(0);
        out[1] = pixel.get(1);
        out[2] = pixel.get(2);
        out[3] = pixel.get(3);
    }

    private static void readPixelUnsignedByte(int x, int y, int[] out) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(Math.max(0, x), Math.max(0, y), 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        out[0] = Byte.toUnsignedInt(pixel.get(0));
        out[1] = Byte.toUnsignedInt(pixel.get(1));
        out[2] = Byte.toUnsignedInt(pixel.get(2));
        out[3] = Byte.toUnsignedInt(pixel.get(3));
    }

    private static String formatFloat(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return Float.toString(value);
        }

        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static void logGlState(String stage, int error) {
        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);

        Minecraft mc = Minecraft.getMinecraft();
        Framebuffer main = mc.getFramebuffer();
        int mainFbo = main != null ? main.framebufferObject : -1;
        int mainTex = main != null ? main.framebufferTexture : -1;
        int mainWidth = main != null ? main.framebufferWidth : -1;
        int mainHeight = main != null ? main.framebufferHeight : -1;

        LOGGER.error(
            "checkpoint={} error={} fb={} readFb={} drawFb={} drawBuffer={} readBuffer={} program={} vao={} viewport=[{},{},{},{}] mainFbo={} mainTex={} mainSize={}x{} display={}x{}",
            stage,
            error,
            GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL11.GL_DRAW_BUFFER),
            GL11.glGetInteger(GL11.GL_READ_BUFFER),
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
            VIEWPORT.get(0),
            VIEWPORT.get(1),
            VIEWPORT.get(2),
            VIEWPORT.get(3),
            mainFbo,
            mainTex,
            mainWidth,
            mainHeight,
            mc.displayWidth,
            mc.displayHeight
        );
    }

    private static final class DepthPixels {
        private final int texture;
        private final int width;
        private final int height;
        private final int internalFormat;

        private DepthPixels(int texture, int width, int height, int internalFormat) {
            this.texture = texture;
            this.width = width;
            this.height = height;
            this.internalFormat = internalFormat;
        }
    }
}
