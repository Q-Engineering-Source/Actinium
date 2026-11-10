package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.pipeline.transform.parameter.AttributeParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Cache key behaviour of the weather instanced variant.
 *
 * <p>The instancing kind is part of the parameter key, so a program patched for instancing can never
 * pick up the ordinary weather transform from the shared cache. The transform itself needs a GL
 * context for version negotiation and is exercised in {@code WeatherInstancedProgramGLTest}.</p>
 */
class WeatherInstancedVariantTest {

    private static final InputAvailability AVAILABILITY = new InputAvailability(true, true);

    @Test
    void instancingKindSeparatesTheTransformKeys() {
        AttributeParameters plain = new AttributeParameters(Patch.ATTRIBUTES, false, AVAILABILITY);
        AttributeParameters weather = new AttributeParameters(Patch.ATTRIBUTES, false, AVAILABILITY, Instancing.WEATHER);

        assertEquals(Instancing.NONE, plain.instancing, "the ordinary constructor keeps the plain kind");
        assertNotEquals(plain, weather, "the instanced program must not reuse the plain transform");
        assertNotEquals(plain.hashCode(), weather.hashCode(), "the cache key has to differ");
        assertEquals(weather, new AttributeParameters(Patch.ATTRIBUTES, false, AVAILABILITY, Instancing.WEATHER));
    }
}
