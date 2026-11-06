package com.gtnewhorizons.angelica.glsm.shader;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * Saves linked GL programs to disk and loads them back with glProgramBinary, skipping compile and link.
 */
public final class ProgramBinaryCache {
    private static final Logger LOGGER = LogManager.getLogger("ProgramBinaryCache");
    static final String LAYER = "gl-program";
    /**
     * Suffix of the layer holding the folder markers. It sits beside the pack's {@code /settings} layer
     * so {@code deleteLayer} clears it with the pack, and so {@code finishFullCompile} pruning the
     * settings layer cannot drop a folder marker.
     */
    private static final String FOLDER_MARKERS = "/folders";
    private static final int PACKS_KEPT = 3;

    private static volatile boolean enabled;
    private static volatile String packLayer;
    private static String driverId;
    private static String fullCompileLayer;
    private static Set<String> keysInUse;
    private static List<CompletableFuture<Boolean>> fullCompileWrites;
    private static Object fullCompileGeneration;
    private static volatile FolderSession folderSession;
    /**
     * Hex digests of the folder markers that were completed but may not be on disk yet, so a query right
     * after {@link #endFolderCompile()} still sees the folder as compiled. The digest is the marker's own
     * {@link ShaderDiskCache.Key#hex()}: it covers layer, driver, FFP flag, settings and folder through
     * length prefixed fields, so it cannot collide with another folder's marker, and it is the same value
     * the on-disk entry is named after. Entries are removed when the write fails, when the folder is
     * forgotten, or when the cache is cleared.
     */
    private static final Set<String> completedFolderMarkers = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean rejectWarned = new AtomicBoolean();
    private static final Object WRITE_LOCK = new Object();
    private static final Map<String, Object> compileGenerations = new HashMap<>();
    private static int deleteCount;

