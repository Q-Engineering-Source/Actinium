package com.dhj.actinium.render.terrain.compile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchedModelRenderDecisionTest {
    @Test
    void emptyAndFullyFilteredFacesDoNotRender() {
        assertFalse(DispatchedModelRenderDecision.hasQuads(List.of()));
        assertFalse(DispatchedModelRenderDecision.shouldRenderFace(false, true, true, false));

        List<String> originalQuads = List.of("quad");
        List<String> transformerOutput = List.of();
        assertTrue(DispatchedModelRenderDecision.hasQuads(originalQuads));
        assertFalse(DispatchedModelRenderDecision.hasQuads(transformerOutput));
    }

    @Test
    void sideCullingAndDisabledSideChecksMatchDispatcherRules() {
        assertFalse(DispatchedModelRenderDecision.shouldRenderFace(true, true, false, false));
        assertTrue(DispatchedModelRenderDecision.shouldRenderFace(true, true, false, true));
        assertTrue(DispatchedModelRenderDecision.shouldRenderFace(true, false, false, false));
    }

    @Test
    void resultRequiresAnAcceptedVertexUpload() {
        QuadEmissionCounter emissions = new QuadEmissionCounter();

        assertFalse(emissions.hasEmittedQuads());
        emissions.recordEmission();
        assertTrue(emissions.hasEmittedQuads());
        emissions.reset();
        assertFalse(emissions.hasEmittedQuads());
    }
}
