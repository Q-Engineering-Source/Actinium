package dhj.embeddedt.embeddium.impl.render.chunk;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import dhj.embeddedt.embeddium.impl.runtime.EmbeddiumRuntimeOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the multidraw mode a real {@link DefaultChunkRenderer} draws with.
 *
 * <p>The renderer resolves its mode through {@code MultiDrawModeResolver}, which the culling core also consults. A
 * renderer that parsed the override and downgraded capabilities itself could drift from that resolver, and a drift
 * makes the culler hand compute-culled batches to a renderer that casts them to the direct layout and draws them
 * individually. So these tests construct the renderer, not the resolver.
 */
class DefaultChunkRendererModeGLTest extends GLStateManagerIntegrationFixture {
    private static final String OVERRIDE_PROPERTY = "actinium.chunkMultiDrawMode";

    private CommandList commandList;
    private String previousOverride;
    private MultiDrawMode previousConfiguredMode;
    private TestRenderer renderer;

    @BeforeEach
    void setUp() {
        RenderDevice.INSTANCE.makeActive();
        this.commandList = RenderDevice.INSTANCE.createCommandList();
        this.previousOverride = System.getProperty(OVERRIDE_PROPERTY);
        this.previousConfiguredMode = EmbeddiumRuntimeOptions.chunkMultiDrawMode();
    }

    @AfterEach
    void tearDown() {
        try {
            if (this.renderer != null) {
                this.renderer.delete(this.commandList);
            }
        } finally {
            try {
                EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> this.previousConfiguredMode);
            } finally {
                restoreOverride(this.previousOverride);
                RenderDevice.INSTANCE.makeInactive();
            }
        }
    }

    @Test
    void individualOverrideSelectsTheIndividualModeInTheRenderer() {
        System.setProperty(OVERRIDE_PROPERTY, "individual");

        assertEquals(MultiDrawMode.INDIVIDUAL, createRenderer().multiDrawMode(),
            "the renderer draws with the mode the system property names, so it agrees with the culling core");
    }

    @Test
    void unknownOverrideFallsBackToDirectInTheRenderer() {
        EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> MultiDrawMode.INDIVIDUAL);
        System.setProperty(OVERRIDE_PROPERTY, "bogus");

        assertEquals(MultiDrawMode.DIRECT, createRenderer().multiDrawMode(),
            "an override the renderer cannot parse draws direct, not with the configured mode");
    }

    private TestRenderer createRenderer() {
        this.renderer = new TestRenderer();
        return this.renderer;
    }

    private static void restoreOverride(String previous) {
        if (previous == null) {
            System.clearProperty(OVERRIDE_PROPERTY);
        } else {
            System.setProperty(OVERRIDE_PROPERTY, previous);
        }
    }

    /**
     * A real renderer subclass: it is constructed like the game constructs one, and only exposes the mode field the
     * subclasses read so the test can observe the decision the constructor made.
     */
    private static final class TestRenderer extends DefaultChunkRenderer {
        private TestRenderer() {
            super(RenderDevice.INSTANCE, null, null);
        }

        @Override
        protected void configureShaderInterface(ChunkShaderInterface shader) {
        }

        private MultiDrawMode multiDrawMode() {
            return this.multiDrawMode;
        }
    }
}
