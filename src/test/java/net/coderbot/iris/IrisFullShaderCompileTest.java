package net.coderbot.iris;

import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCacheTestSupport;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryOps;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import net.coderbot.iris.config.IrisConfig;
import net.coderbot.iris.gl.program.RetainedProgramsTestSupport;
import net.coderbot.iris.pipeline.FixedFunctionWorldRenderingPipeline;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.shaderpack.ShaderPackFixtures;
import net.minecraft.launchwrapper.Launch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the full compile of every shader folder of a pack: when it is allowed to run, what it does to the
 * folders it visits, when the fingerprint it was run for is marked, what a folder that fails leaves behind,
 * and what deleting the saved programs does to the marker of a run that already finished or is still in
 * flight.
 *
 * <p>The pipeline that a folder is compiled into needs a GL context, so building one is a port installed by
 * the caller; everything else - the gates, the marker, the per-folder release and the failure handling - is
 * the production code.</p>
 */
class IrisFullShaderCompileTest {

    /** Name the pack is compiled under, and the fingerprint a run is marked with. */
    private static final String PACK = "Pack";
    private static final String SETTINGS = "Pack\nfiles\n\nSHADOWS=off\n";
    private static final String CHANGED_SETTINGS = "Pack\nfiles\n\nSHADOWS=on\n";

    /** Fingerprint reported by {@link SilentDriver}. */
    private static final String DRIVER = "fake-vendor|fake-renderer|fake-version|4.60|false|460";

    @TempDir
    Path tempDir;

    /** Records what the run did, in order: a build of a folder, then the release of its pipeline. */
    private final List<String> events = new ArrayList<>();
    private final Map<ProgramSet, String> names = new IdentityHashMap<>();

    @BeforeAll
    static void initializeLaunchEnvironment() {
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("fml.deobfuscatedEnvironment", false);
    }

    @BeforeEach
    void configureCache() {
        RetainedProgramsTestSupport.installNoNativeContext();
        ProgramBinaryCacheTestSupport.installOps(new SilentDriver());
        ShaderDiskCache.configure(tempDir.resolve("cache"), "salt");
        ProgramBinaryCache.setEnabled(true);
        ProgramBinaryCache.usePack(PACK);
    }

    @AfterEach
    void releaseCache() {
        try {
            ProgramBinaryCache.setEnabled(false);
            ProgramBinaryCache.usePack(null);
            ShaderDiskCache.disable();
            ProgramBinaryCacheTestSupport.installOps(null);
        } finally {
            RetainedProgramsTestSupport.restoreProductionProvider();
        }
    }

    @Test
    void nothingIsEnumeratedOrBuiltWhenSavingCompiledShadersIsOff() {
        final AtomicInteger enumerations = new AtomicInteger();

        final boolean compiled = Iris.compileEveryShaderFolder(false, PACK, SETTINGS,
            () -> {
                enumerations.incrementAndGet();
                throw new AssertionError("the folders may not even be enumerated while saving is off");
            },
            programs -> {
                throw new AssertionError("no pipeline may be built while saving is off");
            },
            pipeline -> {
                throw new AssertionError("no pipeline may be released while saving is off");
            });

        assertFalse(compiled, "nothing was compiled, so the run cannot have finished");
        assertEquals(0, enumerations.get(), "the folders must not be enumerated while saving is off");
        assertFalse(ProgramBinaryCache.wasCompiledFor(SETTINGS), "nothing was compiled, so nothing may be marked");
    }

    @Test
    void aDefaultConfigurationLeavesTheSavedProgramCacheClosed() throws IOException {
        // A shaders.properties that predates the option, which is what a fresh install looks like
        final Path properties = tempDir.resolve("shaders.properties");
        Files.writeString(properties, "shaderPack=Pack\nenableShaders=true\n", StandardCharsets.ISO_8859_1);

        final IrisConfig config = new IrisConfig(properties);
        config.initialize();

        assertFalse(config.shouldSaveCompiledShaders(), "saving linked programs is opt-in");
        assertFalse(ProgramBinaryCache.isEnabled(),
            "the configuration is what turns the saved-program cache on, so a default configuration keeps it closed");
    }

