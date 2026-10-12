package com.gtnewhorizons.angelica.glsm.shader;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.streaming.StubRenderBackend;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL41;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the part of the saved-program cache that drives the disk cache: which layer a pack writes to,
 * how a full compile of every shader folder ends, and when a link is asked to keep its program binary
 * readable.
 *
 * <p>Reading and writing program binaries needs a live GL context, so that half is exercised in game;
 * everything here is the bookkeeping around it.</p>
 */
class ProgramBinaryCacheTest {

    /** Records the program parameters a link asks the driver for. */
    private static final class RecordingBackend extends StubRenderBackend {
        final List<String> programParameters = new ArrayList<>();

        @Override public void programParameteri(int program, int pname, int value) {
            programParameters.add(program + ":" + pname + ":" + value);
        }

        /** GLStateManager reads the texture unit count while it initializes. */
        @Override public int getInteger(int pname) { return 32; }
    }

    @TempDir
    Path dir;

    private RenderBackend originalBackend;

    @AfterEach
    void reset() {
        ProgramBinaryCache.setEnabled(false);
        ProgramBinaryCache.usePack(null);
        // The disk cache is a process-wide singleton, so its root has to be handed back
        Reflect.setStatic(ShaderDiskCache.class, "root", null);
        if (originalBackend != null) {
            Reflect.setStaticFinal(BackendManager.class, "RENDER_BACKEND", RenderBackend.class, originalBackend);
            originalBackend = null;
        }
    }

    private RecordingBackend installBackend() {
        final RecordingBackend backend = new RecordingBackend();
        originalBackend = BackendManager.RENDER_BACKEND;
        Reflect.setStaticFinal(BackendManager.class, "RENDER_BACKEND", RenderBackend.class, backend);
        return backend;
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

    @Test
    void theRetrievableHintIsRequestedOnlyWhileSavingIsOn() throws IOException {
        final RecordingBackend backend = installBackend();
        ShaderDiskCache.configure(dir, "salt");
        ProgramBinaryCache.setEnabled(true);
        ProgramBinaryCache.usePack("pack");
        Reflect.setStatic(RenderSystem.class, "supportsProgramBinary", true);

        ProgramBinaryCache.markRetrievable(7);

        assertEquals(List.of(7 + ":" + GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT + ":" + GL11.GL_TRUE), backend.programParameters,
            "the link has to be told to keep the binary before the driver links it");

        ProgramBinaryCache.setEnabled(false);
        ProgramBinaryCache.markRetrievable(8);

        assertEquals(1, backend.programParameters.size(), "a program linked with saving off is never read back");
    }
}
