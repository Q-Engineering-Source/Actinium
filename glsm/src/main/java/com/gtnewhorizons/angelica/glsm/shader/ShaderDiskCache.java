package com.gtnewhorizons.angelica.glsm.shader;

import com.gtnewhorizons.angelica.glsm.threading.AngelicaWorkers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static com.gtnewhorizons.angelica.glsm.shader.ShaderCacheIO.readBytes;
import static com.gtnewhorizons.angelica.glsm.shader.ShaderCacheIO.readCount;
import static com.gtnewhorizons.angelica.glsm.shader.ShaderCacheIO.readStr;
import static com.gtnewhorizons.angelica.glsm.shader.ShaderCacheIO.writeBytes;
import static com.gtnewhorizons.angelica.glsm.shader.ShaderCacheIO.writeStr;

/** Stores shader cache payloads in versioned, checksummed files under the configured cache root. */
public final class ShaderDiskCache {
    /** Identifies cache IO warnings and configuration messages in the game log. */
    private static final Logger LOGGER = LogManager.getLogger("ShaderDiskCache");
    /** Synchronizes generation changes with reads, writes, and layer mutations. */
    private static final Object LOCK = new Object();
    /** Marks the existing 1.12 shader cache file format. */
    private static final int MAGIC = 0x41534331;
    /** Stores magic, payload length, and CRC32 before each cache payload. */
    private static final int HEADER_BYTES = 12;
    /** Keeps the cache bounded by the same limit used by the original implementation. */
    static final long MAX_BYTES = 256L << 20;
    /** Prevents collisions between temporary files created by concurrent cache writers. */
    private static final AtomicInteger TMP_SEQ = new AtomicInteger();
    /** Identifies the active root and salt; replacing this object invalidates captured keys. */
    private static volatile CacheState currentState;
    /** Advances whenever configuration is replaced or disabled. */
    private static long nextGeneration;

    /** Prevents constructing this static cache utility. */
    private ShaderDiskCache() {}

    /** Configures the cache root and invalidates entries when the build salt changes. */
    public static void configure(Path directory, String salt) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(salt, "salt");
        final Path root = directory.toAbsolutePath().normalize();
        final CacheState configured;
        synchronized (LOCK) {
            final CacheState old = currentState;
            if (old != null && old.root.equals(root) && old.salt.equals(salt)) return;
            currentState = null;
            try {
                Files.createDirectories(root);
                final Path saltFile = root.resolve("salt");
                final String existingSalt = Files.isRegularFile(saltFile)
                    ? new String(Files.readAllBytes(saltFile), StandardCharsets.UTF_8)
                    : null;
                if (!salt.equals(existingSalt)) {
                    wipe(root);
                    Files.write(saltFile, salt.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                }
                configured = new CacheState(root, salt, ++nextGeneration);
                currentState = configured;
                LOGGER.info("Shader disk cache configured at {}", root);
                LOGGER.debug("Shader disk cache is using configuration generation {}", configured.generation);
            } catch (IOException | RuntimeException exception) {
                ++nextGeneration;
                currentState = null;
                LOGGER.warn("Shader disk cache disabled because configuration failed for {}", root, exception);
                return;
            }
        }

        try {
            AngelicaWorkers.run(() -> enforceCap(configured, MAX_BYTES));
        } catch (RuntimeException exception) {
            configured.capFailureLogged.compareAndSet(false, true);
            LOGGER.warn("Shader disk cache cap check could not be scheduled for {}", root, exception);
        }
    }

    /** Disables the cache and invalidates keys or cleanup tasks from the previous generation. */
    public static void disable() {
        synchronized (LOCK) {
            if (currentState == null) return;
            currentState = null;
            ++nextGeneration;
        }
    }

    /** Reports whether a cache root is currently configured. */
    public static boolean isEnabled() {
        return currentState != null;
    }

    /** Captures the current cache generation in a key builder. */
    public static Key key(String layer) {
        return new Key(layer, currentState);
    }

