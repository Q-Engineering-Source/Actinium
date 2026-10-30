package com.gtnewhorizons.angelica.glsm.debug;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class GLSMPerfDebug {
    private static final Logger LOGGER = LogManager.getLogger("GLSMPerfDebug");
    private static final long REPORT_INTERVAL_NS = 1_000_000_000L;
    private static final int SAMPLE_MASK = 255;
    private static final int MAX_BUFFERBUILDER_SOURCE_LINES = 12;
    private static final int MAX_CLIENT_ARRAY_STACK_LINES = 12;
    private static final String ENABLED_OVERRIDE = System.getProperty("actinium.glsmPerfDebug");
    private static volatile boolean enabled = resolveEnabled(ENABLED_OVERRIDE, false);

    public enum Stage {
        STREAM_DRAW("stream.draw"),
        STREAM_DRAW_DIRECT("stream.drawDirect"),
        STREAM_UPLOAD_AND_DRAW("stream.uploadAndDraw"),
        STREAM_PERSISTENT_UPLOAD("stream.persistentUpload"),
        STREAM_ORPHAN_UPLOAD("stream.orphanUpload"),
        STREAM_FENCE_CREATE("stream.fenceCreate", true),
        STREAM_FENCE_POLL("stream.fencePoll", true),
        STREAM_FENCE_WAIT("stream.fenceWait", true),
        STREAM_SHADER_PREDRAW("stream.shaderPreDraw"),
        STREAM_DRAW_CALL("stream.drawCall"),
        FFP_PREDRAW("ffp.preDraw"),
        FFP_UNIFORMS("ffp.uniforms"),
        GL_PREPARE_CLIENT_ARRAYS("gl.prepareClientArrays"),
        GL_CLIENT_ARRAY_UPLOAD("gl.clientArrayUpload"),
        QUAD_ARRAYS("quad.arrays"),
        QUAD_ELEMENTS("quad.elements"),
        QUAD_SCRATCH_UPLOAD_DRAW("quad.scratchUploadDraw"),
        BUFFERBUILDER_STREAM_DRAW("bufferbuilder.streamDraw"),
        BUFFERBUILDER_PERSISTENT_UPLOAD("bufferbuilder.persistentUpload"),
        BUFFERBUILDER_ORPHAN_UPLOAD("bufferbuilder.orphanUpload"),
        BUFFERBUILDER_DRAW_CALL("bufferbuilder.drawCall"),
        CHUNK_UPLOAD("chunk.upload"),
        CHUNK_UPDATE_CHUNKS("chunk.updateChunks", true),
        CHUNK_OCCLUSION_SEARCH("chunk.occlusionSearch", true);

        private final String label;
        private final boolean alwaysSample;

        Stage(String label) {
            this(label, false);
        }

        Stage(String label, boolean alwaysSample) {
            this.label = label;
            this.alwaysSample = alwaysSample;
        }
    }

    public enum Source {
        STREAM_TESSELLATOR("source.streamTessellator"),
        DIRECT_LIVE_IMMEDIATE("source.directLiveImmediate"),
        DIRECT_COMPILE_EXECUTE("source.directCompileExecute"),
        DIRECT_EXTERNAL("source.directExternal");

        private final String label;

        Source(String label) {
            this.label = label;
        }
    }

    private static final long[] totalNanos = new long[Stage.values().length];
    private static final long[] maxNanos = new long[Stage.values().length];
    private static final int[] sampledCounts = new int[Stage.values().length];
    private static final int[] observedCounts = new int[Stage.values().length];
    private static final int[] sourceCounts = new int[Source.values().length];
    private static final Map<String, Integer> bufferBuilderSourceCounts = new HashMap<>();
    private static final Map<String, Integer> bufferBuilderStackSamples = new HashMap<>();
    private static final Map<String, Integer> clientArrayStackSamples = new HashMap<>();
    private static long fenceReclaimedBytes;
    private static int fenceReclaimedRegions;
    private static int fenceQueuePeak;
    private static long lastReportNanos;

    private GLSMPerfDebug() {}

    public static boolean isEnabled() {
        return enabled;
    }

    static boolean setConfiguredEnabled(boolean configuredEnabled) {
        boolean resolvedEnabled = resolveEnabled(ENABLED_OVERRIDE, configuredEnabled);
        if (enabled == resolvedEnabled) {
            return false;
        }

        enabled = false;
        resetStats();
        enabled = resolvedEnabled;
        return true;
    }

    static boolean resolveEnabled(String explicitOverride, boolean configuredEnabled) {
        return explicitOverride != null ? Boolean.parseBoolean(explicitOverride) : configuredEnabled;
    }

    static int getSampledCount(Stage stage) {
        return sampledCounts[stage.ordinal()];
    }

    private static void resetStats() {
        Arrays.fill(totalNanos, 0L);
        Arrays.fill(maxNanos, 0L);
        Arrays.fill(sampledCounts, 0);
        Arrays.fill(observedCounts, 0);
        Arrays.fill(sourceCounts, 0);
        bufferBuilderSourceCounts.clear();
        bufferBuilderStackSamples.clear();
        clientArrayStackSamples.clear();
        fenceReclaimedBytes = 0L;
        fenceReclaimedRegions = 0;
        fenceQueuePeak = 0;
        lastReportNanos = 0L;
    }

    public static long begin(Stage stage) {
        final int index = stage.ordinal();
        observedCounts[index]++;
        if (!stage.alwaysSample && (observedCounts[index] & SAMPLE_MASK) != 0) {
            return 0L;
        }
        return System.nanoTime();
    }

    public static void end(Stage stage, long startNanos) {
        if (startNanos == 0L) return;
        final long endNanos = System.nanoTime();
        recordSample(stage, endNanos, endNanos - startNanos);
    }

    public static long now() {
        return System.nanoTime();
    }

    public static void count(Source source) {
        sourceCounts[source.ordinal()]++;
    }

    public static void countBufferBuilder(String source, int drawMode, int vertexCount) {
        if (!isEnabled()) return;
        final String sourceKey = source + "/mode=" + drawMode;
        bufferBuilderSourceCounts.put(sourceKey, bufferBuilderSourceCounts.getOrDefault(sourceKey, 0) + 1);
        final int observed = observedCounts[Stage.BUFFERBUILDER_STREAM_DRAW.ordinal()];
        if ((observed & SAMPLE_MASK) == 0) {
            final String stackKey = sourceKey + "/" + findBufferBuilderCaller() + "/vertices~" + bucketVertexCount(vertexCount);
            bufferBuilderStackSamples.put(stackKey, bufferBuilderStackSamples.getOrDefault(stackKey, 0) + 1);
        }
    }

    /**
     * Attributes a client-array VBO upload to its draw-call origin. Sampled at the same cadence
     * as the stage timer; the key carries the first non-glsm caller and a power-of-two bucket of
     * the uploaded byte total, so the periodic report shows who drives large uploads.
     */
    public static void countClientArrayUpload(int totalBytes) {
        if (!isEnabled()) return;
        final int observed = observedCounts[Stage.GL_CLIENT_ARRAY_UPLOAD.ordinal()];
        if ((observed & SAMPLE_MASK) == 0) {
            final String stackKey = findClientArrayCaller() + "/bytes~" + bucketVertexCount(totalBytes);
            clientArrayStackSamples.put(stackKey, clientArrayStackSamples.getOrDefault(stackKey, 0) + 1);
        }
    }

    public static void recordFenceReclaim(int bytes) {
        fenceReclaimedRegions++;
        fenceReclaimedBytes += bytes;
    }

    public static void recordFenceQueueDepth(int depth) {
        if (depth > fenceQueuePeak) {
            fenceQueuePeak = depth;
        }
    }

    static int getFenceReclaimedRegions() {
        return fenceReclaimedRegions;
    }

    static long getFenceReclaimedBytes() {
        return fenceReclaimedBytes;
    }

    static int getFenceQueuePeak() {
        return fenceQueuePeak;
    }

    public static void record(Stage stage, long startNanos, long endNanos) {
        if (startNanos == 0L) return;
        observedCounts[stage.ordinal()]++;
        recordSample(stage, endNanos, endNanos - startNanos);
    }

    private static void recordSample(Stage stage, long now, long elapsed) {
        final int index = stage.ordinal();
        totalNanos[index] += elapsed;
        sampledCounts[index]++;
        if (elapsed > maxNanos[index]) {
            maxNanos[index] = elapsed;
        }
        if (lastReportNanos == 0L) {
            lastReportNanos = now;
        } else if (now - lastReportNanos >= REPORT_INTERVAL_NS) {
            report(now);
        }
    }

    private static void report(long now) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("GLSM perf:");
        boolean hasSamples = false;
        final Stage[] stages = Stage.values();
        for (int i = 0; i < stages.length; i++) {
            final int observed = observedCounts[i];
            final int sampled = sampledCounts[i];
            if (observed == 0) continue;
            hasSamples = true;
            sb.append(' ')
                .append(stages[i].label)
                .append("[count=").append(observed)
                .append(",samples=").append(sampled)
                .append(",avgUs=").append(sampled == 0 ? "n/a" : formatMicros(totalNanos[i] / sampled))
                .append(",maxUs=").append(formatMicros(maxNanos[i]))
                .append(",totalMs=").append(formatMillis(totalNanos[i]))
                .append(']');
            observedCounts[i] = 0;
            sampledCounts[i] = 0;
            totalNanos[i] = 0L;
            maxNanos[i] = 0L;
        }
        final Source[] sources = Source.values();
        for (int i = 0; i < sources.length; i++) {
            final int count = sourceCounts[i];
            if (count == 0) continue;
            hasSamples = true;
            sb.append(' ')
                .append(sources[i].label)
                .append("[count=").append(count).append(']');
            sourceCounts[i] = 0;
        }
        appendTopEntries(sb, "bufferbuilder.source", bufferBuilderSourceCounts, MAX_BUFFERBUILDER_SOURCE_LINES);
        appendTopEntries(sb, "bufferbuilder.stackSample", bufferBuilderStackSamples, MAX_BUFFERBUILDER_SOURCE_LINES);
        appendTopEntries(sb, "clientArray.stackSample", clientArrayStackSamples, MAX_CLIENT_ARRAY_STACK_LINES);
        if (fenceReclaimedRegions != 0 || fenceQueuePeak != 0) {
            sb.append(" stream.fence[")
                .append("reclaimedRegions=").append(fenceReclaimedRegions)
                .append(",reclaimedBytes=").append(fenceReclaimedBytes)
                .append(",queuePeak=").append(fenceQueuePeak)
                .append(']');
            fenceReclaimedRegions = 0;
            fenceReclaimedBytes = 0L;
            fenceQueuePeak = 0;
        }
        String extraStats = GLSMPerfDebugHooks.getExtraStats();
        if (extraStats != null && !extraStats.isEmpty()) {
            sb.append(' ').append(extraStats);
        }
        lastReportNanos = now;
        if (hasSamples) {
            LOGGER.info(sb.toString());
        }
    }

    private static void appendTopEntries(StringBuilder sb, String label, Map<String, Integer> counts, int limit) {
        if (counts.isEmpty()) return;
        for (int i = 0; i < limit; i++) {
            String bestKey = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    bestKey = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            if (bestKey == null) {
                break;
            }
            sb.append(' ')
                .append(label)
                .append('[')
                .append(bestKey)
                .append(",count=")
                .append(bestCount)
                .append(']');
            counts.remove(bestKey);
        }
        counts.clear();
    }

    private static String findBufferBuilderCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.startsWith("com.gtnewhorizons.angelica.glsm.debug.")
                || className.startsWith("com.dhj.actinium.render.")
                || className.startsWith("com.dhj.actinium.mixin.vintage.core.")
                || isBufferBuilderBridge(className, element.getMethodName())
                || className.equals("java.lang.Thread")) {
                continue;
            }
            return shortenClassName(className) + "#" + element.getMethodName();
        }
        return "unknown";
    }

    private static boolean isBufferBuilderBridge(String className, String methodName) {
        return (className.equals("net.minecraft.client.renderer.Tessellator")
            || className.equals("net.minecraft.client.renderer.WorldVertexBufferUploader"))
            && (methodName.equals("draw") || methodName.startsWith("handler$"));
    }

    /**
     * Like {@link #findBufferBuilderCaller()} but additionally skips all glsm frames: the
     * client-array upload is triggered from GLStateManager draw entry points, so the first
     * interesting frame is the Minecraft/mod code that issued the draw.
     */
    private static String findClientArrayCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.startsWith("com.gtnewhorizons.angelica.glsm.")
                || className.startsWith("com.dhj.actinium.render.")
                || className.startsWith("com.dhj.actinium.mixin.vintage.core.")
                || className.equals("java.lang.Thread")) {
                continue;
            }
            return shortenClassName(className) + "#" + element.getMethodName();
        }
        return "unknown";
    }

    private static String shortenClassName(String className) {
        int index = className.lastIndexOf('.');
        return index >= 0 ? className.substring(index + 1) : className;
    }

    private static int bucketVertexCount(int vertexCount) {
        if (vertexCount <= 0) return 0;
        int bucket = 1;
        while (bucket < vertexCount && bucket < 16384) {
            bucket <<= 1;
        }
        return bucket;
    }

    private static String formatMicros(long nanos) {
        return String.format("%.2f", nanos / 1_000.0);
    }

    private static String formatMillis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}
