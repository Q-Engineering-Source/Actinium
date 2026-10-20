package net.coderbot.iris.gl.program;

import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    private FakeFallback fallback;

    @BeforeEach
    void resetPool() {
        RetainedPrograms.setDeleterForTesting(program -> {});
        RetainedPrograms.useConfiguration(null);
        fallback = new FakeFallback();
    }

    @AfterEach
    void releasePool() {
        RetainedPrograms.useConfiguration(null);
        RetainedPrograms.setDeleterForTesting(null);
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

    /** Records which tier was reached; the returned key is a stand-in because the tiers never touch GL. */
    private static final class FakeFallback implements ProgramFallback {
        private int cacheKeyCalls;
        private int restoreCalls;
        private int compileCalls;
        private int restored;
        private int compiled;
        private RetainedPrograms.Sources compiledSources;

        @Override
        @Nullable
        public ProgramBinaryCache.Key cacheKey(RetainedPrograms.Sources sources) {
            cacheKeyCalls++;
            return null;
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
            return compiled;
        }
    }
}
