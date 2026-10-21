package com.gtnewhorizons.angelica.glsm.shader;

import com.dhj.actinium.startup.ProgramCacheContextReset;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import net.coderbot.iris.gl.shader.ProgramCreator;
import net.coderbot.iris.gl.program.RetainedProgramsTestSupport;
import net.coderbot.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL41;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the saved-program cache through its production entry points: the layer a pack writes to, the
 * link-time hint, the restore/reject state machine and the composition of the cache key.
 *
 * <p>Reading and writing program binaries needs a driver, so the GL half is replaced by {@link FakeDriver}
 * through the package-visible port. Everything else - the disk layer, the counters and the rejection
 * bookkeeping - is the real code.</p>
 */
class ProgramBinaryCacheTest {

    /** Cache layer the pack "pack" writes to. */
    private static final String PACK_LAYER = ProgramBinaryCache.LAYER + "/pack";

    /**
     * A stand-in driver. It keeps the programs it restored, records every link-time parameter it was asked
     * for and can be told to reject a restore or to offer no binary at all, which is what the cache has to
     * tell apart from an ordinary miss.
     */
    private static final class FakeDriver implements ProgramBinaryOps {
        final Map<Integer, byte[]> restored = new LinkedHashMap<>();
        final Map<Integer, Integer> restoredFormats = new LinkedHashMap<>();
        final List<String> programParameters = new ArrayList<>();
        final List<Integer> created = new ArrayList<>();
        final List<Integer> deleted = new ArrayList<>();
        String fingerprint = "fake-vendor|fake-renderer|fake-version|4.60|false|460";
        boolean supported = true;
        boolean restoreLinks = true;
        byte[] binaryToSave;
        int formatToSave = 0x8C3A;
        int nextProgram = 1;

        @Override
        public boolean isSupported() {
            return supported;
        }

        @Override
        public String driverFingerprint() {
            return fingerprint;
        }

        @Override
        public int createProgram() {
            final int program = nextProgram++;
            created.add(program);
            return program;
        }

        @Override
        public void deleteProgram(int program) {
            deleted.add(program);
            restored.remove(program);
        }

        @Override
        public int getProgrami(int program, int pname) {
            if (pname == GL41.GL_PROGRAM_BINARY_LENGTH) {
                return binaryToSave == null ? 0 : binaryToSave.length;
            }
            if (pname == GL20.GL_LINK_STATUS) {
                return restoreLinks ? GL11.GL_TRUE : GL11.GL_FALSE;
            }
            throw new IllegalArgumentException("unexpected program parameter " + pname);
        }

        @Override
        public void programParameteri(int program, int pname, int value) {
            programParameters.add(program + ":" + pname + ":" + value);
        }

        @Override
        public void getProgramBinary(int program, IntBuffer length, IntBuffer binaryFormat, ByteBuffer binary) {
            if (binaryToSave == null) {
                length.put(0, 0);
                return;
            }
            // A real driver writes through the raw address and leaves the caller's buffer position alone.
            binary.duplicate().put(binaryToSave);
            binaryFormat.put(0, formatToSave);
            length.put(0, binaryToSave.length);
        }

        @Override
        public void programBinary(int program, int binaryFormat, ByteBuffer binary) {
            final ByteBuffer copy = binary.duplicate();
            final byte[] data = new byte[copy.remaining()];
            copy.get(data);
            restored.put(program, data);
            restoredFormats.put(program, binaryFormat);
        }
    }

    @TempDir
    Path cacheRoot;

    private FakeDriver driver;

    @BeforeEach
    void resetProcessState() {
        ShaderDiskCache.disable();
        ProgramBinaryCache.setEnabled(false);
        ProgramBinaryCache.usePack(null);
        ProgramBinaryCache.setOpsForTesting(null);
        ProgramBinaryCache.deleteAll();
        driver = new FakeDriver();
        ProgramBinaryCache.setOpsForTesting(driver);
    }

    @AfterEach
    void releaseProcessState() {
        ProgramBinaryCache.setEnabled(false);
        ProgramBinaryCache.usePack(null);
        ProgramBinaryCache.setOpsForTesting(null);
        ShaderDiskCache.disable();
    }

