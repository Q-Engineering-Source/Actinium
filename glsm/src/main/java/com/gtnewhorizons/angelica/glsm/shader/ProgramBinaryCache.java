package com.gtnewhorizons.angelica.glsm.shader;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
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

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * Keeps linked programs on disk so a shader pack reload, a shader toggle, or a new dimension restores
 * them with {@code glProgramBinary} instead of compiling and linking again.
 *
 * <p>Entries are grouped per pack under {@link #LAYER}, keyed by the pack's settings fingerprint, the
 * program's full source set, and the driver that produced them. Only the newest {@link #PACKS_KEPT}
 * packs keep their entries.</p>
 */
public final class ProgramBinaryCache {
    private static final Logger LOGGER = LogManager.getLogger("ProgramBinaryCache");
    static final String LAYER = "gl-program";
    private static final int PACKS_KEPT = 3;

    private static volatile boolean enabled;
    private static volatile String packLayer;
    private static String driverId;
    private static String fullCompileLayer;
    private static Set<String> keysInUse;
    private static List<CompletableFuture<Boolean>> fullCompileWrites;
    private static Object fullCompileGeneration;
    private static final AtomicBoolean rejectWarned = new AtomicBoolean();
    private static final Object WRITE_LOCK = new Object();
    private static final Map<String, Object> compileGenerations = new HashMap<>();
    private static int deleteCount;

    /**
     * What the cache did, as an interval (the periodic report's segment) and as an epoch (the summary
     * logged once a pack's programs have been loaded). The writes happen wherever a program is loaded or
     * linked - the render thread for the pipeline's own programs, a worker for the ones the full compile
     * builds - so every field is atomic, and a read takes the value and clears it in the same step so
     * neither report can lose an event to the other one.
     */
    private static final Counters intervalCounters = new Counters();
    private static final Counters epochCounters = new Counters();
    private static final AtomicLong totalBuildNanos = new AtomicLong();
    private static final AtomicLong totalBuildCount = new AtomicLong();

    private ProgramBinaryCache() {}

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled && packLayer != null && RenderSystem.supportsProgramBinary() && ShaderDiskCache.isEnabled();
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

    public static Key key() {
        return new Key(ShaderDiskCache.key(packLayer).str(driverId()).b(ShaderManager.isEnabled()));
    }

    /**
     * Asks the driver to keep this program's binary readable after linking, while the cache is on. A
     * hint, so a driver that ignores it only costs a failed {@code glGetProgramBinary} later.
     *
     * <p>The link-time home of this call is {@link GLStateManager#glLinkProgram(int)}. Programs that
     * celeritas links itself reach it through that linker's hook instead, so both kinds of program are
     * still on disk when the pack is loaded again.</p>
     */
    public static void markRetrievable(int program) {
        if (!isEnabled()) return;
        GLStateManager.glProgramParameteri(program, GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL11.GL_TRUE);
    }

    /** {@return the restored program, or 0 when nothing usable was stored under {@code key}} */
    public static int load(Key key) {
        final long startNanos = System.nanoTime();
        final ShaderDiskCache.Key diskKey = key.diskKey;
        final ShaderDiskCache.Blob blob = ShaderDiskCache.getBlob(diskKey);
        if (blob == null) return recordMiss();
        final int format;
        try {
            format = Integer.parseInt(blob.tag());
        } catch (NumberFormatException e) {
            ShaderDiskCache.remove(diskKey);
            return recordMiss();
        }
        final int program = GLStateManager.glCreateProgram();
        final ByteBuffer data = MemoryUtilities.memAlloc(blob.data().length);
        try {
            data.put(blob.data()).flip();
            GLStateManager.glProgramBinary(program, format, data);
        } finally {
            MemoryUtilities.memFree(data);
        }
        if (GLStateManager.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            GLStateManager.glDeleteProgram(program);
            ShaderDiskCache.remove(diskKey);
            intervalCounters.reject();
            epochCounters.reject();
            if (rejectWarned.compareAndSet(false, true)) LOGGER.error("Driver rejected a saved shader program!! Recompiling rejected programs from source");
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
     * where its own build starts and ends: the link call on its own does not bracket the source
     * compilation that precedes it.
     */
    public static void recordMissBuild(long nanos) {
        intervalCounters.build(nanos);
        epochCounters.build(nanos);
        totalBuildNanos.addAndGet(nanos);
        totalBuildCount.incrementAndGet();
    }

    /**
     * The periodic report's cache segment, or an empty string while the cache was not touched: what
     * this interval restored instead of compiling, and what it compiled and saved.
     */
    public static String statsSummary() {
        final Snapshot snapshot = intervalCounters.take();
        return snapshot.idle() ? "" : "progCache[" + describe(snapshot) + ']';
    }

    /**
     * The summary of one pack's program loading, logged once the batch that fills the cache has
     * finished. The compile time the hits saved is priced with the session's average miss build: a
     * batch that only hits has no build of its own to price them with.
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

    public static void save(Key key, int program) {
        if (!saveBinary(key, program) && fullCompileWrites != null) {
            fullCompileWrites.add(CompletableFuture.completedFuture(false));
        }
    }

    private static boolean saveBinary(Key key, int program) {
        final int length = GLStateManager.glGetProgrami(program, GL41.GL_PROGRAM_BINARY_LENGTH);
        if (length <= 0) return false;
        final ByteBuffer data = MemoryUtilities.memAlloc(length);
        final IntBuffer written = MemoryUtilities.memAllocInt(1);
        final IntBuffer format = MemoryUtilities.memAllocInt(1);
        try {
            GLStateManager.glGetProgramBinary(program, written, format, data);
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

    public static boolean wasCompiledFor(String settings) {
        final String layer = packLayer;
        return layer != null && ShaderDiskCache.getString(compiledSettingsKey(layer, settings)) != null;
    }

    private static ShaderDiskCache.Key compiledSettingsKey(String layer, String settings) {
        return ShaderDiskCache.key(layer + "/settings").str(driverId()).b(ShaderManager.isEnabled()).str(settings);
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
     * A saved binary is only valid for the driver that produced it, and FFP emulation changes the
     * generated sources, so both go into every key.
     */
    private static String driverId() {
        String id = driverId;
        if (id == null) {
            id = RENDER_BACKEND.getString(GL11.GL_VENDOR) + '|' + RENDER_BACKEND.getString(GL11.GL_RENDERER) + '|'
                + RENDER_BACKEND.getString(GL11.GL_VERSION) + '|' + RENDER_BACKEND.getString(GL20.GL_SHADING_LANGUAGE_VERSION) + '|'
                + RenderSystem.isGLES() + '|' + RENDER_BACKEND.getMinGLSLVersion();
            driverId = id;
        }
        return id;
    }

    public static final class Key {
        private final ShaderDiskCache.Key diskKey;

        private Key(ShaderDiskCache.Key diskKey) {
            this.diskKey = diskKey;
        }

        public Key stage(int shaderType, String source) {
            diskKey.str("stage").i(shaderType).str(source);
            return this;
        }

        public Key attribute(String name, int index) {
            diskKey.str("attribute").str(name).i(index);
            return this;
        }
    }
}
