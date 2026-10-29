package com.gtnewhorizons.angelica.glsm.shader;

import com.gtnewhorizons.angelica.glsm.threading.AngelicaWorkers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises disk cache encoding and lifecycle through its production API. */
class ShaderDiskCacheTest {
    /** Supplies isolated cache roots without changing process-wide files. */
    @TempDir
    Path temporaryDirectory;

    /** Clears a previous test configuration before each cache scenario. */
    @BeforeEach
    void disablePreviousGeneration() {
        ShaderDiskCache.disable();
    }

    /** Invalidates queued worker actions before JUnit removes the temporary root. */
    @AfterEach
    void disableTestGeneration() {
        ShaderDiskCache.disable();
    }

    /** Checks the existing file envelope and all typed payload APIs by round-tripping values. */
    @Test
    void writesAndReadsStringsMapsAndBlobs() throws IOException {
        ShaderDiskCache.configure(temporaryDirectory, "build-a");
        final ShaderDiskCache.Key stringKey = ShaderDiskCache.key("strings").str("source");
        final ShaderDiskCache.Key mapKey = ShaderDiskCache.key("maps").str("source");
        final ShaderDiskCache.Key blobKey = ShaderDiskCache.key("blobs").str("source");

        ShaderDiskCache.putString(stringKey, "#version 120\nvoid main() {}\n");
        ShaderDiskCache.putStrings(mapKey, Map.of("ONE", "1", "TWO", "2"));
        assertTrue(ShaderDiskCache.putBlob(blobKey, "binary-format", new byte[] {9, 8, 7}));

        assertEquals("#version 120\nvoid main() {}\n", ShaderDiskCache.getString(stringKey));
        assertEquals(Map.of("ONE", "1", "TWO", "2"), ShaderDiskCache.getStrings(mapKey));
        final ShaderDiskCache.Blob blob = ShaderDiskCache.getBlob(blobKey);
        assertNotNull(blob);
        assertEquals("binary-format", blob.tag());
        assertArrayEquals(new byte[] {9, 8, 7}, blob.data());

        final byte[] entry = Files.readAllBytes(entryPath(temporaryDirectory, "blobs", blobKey));
        assertEquals(0x41534331, readInt(entry, 0));
        assertEquals(entry.length - 12, readInt(entry, 4));
    }

    /** Ensures a CRC mismatch is reported as a miss and removes the unusable entry. */
    @Test
    void corruptCrcIsDiscarded() throws IOException {
        ShaderDiskCache.configure(temporaryDirectory, "build-a");
        final ShaderDiskCache.Key key = ShaderDiskCache.key("layer").str("corrupt");
        assertTrue(ShaderDiskCache.put(key, new byte[] {1, 2, 3, 4}));
        final Path file = entryPath(temporaryDirectory, "layer", key);
        final byte[] contents = Files.readAllBytes(file);
        contents[contents.length - 1] ^= 0x5a;
        Files.write(file, contents);

        assertNull(ShaderDiskCache.get(key));
        assertFalse(Files.exists(file));
    }

    /** Ensures typed decoding failures are observable through the miss-and-delete contract. */
    @Test
    void malformedBlobPayloadIsDiscarded() throws IOException {
        ShaderDiskCache.configure(temporaryDirectory, "build-a");
        final ShaderDiskCache.Key key = ShaderDiskCache.key("blob").str("bad-typed-payload");
        assertTrue(ShaderDiskCache.put(key, new byte[] {0, 0, 0, 5, 1}));
        final Path file = entryPath(temporaryDirectory, "blob", key);

        assertNull(ShaderDiskCache.getBlob(key));
        assertFalse(Files.exists(file));
    }

    /** Reuses entries for an unchanged salt and wipes them after the salt changes. */
    @Test
    void saltChangeInvalidatesEntries() {
        ShaderDiskCache.configure(temporaryDirectory, "build-a");
        final ShaderDiskCache.Key key = ShaderDiskCache.key("layer").str("value");
        assertTrue(ShaderDiskCache.put(key, new byte[] {3}));

        ShaderDiskCache.configure(temporaryDirectory, "build-a");
        assertArrayEquals(new byte[] {3}, ShaderDiskCache.get(key));
        ShaderDiskCache.configure(temporaryDirectory, "build-b");

        assertNull(ShaderDiskCache.get(ShaderDiskCache.key("layer").str("value")));
    }