    @Test
    void nothingIsEnumeratedWhenNoProgramBinaryCanBeSaved() {
        // What an unsupported driver, a missing disk cache or the switch itself look like to the cache
        ProgramBinaryCache.setEnabled(false);
        final AtomicInteger enumerations = new AtomicInteger();

        final boolean compiled = Iris.compileEveryShaderFolder(true, PACK, SETTINGS,
            () -> {
                enumerations.incrementAndGet();
                return Map.of();
            },
            programs -> {
                throw new AssertionError("no pipeline may be built when no program binary can be saved");
            },
            pipeline -> {
                throw new AssertionError("no pipeline may be released when no program binary can be saved");
            });

        assertFalse(compiled);
        assertEquals(0, enumerations.get(), "the folders must not be enumerated when the driver offers no binary");
        assertFalse(ProgramBinaryCache.wasCompiledFor(SETTINGS));
    }

    @Test
    void everyFolderIsCompiledReleasedAndThenMarkedAsCompiled() throws IOException, InterruptedException {
        final Map<String, ProgramSet> sets = readPackWithEveryFolder();

        assertTrue(compile(sets, programs -> new FixedFunctionWorldRenderingPipeline()), "every folder of the pack compiled");
        awaitCompiled(SETTINGS);

        assertEquals(List.of("build:world-1", "release", "build:world0", "release", "build:(base shaders)", "release"),
            events, "every folder is released before the next one is built, which is what bounds the GL object peak");
        assertTrue(ProgramBinaryCache.wasCompiledFor(SETTINGS),
            "a run that compiled every folder marks the fingerprint it was run for");
        assertFalse(ProgramBinaryCache.isFullCompileRunning(), "the run's bookkeeping is closed once it finished");
    }

    @Test
    void theFoldersAreNotEnumeratedAgainForFingerprintThatWasCompiledInFull() throws IOException, InterruptedException {
        final Map<String, ProgramSet> sets = readPackWithEveryFolder();
        assertTrue(compile(sets, programs -> new FixedFunctionWorldRenderingPipeline()));
        awaitCompiled(SETTINGS);
        final int compiledOnce = events.size();

        final AtomicInteger enumerations = new AtomicInteger();
        final boolean compiledAgain = Iris.compileEveryShaderFolder(true, PACK, SETTINGS,
            () -> {
                enumerations.incrementAndGet();
                return sets;
            },
            programs -> {
                throw new AssertionError("a fingerprint that was compiled in full is not compiled again");
            },
            pipeline -> {
                throw new AssertionError("a fingerprint that was compiled in full is not compiled again");
            });

        assertFalse(compiledAgain);
        assertEquals(0, enumerations.get(), "the folders are not even enumerated once the fingerprint is marked");
        assertEquals(compiledOnce, events.size());

        assertTrue(compile(CHANGED_SETTINGS, sets, programs -> new FixedFunctionWorldRenderingPipeline()),
            "changing a pack option has to compile every folder again");
        assertEquals(compiledOnce * 2, events.size(), "the changed fingerprint visits every folder once more");
    }