    /** Configures the disk cache and turns saving on for the pack "pack". */
    private void enableSaving() {
        ShaderDiskCache.configure(cacheRoot, "salt");
        ProgramBinaryCache.setEnabled(true);
        ProgramBinaryCache.usePack("pack");
    }

    /** Builds the key of a two-stage program with no attribute bindings. */
    private static ProgramBinaryCache.Key programKey(String vertex, String fragment) {
        return ProgramBinaryCache.key()
            .stage(ShaderType.VERTEX.id, vertex)
            .stage(ShaderType.FRAGMENT.id, fragment);
    }

    @Test
    void packsGetTheirOwnSanitizedLayer() throws IOException {
        ShaderDiskCache.configure(cacheRoot, "salt");
        ProgramBinaryCache.setEnabled(true);

        ProgramBinaryCache.usePack("Complementary Unbound v5.8.1!!");
        assertTrue(Files.isDirectory(cacheRoot.resolve("gl-program").resolve("Complementary_Unbound_v5.8.1__")),
            "the pack layer is created from the pack name, with everything the filesystem dislikes replaced");

        ProgramBinaryCache.usePack("x".repeat(200));
        assertTrue(Files.isDirectory(cacheRoot.resolve("gl-program").resolve("x".repeat(64))),
            "long pack names are cut off instead of producing a path too long for the filesystem");
    }

    @Test
    void turningSavingOffDropsEveryLayerAndKeepsTheCacheConfigured() throws IOException {
        enableSaving();
        ShaderDiskCache.put(ShaderDiskCache.key(PACK_LAYER).str("program"), new byte[] {1});
        assertTrue(Files.isDirectory(cacheRoot.resolve(PACK_LAYER)));

        ProgramBinaryCache.deleteAll();

        assertFalse(Files.exists(cacheRoot.resolve("gl-program")), "every saved program goes with the layer");
        assertTrue(Files.isRegularFile(cacheRoot.resolve("salt")), "the cache itself stays configured");
    }

    @Test
    void theRetrievableHintIsRequestedOnlyWhileSavingIsOn() {
        enableSaving();

        ProgramBinaryCache.markRetrievable(7);

        assertEquals(List.of(7 + ":" + GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT + ":" + GL11.GL_TRUE),
            driver.programParameters, "the link has to be told to keep the binary before the driver links it");

        ProgramBinaryCache.setEnabled(false);
        ProgramBinaryCache.markRetrievable(8);

        assertEquals(1, driver.programParameters.size(), "a program linked with saving off is never read back");
    }

    @Test
    void theRetrievableHintIsNotRequestedOnADriverWithoutBinarySupport() {
        enableSaving();
        driver.supported = false;

        ProgramBinaryCache.markRetrievable(7);

        assertTrue(driver.programParameters.isEmpty());
    }

    @Test
    void aProgramWithNoSavedBinaryIsAMissThatNeverAsksTheDriver() {
        enableSaving();

        assertEquals(0, ProgramBinaryCache.load(programKey("void main() {}", "void main() {}")));

        assertTrue(driver.created.isEmpty(), "nothing has to be created to discover that nothing was saved");
        assertTrue(driver.programParameters.isEmpty());
        assertTrue(ProgramBinaryCache.statsSummary().startsWith("progCache[hit=0,miss=1,hitRate=0.0%,"));
    }

