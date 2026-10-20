package com.gtnewhorizons.angelica.glsm.shader;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.threading.AngelicaWorkers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL41;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps linked programs on disk so a shader pack reload, a shader toggle, or a new dimension restores
 * them with {@code glProgramBinary} instead of compiling and linking again.
 *
 * <p>Entries are grouped per pack under {@link #LAYER} and keyed by the driver that produced them, the
 * state of fixed-function emulation and the program's full source set, so a binary is never reused for a
 * program the driver would have compiled differently. Only the newest {@link #PACKS_KEPT} packs keep their
 * entries.</p>
 *
 * <p>The GL calls go through {@link ProgramBinaryOps} rather than being made here: the disk bookkeeping and
 * the "driver rejected the binary" state machine are then exercisable without a context.</p>
 */
public final class ProgramBinaryCache {
    /** Identifies this cache in the game log. */
    private static final Logger LOGGER = LogManager.getLogger("ProgramBinaryCache");
    /** Cache layer holding every pack's saved programs. */
    static final String LAYER = "gl-program";
    /** Number of packs whose saved programs survive a pack switch. */
    private static final int PACKS_KEPT = 3;

    /** Set from the user configuration; the rest of {@link #isEnabled()} still has to agree. */
    private static volatile boolean enabled;
    /** Layer of the pack currently being loaded, or null while no pack wants saved programs. */
    private static volatile String packLayer;
    /** Memoized {@link ProgramBinaryOps#driverFingerprint()}; null until a key needs it. */
    private static String driverId;
    /** Layer the running full compile belongs to. */
    private static String fullCompileLayer;
    /** Cache entry hashes the running full compile touched. */
    private static Set<String> keysInUse;
    /** Disk writes the running full compile started, so it can wait for them before pruning. */
    private static List<CompletableFuture<Boolean>> fullCompileWrites;
    /** Identifies the running full compile; a newer run makes the old one's pruning a no-op. */
    private static Object fullCompileGeneration;
    /** Keeps the driver-rejected warning to one line per process. */
    private static final AtomicBoolean rejectWarned = new AtomicBoolean();
    /** Serializes layer pruning against queued writes. */
    private static final Object WRITE_LOCK = new Object();
    /** Identifies the newest full compile per layer, so a superseded run cannot prune. */
    private static final Map<String, Object> compileGenerations = new HashMap<>();
    /** Counts {@link #deleteAll()} calls, so a write queued before one does not resurrect the layer. */
    private static int deleteCount;
    /** Replaced only by tests; see {@link #setOpsForTesting}. */
    private static volatile ProgramBinaryOps ops = new ProgramBinaryOpsImpl();

    /**
     * What the cache did, as an interval (the periodic report's segment) and as an epoch (the summary
     * logged once a pack's programs have been loaded). The writes happen wherever a program is loaded or
     * linked, so every field is atomic, and a read takes the value and clears it in the same step so
     * neither report can lose an event to the other one.
     */
    private static final Counters intervalCounters = new Counters();
    /** Counters for the current pack loading, reported by {@link #dumpEventSummary()}. */
    private static final Counters epochCounters = new Counters();
    /** Compile time of every timed miss in this session, used to price what the hits saved. */
    private static final AtomicLong totalBuildNanos = new AtomicLong();
    /** Number of timed misses in this session. */
    private static final AtomicLong totalBuildCount = new AtomicLong();

    /** Prevents constructing this static cache utility. */
    private ProgramBinaryCache() {}

    /** Enables or disables saving programs; the configuration owns the switch. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Reports whether a program may be read from or written to disk right now. All four conditions matter:
     * the user turned saving on, a pack selected a layer, the driver can hand a binary back, and the disk
     * cache below has a configured root.
     */
    public static boolean isEnabled() {
        return enabled && packLayer != null && ops.isSupported() && ShaderDiskCache.isEnabled();
    }

    /** Selects the layer holding the given pack's programs; {@code null} turns the cache off. */
    public static void usePack(String packName) {
        epochCounters.clear();
        if (packName == null) {
            packLayer = null;
            return;
        }
        final String layer = LAYER + '/' + folderName(packName);
        packLayer = layer;
        if (!enabled) return;
        ShaderDiskCache.touchLayer(layer);
        AngelicaWorkers.run(() -> ShaderDiskCache.keepNewestSubLayers(LAYER, PACKS_KEPT));
    }

    /**
     * Drops what this process learned about the driver of the display context that is gone.
     *
     * <p>A binary is only valid for the driver that produced it, and {@link #driverId()} reads that identity
     * once. After a context migration the fingerprint has to be read again: otherwise every program linked
     * on the new context would be keyed on the old driver's identity, so the entries the previous context
     * wrote would come back as hits for a driver that never produced them - and every entry the new context
     * writes would be looked up by the wrong name afterwards.</p>
     *
     * <p>The selected pack layer, the user's switch and the collected counters describe the session rather
     * than the context, so they stay as they are.</p>
     */
    public static void invalidateContext() {
        driverId = null;
    }

    /**
     * Starts a key for a program of the selected pack. The caller adds the attributes it binds and the
     * stage sources it links, because only it knows them.
     *
     * @return a key builder bound to the current pack layer, driver and fixed-function emulation state
     */
    public static Key key() {
        final String layer = packLayer;
        return new Key(layer, ShaderDiskCache.key(packLayer).str(driverId()).b(ShaderManager.getInstance().isEnabled()));
    }

    /**
     * Asks the driver to keep this program's binary readable after linking, while the cache is on. A hint,
     * so a driver that ignores it only costs a failed {@code glGetProgramBinary} later.
     *
     * <p>The link-time home of this call is {@code GLStateManager.glLinkProgram}. Programs that celeritas
     * links itself reach it through that linker's hook instead, so both kinds of program are still on disk
     * when the pack is loaded again.</p>
     */
    public static void markRetrievable(int program) {
        if (!isEnabled()) return;
        ops.programParameteri(program, GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL11.GL_TRUE);
    }

    /**
     * Restores the program stored under {@code key}.
     *
     * <p>A driver may reject a binary it accepted when it was written (another GPU, another driver
     * generation, a changed link). That case deletes the program, drops the entry and reports it once; a
     * plain miss, which is what the first run of every program looks like, stays silent.</p>
     *
     * @return the restored program, or 0 when nothing usable was stored under {@code key}
     */
    public static int load(Key key) {
        final long startNanos = System.nanoTime();
        final ShaderDiskCache.Key diskKey = key.diskKey;
        final ShaderDiskCache.Blob blob = ShaderDiskCache.getBlob(diskKey);
        if (blob == null) return recordMiss();
        final int format;
        try {
            format = Integer.parseInt(blob.tag());
        } catch (NumberFormatException exception) {
            LOGGER.warn("Discarding a saved shader program in layer '{}' whose binary format tag '{}' is unreadable (entry {})",
                key.layer, blob.tag(), diskKey.hex(), exception);
            ShaderDiskCache.remove(diskKey);
            return recordMiss();
        }
        final int program = ops.createProgram();
        final ByteBuffer data = MemoryUtilities.memAlloc(blob.data().length);
        try {
            data.put(blob.data()).flip();
            ops.programBinary(program, format, data);
        } finally {
            MemoryUtilities.memFree(data);
        }
        if (ops.getProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            ops.deleteProgram(program);
            ShaderDiskCache.remove(diskKey);
            intervalCounters.reject();
            epochCounters.reject();
            if (rejectWarned.compareAndSet(false, true)) {
                LOGGER.error("Driver rejected a saved shader program from layer '{}' (entry {}); recompiling rejected programs from source",
                    key.layer, diskKey.hex());
            }
            return recordMiss();
        }
        keep(diskKey);
        final long elapsed = System.nanoTime() - startNanos;
        intervalCounters.hit(elapsed);
        epochCounters.hit(elapsed);
        return program;
    }

    /**
     * Records the compile-and-link that a cache miss led into. Timed by the caller, because only it knows
     * where its own build starts and ends: the link call on its own does not bracket the source compilation
     * that precedes it.
     */
    public static void recordMissBuild(long nanos) {
        intervalCounters.build(nanos);
        epochCounters.build(nanos);
        totalBuildNanos.addAndGet(nanos);
        totalBuildCount.incrementAndGet();
    }

    /**
     * The periodic report's cache segment, or an empty string while the cache was not touched: what this
     * interval restored instead of compiling, and what it compiled and saved.
     */
    public static String statsSummary() {
        final Snapshot snapshot = intervalCounters.take();
        return snapshot.idle() ? "" : "progCache[" + describe(snapshot) + ']';
    }

    /**
     * The summary of one pack's program loading, logged once the batch that fills the cache has finished.
     * The compile time the hits saved is priced with the session's average miss build: a batch that only
     * hits has no build of its own to price them with.
     */
    public static String dumpEventSummary() {
        final Snapshot snapshot = epochCounters.take();
        return "progCache[" + describeEpoch(snapshot, totalBuildCount.get(), totalBuildNanos.get()) + ']';
    }

    private static int recordMiss() {
        intervalCounters.miss();
        epochCounters.miss();
        return 0;
    }

    static String describe(Snapshot snapshot) {
        final long lookups = snapshot.hits() + snapshot.misses();
        final String hitRate = lookups == 0L ? "n/a" : String.format("%.1f%%", 100.0 * snapshot.hits() / lookups);
        return "hit=" + snapshot.hits()
            + ",miss=" + snapshot.misses()
            + ",hitRate=" + hitRate
            + ",save=" + snapshot.saves()
            + ",reject=" + snapshot.rejects()
            + ",builds=" + snapshot.builds()
            + ",restoreMs=" + formatMillis(snapshot.restoreNanos())
            + ",missBuildMs=" + formatMillis(snapshot.buildNanos());
    }

    /**
     * Adds what only the whole session knows: the average cost of the compile a miss pays, and the compile
     * time this epoch's hits did not pay. Both need a timed build to be priced, hence the {@code n/a}.
     */
    static String describeEpoch(Snapshot snapshot, long sessionBuilds, long sessionBuildNanos) {
        final long average = sessionBuilds == 0L ? 0L : sessionBuildNanos / sessionBuilds;
        return describe(snapshot)
            + ",avgMissBuildMs=" + (sessionBuilds == 0L ? "n/a" : formatMillis(average))
            + ",estSavedCompileMs=" + (sessionBuilds == 0L ? "n/a" : formatMillis(snapshot.hits() * average))
            + ",sessionMissBuilds=" + sessionBuilds;
    }

    private static String formatMillis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }

    /** One period's or one epoch's cache events, taken as a consistent set so a report can clear it. */
    record Snapshot(long hits, long misses, long saves, long rejects, long builds,
                    long restoreNanos, long buildNanos) {
        private boolean idle() {
            return hits == 0L && misses == 0L && saves == 0L && rejects == 0L && builds == 0L;
        }
    }

    /** The mutable counter set behind a {@link Snapshot}. */
    private static final class Counters {
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong saves = new AtomicLong();
        private final AtomicLong rejects = new AtomicLong();
        private final AtomicLong builds = new AtomicLong();
        private final AtomicLong restoreNanos = new AtomicLong();
        private final AtomicLong buildNanos = new AtomicLong();

        private void hit(long nanos) {
            this.hits.incrementAndGet();
            this.restoreNanos.addAndGet(nanos);
        }

        private void miss() {
            this.misses.incrementAndGet();
        }

        private void save() {
            this.saves.incrementAndGet();
        }

        private void reject() {
            this.rejects.incrementAndGet();
        }

        private void build(long nanos) {
            this.builds.incrementAndGet();
            this.buildNanos.addAndGet(nanos);
        }

        private Snapshot take() {
            return new Snapshot(this.hits.getAndSet(0L), this.misses.getAndSet(0L), this.saves.getAndSet(0L),
                this.rejects.getAndSet(0L), this.builds.getAndSet(0L), this.restoreNanos.getAndSet(0L),
                this.buildNanos.getAndSet(0L));
        }

        private void clear() {
            take();
        }
    }

    /** Saves a freshly linked program under {@code key}; the disk write happens on a worker. */
    public static void save(Key key, int program) {
        if (!saveBinary(key, program) && fullCompileWrites != null) {
            fullCompileWrites.add(CompletableFuture.completedFuture(false));
        }
    }

    private static boolean saveBinary(Key key, int program) {
        final int length = ops.getProgrami(program, GL41.GL_PROGRAM_BINARY_LENGTH);
        // No binary to save: either the driver offers no format for this program or the retrievable hint
        // was not honoured. Both are expected, so this stays quiet.
        if (length <= 0) return false;
        final ByteBuffer data = MemoryUtilities.memAlloc(length);
        final IntBuffer written = MemoryUtilities.memAllocInt(1);
        final IntBuffer format = MemoryUtilities.memAllocInt(1);
        try {
            ops.getProgramBinary(program, written, format, data);
            if (written.get(0) <= 0) return false;
            data.limit(written.get(0));
            final byte[] binary = ShaderCacheIO.toHeap(data);
            final String formatTag = Integer.toString(format.get(0));
            final ShaderDiskCache.Key diskKey = key.diskKey;
            keep(diskKey);
            final int deletesBefore;
            synchronized (WRITE_LOCK) {
                deletesBefore = deleteCount;
            }
            final CompletableFuture<Boolean> write = AngelicaWorkers.submit(() -> {
                synchronized (WRITE_LOCK) {
                    return deletesBefore == deleteCount && ShaderDiskCache.putBlob(diskKey, formatTag, binary);
                }
            });
            final List<CompletableFuture<Boolean>> writes = fullCompileWrites;
            if (writes != null) writes.add(write);
            intervalCounters.save();
            epochCounters.save();
            return true;
        } finally {
            MemoryUtilities.memFree(data);
            MemoryUtilities.memFree(written);
            MemoryUtilities.memFree(format);
        }
    }

    /** Reports whether a full compile of every shader folder of the current pack is running. */
    public static boolean isFullCompileRunning() {
        return keysInUse != null;
    }

    /**
     * Saves a program that the pipeline is about to keep, without re-reading a blob that is already on
     * disk: the entry then counts as used by the running full compile.
     */
    public static boolean saveRetained(Key key, int program) {
        if (ShaderDiskCache.getBlob(key.diskKey) != null) {
            keep(key.diskKey);
            return true;
        }
        return saveBinary(key, program);
    }

    /**
     * Starts a run that compiles every shader folder of the current pack. Programs built during the run
     * are recorded, so {@link #finishFullCompile(String)} can drop everything the run did not touch.
     */
    public static void beginFullCompile() {
        fullCompileLayer = packLayer;
        keysInUse = new HashSet<>();
        fullCompileWrites = new ArrayList<>();
        synchronized (WRITE_LOCK) {
            fullCompileGeneration = new Object();
            if (fullCompileLayer != null) compileGenerations.put(fullCompileLayer, fullCompileGeneration);
        }
    }

    /**
     * Ends a full compile. {@code completedSettings} is the pack fingerprint that was fully compiled, or
     * null when the run did not finish: the layer is then left as it is, since the next run will redo it.
     */
    public static void finishFullCompile(@Nullable String completedSettings) {
        final String layer = fullCompileLayer;
        final Set<String> used = keysInUse;
        final List<CompletableFuture<Boolean>> writes = fullCompileWrites;
        final Object generation = fullCompileGeneration;
        fullCompileLayer = null;
        keysInUse = null;
        fullCompileWrites = null;
        fullCompileGeneration = null;
        if (layer == null || used == null || writes == null) return;

        final ShaderDiskCache.Key marker = completedSettings != null ? compiledSettingsKey(layer, completedSettings) : null;
        final String markerHex = marker != null ? marker.hex() : null;
        CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0])).whenComplete((ignored, failure) -> {
            synchronized (WRITE_LOCK) {
                if (compileGenerations.get(layer) != generation) return;
                if (marker == null || failure != null) return;
                for (CompletableFuture<Boolean> write : writes) {
                    if (!write.join()) return;
                }
                ShaderDiskCache.retainLayer(layer, used);
                ShaderDiskCache.retainLayer(layer + "/settings", Collections.singleton(markerHex));
                ShaderDiskCache.putString(marker, "");
            }
        });
    }

    /** Drops every saved program, used when the user turns saving off. */
    public static void deleteAll() {
        synchronized (WRITE_LOCK) {
            deleteCount++;
            compileGenerations.clear();
            ShaderDiskCache.deleteLayer(LAYER);
        }
        intervalCounters.clear();
        epochCounters.clear();
        totalBuildNanos.set(0L);
        totalBuildCount.set(0L);
    }

    /** Reports whether a previous full compile finished for the given pack settings fingerprint. */
    public static boolean wasCompiledFor(String settings) {
        final String layer = packLayer;
        return layer != null && ShaderDiskCache.getString(compiledSettingsKey(layer, settings)) != null;
    }

    private static ShaderDiskCache.Key compiledSettingsKey(String layer, String settings) {
        return ShaderDiskCache.key(layer + "/settings").str(driverId())
            .b(ShaderManager.getInstance().isEnabled()).str(settings);
    }

    private static String folderName(String packName) {
        final StringBuilder name = new StringBuilder(Math.min(packName.length(), 64));
        for (int i = 0; i < packName.length() && name.length() < 64; i++) {
            final char c = packName.charAt(i);
            name.append(c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') ? c : '_');
        }
        return name.toString();
    }

    private static void keep(ShaderDiskCache.Key diskKey) {
        final String hex = diskKey.hex();
        final Set<String> used = keysInUse;
        if (used != null) used.add(hex);
    }

    /**
     * A saved binary is only valid for the driver that produced it, and fixed-function emulation changes the
     * generated sources, so both go into every key.
     */
    private static String driverId() {
        String id = driverId;
        if (id == null) {
            id = ops.driverFingerprint();
            driverId = id;
        }
        return id;
    }

    /**
     * Replaces the GL calls the cache makes. Only tests use it: it lets the rejection state machine and the
     * hit path run without a context. Passing null restores the production implementation.
     *
     * <p>The memoized driver fingerprint is dropped with it, because a different port means a different
     * driver identity and reusing the old one would key new entries under the previous driver.</p>
     */
    static void setOpsForTesting(@Nullable ProgramBinaryOps replacement) {
        ops = replacement != null ? replacement : new ProgramBinaryOpsImpl();
        driverId = null;
    }

    /** A cache key for one program: pack layer, driver, emulation state, attribute bindings, stage sources. */
    public static final class Key {
        /** Layer this key belongs to, kept for diagnostics. */
        private final String layer;
        /** The underlying disk key, extended with every program-specific field. */
        private final ShaderDiskCache.Key diskKey;

        private Key(String layer, ShaderDiskCache.Key diskKey) {
            this.layer = layer;
            this.diskKey = diskKey;
        }

        /** Adds one stage's shader type and full source. */
        public Key stage(int shaderType, String source) {
            diskKey.str("stage").i(shaderType).str(source);
            return this;
        }

        /** Adds one attribute binding the program is linked with. */
        public Key attribute(String name, int index) {
            diskKey.str("attribute").str(name).i(index);
            return this;
        }

        /**
         * {@return the lowercase hexadecimal digest identifying this program's saved entry} Finalizing the
         * key here means it can no longer be extended, so call this only once every stage and attribute has
         * been added.
         */
        public String hex() {
            return diskKey.hex();
        }

        /** The underlying disk key, exposed to package tests so they can seed and inspect entries. */
        ShaderDiskCache.Key diskKey() {
            return diskKey;
        }
    }
}