    private ProgramBinaryCache() {}

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled && packLayer != null && RenderSystem.supportsProgramBinary() && ShaderDiskCache.isEnabled();
    }

    /** Whether the cache can hold entries at all, independent of which pack is active. */
    private static boolean isStorageEnabled() {
        return enabled && RenderSystem.supportsProgramBinary() && ShaderDiskCache.isEnabled();
    }

    public static void usePack(String packName) {
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
     * Points the cache at a pack layer without touching it, so precompiling a pack the player is not playing
     * does not reorder eviction or trim other packs.
     */
    public static void usePackForPrecompile(String packName) {
        packLayer = packName == null ? null : LAYER + '/' + folderName(packName);
    }

    public static Key key() {
        return new Key(ShaderDiskCache.key(packLayer).str(driverId()).b(ShaderManager.isEnabled()));
    }

    public static void markRetrievable(int program) {
        GLSMPrograms.glProgramParameteri(program, GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL11.GL_TRUE);
    }

    public static int load(Key key) {
        final ShaderDiskCache.Key diskKey = key.diskKey;
        final ShaderDiskCache.Blob blob = ShaderDiskCache.getBlob(diskKey);
        if (blob == null) return 0;
        final int format;
        try {
            format = Integer.parseInt(blob.tag());
        } catch (NumberFormatException e) {
            ShaderDiskCache.remove(diskKey);
            return 0;
        }
        final int program = GLSMPrograms.glCreateProgram();
        final ByteBuffer data = MemoryUtilities.memAlloc(blob.data().length);
        try {
            data.put(blob.data()).flip();
            GLSMPrograms.glProgramBinary(program, format, data);
        } finally {
            MemoryUtilities.memFree(data);
        }
        if (GLSMPrograms.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            GLSMPrograms.glDeleteProgram(program);
            ShaderDiskCache.remove(diskKey);
            if (rejectWarned.compareAndSet(false, true)) LOGGER.error("Driver rejected a saved shader program!! Recompiling rejected programs from source");
            return 0;
        }
        keep(diskKey);
        return program;
    }

    public static void save(Key key, int program) {
        if (!saveBinary(key, program) && fullCompileWrites != null) {
            fullCompileWrites.add(CompletableFuture.completedFuture(false));
        }
    }

    private static boolean saveBinary(Key key, int program) {
        final int length = GLSMPrograms.glGetProgrami(program, GL41.GL_PROGRAM_BINARY_LENGTH);
        if (length <= 0) return false;
        final ByteBuffer data = MemoryUtilities.memAlloc(length);
        final IntBuffer written = MemoryUtilities.memAllocInt(1);
        final IntBuffer format = MemoryUtilities.memAllocInt(1);
        try {
            GLSMPrograms.glGetProgramBinary(program, written, format, data);
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
            creditFolder(diskKey, write);
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

    public static boolean saveRetained(Key key, int program) {
        if (ShaderDiskCache.getBlob(key.diskKey) != null) {
            keep(key.diskKey);
            creditFolder(key.diskKey, CompletableFuture.completedFuture(true));
            return true;
        }
        return saveBinary(key, program);
    }

    public static void beginFullCompile() {
        fullCompileLayer = packLayer;
        keysInUse = new HashSet<>();
        fullCompileWrites = new ArrayList<>();
        synchronized (WRITE_LOCK) {
            fullCompileGeneration = new Object();
            if (fullCompileLayer != null) compileGenerations.put(fullCompileLayer, fullCompileGeneration);
        }
    }

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

    public static void deleteAll() {
        synchronized (WRITE_LOCK) {
            deleteCount++;
            compileGenerations.clear();
            completedFolderMarkers.clear();
            ShaderDiskCache.deleteLayer(LAYER);
        }
    }

    public static boolean wasCompiledFor(String settings) {
        final String layer = packLayer;
        return layer != null && ShaderDiskCache.getString(compiledSettingsKey(layer, settings)) != null;
    }

    /**
     * Starts a per-folder session; every program saved until {@link #endFolderCompile()} is credited to
     * this folder. Runs alongside {@link #beginFullCompile()} without sharing its state.
     */
    public static void beginFolderCompile(String settings, String folder) {
        final String layer = packLayer;
        if (layer == null) {
            LOGGER.warn("Folder compile for {} ignored: no shader pack layer is active", folder);
            return;
        }
        synchronized (WRITE_LOCK) {
            if (folderSession != null) {
                LOGGER.warn("Folder compile for {} replaced the unfinished session for {}", folder, folderSession.folder);
            }
            folderSession = new FolderSession(layer, settings, folder);
        }
    }

    /**
     * Writes the folder marker that lists every key saved since {@link #beginFolderCompile()}, then ends
     * the session. The folder is reported as compiled as soon as this returns; the marker file itself is
     * written on a worker thread once the session's writes settle, and the in-memory flag is dropped
     * again if that write fails. A folder that produced no programs gets an empty marker; a folder with
     * a failed program write gets none, so the caller retries it.
     */
    public static void endFolderCompile() {
        final FolderSession session;
        final Map<String, CompletableFuture<Boolean>> writes;
        final int deletesBefore;
        synchronized (WRITE_LOCK) {
            session = folderSession;
            folderSession = null;
            if (session == null) return;
            writes = new LinkedHashMap<>(session.writes);
            deletesBefore = deleteCount;
        }
        final ShaderDiskCache.Key marker = folderMarkerKey(session.layer, session.settings, session.folder);
        final String markerHex = marker.hex();
        completedFolderMarkers.add(markerHex);
        CompletableFuture.allOf(writes.values().toArray(new CompletableFuture<?>[0])).whenComplete((ignored, failure) -> {
            final StringBuilder body = new StringBuilder();
            boolean failed = false;
            for (Map.Entry<String, CompletableFuture<Boolean>> entry : writes.entrySet()) {
                if (!entry.getValue().handle((written, error) -> error == null && Boolean.TRUE.equals(written)).join()) {
                    LOGGER.warn("Program {} was not written; the {} folder stays uncompiled", entry.getKey(), session.folder);
                    failed = true;
                    continue;
                }
                if (body.length() > 0) body.append('\n');
                body.append(entry.getKey());
            }
            synchronized (WRITE_LOCK) {
                if (failed) {
                    completedFolderMarkers.remove(markerHex);
                    return;
                }
                if (deletesBefore != deleteCount) {
                    completedFolderMarkers.remove(markerHex);
                    LOGGER.warn("Cache was cleared during the {} folder compile; no marker written", session.folder);
                    return;
                }
                if (!ShaderDiskCache.isEnabled()) {
                    completedFolderMarkers.remove(markerHex);
                    LOGGER.warn("Shader disk cache was disabled during the {} folder compile; no marker written", session.folder);
                    return;
                }
                if (!ShaderDiskCache.put(marker, body.toString().getBytes(StandardCharsets.UTF_8))) {
                    completedFolderMarkers.remove(markerHex);
                    LOGGER.warn("Could not write the {} folder marker", session.folder);
                }
            }
        });
    }

    /** Aborts the session without writing a marker. Entries already written stay in the cache. */
    public static void abortFolderCompile() {
        final FolderSession session;
        synchronized (WRITE_LOCK) {
            session = folderSession;
            folderSession = null;
        }
        if (session != null) LOGGER.debug("Folder compile for {} aborted with {} writes pending", session.folder, session.writes.size());
    }

    /** True when a folder marker exists for this settings hash and folder, in memory or on disk. */
    public static boolean isFolderCompiled(String settings, String folder) {
        final String layer = packLayer;
        return layer != null && isFolderCompiledIn(layer, settings, folder);
    }

    /** Same check for a pack the player is not currently playing. */
    public static boolean isFolderCompiledFor(String packName, String settings, String folder) {
        return isFolderCompiledIn(LAYER + '/' + folderName(packName), settings, folder);
    }

    private static boolean isFolderCompiledIn(String layer, String settings, String folder) {
        if (!isStorageEnabled()) return false;
        final ShaderDiskCache.Key marker = folderMarkerKey(layer, settings, folder);
        return completedFolderMarkers.contains(marker.hex()) || ShaderDiskCache.getString(marker) != null;
    }

    /** Deletes every cache entry the folder marker lists, then the marker itself. */
    public static void forgetFolder(String settings, String folder) {
        final String layer = packLayer;
        if (layer != null) forgetFolderIn(layer, settings, folder);
    }

    /** Same removal for a pack the player is not currently playing. */
    public static void forgetFolderFor(String packName, String settings, String folder) {
        forgetFolderIn(LAYER + '/' + folderName(packName), settings, folder);
    }

    private static void forgetFolderIn(String layer, String settings, String folder) {
        if (!isStorageEnabled()) return;
        final ShaderDiskCache.Key marker = folderMarkerKey(layer, settings, folder);
        synchronized (WRITE_LOCK) {
            completedFolderMarkers.remove(marker.hex());
            final String body = ShaderDiskCache.getString(marker);
            if (body == null) {
                LOGGER.warn("No folder marker for {} in {}; nothing to forget", folder, layer);
                return;
            }
            for (String line : body.split("\n")) {
                if (line.isEmpty()) continue;
                final ShaderDiskCache.Key entry;
                try {
                    entry = ShaderDiskCache.Key.fromHex(layer, line);
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Folder marker for {} lists a malformed digest {}", folder, line, e);
                    continue;
                }
                ShaderDiskCache.remove(entry);
            }
            ShaderDiskCache.remove(marker);
        }
    }

    /**
     * Credits a submitted write to the running folder session. The caller is the thread that issued the
     * write, so the session map is guarded by {@link #WRITE_LOCK}; the future decides whether the key
     * ends up in the marker.
     */
    private static void creditFolder(ShaderDiskCache.Key diskKey, CompletableFuture<Boolean> write) {
        synchronized (WRITE_LOCK) {
            final FolderSession session = folderSession;
            if (session != null) session.writes.put(diskKey.hex(), write);
        }
    }

    private static ShaderDiskCache.Key compiledSettingsKey(String layer, String settings) {
        return ShaderDiskCache.key(layer + "/settings").str(driverId()).b(ShaderManager.isEnabled()).str(settings);
    }

    private static ShaderDiskCache.Key folderMarkerKey(String layer, String settings, String folder) {
        return ShaderDiskCache.key(layer + FOLDER_MARKERS).str(driverId()).b(ShaderManager.isEnabled()).str(settings).str(folder);
    }

    private static final class FolderSession {
        private final String layer;
        private final String settings;
        private final String folder;
        private final Map<String, CompletableFuture<Boolean>> writes = new LinkedHashMap<>();

        private FolderSession(String layer, String settings, String folder) {
            this.layer = layer;
            this.settings = settings;
            this.folder = folder;
        }
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