    @Test
    void aLinkedProgramIsSavedAndRestoredWithItsBinaryFormat() throws IOException, InterruptedException {
        enableSaving();
        driver.binaryToSave = new byte[] {9, 8, 7};
        final ProgramBinaryCache.Key key = programKey("void main() { gl_Position = vec4(1.0); }", "void main() {}");

        ProgramBinaryCache.save(key, 42);
        awaitSavedBinary();

        final int restoredProgram = ProgramBinaryCache.load(key);

        assertNotEquals(0, restoredProgram, "the entry that was just written has to come back");
        assertArrayEquals(new byte[] {9, 8, 7}, driver.restored.get(restoredProgram),
            "the bytes handed to glProgramBinary are the bytes read from disk");
        assertEquals(driver.formatToSave, driver.restoredFormats.get(restoredProgram),
            "the driver-chosen format stored as the blob tag is the one the restore is told about");
        assertTrue(driver.deleted.isEmpty());
        final String summary = ProgramBinaryCache.statsSummary();
        assertTrue(summary.startsWith("progCache[hit=1,miss=0,hitRate=100.0%,save=1,reject=0,builds=0,restoreMs="),
            summary);
        assertTrue(summary.endsWith(",missBuildMs=0.000]"), summary);
    }

    @Test
    void aBinaryTheDriverRejectsIsDeletedWithdrawnAndReported() throws IOException {
        enableSaving();
        final ProgramBinaryCache.Key key = programKey("void main() {}", "void main() {}");
        ShaderDiskCache.putBlob(key.diskKey(), Integer.toString(0x8C3A), new byte[] {1, 2});
        driver.restoreLinks = false;

        assertEquals(0, ProgramBinaryCache.load(key), "a rejected binary must not be handed back to the caller");

        assertEquals(driver.created, driver.deleted, "the program created for the rejected binary is deleted again");
        assertNull(ShaderDiskCache.getBlob(key.diskKey()), "the unusable entry is withdrawn so the next load recompiles");
        assertTrue(ProgramBinaryCache.statsSummary()
            .startsWith("progCache[hit=0,miss=1,hitRate=0.0%,save=0,reject=1,builds=0,restoreMs="));
    }

    @Test
    void aBinaryWithAnUnreadableFormatTagIsWithdrawnWithoutAskingTheDriver() {
        enableSaving();
        final ProgramBinaryCache.Key key = programKey("void main() {}", "void main() {}");
        ShaderDiskCache.putBlob(key.diskKey(), "not-a-format", new byte[] {1, 2});

        assertEquals(0, ProgramBinaryCache.load(key));

        assertTrue(driver.created.isEmpty(), "a corrupt entry is recognised before a program is created");
        assertNull(ShaderDiskCache.getBlob(key.diskKey()));
        assertTrue(ProgramBinaryCache.statsSummary().startsWith("progCache[hit=0,miss=1,hitRate=0.0%,save=0,reject=0,"),
            "a corrupt entry is a miss, not a driver rejection");
    }

    @Test
    void nothingIsSavedWhenTheDriverOffersNoBinary() throws IOException {
        enableSaving();
        driver.binaryToSave = null;
        final ProgramBinaryCache.Key key = programKey("void main() {}", "void main() {}");

        ProgramBinaryCache.save(key, 42);

        assertEquals("", ProgramBinaryCache.statsSummary(),
            "a driver that refuses to hand out a binary is an expected miss, not a save");
        assertEquals(0L, countSavedBinaries(), "no entry may appear on disk");
    }

    @Test
    void theKeyCoversTheDriverEmulationAttributesAndEveryStageSource() {
        enableSaving();
        final ProgramBinaryCache.Key key = programKey("vertex-a", "fragment-a");
        final String baseline = key.hex();

        driver.fingerprint = "another-driver";
        // Reinstalling the port is what drops the memoized fingerprint, exactly like a new driver at runtime.
        ProgramBinaryCache.setOpsForTesting(driver);
        assertNotEquals(baseline, programKey("vertex-a", "fragment-a").hex(),
            "a binary is only valid for the driver that linked it");

        driver.fingerprint = "fake-vendor|fake-renderer|fake-version|4.60|false|460";
        ProgramBinaryCache.setOpsForTesting(driver);
        assertEquals(baseline, programKey("vertex-a", "fragment-a").hex(), "the driver fingerprint is memoized");
        assertNotEquals(baseline, programKey("vertex-b", "fragment-a").hex(), "every stage source is part of the key");
        assertNotEquals(baseline, programKey("vertex-a", "fragment-b").hex(), "every stage source is part of the key");
        assertNotEquals(baseline, programKey("vertex-a", "fragment-a").attribute("mc_Entity", 11).hex(),
            "the attribute bindings the program is linked with are part of the key");
        assertNotEquals(
            programKey("vertex-a", "fragment-a").attribute("mc_Entity", 11).hex(),
            programKey("vertex-a", "fragment-a").attribute("mc_Entity", 12).hex(),
            "an attribute that moves to another slot is a different program");
    }