    /** Reads a payload, returning null for a miss or an invalid entry. */
    public static byte[] get(Key key) {
        Objects.requireNonNull(key, "key");
        final CacheState expected = key.state;
        if (expected == null) return null;
        synchronized (LOCK) {
            if (currentState != expected) return null;
            final Path file = key.path(expected.root);
            final CacheRead read;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                read = read(channel);
            } catch (NoSuchFileException ignored) {
                return null;
            } catch (EOFException exception) {
                reportCorrupt(expected, file, "truncated cache file", exception);
                deleteFile(expected, file);
                return null;
            } catch (IOException | RuntimeException exception) {
                reportReadFailure(expected, file, exception);
                return null;
            }
            if (read.corruption != null) {
                reportCorrupt(expected, file, read.corruption, null);
                deleteFile(expected, file);
            }
            return read.payload;
        }
    }

    /** Reads and CRC-checks one existing cache file. */
    private static CacheRead read(FileChannel channel) throws IOException {
        final long size = channel.size();
        if (size < HEADER_BYTES) return CacheRead.corrupt("file is shorter than the cache header");
        if (size - HEADER_BYTES > MAX_BYTES) return CacheRead.corrupt("payload exceeds the cache size limit");
        final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        readFully(channel, header);
        header.flip();
        if (header.getInt() != MAGIC) return CacheRead.corrupt("header magic does not match");
        final int length = header.getInt();
        final int expectedCrc = header.getInt();
        if (length < 0 || length != size - HEADER_BYTES) return CacheRead.corrupt("payload length does not match the file size");
        final byte[] payload = new byte[length];
        readFully(channel, ByteBuffer.wrap(payload));
        final CRC32 crc = new CRC32();
        crc.update(payload, 0, length);
        if ((int) crc.getValue() != expectedCrc) return CacheRead.corrupt("payload CRC32 does not match");
        return CacheRead.valid(payload);
    }

    /** Reads every requested byte and reports truncation as an EOF failure. */
    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new EOFException();
        }
    }

    /** Writes a checksummed payload if the key still belongs to the active generation. */
    public static boolean put(Key key, byte[] payload) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        final CacheState expected = key.state;
        if (expected == null) return false;
        if (payload.length > MAX_BYTES) {
            reportWriteFailure(expected, key.path(expected.root), new IOException("payload exceeds the cache size limit"));
            return false;
        }
        synchronized (LOCK) {
            if (currentState != expected) return false;
            final Path target = key.path(expected.root);
            final Path temporary = target.resolveSibling(key.hex() + '.' + TMP_SEQ.getAndIncrement() + ".tmp");
            try {
                Files.createDirectories(target.getParent());
                write(temporary, payload);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException | RuntimeException exception) {
                deleteTemporary(expected, temporary);
                reportWriteFailure(expected, target, exception);
                return false;
            }
        }
    }

    /** Writes the existing cache envelope and payload to a temporary file. */
    private static void write(Path temporary, byte[] payload) throws IOException {
        final CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length);
        final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(MAGIC).putInt(payload.length).putInt((int) crc.getValue()).flip();
        final ByteBuffer body = ByteBuffer.wrap(payload);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (header.hasRemaining() || body.hasRemaining()) channel.write(new ByteBuffer[] {header, body});
        }
    }

    /** Reads a UTF-8 string from a valid cache entry. */
    public static String getString(Key key) {
        final byte[] payload = get(key);
        return payload == null ? null : new String(payload, StandardCharsets.UTF_8);
    }

    /** Writes a UTF-8 string to the cache. */
    public static void putString(Key key, String value) {
        Objects.requireNonNull(value, "value");
        put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads a string map payload and deletes it if its typed encoding is malformed. */
    public static Map<String, String> getStrings(Key key) {
        final byte[] payload = get(key);
        if (payload == null) return null;
        try {
            final ByteBuffer input = ByteBuffer.wrap(payload);
            final int count = readCount(input);
            final Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) values.put(readStr(input), readStr(input));
            if (input.hasRemaining()) throw new IllegalArgumentException("Trailing bytes in string map payload");
            return values;
        } catch (RuntimeException exception) {
            discardMalformedPayload(key, "string map", exception);
            return null;
        }
    }

    /** Writes a string map using the existing count-and-pairs payload encoding. */
    public static void putStrings(Key key, Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(values.size());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                writeStr(output, entry.getKey());
                writeStr(output, entry.getValue());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        put(key, bytes.toByteArray());
    }

    /** Reads a tagged byte blob and deletes it if its typed encoding is malformed. */
    public static Blob getBlob(Key key) {
        final byte[] payload = get(key);
        if (payload == null) return null;
        try {
            final ByteBuffer input = ByteBuffer.wrap(payload);
            final String tag = readStr(input);
            final byte[] data = readBytes(input);
            if (input.hasRemaining()) throw new IllegalArgumentException("Trailing bytes in blob payload");
            return new Blob(tag, data);
        } catch (RuntimeException exception) {
            discardMalformedPayload(key, "blob", exception);
            return null;
        }
    }

    /** Writes a tag and byte array using the existing typed blob encoding. */
    public static boolean putBlob(Key key, String tag, byte[] data) {
        Objects.requireNonNull(data, "data");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(data.length + 64);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeStr(output, tag);
            writeBytes(output, data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return put(key, bytes.toByteArray());
    }

    /** Carries the type tag and raw data stored by the blob API.
     *
     * @param tag payload type identifier
     * @param data encoded payload bytes
     */
    public record Blob(String tag, byte[] data) {}

    /** Removes one entry if its key still belongs to the active cache generation. */
    public static void remove(Key key) {
        Objects.requireNonNull(key, "key");
        final CacheState expected = key.state;
        if (expected == null) return;
        synchronized (LOCK) {
            if (currentState == expected) deleteFile(expected, key.path(expected.root));
        }
    }

    /** Deletes every binary entry in a layer that is not represented by a kept key hash. */
    public static void retainLayer(String layer, Set<String> keep) {
        final CacheState expected = currentState;
        if (expected == null) return;
        synchronized (LOCK) {
            if (currentState != expected) return;
            final Path directory = resolveLayer(expected.root, layer);
            final List<Path> files = new ArrayList<>();
            try (Stream<Path> listed = Files.list(directory)) {
                listed.forEach(files::add);
            } catch (NoSuchFileException ignored) {
                return;
            } catch (IOException | RuntimeException exception) {
                reportLayerFailure(expected, "could not list", directory, exception);
                return;
            }
            for (Path file : files) {
                final String name = file.getFileName().toString();
                if (!name.endsWith(".bin")) continue;
                final String hash = name.substring(0, name.length() - 4);
                if (keep != null && keep.contains(hash)) continue;
                deleteFile(expected, file);
            }
        }
    }

    /** Captures the cache generation so queued maintenance cannot mutate a later configuration. */
    public static Runnable bindCurrentGeneration(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        final CacheState expected = currentState;
        return () -> {
            if (expected == null) return;
            synchronized (LOCK) {
                if (currentState == expected) operation.run();
            }
        };
    }

    /** Marks a layer as recently used for newest-sub-layer retention. */
    public static void touchLayer(String layer) {
        final CacheState expected = currentState;
        if (expected == null) return;
        synchronized (LOCK) {
            if (currentState != expected) return;
            final Path directory = resolveLayer(expected.root, layer);
            try {
                Files.createDirectories(directory);
                Files.setLastModifiedTime(directory, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException | RuntimeException exception) {
                reportLayerFailure(expected, "could not mark as used", directory, exception);
            }
        }
    }

    /** Keeps only the newest requested number of child directories under a cache layer. */
    public static void keepNewestSubLayers(String layer, int keep) {
        if (keep < 0) throw new IllegalArgumentException("keep must not be negative");
        final CacheState expected = currentState;
        if (expected == null) return;
        synchronized (LOCK) {
            if (currentState != expected) return;
            final Path directory = resolveLayer(expected.root, layer);
            final List<Path> children = new ArrayList<>();
            try (Stream<Path> listed = Files.list(directory)) {
                listed.filter(Files::isDirectory).forEach(children::add);
            } catch (NoSuchFileException ignored) {
                return;
            } catch (IOException | RuntimeException exception) {
                reportLayerFailure(expected, "could not list", directory, exception);
                return;
            }
            children.sort(Comparator.comparingLong(ShaderDiskCache::lastModifiedMillis).reversed());
            for (int i = keep; i < children.size(); i++) deleteRecursively(expected, children.get(i));
        }
    }

    /** Deletes all files and child directories in the requested layer. */
    public static void deleteLayer(String layer) {
        final CacheState expected = currentState;
        if (expected == null) return;
        synchronized (LOCK) {
            if (currentState == expected) deleteRecursively(expected, resolveLayer(expected.root, layer));
        }
    }

    /** Returns a layer timestamp, treating unreadable timestamps as the oldest value. */
    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (NoSuchFileException ignored) {
            return 0L;
        } catch (IOException exception) {
            final CacheState expected = currentState;
            if (expected != null) reportLayerFailure(expected, "could not read modification time for", path, exception);
            return 0L;
        }
    }

    /** Removes an entry after typed payload decoding fails. */
    private static void discardMalformedPayload(Key key, String kind, RuntimeException exception) {
        final CacheState expected = key.state;
        if (expected == null) return;
        synchronized (LOCK) {
            if (currentState != expected) return;
            final Path file = key.path(expected.root);
            if (expected.corruptionLogged.compareAndSet(false, true)) {
                LOGGER.warn("Shader disk cache discarded malformed {} payload at {}", kind, file, exception);
            }
            deleteFile(expected, file);
        }
    }

    /** Logs a malformed cache envelope once per cache generation. */
    private static void reportCorrupt(CacheState expected, Path file, String reason, Exception exception) {
        if (!expected.corruptionLogged.compareAndSet(false, true)) return;
        if (exception == null) LOGGER.warn("Shader disk cache discarded corrupt entry at {}: {}", file, reason);
        else LOGGER.warn("Shader disk cache discarded corrupt entry at {}: {}", file, reason, exception);
    }

    /** Logs a cache read failure once per cache generation while preserving the miss behavior. */
    private static void reportReadFailure(CacheState expected, Path file, Exception exception) {
        if (expected.readFailureLogged.compareAndSet(false, true)) {
            LOGGER.warn("Shader disk cache read failed for {}; treating it as a miss", file, exception);
        }
    }

    /** Logs a cache write failure once per cache generation. */
    private static void reportWriteFailure(CacheState expected, Path file, Exception exception) {
        if (expected.writeFailureLogged.compareAndSet(false, true)) {
            LOGGER.warn("Shader disk cache write failed for {}", file, exception);
        }
    }

    /** Logs a cleanup or layer operation failure once per cache generation. */
    private static void reportLayerFailure(CacheState expected, String action, Path path, Exception exception) {
        if (expected.layerFailureLogged.compareAndSet(false, true)) {
            LOGGER.warn("Shader disk cache {} {}", action, path, exception);
        }
    }

    /** Deletes a file and reports failures instead of hiding damaged or unwritable cache state. */
    private static void deleteFile(CacheState expected, Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException exception) {
            reportLayerFailure(expected, "could not delete", file, exception);
        }
    }

    /** Removes an incomplete temporary write after the atomic replacement failed. */
    private static void deleteTemporary(CacheState expected, Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException exception) {
            reportLayerFailure(expected, "could not delete temporary file", temporary, exception);
        }
    }

    /** Recursively removes a directory in reverse path order while reporting failures. */
    private static void deleteRecursively(CacheState expected, Path directory) {
        final List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.forEach(paths::add);
        } catch (NoSuchFileException ignored) {
            return;
        } catch (IOException | RuntimeException exception) {
            reportLayerFailure(expected, "could not list", directory, exception);
            return;
        }
        Collections.reverse(paths);
        for (Path path : paths) deleteFile(expected, path);
    }

    /** Schedules the initial size check only while its captured generation remains active. */
    private static void enforceCap(CacheState expected, long maxBytes) {
        synchronized (LOCK) {
            if (currentState != expected) return;
            enforceCapLocked(expected, maxBytes);
        }
    }

    /** Synchronously checks the active root size for package tests and maintenance callers. */
    static void enforceCap(Path directory, long maxBytes) {
        final CacheState expected = currentState;
        if (expected == null) return;
        synchronized (LOCK) {
            if (currentState != expected || !expected.root.equals(directory.toAbsolutePath().normalize())) return;
            enforceCapLocked(expected, maxBytes);
        }
    }

    /** Wipes cache entries only when the root exceeds its configured byte limit. */
    private static void enforceCapLocked(CacheState expected, long maxBytes) {
        final long[] total = {0L};
        try {
            Files.walkFileTree(expected.root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    total[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    reportLayerFailure(expected, "could not inspect", file, exception);
                    return FileVisitResult.CONTINUE;
                }
            });
            if (total[0] > maxBytes) wipe(expected.root);
        } catch (IOException | RuntimeException exception) {
            if (expected.capFailureLogged.compareAndSet(false, true)) {
                LOGGER.warn("Shader disk cache cap check failed for {}", expected.root, exception);
            }
        }
    }

    /** Deletes cache payload files while preserving the root salt file. */
    private static void wipe(Path directory) throws IOException {
        final List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.forEach(paths::add);
        }
        for (Path path : paths) {
            if (Files.isRegularFile(path) && !path.equals(directory.resolve("salt"))) Files.deleteIfExists(path);
        }
    }

    /** Resolves a relative cache layer and prevents it from escaping the configured root. */
    private static Path resolveLayer(Path root, String layer) {
        Objects.requireNonNull(layer, "layer");
        final Path relative = Path.of(layer).normalize();
        if (relative.isAbsolute() || relative.toString().isEmpty() || relative.startsWith("..")) {
            throw new IllegalArgumentException("Cache layer must stay below the cache root: " + layer);
        }
        final Path normalizedRoot = root.toAbsolutePath().normalize();
        final Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) throw new IllegalArgumentException("Cache layer must stay below the cache root: " + layer);
        return resolved;
    }

    /** Represents an immutable cache root and generation identity. */
    private static final class CacheState {
        /** Root directory containing this generation's files. */
        private final Path root;
        /** Build salt stored in the root's existing salt file. */
        private final String salt;
        /** Monotonic diagnostic generation number. */
        private final long generation;
        /** Rate-limits corruption reports for this generation. */
        private final AtomicBoolean corruptionLogged = new AtomicBoolean();
        /** Rate-limits read failure reports for this generation. */
        private final AtomicBoolean readFailureLogged = new AtomicBoolean();
        /** Rate-limits write failure reports for this generation. */
        private final AtomicBoolean writeFailureLogged = new AtomicBoolean();
        /** Rate-limits cleanup failure reports for this generation. */
        private final AtomicBoolean layerFailureLogged = new AtomicBoolean();
        /** Rate-limits cap check failure reports for this generation. */
        private final AtomicBoolean capFailureLogged = new AtomicBoolean();

        /** Creates the immutable state captured by cache keys and maintenance work.
         *
         * @param root cache directory for this configuration
         * @param salt build identity persisted beside cache entries
         * @param generation monotonic identity assigned to this configuration
         */
        private CacheState(Path root, String salt, long generation) {
            this.root = root;
            this.salt = salt;
            this.generation = generation;
        }
    }

    /** Carries either a valid payload or a reason that its envelope is corrupt.
     *
     * @param payload decoded bytes when the envelope is valid
     * @param corruption explanation when the envelope is invalid
     */
    private record CacheRead(byte[] payload, String corruption) {
        /** Builds a result containing a valid payload. */
        private static CacheRead valid(byte[] payload) {
            return new CacheRead(payload, null);
        }

        /** Builds a result describing a corrupt envelope. */
        private static CacheRead corrupt(String reason) {
            return new CacheRead(null, reason);
        }
    }

    /** Builds a stable SHA-256 key and binds it to the configuration generation. */
    public static final class Key {
        /** Relative layer storing this key's entry. */
        private final String layer;
        /** Cache generation captured when the key was created. */
        private final CacheState state;
        /** Incremental digest of the layer and caller-supplied key fields. */
        private final MessageDigest digest;
        /** Cached lowercase hexadecimal digest after the key is finalized. */
        private String hex;

        /** Creates a key bound to the selected cache layer and active generation. */
        private Key(String layer, CacheState state) {
            resolveLayer(Path.of("."), layer);
            this.layer = layer;
            this.state = state;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is required for shader cache keys", exception);
            }
            str(layer);
        }

        /** Adds a nullable UTF-8 string to the key using an unambiguous length prefix. */
        public Key str(String value) {
            ensureMutable();
            if (value == null) return i(-1);
            final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            i(utf8.length);
            digest.update(utf8);
            return this;
        }

        /** Adds a four-byte integer to the key. */
        public Key i(int value) {
            ensureMutable();
            digest.update((byte) (value >>> 24));
            digest.update((byte) (value >>> 16));
            digest.update((byte) (value >>> 8));
            digest.update((byte) value);
            return this;
        }

        /** Adds a single boolean byte to the key. */
        public Key b(boolean value) {
            ensureMutable();
            digest.update((byte) (value ? 1 : 0));
            return this;
        }

        /** Adds a byte array with its length prefix to the key. */
        public Key bytes(byte[] value) {
            Objects.requireNonNull(value, "value");
            ensureMutable();
            i(value.length);
            digest.update(value);
            return this;
        }

        /** Adds a collection of strings in sorted order to make set-like keys stable. */
        public Key sortedStrs(Collection<String> values) {
            Objects.requireNonNull(values, "values");
            ensureMutable();
            final String[] sorted = values.toArray(new String[0]);
            Arrays.sort(sorted);
            i(sorted.length);
            for (String value : sorted) str(value);
            return this;
        }

        /** Finalizes this key and returns its lowercase SHA-256 representation. */
        public String hex() {
            if (hex == null) {
                final byte[] hash = digest.digest();
                final StringBuilder result = new StringBuilder(hash.length * 2);
                for (byte value : hash) {
                    result.append(Character.forDigit((value >>> 4) & 0xF, 16));
                    result.append(Character.forDigit(value & 0xF, 16));
                }
                hex = result.toString();
            }
            return hex;
        }

        /** Resolves the final cache file below the selected generation root. */
        private Path path(Path root) {
            return resolveLayer(root, layer).resolve(hex() + ".bin");
        }

        /** Prevents a key from being changed after its digest has been observed. */
        private void ensureMutable() {
            if (hex != null) throw new IllegalStateException("A finalized shader cache key cannot be changed");
        }
    }
}
