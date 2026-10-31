package net.coderbot.iris.pipeline;

import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gbuffer_overrides.matching.RenderCondition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredWorldRenderingPipelineInputTest {
    @Test
    void shadowProgramsAlwaysReceiveTextureAndLightmapInputs() {
        InputAvailability liveInputs = new InputAvailability(true, false, false);

        for (RenderCondition condition : new RenderCondition[] {
                RenderCondition.SHADOW, RenderCondition.SHADOW_TRANSLUCENT}) {
            InputAvailability shadowInputs = DeferredWorldRenderingPipeline.inputsFor(condition, liveInputs);

            assertTrue(shadowInputs.texture);
            assertTrue(shadowInputs.lightmap);
        }
    }

    @Test
    void nonShadowProgramsKeepLiveInputs() {
        InputAvailability liveInputs = new InputAvailability(true, false, false);

        assertSame(liveInputs, DeferredWorldRenderingPipeline.inputsFor(RenderCondition.ENTITIES, liveInputs));
    }
}
