package net.coderbot.iris.layer;

import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.uniforms.RecordingStateRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbufferProgramsStateStackTest {
    private final FakePipeline pipeline = new FakePipeline();

    @BeforeEach
    void resetDeclarations() {
        GbufferPrograms.setOverridePhase(this.pipeline, null);
        GbufferPrograms.setTranslucencyDeclaration(this.pipeline, null);
        this.pipeline.appliedPhases.clear();
        this.pipeline.appliedTranslucencies.clear();
    }

    @AfterEach
    void removeRecorder() {
        CapturedRenderingState.INSTANCE.setStateRecorder(null);
    }

    @Test
    void nestedOverridePhasesRestoreEachPreviousPhase() {
        GbufferPrograms.pushOverridePhase(this.pipeline, WorldRenderingPhase.ENTITIES);
        assertEquals(WorldRenderingPhase.ENTITIES, GbufferPrograms.getOverridePhase());

        GbufferPrograms.pushOverridePhase(this.pipeline, WorldRenderingPhase.HAND_SOLID);
        assertEquals(WorldRenderingPhase.HAND_SOLID, GbufferPrograms.getOverridePhase());

        GbufferPrograms.popOverridePhase(this.pipeline);
        assertEquals(WorldRenderingPhase.ENTITIES, GbufferPrograms.getOverridePhase());

        GbufferPrograms.popOverridePhase(this.pipeline);
        assertNull(GbufferPrograms.getOverridePhase());
        assertEquals(
            Arrays.asList(WorldRenderingPhase.ENTITIES, WorldRenderingPhase.HAND_SOLID,
                WorldRenderingPhase.ENTITIES, null),
            this.pipeline.appliedPhases
        );
    }

    @Test
    void poppingAnEmptyOverridePhaseStackFails() {
        assertThrows(IllegalStateException.class, () -> GbufferPrograms.popOverridePhase(this.pipeline));
    }

    @Test
    void nestedEntityPhaseOnlyRunsInsideTheBlockEntityPhase() {
        this.pipeline.setPhase(WorldRenderingPhase.BLOCK_ENTITIES);

        assertTrue(GbufferPrograms.beginNestedEntityPhase(this.pipeline));
        assertEquals(WorldRenderingPhase.ENTITIES, GbufferPrograms.getOverridePhase());
        GbufferPrograms.endNestedEntityPhase(this.pipeline, true);
        assertNull(GbufferPrograms.getOverridePhase());
        assertEquals(WorldRenderingPhase.BLOCK_ENTITIES, this.pipeline.getPipelinePhase());

        this.pipeline.setPhase(WorldRenderingPhase.ENTITIES);
        assertFalse(GbufferPrograms.beginNestedEntityPhase(this.pipeline));
        GbufferPrograms.endNestedEntityPhase(this.pipeline, false);
        assertEquals(WorldRenderingPhase.ENTITIES, this.pipeline.getPipelinePhase());
        assertNull(GbufferPrograms.getOverridePhase());
    }

    @Test
    void translucencyDeclarationRestoresThePreviousDeclaration() {
        assertNull(GbufferPrograms.beginTranslucencyDeclaration(this.pipeline, Boolean.FALSE));
        assertEquals(Boolean.FALSE, GbufferPrograms.getDeclaredTranslucency());

        assertEquals(Boolean.FALSE, GbufferPrograms.beginTranslucencyDeclaration(this.pipeline, Boolean.TRUE));
        assertEquals(Boolean.TRUE, GbufferPrograms.getDeclaredTranslucency());

        GbufferPrograms.endTranslucencyDeclaration(this.pipeline, Boolean.FALSE);
        assertEquals(Boolean.FALSE, GbufferPrograms.getDeclaredTranslucency());

        GbufferPrograms.endTranslucencyDeclaration(this.pipeline, null);
        assertNull(GbufferPrograms.getDeclaredTranslucency());
        assertEquals(Arrays.asList(Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, null),
            this.pipeline.appliedTranslucencies);
    }

    @Test
    void explicitTranslucencyDeclarationReplacesTheCurrentValue() {
        GbufferPrograms.setTranslucencyDeclaration(this.pipeline, Boolean.TRUE);
        assertEquals(Boolean.TRUE, GbufferPrograms.getDeclaredTranslucency());

        GbufferPrograms.setTranslucencyDeclaration(this.pipeline, null);
        assertNull(GbufferPrograms.getDeclaredTranslucency());
        assertNull(this.pipeline.getDeclaredTranslucency());
    }

    @Test
    void specialConditionsNestAndRestoreThePreviousCondition() {
        GbufferPrograms.setupSpecialRenderCondition(this.pipeline, SpecialCondition.GLINT);
        assertEquals(SpecialCondition.GLINT, GbufferPrograms.getSpecialCondition());

        GbufferPrograms.setupSpecialRenderCondition(this.pipeline, SpecialCondition.ENTITY_EYES);
        assertEquals(SpecialCondition.ENTITY_EYES, GbufferPrograms.getSpecialCondition());
        assertEquals(SpecialCondition.ENTITY_EYES, this.pipeline.getSpecialCondition());

        GbufferPrograms.teardownSpecialRenderCondition(this.pipeline);
        assertEquals(SpecialCondition.GLINT, GbufferPrograms.getSpecialCondition());

        GbufferPrograms.teardownSpecialRenderCondition(this.pipeline);
        assertNull(GbufferPrograms.getSpecialCondition());
        assertNull(this.pipeline.getSpecialCondition());
    }

    @Test
    void everyStateChangeReachesTheInstalledRecorder() {
        RecordingStateRecorder recorder = new RecordingStateRecorder();
        CapturedRenderingState.INSTANCE.setStateRecorder(recorder);

        GbufferPrograms.pushOverridePhase(this.pipeline, WorldRenderingPhase.ENTITIES);
        GbufferPrograms.popOverridePhase(this.pipeline);
        GbufferPrograms.setupSpecialRenderCondition(this.pipeline, SpecialCondition.GLINT);
        GbufferPrograms.teardownSpecialRenderCondition(this.pipeline);
        GbufferPrograms.beginTranslucencyDeclaration(this.pipeline, Boolean.TRUE);
        GbufferPrograms.endTranslucencyDeclaration(this.pipeline, null);
        GbufferPrograms.setTranslucencyDeclaration(this.pipeline, Boolean.FALSE);
        this.pipeline.setPhase(WorldRenderingPhase.BLOCK_ENTITIES);
        assertTrue(GbufferPrograms.beginNestedEntityPhase(this.pipeline));
        GbufferPrograms.endNestedEntityPhase(this.pipeline, true);

        assertEquals(List.of(
            "phaseScope(ENTITIES,true)",
            "phaseScope(null,false)",
            "specialCondition(GLINT,true)",
            "specialCondition(null,false)",
            "translucencyScope(true,true)",
            "translucencyScope(null,false)",
            "translucency(false)",
            "nestedEntityScope(true)",
            "nestedEntityScope(false)"
        ), recorder.events);
    }

    /**
     * Mirrors the pipeline state the overrides act on: a set override phase is what
     * {@code getPhase()} reports, exactly like the deferred pipeline.
     */
    private static final class FakePipeline implements GbufferPrograms.PhaseOperations {
        private final List<WorldRenderingPhase> appliedPhases = new ArrayList<>();
        private final List<Boolean> appliedTranslucencies = new ArrayList<>();
        private WorldRenderingPhase phase = WorldRenderingPhase.NONE;
        private WorldRenderingPhase overridePhase;
        private Boolean declaredTranslucency;
        private SpecialCondition specialCondition;

        private void setPhase(WorldRenderingPhase phase) {
            this.phase = phase;
        }

        private Boolean getDeclaredTranslucency() {
            return this.declaredTranslucency;
        }

        @Override
        public GbufferPrograms.OwnedPhase getOwnedPhase() {
            throw new UnsupportedOperationException("phase ownership is not part of the override stack");
        }

        @Override
        public WorldRenderingPhase getPipelinePhase() {
            return this.overridePhase != null ? this.overridePhase : this.phase;
        }

        @Override
        public void beginEntities() {
            throw new UnsupportedOperationException("phase ownership is not part of the override stack");
        }

        @Override
        public void endEntities() {
            throw new UnsupportedOperationException("phase ownership is not part of the override stack");
        }

        @Override
        public void beginBlockEntities() {
            throw new UnsupportedOperationException("phase ownership is not part of the override stack");
        }

        @Override
        public void endBlockEntities() {
            throw new UnsupportedOperationException("phase ownership is not part of the override stack");
        }

        @Override
        public void setOverridePhase(WorldRenderingPhase phase) {
            this.overridePhase = phase;
            this.appliedPhases.add(phase);
        }

        @Override
        public void setDeclaredTranslucency(Boolean translucent) {
            this.declaredTranslucency = translucent;
            this.appliedTranslucencies.add(translucent);
        }

        @Override
        public void setSpecialCondition(SpecialCondition condition) {
            this.specialCondition = condition;
        }

        private SpecialCondition getSpecialCondition() {
            return this.specialCondition;
        }
    }
}
