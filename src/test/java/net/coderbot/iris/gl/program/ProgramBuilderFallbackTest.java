package net.coderbot.iris.gl.program;

import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCacheTestSupport;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryOps;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import org.lwjgl.opengl.GL41;
import org.junit.jupiter.api.io.TempDir;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the order a program is obtained in: the pool of programs a destroyed pipeline kept, then the
 * binary a previous run saved to disk, then a compile and link, and that every earlier hit short-circuits the
 * later tiers.
 *
 * <p>The disk and link tiers are replaced by a recording {@link ProgramFallback}, which is the same split the
 * program-binary cache uses for its driver ({@code ProgramBinaryOps}): the ordering is plain Java, only the
 * tiers themselves need GL.</p>
 */
class ProgramBuilderFallbackTest {
	private static final String FULL_COMPILE_SETTINGS = "pack-state";

    private FakeFallback fallback;
    private TestProgramContextProvider contextProvider;
    @TempDir
    Path cacheRoot;

    @BeforeEach
    void resetPool() {
        ProgramBinaryCache.setEnabled(false);
        ProgramBinaryCache.usePack(null);
        ProgramBinaryCacheTestSupport.installOps(null);
        ShaderDiskCache.disable();
        ProgramBinaryCache.deleteAll();
        contextProvider = new TestProgramContextProvider();
        RetainedPrograms.setContextProviderForTesting(contextProvider);
        RetainedPrograms.discardAll();
        RetainedPrograms.setDeleterForTesting(program -> {});
        RetainedPrograms.useConfiguration(null);
        fallback = new FakeFallback();
    }

    @AfterEach
    void releasePool() {
        if (ProgramBinaryCache.isFullCompileRunning()) {
            ProgramBinaryCache.finishFullCompile(null);
        }
        RetainedPrograms.useConfiguration(null);
        RetainedPrograms.discardAll();
        RetainedPrograms.setDeleterForTesting(null);
        RetainedPrograms.setContextProviderForTesting(null);
        ProgramBinaryCache.setEnabled(false);
        ProgramBinaryCache.usePack(null);
        ProgramBinaryCache.deleteAll();
        ShaderDiskCache.disable();
        ProgramBinaryCacheTestSupport.installOps(null);
    }

    @Test
    void aKeptProgramNeitherReadsTheDiskNorLinksTheStages() {
        final RetainedPrograms.Sources sources = graphics("void main() { gl_Position = vec4(0.0); }", "void main() {}");
        RetainedPrograms.retain(sources, 42);

        assertEquals(42, ProgramBuilder.acquire("gbuffers_basic", sources, fallback));

        assertEquals(0, fallback.cacheKeyCalls, "a kept program does not even build a cache key");
        assertEquals(0, fallback.restoreCalls, "a kept program never reaches the disk tier");
        assertEquals(0, fallback.compileCalls, "a kept program never creates a shader or a program");
    }

    @Test
    void retainedGraphicsAndComputeProgramsAreSavedDuringTheFullCompile() throws InterruptedException {
        final CacheDriver driver = enableProgramCache();
        final RetainedPrograms.Sources graphics = graphics("vertex", "fragment");
        final RetainedPrograms.Sources compute = compute("compute");
        RetainedPrograms.retain(graphics, 42);
        RetainedPrograms.retain(compute, 43);
        ProgramBinaryCache.beginFullCompile();

        assertEquals(42, ProgramBuilder.acquire("gbuffers_basic", graphics, fallback));
        assertEquals(43, ProgramBuilder.acquire("compute", compute, fallback));

        assertEquals(List.of(42, 43), driver.savedPrograms,
            "graphics and compute retained hits both reach the binary cache during a full compile");
        assertEquals(2, fallback.cacheKeyCalls);
        assertEquals(0, fallback.restoreCalls, "a successfully saved retained program does not need disk restore");
        assertEquals(0, fallback.compileCalls, "a successfully saved retained program does not need relinking");

        ProgramBinaryCache.finishFullCompile(FULL_COMPILE_SETTINGS);
        awaitFullCompileMarker();
        assertTrue(ProgramBinaryCache.wasCompiledFor(FULL_COMPILE_SETTINGS),
            "the full compile marker is written after every retained binary reaches disk");
    }

    @Test
    void aRetainedProgramThatCannotBeSavedIsDeletedAndRelinkedWithoutMarkingTheFullCompileComplete() {
        final CacheDriver driver = enableProgramCache();
        driver.binary = null;
        final RetainedPrograms.Sources sources = compute("compute");
        RetainedPrograms.retain(sources, 42);
        fallback.compiled = 51;
        ProgramBinaryCache.beginFullCompile();

        assertEquals(51, ProgramBuilder.acquire("compute", sources, fallback));

        assertEquals(List.of(42), fallback.deletedPrograms,
            "a retained program whose binary could not be saved is released before restore or relink");
        assertEquals(1, fallback.restoreCalls);
        assertEquals(1, fallback.compileCalls);
        ProgramBinaryCache.finishFullCompile(FULL_COMPILE_SETTINGS);
        assertFalse(ProgramBinaryCache.wasCompiledFor(FULL_COMPILE_SETTINGS),
            "the relink also failed to save a binary, so the incomplete cache cannot get a full-compile marker");
    }

    @Test
    void aSavedBinaryIsRestoredInsteadOfLinkingTheStages() {
        fallback.restored = 7;

        assertEquals(7, ProgramBuilder.acquire("gbuffers_basic", graphics("vertex", "fragment"), fallback));

        assertEquals(1, fallback.cacheKeyCalls);
        assertEquals(1, fallback.restoreCalls);
        assertEquals(0, fallback.compileCalls, "a restored program is not linked from source again");
    }

