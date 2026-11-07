package net.coderbot.iris;

import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecompileQueueTest {

    @TempDir
    Path dir;

    private FakeBackend backend;

    @BeforeEach
    void setUp() {
        ShaderDiskCache.configure(dir, "precompile-queue-test");
        ProgramBinaryCache.useDriverIdForTesting("test-driver");
        ProgramBinaryCache.setEnabled(true);
        ProgramBinaryCache.usePackForPrecompile("pack");
        backend = new FakeBackend();
        PrecompileQueue.useBackend(backend);
    }

    @AfterEach
    void tearDown() {
        PrecompileQueue.cancel();
        PrecompileQueue.useDefaultBackend();
        ProgramBinaryCache.resetForTesting();
        ShaderDiskCache.resetForTesting();
    }

    @Test
    void startExposesTheFirstJobToTheScreenBeforeCompilation() {
        PrecompileQueue.Job job = new PrecompileQueue.Job("pack", "world0", "world0");

        PrecompileQueue.start(List.of(job));

        assertSame(job, PrecompileQueue.currentJob());
    }

    @Test
    void parentProgressAggregatesFolderProgress() {
        PrecompileQueue.Job first = new PrecompileQueue.Job("a", "world0", "world0");
        PrecompileQueue.Job second = new PrecompileQueue.Job("a", "world1", "world1");
        PrecompileQueue.Job third = new PrecompileQueue.Job("b", "world0", "world0");

        PrecompileQueue.start(List.of(first, second, third));

        assertEquals(1.0F / 3.0F, PrecompileQueue.overallProgress(), 0.0001F);
        assertEquals(0.5F, PrecompileQueue.packProgress("a"), 0.0001F);
        assertEquals(0.0F, PrecompileQueue.packProgress("b"), 0.0001F);
        assertEquals(1.0F, PrecompileQueue.folderProgress("a", "world0"), 0.0001F);
        assertEquals(0.0F, PrecompileQueue.folderProgress("a", "world1"), 0.0001F);
    }

    @Test
    void selectingEveryFolderCompilesOnePerFrameAndMarksThePack() {
        backend.folders = List.of(entry("world0"), entry("world1"));

        PrecompileQueue.start(jobs("world0", "world1"));
        assertTrue(PrecompileQueue.isRunning());

        PrecompileQueue.tick();
        assertEquals(List.of("world0"), backend.compiled);
        assertTrue(PrecompileQueue.isRunning(), "a run must not compile the whole pack in one frame");

        PrecompileQueue.tick();
        assertEquals(List.of("world0"), backend.compiled, "the frame between two folders only draws");

        PrecompileQueue.tick();
        assertEquals(List.of("world0", "world1"), backend.compiled);
        assertFalse(PrecompileQueue.isRunning());
        assertTrue(ProgramBinaryCache.wasCompiledFor("settings-1"));
        assertEquals(1, backend.restored, "the world pipeline is rebuilt once the run ends");
    }

    @Test
    void aCancelledSelectionWritesNoMarker() {
        backend.folders = List.of(entry("world0"), entry("world1"));

        PrecompileQueue.start(jobs("world0", "world1"));
        PrecompileQueue.tick();
        PrecompileQueue.cancel();

        assertFalse(PrecompileQueue.isRunning());
        assertFalse(ProgramBinaryCache.wasCompiledFor("settings-1"));
        assertEquals(1, backend.restored);
    }

    @Test
    void aFailedFolderWritesNoMarker() {
        backend.folders = List.of(entry("world0"), entry("world1"));
        backend.failing = "world1";

        PrecompileQueue.start(jobs("world0", "world1"));
        PrecompileQueue.tick();
        PrecompileQueue.tick();
        PrecompileQueue.tick();

        assertFalse(PrecompileQueue.isRunning());
        assertEquals(List.of("world0", "world1"), backend.compiled);
        assertFalse(ProgramBinaryCache.wasCompiledFor("settings-1"));
    }

    @Test
    void aReplacingSelectionClosesTheAbandonedSession() {
        backend.folders = List.of(entry("world0"), entry("world1"));

        PrecompileQueue.start(jobs("world0", "world1"));
        PrecompileQueue.tick();
        assertTrue(ProgramBinaryCache.isFullCompileRunning());

        PrecompileQueue.start(jobs("world1"));

        assertFalse(ProgramBinaryCache.isFullCompileRunning());
        PrecompileQueue.tick();
        assertFalse(PrecompileQueue.isRunning());
        assertFalse(ProgramBinaryCache.wasCompiledFor("settings-1"));
    }

    @Test
    void aPartialSelectionDoesNotStartFullCacheTracking() {
        backend.folders = List.of(entry("world0"), entry("world1"));

        PrecompileQueue.start(jobs("world1"));

        assertFalse(ProgramBinaryCache.isFullCompileRunning());
        PrecompileQueue.tick();
        assertFalse(PrecompileQueue.isRunning());
        assertFalse(ProgramBinaryCache.wasCompiledFor("settings-1"));
    }

    private static Iris.PrecompileEntry entry(String folder) {
        return new Iris.PrecompileEntry(folder, folder, false);
    }

    private static List<PrecompileQueue.Job> jobs(String... folders) {
        final List<PrecompileQueue.Job> jobs = new ArrayList<>();
        for (String folder : folders) {
            jobs.add(new PrecompileQueue.Job("pack", folder, folder));
        }
        return jobs;
    }

    private static final class FakeBackend implements PrecompileBackend {
        private @Nullable List<Iris.PrecompileEntry> folders = List.of();
        private final List<String> compiled = new ArrayList<>();
        private final Set<String> alreadyCompiled = new LinkedHashSet<>();
        private @Nullable String failing;
        private int restored;

        @Override
        public boolean isFolderCompiled(String packName, @Nullable String folder) {
            return alreadyCompiled.contains(folder);
        }

        @Override
        public void forgetFolder(String packName, @Nullable String folder) {
            alreadyCompiled.remove(folder);
        }

        @Override
        public boolean compileFolder(String packName, @Nullable String folder) {
            compiled.add(folder);
            return !folder.equals(failing);
        }

        @Override
        @Nullable
        public List<Iris.PrecompileEntry> folders(String packName) {
            return folders;
        }

        @Override
        @Nullable
        public String settings(String packName) {
            return "settings-1";
        }

        @Override
        public void restore() {
            restored++;
        }
    }
}
