package com.dhj.actinium.texture.atlas;

import com.dhj.actinium.render.terrain.TerrainTextureFiltering;
import com.dhj.actinium.texture.mipmap.MipmapStrategy;
import com.gtnewhorizons.angelica.glsm.threading.AngelicaWorkers;
import dhj.embeddedt.embeddium.impl.texture.TextureFilterMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.Map;

/**
 * One atlas load's observable facts, collected while the load runs and reported as a single line when it ends.
 *
 * <p>The atlas is rebuilt on every resource reload and on every mipmap or filtering option change, and the
 * three parallel switches plus the mipmap and filtering ports all change what that rebuild costs. This is
 * where the load says which of them were in effect, which path the load actually took, and how long each
 * phase took, so the log answers "did the optimization run" without a profiler.</p>
 *
 * <p>Main thread only, like the load itself: the parallel work is joined before the load returns, so every
 * number here is complete by the time {@link #finish} reports it. When no load is in progress the recording
 * calls return on the first read, which is what keeps the per-sprite hooks off the atlas load's critical path
 * when the atlas is loaded for an unrelated reason.</p>
 */
public final class AtlasLoadStats {

    private static final Logger LOGGER = LogManager.getLogger("Actinium");

    private static volatile long loadStartNanos;
    private static long stitchNanos;
    private static long prepareNanos;
    private static long assembleNanos;
    private static int sprites;
    private static int prepared;
    private static int decodeSubmitted;
    private static int decoded;
    private static int decodeFailures;
    private static boolean decodeDesynced;
    private static int mipmapsOffloaded;
    private static int batchedUploaded;
    private static final Map<MipmapStrategy, Integer> strategies = new EnumMap<>(MipmapStrategy.class);

    private AtlasLoadStats() {}

    /**
     * The numbers one atlas load reports. Separate from the collector above so the line can be formatted,
     * and therefore verified, without loading an atlas.
     */
    public record Values(String atlas, int sprites, int mipLevels, int gutter, TextureFilterMode filterMode,
                         int anisotropy, boolean texelSnap, int workerThreads, boolean parallelDecode,
                         boolean parallelMipmaps, boolean batchedUpload, int decodeSubmitted, int decoded,
                         int decodeFailures, boolean decodeDesynced, int mipmapsOffloaded, int batchedUploaded,
                         int prepared, long stitchNanos, long prepareNanos, long assembleNanos, long totalNanos,
                         Map<MipmapStrategy, Integer> strategies) {}

    /** Starts a load's collection, discarding whatever an interrupted previous load left behind. */
    public static void begin() {
        reset();
        loadStartNanos = System.nanoTime();
    }

    /**
     * {@return whether a load is being measured}, which is what keeps the per-sprite hooks off the atlas
     * load's critical path when nothing is collecting.
     */
    public static boolean isCollecting() {
        return loadStartNanos != 0L;
    }

    /** Ends the collection, so the records that follow belong to the next load. */
    private static void reset() {
        stitchNanos = 0L;
        prepareNanos = 0L;
        assembleNanos = 0L;
        sprites = 0;
        prepared = 0;
        decodeSubmitted = 0;
        decoded = 0;
        decodeFailures = 0;
        decodeDesynced = false;
        mipmapsOffloaded = 0;
        batchedUploaded = 0;
        strategies.clear();
    }

    public static void recordStitch(long nanos) {
        if (isCollecting()) stitchNanos = nanos;
    }

    public static void recordSprites(int count) {
        if (isCollecting()) sprites = count;
    }

    public static void recordPrepare(long nanos) {
        if (isCollecting()) prepareNanos = nanos;
    }

    public static void recordAssemble(long nanos) {
        if (isCollecting()) assembleNanos = nanos;
    }

    public static void recordPrepared(int count) {
        if (isCollecting()) prepared = count;
    }

    public static void recordDecode(int submitted, int substituted, int failures, boolean desynced) {
        if (!isCollecting()) return;
        decodeSubmitted = submitted;
        decoded = substituted;
        decodeFailures = failures;
        decodeDesynced = desynced;
    }