    @Test
    void aProgramKeptNowhereIsLinkedAndKeepsItsKeyForTheSave() {
        fallback.restored = 0;
        final RetainedPrograms.Sources sources = graphics("vertex", "fragment");
        fallback.compiled = 11;

        assertEquals(11, ProgramBuilder.acquire("gbuffers_basic", sources, fallback));

        assertEquals(1, fallback.restoreCalls);
        assertEquals(1, fallback.compileCalls);
        assertSame(sources, fallback.compiledSources, "the linker receives the stages the key was built for");
    }

    @Test
    void aProgramKeptInThePoolIsLinkedAgainOnceItWasTaken() {
        final RetainedPrograms.Sources sources = graphics("vertex", "fragment");
        RetainedPrograms.retain(sources, 42);
        fallback.compiled = 11;

        assertEquals(42, ProgramBuilder.acquire("gbuffers_basic", sources, fallback));
        assertEquals(11, ProgramBuilder.acquire("gbuffers_basic", sources, fallback));

        assertEquals(1, fallback.compileCalls, "the pool hands each program out once, so the next pipeline links");
    }

    @Test
    void aKeptProgramWithOtherStagesDoesNotMatch() {
        RetainedPrograms.retain(graphics("vertex", "fragment"), 42);
        fallback.compiled = 11;

        assertEquals(11, ProgramBuilder.acquire("shadow", graphics("vertex", "other fragment"), fallback));

        assertEquals(1, fallback.compileCalls);
    }

    private static RetainedPrograms.Sources graphics(String vertex, String fragment) {
        return new RetainedPrograms.Sources(vertex, null, null, null, fragment, null);
    }

    private static RetainedPrograms.Sources compute(String source) {
        return new RetainedPrograms.Sources(null, null, null, null, null, source);
    }

    private CacheDriver enableProgramCache() {
        final CacheDriver driver = new CacheDriver();
        ProgramBinaryCacheTestSupport.installOps(driver);
        ShaderDiskCache.configure(cacheRoot, "salt");
        ProgramBinaryCache.setEnabled(true);
        ProgramBinaryCache.usePack("pack");
        return driver;
    }

    private static void awaitFullCompileMarker() throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (ProgramBinaryCache.wasCompiledFor(FULL_COMPILE_SETTINGS)) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(ProgramBinaryCache.wasCompiledFor(FULL_COMPILE_SETTINGS),
            "the cache never marked a full compile after its retained binaries were written");
    }

    /** Records which tier was reached; the returned key is a stand-in because the tiers never touch GL. */
    private static final class FakeFallback implements ProgramFallback {
        private int cacheKeyCalls;
        private int restoreCalls;
        private int compileCalls;
        private int restored;
        private int compiled;
        private RetainedPrograms.Sources compiledSources;
        private final List<Integer> deletedPrograms = new ArrayList<>();

        @Override
        @Nullable
        public ProgramBinaryCache.Key cacheKey(RetainedPrograms.Sources sources) {
            cacheKeyCalls++;
            return new ProgramFallbackImpl().cacheKey(sources);
        }

        @Override
        public int restore(String name, @Nullable ProgramBinaryCache.Key cacheKey) {
            restoreCalls++;
            return restored;
        }

        @Override
        public int compile(String name, @Nullable ProgramBinaryCache.Key cacheKey, RetainedPrograms.Sources sources) {
            compileCalls++;
            compiledSources = sources;
            if (cacheKey != null) {
                ProgramBinaryCache.save(cacheKey, compiled);
            }
            return compiled;
        }

        @Override
        public void deleteProgram(int program) {
            deletedPrograms.add(program);
        }
    }

    /** Supplies real cache reads and writes without a live OpenGL context. */
    private static final class CacheDriver implements ProgramBinaryOps {
        private static final String DRIVER_ID = "fake-vendor|fake-renderer|fake-version|4.60|false|460";
        private static final int BINARY_FORMAT = 0x8C3A;

        private final List<Integer> savedPrograms = new ArrayList<>();
        private byte[] binary = {9, 8, 7};

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public String driverFingerprint() {
            return DRIVER_ID;
        }

        @Override
        public int createProgram() {
            throw new UnsupportedOperationException("retained-hit tests never restore a binary");
        }

        @Override
        public void deleteProgram(int program) {
            throw new UnsupportedOperationException("the fallback owns retained-program deletion in these tests");
        }

        @Override
        public int getProgrami(int program, int pname) {
            if (pname != GL41.GL_PROGRAM_BINARY_LENGTH) {
                throw new IllegalArgumentException("unexpected program parameter " + pname);
            }
            return binary == null ? 0 : binary.length;
        }

        @Override
        public void programParameteri(int program, int pname, int value) {
            throw new UnsupportedOperationException("retained-hit tests do not link new programs");
        }

        @Override
        public void getProgramBinary(int program, IntBuffer length, IntBuffer binaryFormat, ByteBuffer data) {
            savedPrograms.add(program);
            length.put(0, binary.length);
            binaryFormat.put(0, BINARY_FORMAT);
            data.duplicate().put(binary);
        }

        @Override
        public void programBinary(int program, int binaryFormat, ByteBuffer data) {
            throw new UnsupportedOperationException("retained-hit tests do not restore a binary");
        }
    }
}