    /** Prevents a queued old-generation write from entering a reconfigured cache root. */
    @Test
    void lateWriterCannotWriteIntoNextGeneration() throws Exception {
        final Path firstRoot = temporaryDirectory.resolve("first");
        final Path secondRoot = temporaryDirectory.resolve("second");
        ShaderDiskCache.configure(firstRoot, "build-a");
        final ShaderDiskCache.Key oldKey = ShaderDiskCache.key("layer").str("late");
        final CompletableFuture<Void> started = new CompletableFuture<>();
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final CompletableFuture<Boolean> lateWrite = AngelicaWorkers.submit(() -> {
            started.complete(null);
            awaitRelease(release);
            return ShaderDiskCache.put(oldKey, new byte[] {4, 5, 6});
        });
        started.get(5, TimeUnit.SECONDS);

        ShaderDiskCache.configure(secondRoot, "build-b");
        release.complete(null);

        assertFalse(lateWrite.get(5, TimeUnit.SECONDS));
        assertNull(ShaderDiskCache.get(ShaderDiskCache.key("layer").str("late")));
    }

    /** Ensures queued cleanup from an earlier generation cannot delete entries after reconfigure. */
    @Test
    void lateLayerDeletionCannotAffectNextGeneration() throws Exception {
        ShaderDiskCache.configure(temporaryDirectory, "build-a");
        final Runnable oldCleanup = ShaderDiskCache.bindCurrentGeneration(() -> ShaderDiskCache.deleteLayer("programs"));
        ShaderDiskCache.configure(temporaryDirectory, "build-b");
        final ShaderDiskCache.Key newKey = ShaderDiskCache.key("programs").str("new-generation");
        assertTrue(ShaderDiskCache.put(newKey, new byte[] {7}));

        AngelicaWorkers.run(oldCleanup).get(5, TimeUnit.SECONDS);

        assertArrayEquals(new byte[] {7}, ShaderDiskCache.get(newKey));
    }

    /** Exercises layer retention, recency selection, removal, and recursive deletion. */
    @Test
    void layerOperationsKeepAndDeleteEntries() throws IOException {
        ShaderDiskCache.configure(temporaryDirectory, "build-a");
        final ShaderDiskCache.Key retained = ShaderDiskCache.key("single").str("kept");
        final ShaderDiskCache.Key removed = ShaderDiskCache.key("single").str("removed");
        final ShaderDiskCache.Key older = ShaderDiskCache.key("packs/older").str("program");
        final ShaderDiskCache.Key newer = ShaderDiskCache.key("packs/newer").str("program");
        ShaderDiskCache.put(retained, new byte[] {1});
        ShaderDiskCache.put(removed, new byte[] {2});
        ShaderDiskCache.put(older, new byte[] {3});
        ShaderDiskCache.put(newer, new byte[] {4});
        Files.setLastModifiedTime(temporaryDirectory.resolve("packs/older"), FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(temporaryDirectory.resolve("packs/newer"), FileTime.fromMillis(2_000L));

        ShaderDiskCache.retainLayer("single", Set.of(retained.hex()));
        ShaderDiskCache.keepNewestSubLayers("packs", 1);

        assertNotNull(ShaderDiskCache.get(retained));
        assertNull(ShaderDiskCache.get(removed));
        assertNull(ShaderDiskCache.get(older));
        assertNotNull(ShaderDiskCache.get(newer));
        ShaderDiskCache.remove(newer);
        assertNull(ShaderDiskCache.get(newer));
        ShaderDiskCache.deleteLayer("packs");
        assertFalse(Files.exists(temporaryDirectory.resolve("packs")));
    }

    /** Reads a big-endian integer from the production cache envelope for format assertions. */
    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
            | (bytes[offset + 1] & 0xff) << 16
            | (bytes[offset + 2] & 0xff) << 8
            | (bytes[offset + 3] & 0xff);
    }

    /** Waits for the late-writer gate while preserving interruption and reporting a stuck test. */
    private static void awaitRelease(CompletableFuture<Void> release) {
        try {
            release.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to release the late cache writer", exception);
        } catch (ExecutionException exception) {
            throw new AssertionError("Late cache writer release failed", exception);
        } catch (TimeoutException exception) {
            throw new AssertionError("Timed out waiting to release the late cache writer", exception);
        }
    }

    /** Resolves the file generated by the production layer and key hashing rules. */
    private static Path entryPath(Path root, String layer, ShaderDiskCache.Key key) {
        return root.resolve(layer).resolve(key.hex() + ".bin");
    }
}
