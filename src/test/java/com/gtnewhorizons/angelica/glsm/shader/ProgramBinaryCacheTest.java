package com.gtnewhorizons.angelica.glsm.shader;

import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the part of the saved-program cache that drives the disk cache: which layer a pack writes to,
 * and how a full compile of every shader folder ends.
 *
 * <p>Reading and writing program binaries needs a live GL context, so that half is exercised in game;
 * everything here is the bookkeeping around it.</p>
 */
class ProgramBinaryCacheTest {

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        ProgramBinaryCache.setEnabled(false);
        ProgramBinaryCache.usePack(null);
        // The disk cache is a process-wide singleton, so its root has to be handed back
        Reflect.setStatic(ShaderDiskCache.class, "root", null);
    }

    @Test
    void packsGetTheirOwnSanitizedLayer() throws IOException {
        ShaderDiskCache.configure(dir, "salt");
        ProgramBinaryCache.setEnabled(true);

        ProgramBinaryCache.usePack("Complementary Unbound v5.8.1!!");
        assertTrue(Files.isDirectory(dir.resolve("gl-program").resolve("Complementary_Unbound_v5.8.1__")),
            "the pack layer is created from the pack name, with everything the filesystem dislikes replaced");

        ProgramBinaryCache.usePack("x".repeat(200));
        assertTrue(Files.isDirectory(dir.resolve("gl-program").resolve("x".repeat(64))),
            "long pack names are cut off instead of producing a path too long for the filesystem");
    }

    @Test
    void fullCompileRunsAndTurningSavingOffDropsEveryLayer() throws IOException {
        ShaderDiskCache.configure(dir, "salt");
        ProgramBinaryCache.setEnabled(true);
        ProgramBinaryCache.usePack("pack");

        assertFalse(ProgramBinaryCache.isFullCompileRunning());
        ProgramBinaryCache.beginFullCompile();
        assertTrue(ProgramBinaryCache.isFullCompileRunning());
        // No fingerprint is recorded when the run did not cover the whole pack, which is what null means
        ProgramBinaryCache.finishFullCompile(null);
        assertFalse(ProgramBinaryCache.isFullCompileRunning());

        ShaderDiskCache.put(ShaderDiskCache.key("gl-program/pack").str("program"), new byte[] {1});
        assertTrue(Files.exists(dir.resolve("gl-program").resolve("pack")));

        ProgramBinaryCache.deleteAll();
        assertFalse(Files.exists(dir.resolve("gl-program")), "every saved program and settings marker goes");
        assertTrue(Files.isRegularFile(dir.resolve("salt")), "the cache itself stays configured");
    }
}
