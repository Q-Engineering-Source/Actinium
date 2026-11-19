package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubeInstancingGateTest {

    @AfterEach
    void clearProvider() {
        InstancingSupportProvider.setProvider(null);
    }

    @Test
    void cubeSupportRequiresTheHostOption() {
        assertFalse(DeferredWorldRenderingPipeline.initialInstancingSupport()[Instancing.CUBE.ordinal()],
            "cube variants must stay off until the host registers its option provider");

        InstancingSupportProvider.setProvider(() -> false);
        assertFalse(DeferredWorldRenderingPipeline.initialInstancingSupport()[Instancing.CUBE.ordinal()]);

        InstancingSupportProvider.setProvider(() -> true);
        assertTrue(DeferredWorldRenderingPipeline.initialInstancingSupport()[Instancing.CUBE.ordinal()]);
    }

    @Test
    void templateAndParticleSupportIgnoreTheCubeOption() {
        for (boolean cube : new boolean[] { false, true }) {
            InstancingSupportProvider.setProvider(() -> cube);
            final boolean[] support = DeferredWorldRenderingPipeline.initialInstancingSupport();
            assertTrue(support[Instancing.TEMPLATE.ordinal()], "the template layout is always available");
            assertFalse(support[Instancing.NONE.ordinal()], "there is no variant for the per-vertex layout");
            assertTrue(support[Instancing.PARTICLE.ordinal()],
                "the particle layout only needs the fixed quad mesh, so it is attempted and cleared on failure");
            assertFalse(support[Instancing.WEATHER.ordinal()], "weather instancing has no shared-program variant yet");
        }
    }

    @Test
    void anUnregisteredProviderKeepsCubeVariantsOff() {
        assertFalse(InstancingSupportProvider.cubeInstancingEnabled(),
            "a pipeline built before the host registers has no cube variants");
    }

    @Test
    void theProviderIsReadOnEveryQuery() {
        final boolean[] enabled = { false };
        InstancingSupportProvider.setProvider(() -> enabled[0]);

        assertFalse(InstancingSupportProvider.cubeInstancingEnabled());
        enabled[0] = true;
        assertTrue(InstancingSupportProvider.cubeInstancingEnabled(),
            "the host reads the option live, so the next shader pack load must see the new value");
    }
}
