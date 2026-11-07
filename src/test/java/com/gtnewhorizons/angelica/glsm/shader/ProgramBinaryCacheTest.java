package com.gtnewhorizons.angelica.glsm.shader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GL20;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the whole-pack compile session: which entries survive a finished run, when the marker that lets a
 * later load skip the run is written, and what a run that never finished leaves behind. Programs are seeded
 * through the disk cache, so the session is exercised without a GL context.
 */
class ProgramBinaryCacheTest {

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        ProgramBinaryCache.resetForTesting();
        ShaderDiskCache.configure(dir, "program-binary-cache-test");
        ProgramBinaryCache.useDriverIdForTesting("test-driver");
        ProgramBinaryCache.setEnabled(true);
        ProgramBinaryCache.usePackForPrecompile("pack");
    }

    @AfterEach
    void tearDown() {
        ProgramBinaryCache.resetForTesting();
        ShaderDiskCache.resetForTesting();
    }

    private static ProgramBinaryCache.Key key(String source) {
        return ProgramBinaryCache.key().stage(GL20.GL_VERTEX_SHADER, source);
    }

    private static void seed(ProgramBinaryCache.Key key, byte value) {
        ShaderDiskCache.putBlob(key.diskKey(), "1", new byte[] {value});
    }

    @Test
    void aFinishedRunKeepsItsProgramsAndWritesTheMarker() {
        final ProgramBinaryCache.Key used = key("used");
        final ProgramBinaryCache.Key untouched = key("untouched");
        seed(used, (byte) 1);
        seed(untouched, (byte) 2);

        ProgramBinaryCache.beginFullCompile();
        assertTrue(ProgramBinaryCache.isFullCompileRunning());
        assertTrue(ProgramBinaryCache.saveRetained(used, 0), "an entry already on disk counts as written");
        ProgramBinaryCache.finishFullCompile("settings-1");

        assertFalse(ProgramBinaryCache.isFullCompileRunning());
        assertTrue(ProgramBinaryCache.wasCompiledFor("settings-1"), "every folder compiled, so the pack is marked");
        assertNotNull(ShaderDiskCache.getBlob(used.diskKey()), "a program the run touched stays in the cache");
        assertNull(ShaderDiskCache.getBlob(untouched.diskKey()), "a program the run never touched is pruned");
    }

    @Test
    void anUnfinishedRunWritesNoMarkerAndPrunesNothing() {
        final ProgramBinaryCache.Key used = key("used");
        final ProgramBinaryCache.Key untouched = key("untouched");
        seed(used, (byte) 1);
        seed(untouched, (byte) 2);

        ProgramBinaryCache.beginFullCompile();
        assertTrue(ProgramBinaryCache.saveRetained(used, 0));
        ProgramBinaryCache.finishFullCompile(null);

        assertFalse(ProgramBinaryCache.isFullCompileRunning());
        assertFalse(ProgramBinaryCache.wasCompiledFor("settings-1"), "a cancelled or failed run must not mark the pack");
        assertNotNull(ShaderDiskCache.getBlob(used.diskKey()));
        assertNotNull(ShaderDiskCache.getBlob(untouched.diskKey()), "an unmarked run leaves the cache as it was");
    }

    @Test
    void aReplacementRunDescribesOnlyItsOwnPrograms() {
        final ProgramBinaryCache.Key fromFirstRun = key("first");
        final ProgramBinaryCache.Key fromSecondRun = key("second");
        seed(fromFirstRun, (byte) 1);
        seed(fromSecondRun, (byte) 2);

        ProgramBinaryCache.beginFullCompile();
        assertTrue(ProgramBinaryCache.saveRetained(fromFirstRun, 0));
        ProgramBinaryCache.beginFullCompile();
        assertTrue(ProgramBinaryCache.saveRetained(fromSecondRun, 0));
        ProgramBinaryCache.finishFullCompile("settings-1");

        assertTrue(ProgramBinaryCache.wasCompiledFor("settings-1"));
        assertNull(ShaderDiskCache.getBlob(fromFirstRun.diskKey()), "the replaced run's keys are not retained");
        assertNotNull(ShaderDiskCache.getBlob(fromSecondRun.diskKey()));
    }

    @Test
    void clearingTheCacheDropsTheOpenSession() {
        final ProgramBinaryCache.Key used = key("used");
        seed(used, (byte) 1);

        ProgramBinaryCache.beginFullCompile();
        assertTrue(ProgramBinaryCache.saveRetained(used, 0));
        ProgramBinaryCache.deleteAll();
        ProgramBinaryCache.finishFullCompile("settings-1");

        assertFalse(ProgramBinaryCache.isFullCompileRunning());
        assertFalse(ProgramBinaryCache.wasCompiledFor("settings-1"), "the cleared run must not come back as a marker");
        assertNull(ShaderDiskCache.getBlob(used.diskKey()));
    }
}