    @Test
    void aFolderThatFailsIsSkippedWithoutMarkingTheFingerprintAndIsRetried() throws IOException {
        final Map<String, ProgramSet> sets = readPackWithEveryFolder();
        final String failingFolder = "world-1";
        final ProgramSet failing = programSetOf(sets, failingFolder);
        final AtomicInteger attempts = new AtomicInteger();

        final boolean compiled = Iris.compileEveryShaderFolder(true, PACK, SETTINGS, () -> sets,
            programs -> {
                attempts.incrementAndGet();
                if (programs == failing) {
                    throw new IllegalStateException("broken shader folder");
                }
                events.add("build:" + names.get(programs));
                return new FixedFunctionWorldRenderingPipeline();
            },
            this::release);

        assertFalse(compiled, "a run that left a folder out cannot claim to have compiled the pack");
        assertEquals(sets.size(), attempts.get(), "every folder is still attempted, a failure only skips its own folder");
        assertEquals(List.of("build:world0", "release", "build:(base shaders)", "release"), events,
            "the folders that did compile are still released, and a failed folder has no pipeline to release");
        assertFalse(ProgramBinaryCache.wasCompiledFor(SETTINGS),
            "a half-compiled pack must never be marked, or the folders it skipped would never be retried");
        assertFalse(ProgramBinaryCache.isFullCompileRunning(), "the run's bookkeeping is closed even when a folder fails");

        final AtomicInteger retries = new AtomicInteger();
        final boolean retried = Iris.compileEveryShaderFolder(true, PACK, SETTINGS, () -> sets,
            programs -> {
                retries.incrementAndGet();
                return new FixedFunctionWorldRenderingPipeline();
            },
            this::release);

        assertTrue(retried, "the next load retries the folders, so a run that failed once can still complete");
        assertEquals(sets.size(), retries.get(), "the retry visits every folder again");
    }

    @Test
    void aFingerprintThatCouldNotBeTakenIsNotCompiledFor() throws IOException {
        final Map<String, ProgramSet> sets = readPackWithEveryFolder();
        final AtomicInteger enumerations = new AtomicInteger();

        final boolean compiled = Iris.compileEveryShaderFolder(true, PACK, null,
            () -> {
                enumerations.incrementAndGet();
                return sets;
            },
            programs -> {
                throw new AssertionError("nothing may be compiled for a fingerprint that could not be taken");
            },
            pipeline -> {
                throw new AssertionError("nothing may be released for a fingerprint that could not be taken");
            });

        assertFalse(compiled, "without a fingerprint a full compile can never be marked, so it is not attempted");
        assertEquals(0, enumerations.get());
    }

    @Test
    void deletingTheSavedProgramsMakesTheNextLoadCompileEveryFolderAgain() throws IOException, InterruptedException {
        final Map<String, ProgramSet> sets = readPackWithEveryFolder();
        final AtomicInteger builds = new AtomicInteger();

        assertTrue(compile(sets, builds), "the first load compiles every folder of the pack");
        awaitCompiled(SETTINGS);
        assertEquals(sets.size(), builds.get(), "every folder was built once");

        assertFalse(compile(sets, builds), "a fingerprint that was compiled in full is not compiled again");
        assertEquals(sets.size(), builds.get(), "the marked fingerprint skips every folder");

        // What the delete button of the shader pack screen runs
        ProgramBinaryCache.deleteAll();

        assertFalse(ProgramBinaryCache.wasCompiledFor(SETTINGS),
            "the marker records its fingerprint inside the layer the deletion removes, so it has to be gone");
        assertFalse(savedProgramLayerExists(),
            "the whole saved-program layer has to be gone, or a half cache would look complete");

        assertTrue(compile(sets, builds), "the next load compiles every folder again instead of trusting the deleted cache");
        assertEquals(2 * sets.size(), builds.get(), "every folder is visited once more");
    }

    @Test
    void aFullCompileThatWasInterruptedByTheDeletionDoesNotMarkItselfCompiled() {
        ProgramBinaryCache.beginFullCompile();
        ProgramBinaryCache.finishFullCompile(SETTINGS);
        assertTrue(ProgramBinaryCache.wasCompiledFor(SETTINGS),
            "a run that nobody deleted marks the fingerprint it compiled as soon as its writes are done");

        ProgramBinaryCache.deleteAll();
        assertFalse(ProgramBinaryCache.wasCompiledFor(SETTINGS),
            "the deletion drops the marker, which is what makes the next load compile every folder again");

        ProgramBinaryCache.beginFullCompile();
        ProgramBinaryCache.deleteAll();
        ProgramBinaryCache.finishFullCompile(SETTINGS);

        assertFalse(ProgramBinaryCache.wasCompiledFor(SETTINGS),
            "the deletion invalidated the run, so finishing it must not re-create the marker and skip the next load");
    }

