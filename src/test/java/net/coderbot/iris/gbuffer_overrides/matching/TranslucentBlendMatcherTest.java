package net.coderbot.iris.gbuffer_overrides.matching;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslucentBlendMatcherTest {
    @Test
    void declaredTranslucencyDecidesWithoutReadingTheLiveBlendState() {
        assertTrue(TranslucentBlendMatcher.resolveTranslucency(Boolean.TRUE));
        assertFalse(TranslucentBlendMatcher.resolveTranslucency(Boolean.FALSE));
    }
}
