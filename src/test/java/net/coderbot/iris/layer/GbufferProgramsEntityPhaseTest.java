package net.coderbot.iris.layer;

import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbufferProgramsEntityPhaseTest {
    @Test
    void ownsEntityPhaseWhenPipelineIsIdle() {
        FakePhaseOperations operations = new FakePhaseOperations(
            GbufferPrograms.OwnedPhase.NONE,
            WorldRenderingPhase.NONE
        );

        GbufferPrograms.EntityPhase phase = GbufferPrograms.enterEntityPhase(operations);

        assertTrue(phase.changedPhase());
        assertEquals(GbufferPrograms.OwnedPhase.ENTITIES, operations.ownedPhase);
        phase.close();
        assertEquals(GbufferPrograms.OwnedPhase.NONE, operations.ownedPhase);
        assertEquals(List.of("beginEntities", "endEntities"), operations.events);
    }

    @Test
    void leavesAnExistingEntityPhaseToItsOwner() {
        FakePhaseOperations operations = new FakePhaseOperations(
            GbufferPrograms.OwnedPhase.ENTITIES,
            WorldRenderingPhase.ENTITIES
        );

        GbufferPrograms.EntityPhase phase = GbufferPrograms.enterEntityPhase(operations);

        assertFalse(phase.changedPhase());
        phase.close();
        assertEquals(GbufferPrograms.OwnedPhase.ENTITIES, operations.ownedPhase);
        assertEquals(List.of(), operations.events);
    }

    @Test
    void suspendsAndRestoresOuterBlockEntityPhaseWhenPipelineWasReset() {
        FakePhaseOperations operations = new FakePhaseOperations(
            GbufferPrograms.OwnedPhase.BLOCK_ENTITIES,
            WorldRenderingPhase.NONE
        );

        GbufferPrograms.EntityPhase phase = GbufferPrograms.enterEntityPhase(operations);

        assertTrue(phase.changedPhase());
        assertEquals(GbufferPrograms.OwnedPhase.ENTITIES, operations.ownedPhase);
        phase.close();
        assertEquals(GbufferPrograms.OwnedPhase.BLOCK_ENTITIES, operations.ownedPhase);
        assertEquals(
            List.of("endBlockEntities", "beginEntities", "endEntities", "beginBlockEntities"),
            operations.events
        );
    }

    @Test
    void restoresBlockEntityPhaseAfterNestedEntityScopesClose() {
        FakePhaseOperations operations = new FakePhaseOperations(
            GbufferPrograms.OwnedPhase.BLOCK_ENTITIES,
            WorldRenderingPhase.BLOCK_ENTITIES
        );

        try (GbufferPrograms.EntityPhase outer = GbufferPrograms.enterEntityPhase(operations)) {
            assertEquals(GbufferPrograms.OwnedPhase.ENTITIES, operations.ownedPhase);
            try (GbufferPrograms.EntityPhase inner = GbufferPrograms.enterEntityPhase(operations)) {
                assertFalse(inner.changedPhase());
                assertEquals(GbufferPrograms.OwnedPhase.ENTITIES, operations.ownedPhase);
            }
            assertEquals(GbufferPrograms.OwnedPhase.ENTITIES, operations.ownedPhase);
        }

        assertEquals(GbufferPrograms.OwnedPhase.BLOCK_ENTITIES, operations.ownedPhase);
        assertEquals(
            List.of("endBlockEntities", "beginEntities", "endEntities", "beginBlockEntities"),
            operations.events
        );
    }

    @Test
    void restoresBlockEntityPhaseBetweenConsecutiveEntityScopes() {
        FakePhaseOperations operations = new FakePhaseOperations(
            GbufferPrograms.OwnedPhase.BLOCK_ENTITIES,
            WorldRenderingPhase.BLOCK_ENTITIES
        );

        try (GbufferPrograms.EntityPhase first = GbufferPrograms.enterEntityPhase(operations)) {
            assertEquals(GbufferPrograms.OwnedPhase.ENTITIES, operations.ownedPhase);
        }
        assertEquals(GbufferPrograms.OwnedPhase.BLOCK_ENTITIES, operations.ownedPhase);

        try (GbufferPrograms.EntityPhase second = GbufferPrograms.enterEntityPhase(operations)) {
            assertEquals(GbufferPrograms.OwnedPhase.ENTITIES, operations.ownedPhase);
        }

        assertEquals(GbufferPrograms.OwnedPhase.BLOCK_ENTITIES, operations.ownedPhase);
        assertEquals(
            List.of(
                "endBlockEntities",
                "beginEntities",
                "endEntities",
                "beginBlockEntities",
                "endBlockEntities",
                "beginEntities",
                "endEntities",
                "beginBlockEntities"
            ),
            operations.events
        );
    }

    @Test
    void restoresBlockEntityPhaseWhenEnteringEntitiesFails() {
        FakePhaseOperations operations = new FakePhaseOperations(
            GbufferPrograms.OwnedPhase.BLOCK_ENTITIES,
            WorldRenderingPhase.BLOCK_ENTITIES
        );
        IllegalStateException failure = new IllegalStateException("begin entity phase failed");
        operations.beginEntitiesFailure = failure;

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> GbufferPrograms.enterEntityPhase(operations)
        );

        assertSame(failure, thrown);
        assertEquals(GbufferPrograms.OwnedPhase.BLOCK_ENTITIES, operations.ownedPhase);
        assertEquals(
            List.of("endBlockEntities", "beginEntities", "beginBlockEntities"),
            operations.events
        );
    }

    @Test
    void restoresBlockEntityPhaseWhenEntityRenderingFails() {
        FakePhaseOperations operations = new FakePhaseOperations(
            GbufferPrograms.OwnedPhase.BLOCK_ENTITIES,
            WorldRenderingPhase.BLOCK_ENTITIES
        );
        IllegalStateException failure = new IllegalStateException("entity render failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> {
            try (GbufferPrograms.EntityPhase ignored = GbufferPrograms.enterEntityPhase(operations)) {
                throw failure;
            }
        });

        assertSame(failure, thrown);
        assertEquals(GbufferPrograms.OwnedPhase.BLOCK_ENTITIES, operations.ownedPhase);
        assertEquals(
            List.of("endBlockEntities", "beginEntities", "endEntities", "beginBlockEntities"),
            operations.events
        );
    }

    private static final class FakePhaseOperations implements GbufferPrograms.PhaseOperations {
        private final List<String> events = new ArrayList<>();
        private GbufferPrograms.OwnedPhase ownedPhase;
        private WorldRenderingPhase pipelinePhase;
        private RuntimeException beginEntitiesFailure;

        private FakePhaseOperations(
            GbufferPrograms.OwnedPhase ownedPhase,
            WorldRenderingPhase pipelinePhase
        ) {
            this.ownedPhase = ownedPhase;
            this.pipelinePhase = pipelinePhase;
        }

        @Override
        public GbufferPrograms.OwnedPhase getOwnedPhase() {
            return this.ownedPhase;
        }

        @Override
        public WorldRenderingPhase getPipelinePhase() {
            return this.pipelinePhase;
        }

        @Override
        public void beginEntities() {
            this.events.add("beginEntities");
            if (this.beginEntitiesFailure != null) {
                throw this.beginEntitiesFailure;
            }
            this.assertOwnedPhase(GbufferPrograms.OwnedPhase.NONE);
            this.ownedPhase = GbufferPrograms.OwnedPhase.ENTITIES;
            this.pipelinePhase = WorldRenderingPhase.ENTITIES;
        }

        @Override
        public void endEntities() {
            this.events.add("endEntities");
            this.assertOwnedPhase(GbufferPrograms.OwnedPhase.ENTITIES);
            this.ownedPhase = GbufferPrograms.OwnedPhase.NONE;
            this.pipelinePhase = WorldRenderingPhase.NONE;
        }

        @Override
        public void beginBlockEntities() {
            this.events.add("beginBlockEntities");
            this.assertOwnedPhase(GbufferPrograms.OwnedPhase.NONE);
            this.ownedPhase = GbufferPrograms.OwnedPhase.BLOCK_ENTITIES;
            this.pipelinePhase = WorldRenderingPhase.BLOCK_ENTITIES;
        }

        @Override
        public void endBlockEntities() {
            this.events.add("endBlockEntities");
            this.assertOwnedPhase(GbufferPrograms.OwnedPhase.BLOCK_ENTITIES);
            this.ownedPhase = GbufferPrograms.OwnedPhase.NONE;
            this.pipelinePhase = WorldRenderingPhase.NONE;
        }

        @Override
        public void setOverridePhase(WorldRenderingPhase phase) {
            throw new UnsupportedOperationException("override phases are covered by GbufferProgramsStateStackTest");
        }

        @Override
        public void setDeclaredTranslucency(Boolean translucent) {
            throw new UnsupportedOperationException("translucency declarations are covered by GbufferProgramsStateStackTest");
        }

        @Override
        public void setSpecialCondition(SpecialCondition condition) {
            throw new UnsupportedOperationException("special conditions are covered by GbufferProgramsStateStackTest");
        }

        private void assertOwnedPhase(GbufferPrograms.OwnedPhase expected) {
            if (this.ownedPhase != expected) {
                throw new IllegalStateException("Expected " + expected + " but owned " + this.ownedPhase);
            }
        }
    }
}