    /** Reads a pack with two dimension folders and shader files in the base folder. */
    private Map<String, ProgramSet> readPackWithEveryFolder() throws IOException {
        final Path shaders = Files.createDirectories(tempDir.resolve("Pack/shaders"));
        ShaderPackFixtures.writeProgram(shaders, "gbuffers_basic", "base");
        ShaderPackFixtures.writeProgram(shaders.resolve("world0"), "gbuffers_basic", "overworld");
        ShaderPackFixtures.writeProgram(shaders.resolve("world-1"), "gbuffers_basic", "nether");
        ShaderPackFixtures.write(shaders, "dimension.properties", """
            dimension.world0=minecraft:overworld
            dimension.world-1=minecraft:the_nether
            """);

        final Map<String, ProgramSet> sets = ShaderPackFixtures.read(shaders, Map.of()).getEveryProgramSet();
        sets.forEach((folder, programs) -> names.put(programs, folder != null ? folder : "(base shaders)"));
        return sets;
    }

    /** Runs the full compile for {@link #SETTINGS} against the given folders and the given pipeline source. */
    private boolean compile(Map<String, ProgramSet> sets, Iris.FolderPipelineBuilder builder) {
        return compile(SETTINGS, sets, builder);
    }

    /** Runs the full compile for {@link #SETTINGS}, counting the folders that were built. */
    private boolean compile(Map<String, ProgramSet> sets, AtomicInteger builds) {
        return compile(SETTINGS, sets, programs -> {
            builds.incrementAndGet();
            return new FixedFunctionWorldRenderingPipeline();
        });
    }

    /** {@return whether the saved-program layer of the test cache is still on disk} */
    private boolean savedProgramLayerExists() {
        // The layer the program cache writes into, as named by ProgramBinaryCache
        return Files.isDirectory(tempDir.resolve("cache").resolve("gl-program"));
    }

    private boolean compile(String settings, Map<String, ProgramSet> sets, Iris.FolderPipelineBuilder builder) {
        return Iris.compileEveryShaderFolder(true, PACK, settings, () -> sets, programs -> {
            events.add("build:" + names.get(programs));
            return builder.build(programs);
        }, this::release);
    }

    private void release(WorldRenderingPipeline pipeline) {
        events.add("release");
        pipeline.destroy();
    }

    private static ProgramSet programSetOf(Map<String, ProgramSet> sets, String folder) {
        final ProgramSet programs = sets.get(folder);
        if (programs == null) throw new AssertionError("the fixture has no folder called " + folder);
        return programs;
    }

    /** Waits for the marker the cache writes on a worker once every saved program of the run is on disk. */
    private static void awaitCompiled(String settings) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (ProgramBinaryCache.wasCompiledFor(settings)) return;
            Thread.sleep(10L);
        }
        assertTrue(ProgramBinaryCache.wasCompiledFor(settings), "the pack never got marked as compiled in full");
    }

    /** A driver that can only report its identity: a full compile that saves nothing asks for nothing else. */
    private static final class SilentDriver implements ProgramBinaryOps {

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public String driverFingerprint() {
            return DRIVER;
        }

        @Override
        public int createProgram() {
            throw new UnsupportedOperationException("no program is created when nothing is compiled into one");
        }

        @Override
        public void deleteProgram(int program) {
            throw new UnsupportedOperationException("no program is deleted when nothing was created");
        }

        @Override
        public int getProgrami(int program, int pname) {
            throw new UnsupportedOperationException("no program parameter is read");
        }

        @Override
        public void programParameteri(int program, int pname, int value) {
            throw new UnsupportedOperationException("no program parameter is set");
        }

        @Override
        public void getProgramBinary(int program, IntBuffer length, IntBuffer binaryFormat, ByteBuffer binary) {
            throw new UnsupportedOperationException("no binary is read");
        }

        @Override
        public void programBinary(int program, int binaryFormat, ByteBuffer binary) {
            throw new UnsupportedOperationException("no binary is restored");
        }
    }
}
