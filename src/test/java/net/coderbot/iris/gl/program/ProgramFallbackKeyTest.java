package net.coderbot.iris.gl.program;

import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCacheTestSupport;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryOps;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import net.coderbot.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The program-binary cache key is what makes a saved program safe to restore: it has to cover every stage
 * that was linked and the attribute bindings the linker applied, or a program saved for one set of sources
 * could come back for another.
 *
 * <p>The production implementation is exercised here through the real disk-cache key composition, with the
 * driver replaced through {@link ProgramBinaryCacheTestSupport} so that building a key needs no context.</p>
 */
class ProgramFallbackKeyTest {

    /** The layer a pack named "pack" writes to, as {@link ProgramBinaryCache} builds it. */
    private static final String PACK_LAYER = "gl-program/pack";
    /** Fingerprint reported by {@link SilentDriver}. */
    private static final String DRIVER = "fake-vendor|fake-renderer|fake-version|4.60|false|460";

    @TempDir
    Path cacheRoot;

    @BeforeEach
    void configureCache() {
        ProgramBinaryCacheTestSupport.installOps(new SilentDriver());
        ShaderDiskCache.configure(cacheRoot, "salt");
        ProgramBinaryCache.setEnabled(true);
        ProgramBinaryCache.usePack("pack");
    }

    @AfterEach
    void releaseCache() {
        ProgramBinaryCache.setEnabled(false);
        ProgramBinaryCache.usePack(null);
        ShaderDiskCache.disable();
        ProgramBinaryCacheTestSupport.installOps(null);
    }

    @Test
    void theKeyDescribesEveryStageAndTheAttributeBindingsTheLinkerApplies() {
        final RetainedPrograms.Sources sources =
            new RetainedPrograms.Sources("vertex", "geometry", "tessControl", "tessEval", "fragment", null);

        // Rebuilt from the documented components, so dropping a stage or moving an attribute binding changes
        // the digest and fails here.
        final String expected = ShaderDiskCache.key(PACK_LAYER)
            .str(DRIVER)
            .b(ShaderManager.getInstance().isEnabled())
            .str("attribute").str("mc_Entity").i(11)
            .str("attribute").str("mc_midTexCoord").i(12)
            .str("attribute").str("at_tangent").i(13)
            .str("attribute").str("at_midBlock").i(14)
            .str("stage").i(ShaderType.VERTEX.id).str("vertex")
            .str("stage").i(ShaderType.GEOMETRY.id).str("geometry")
            .str("stage").i(ShaderType.TESSELATION_CONTROL.id).str("tessControl")
            .str("stage").i(ShaderType.TESSELATION_EVAL.id).str("tessEval")
            .str("stage").i(ShaderType.FRAGMENT.id).str("fragment")
            .hex();

        assertEquals(expected, new ProgramFallbackImpl().cacheKey(sources).hex(),
            "the key has to describe exactly the stages the program is linked from");
    }

    @Test
    void changingAnySingleStageChangesTheKey() {
        final ProgramFallbackImpl fallback = new ProgramFallbackImpl();
        final String baseline = keyOf(fallback, new RetainedPrograms.Sources("v", "g", "tc", "te", "f", null));

        assertNotEquals(baseline, keyOf(fallback, new RetainedPrograms.Sources("v2", "g", "tc", "te", "f", null)),
            "the vertex stage is part of the key");
        assertNotEquals(baseline, keyOf(fallback, new RetainedPrograms.Sources("v", "g2", "tc", "te", "f", null)),
            "the geometry stage is part of the key");
        assertNotEquals(baseline, keyOf(fallback, new RetainedPrograms.Sources("v", "g", "tc2", "te", "f", null)),
            "the tessellation control stage is part of the key");
        assertNotEquals(baseline, keyOf(fallback, new RetainedPrograms.Sources("v", "g", "tc", "te2", "f", null)),
            "the tessellation evaluation stage is part of the key");
        assertNotEquals(baseline, keyOf(fallback, new RetainedPrograms.Sources("v", "g", "tc", "te", "f2", null)),
            "the fragment stage is part of the key");
        assertEquals(baseline, keyOf(fallback, new RetainedPrograms.Sources("v", "g", "tc", "te", "f", null)),
            "the same stages produce the same key");
    }

    @Test
    void aComputeProgramIsKeyedByItsComputeStage() {
        final ProgramFallbackImpl fallback = new ProgramFallbackImpl();
        final RetainedPrograms.Sources graphics =
            new RetainedPrograms.Sources("void main() {}", null, null, null, "void main() {}", null);
        final RetainedPrograms.Sources compute =
            new RetainedPrograms.Sources(null, null, null, null, null, "void main() {}");

        assertNotEquals(keyOf(fallback, graphics), keyOf(fallback, compute),
            "a compute program and a graphics program with the same text are different programs");
    }

    private static String keyOf(ProgramFallbackImpl fallback, RetainedPrograms.Sources sources) {
        return fallback.cacheKey(sources).hex();
    }

    /**
     * A driver that can only report its identity. Building a cache key asks for nothing else, and every other
     * call is a bug in the path under test.
     */
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
            throw new UnsupportedOperationException("no program is created to build a key");
        }

        @Override
        public void deleteProgram(int program) {
            throw new UnsupportedOperationException("no program is deleted to build a key");
        }

        @Override
        public int getProgrami(int program, int pname) {
            throw new UnsupportedOperationException("no program parameter is read to build a key");
        }

        @Override
        public void programParameteri(int program, int pname, int value) {
            throw new UnsupportedOperationException("no program parameter is set to build a key");
        }

        @Override
        public void getProgramBinary(int program, IntBuffer length, IntBuffer binaryFormat, ByteBuffer binary) {
            throw new UnsupportedOperationException("no binary is read to build a key");
        }

        @Override
        public void programBinary(int program, int binaryFormat, ByteBuffer binary) {
            throw new UnsupportedOperationException("no binary is restored to build a key");
        }
    }
}
