package dhj.embeddedt.embeddium.impl.render.culling;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import dhj.embeddedt.embeddium.impl.render.chunk.MultiDrawMode;
import dhj.embeddedt.embeddium.impl.runtime.EmbeddiumRuntimeOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the multidraw mode the culling core decides on to the mode the chunk renderer actually draws with.
 *
 * <p>The chunk renderer lets a system property override the configured mode and falls back to {@code direct} for a
 * value it does not know. The culling core has to answer with the same mode, because a mode it disagrees on makes it
 * hand out compute-culled batches to a renderer that casts its batches to the direct layout and draws them
 * individually.
 */
class MultiDrawModeResolverGLTest extends GLStateManagerIntegrationFixture {
    private static final String OVERRIDE_PROPERTY = "actinium.chunkMultiDrawMode";

    private String previousOverride;
    private MultiDrawMode previousConfiguredMode;

    @BeforeAll
    static void requireIndirectSupport() {
        assumeTrue(MultiDrawModeResolver.supportsMultiDrawIndirect(),
            "the indirect cases need a context that can execute indirect multidraw");
    }

    @BeforeEach
    void captureGlobalState() {
        this.previousOverride = System.getProperty(OVERRIDE_PROPERTY);
        this.previousConfiguredMode = EmbeddiumRuntimeOptions.chunkMultiDrawMode();
    }

    @AfterEach
    void restoreGlobalState() {
        restoreOverride(this.previousOverride);
        EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> this.previousConfiguredMode);
    }

    @Test
    void individualOverrideSelectsTheIndividualMode() {
        assertEquals(MultiDrawMode.INDIVIDUAL, resolveWithOverride("individual"),
            "the system property override has to reach the culling core, or the culler and the chunk renderer disagree");
    }

    @Test
    void overrideIsTrimmedAndCaseInsensitive() {
        assertEquals(MultiDrawMode.INDIVIDUAL, resolveWithOverride("  InDiViDuAl  "),
            "the override is trimmed and matched case-insensitively, like the chunk renderer's own lookup");
    }

    @Test
    void blankOverrideFallsBackToTheConfiguredMode() {
        configure(MultiDrawMode.INDIVIDUAL);

        assertEquals(MultiDrawMode.INDIVIDUAL, resolveWithOverride("   "),
            "a blank override leaves the configured mode in charge");
    }

    @Test
    void absentOverrideUsesTheConfiguredMode() {
        configure(MultiDrawMode.INDIVIDUAL);

        assertEquals(MultiDrawMode.INDIVIDUAL, resolveWithOverride(null),
            "with no override the configured mode decides");
    }

    @Test
    void unknownOverrideFallsBackToDirect() {
        configure(MultiDrawMode.INDIVIDUAL);

        assertEquals(MultiDrawMode.DIRECT, resolveWithOverride("bogus"),
            "an override the chunk renderer cannot parse resolves to direct, not to the configured mode");
    }

    @Test
    void overrideSelectsIndirectOnAContextThatCanExecuteIt() {
        configure(MultiDrawMode.DIRECT);

        assertEquals(MultiDrawMode.INDIRECT, resolveWithOverride("indirect"),
            "an indirect override stays indirect when the context supports it");
    }

    private static void configure(MultiDrawMode mode) {
        EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> mode);
    }

    /**
     * Resolves the mode with {@code override} installed as the system property, and always puts the previous value
     * back.
     */
    private static MultiDrawMode resolveWithOverride(String override) {
        final String previous = System.getProperty(OVERRIDE_PROPERTY);
        if (override == null) {
            System.clearProperty(OVERRIDE_PROPERTY);
        } else {
            System.setProperty(OVERRIDE_PROPERTY, override);
        }
        try {
            return MultiDrawModeResolver.resolve();
        } finally {
            restoreOverride(previous);
        }
    }

    private static void restoreOverride(String previous) {
        if (previous == null) {
            System.clearProperty(OVERRIDE_PROPERTY);
        } else {
            System.setProperty(OVERRIDE_PROPERTY, previous);
        }
    }
}