    @Test
    void theKeyPinsTheLayerTheDriverTheEmulationFlagAndTheAttributeBindings() {
        enableSaving();

        // The expected digest is rebuilt from the documented components, so dropping any of them - the
        // layer, the driver fingerprint, the fixed-function emulation bit, or one of the four attribute
        // bindings - changes the digest and fails here.
        assertEquals(keyHexWithoutStages(), ProgramCreator.cacheKey().hex(),
            "the key has to describe exactly the attribute bindings the linker applies");
    }

    @Test
    void aContextChangeDropsTheMemoizedDriverFingerprint() {
        enableSaving();
        final String before = programKey("void main() {}", "void main() {}").hex();

        driver.fingerprint = "another-context-driver";
        assertEquals(before, programKey("void main() {}", "void main() {}").hex(),
            "the fingerprint is read once and reused for as long as the context it was read on survives");

        ProgramBinaryCache.invalidateContext();

        final String after = programKey("void main() {}", "void main() {}").hex();
        assertNotEquals(before, after, "a binary saved by the previous context must not be looked up as a hit");
        assertEquals(ShaderDiskCache.key(PACK_LAYER).str("another-context-driver")
                .b(ShaderManager.isGloballyEnabled())
                .str("stage").i(ShaderType.VERTEX.id).str("void main() {}")
                .str("stage").i(ShaderType.FRAGMENT.id).str("void main() {}")
                .hex(),
            after, "the key is rebuilt from the current fingerprint, on the pack layer that is still selected");
        assertTrue(ProgramBinaryCache.isEnabled(),
            "a context change neither deselects the pack nor turns saving off");
    }

    @Test
    void displayContextResetUsesTheProductionCallbackToRefreshTheDriverFingerprint() {
        enableSaving();
        final String before = programKey("void main() {}", "void main() {}").hex();

        driver.fingerprint = "callback-context-driver";
        RetainedProgramsTestSupport.installNoNativeContext();
        try {
            ProgramCacheContextReset.onDisplayContextChange();
        } finally {
            RetainedProgramsTestSupport.restoreProductionProvider();
        }

        final String after = programKey("void main() {}", "void main() {}").hex();
        assertNotEquals(before, after, "the installed host callback must invalidate the driver memo");
        assertTrue(ProgramBinaryCache.isEnabled(),
            "the callback resets driver identity without changing the selected pack or saving switch");
    }

    /** Rebuilds the program-independent part of the key from its documented components. */
    private static String keyHexWithoutStages() {
        return ShaderDiskCache.key(PACK_LAYER)
            .str("fake-vendor|fake-renderer|fake-version|4.60|false|460")
            .b(ShaderManager.isGloballyEnabled())
            .str("attribute").str("mc_Entity").i(11)
            .str("attribute").str("mc_midTexCoord").i(12)
            .str("attribute").str("at_tangent").i(13)
            .str("attribute").str("at_midBlock").i(14)
            .hex();
    }

    /** Counts the saved binaries in the pack layer; the worker writes them, so nothing is assumed about timing. */
    private long countSavedBinaries() throws IOException {
        final Path layer = cacheRoot.resolve(PACK_LAYER);
        if (!Files.isDirectory(layer)) return 0L;
        try (Stream<Path> listed = Files.list(layer)) {
            return listed.filter(path -> path.getFileName().toString().endsWith(".bin")).count();
        }
    }

    /** Waits for the worker that writes a saved binary, because the cache never blocks the render thread. */
    private void awaitSavedBinary() throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (countSavedBinaries() > 0L) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("the saved program never reached " + cacheRoot.resolve(PACK_LAYER));
    }
}
