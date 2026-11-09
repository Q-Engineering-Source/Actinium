package net.coderbot.iris.celeritas;

import net.coderbot.iris.gl.blending.AlphaTest;
import net.coderbot.iris.gl.blending.AlphaTestFunction;
import net.coderbot.iris.gl.blending.AlphaTestOverride;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CeleritasTerrainAlphaTestTest {
    @Test
    void shaderPackOverrideWinsOverTerrainFallback() {
        AlphaTestOverride override = new AlphaTestOverride(new AlphaTest(AlphaTestFunction.GREATER, 0.5f));

        assertSame(override, CeleritasTerrainPipeline.resolveAlphaTestOverride(
                Optional.of(override),
                IrisTerrainPass.GBUFFER_TRANSLUCENT));
    }

    @Test
    void offDirectiveDisablesTranslucentAlphaTest() {
        AlphaTestOverride resolved = CeleritasTerrainPipeline.resolveAlphaTestOverride(
                Optional.of(AlphaTestOverride.OFF),
                IrisTerrainPass.GBUFFER_TRANSLUCENT);

        assertSame(AlphaTestOverride.OFF, resolved);
        assertEquals(-1.0f, resolved.getReference());
    }

    @Test
    void opaquePassesIgnoreShaderPackAlphaTest() {
        AlphaTestOverride override = new AlphaTestOverride(new AlphaTest(AlphaTestFunction.GREATER, 0.5f));

        assertSame(AlphaTestOverride.OFF, CeleritasTerrainPipeline.resolveAlphaTestOverride(
                Optional.of(override),
                IrisTerrainPass.GBUFFER_SOLID));
        assertSame(AlphaTestOverride.OFF, CeleritasTerrainPipeline.resolveAlphaTestOverride(
                Optional.of(override),
                IrisTerrainPass.SHADOW));
    }

    @Test
    void cutoutPassesStillFollowShaderPackAlphaTest() {
        AlphaTestOverride override = new AlphaTestOverride(new AlphaTest(AlphaTestFunction.GREATER, 0.5f));

        assertSame(override, CeleritasTerrainPipeline.resolveAlphaTestOverride(
                Optional.of(override),
                IrisTerrainPass.GBUFFER_CUTOUT));
        assertSame(override, CeleritasTerrainPipeline.resolveAlphaTestOverride(
                Optional.of(override),
                IrisTerrainPass.SHADOW_CUTOUT));
    }

    @Test
    void fallbacksMatchIrisSodiumTerrainDefaults() {
        assertEquals(0.0001f, CeleritasTerrainPipeline.resolveAlphaTestOverride(
                Optional.empty(),
                IrisTerrainPass.GBUFFER_TRANSLUCENT).getReference());
        assertEquals(0.1f, CeleritasTerrainPipeline.resolveAlphaTestOverride(
                Optional.empty(),
                IrisTerrainPass.GBUFFER_CUTOUT).getReference());
        assertSame(AlphaTestOverride.OFF, CeleritasTerrainPipeline.resolveAlphaTestOverride(
                Optional.empty(),
                IrisTerrainPass.GBUFFER_SOLID));
    }
}