    public static void recordMipmapsOffloaded(int count) {
        if (isCollecting()) mipmapsOffloaded = count;
    }

    public static void recordBatchedUploads(int count) {
        if (isCollecting()) batchedUploaded = count;
    }

    /** Counts one sprite by the mipmap strategy its levels were generated with. */
    public static void recordStrategy(MipmapStrategy strategy) {
        if (strategy == null || !isCollecting()) return;
        strategies.merge(strategy, 1, Integer::sum);
    }

    /**
     * Reports the load that just finished and stops collecting. Callers pass what the atlas itself knows:
     * its name, the mip level count it really ended up with, and the gutter its sprites were padded with.
     */
    public static void finish(String atlas, int mipLevels, int gutter) {
        final long startNanos = loadStartNanos;
        if (startNanos == 0L) return;
        LOGGER.info(describe(snapshot(atlas, mipLevels, gutter, TerrainTextureFiltering.effectiveTextureFilterMode(),
            TerrainTextureFiltering.resolvedAnisotropicFiltering(), TerrainTextureFiltering.usesTerrainTexelSnap(),
            System.nanoTime() - startNanos)));
    }

    /**
     * Takes the collected numbers and the values the caller knows as one report, ending the collection.
     * Package private because the filtering and anisotropy arguments are what {@link #finish} has to ask the
     * driver for, which is exactly what a test cannot do.
     */
    static Values snapshot(String atlas, int mipLevels, int gutter, TextureFilterMode filterMode, int anisotropy,
                           boolean texelSnap, long totalNanos) {
        final Values values = new Values(atlas, sprites, mipLevels, gutter, filterMode, anisotropy, texelSnap,
            AngelicaWorkers.threads(), decodeSubmitted > 0 || decoded > 0, mipmapsOffloaded > 0, batchedUploaded > 0,
            decodeSubmitted, decoded, decodeFailures, decodeDesynced, mipmapsOffloaded, batchedUploaded, prepared,
            stitchNanos, prepareNanos, assembleNanos, totalNanos, new EnumMap<>(strategies));
        loadStartNanos = 0L;
        reset();
        return values;
    }

    /** Formats one load's line. */
    public static String describe(Values values) {
        final StringBuilder sb = new StringBuilder(320);
        sb.append("Atlas build: atlas=").append(values.atlas())
            .append(" sprites=").append(values.sprites())
            .append(" mipLevels=").append(values.mipLevels())
            .append(" gutter=").append(values.gutter())
            .append(" filter=").append(values.filterMode())
            .append(" anisotropy=").append(values.anisotropy()).append('x')
            .append(" texelSnap=").append(values.texelSnap())
            .append(" workers=").append(values.workerThreads());
        sb.append(" parallel=[decode=").append(values.parallelDecode())
            .append(",mipmaps=").append(values.parallelMipmaps())
            .append(",batchedUpload=").append(values.batchedUpload())
            .append(",submitted=").append(values.decodeSubmitted())
            .append(",decoded=").append(values.decoded())
            .append(",decodeFailures=").append(values.decodeFailures())
            .append(",decodeDesync=").append(values.decodeDesynced())
            .append(",mipmapsOffloaded=").append(values.mipmapsOffloaded())
            .append(",batchedUploaded=").append(values.batchedUploaded())
            .append(",prepared=").append(values.prepared())
            .append(']');
        sb.append(" phases=[stitchMs=").append(millis(values.stitchNanos()))
            .append(",prepareMs=").append(millis(values.prepareNanos()))
            .append(",assembleMs=").append(millis(values.assembleNanos()))
            .append(",totalMs=").append(millis(values.totalNanos()))
            .append(']');
        sb.append(" strategies=[");
        boolean first = true;
        for (Map.Entry<MipmapStrategy, Integer> entry : values.strategies().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.append(']').toString();
    }

    private static String millis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}
