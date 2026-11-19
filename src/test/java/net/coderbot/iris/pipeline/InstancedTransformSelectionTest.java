package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gbuffer_overrides.matching.ProgramTable;
import net.coderbot.iris.gbuffer_overrides.matching.RenderCondition;
import net.coderbot.iris.shaderpack.loading.ProgramGroup;
import net.coderbot.iris.shaderpack.loading.ProgramId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstancedTransformSelectionTest {

    /**
     * The render conditions the batched tile-entity path draws. Glint always draws textured, so its
     * availability only varies by lightmap.
     */
    private static final List<RenderCondition> BATCHED_CONDITIONS = List.of(
        RenderCondition.ENTITIES, RenderCondition.ENTITIES_TRANSLUCENT,
        RenderCondition.BLOCK_ENTITIES, RenderCondition.BLOCK_ENTITIES_TRANSLUCENT,
        RenderCondition.GLINT,
        RenderCondition.SHADOW, RenderCondition.SHADOW_TRANSLUCENT);

    @Test
    void everyBatchedPassResolvesToAProgramTheInstancedLayoutsTransform() {
        final Set<ProgramId> template = new HashSet<>(Arrays.asList(DeferredWorldRenderingPipeline.attributeProgramIds(Instancing.TEMPLATE)));
        final Set<ProgramId> cube = new HashSet<>(Arrays.asList(DeferredWorldRenderingPipeline.attributeProgramIds(Instancing.CUBE)));
        assertTrue(template.equals(cube), "the template and cube layouts share one program set");

        for (RenderCondition condition : BATCHED_CONDITIONS) {
            for (InputAvailability availability : availabilitiesFor(condition)) {
                final ProgramId id = DeferredWorldRenderingPipeline.gbufferProgramId(
                    DeferredWorldRenderingPipeline.PROGRAM_IDS, condition, availability);
                assertNotNull(id, condition + " at " + availability + " resolves no program");
                assertTrue(template.contains(id),
                    condition + " at " + availability + " resolves to " + id + ", which has no instanced transform");
            }
        }
    }

    @Test
    void transformRequestsStayInsideTheGbufferAndShadowGroups() {
        for (Instancing kind : new Instancing[] { Instancing.TEMPLATE, Instancing.CUBE }) {
            final ProgramId[] ids = DeferredWorldRenderingPipeline.attributeProgramIds(kind);
            final Set<ProgramId> unique = new HashSet<>(Arrays.asList(ids));
            assertTrue(unique.size() == ids.length, kind + " requests a program twice");
            for (ProgramId id : ids) {
                assertFalse(id.getGroup() == ProgramGroup.Dh,
                    kind + " must not transform the Distant Horizons programs, which have their own patch");
            }
        }
    }

    @Test
    void everyAvailabilityTheBatchesCanQueryHasAPrecomputedTransform() {
        final List<InputAvailability> submitted = Arrays.asList(DeferredWorldRenderingPipeline.INPUT_AVAILABILITIES);

        for (boolean texture : new boolean[] { false, true }) {
            for (boolean lightmap : new boolean[] { false, true }) {
                final InputAvailability availability = InputAvailability.of(texture, lightmap);
                assertTrue(submitted.contains(availability),
                    "no attribute transform is submitted for " + availability
                        + "; a variant for it would resolve nothing and keep the CPU path forever");
            }
        }
    }

    private static List<InputAvailability> availabilitiesFor(RenderCondition condition) {
        if (condition == RenderCondition.GLINT) {
            return List.of(InputAvailability.of(true, false), InputAvailability.of(true, true));
        }
        return List.of(InputAvailability.of(false, false), InputAvailability.of(true, false),
            InputAvailability.of(false, true), InputAvailability.of(true, true));
    }

    @Test
    void theVariantCompileVisitsEveryTableRowForEveryLayout() {
        final int rows = RenderCondition.values().length * InputAvailability.NUM_VALUES;
        final AtomicInteger next = new AtomicInteger();
        final ProgramTable<Integer> table = new ProgramTable<>((condition, availability) -> next.getAndIncrement());
        final Map<Integer, Set<Instancing>> visits = new HashMap<>();

        DeferredWorldRenderingPipeline.forEachInstancingTarget(table, (row, kind) ->
            visits.computeIfAbsent(row, key -> EnumSet.noneOf(Instancing.class)).add(kind));

        assertEquals(rows, visits.size(), "every render condition and availability row must be compiled");
        for (Map.Entry<Integer, Set<Instancing>> entry : visits.entrySet()) {
            final Set<Instancing> expected = EnumSet.allOf(Instancing.class);
            expected.remove(Instancing.NONE);
            assertEquals(expected, entry.getValue(), "row " + entry.getKey() + " was not compiled for every layout");
        }
    }

    @Test
    void aPassSharedBySeveralRowsIsCompiledOnce() {
        final String shared = "gbuffers_entities";
        final ProgramTable<String> table = new ProgramTable<>((condition, availability) -> shared);
        final AtomicInteger visits = new AtomicInteger();

        DeferredWorldRenderingPipeline.forEachInstancingTarget(table, (row, kind) -> visits.incrementAndGet());

        assertEquals(Instancing.VALUES.length - 1, visits.get(),
            "a shared pass resolves one variant per layout, not one per table row");
    }

    @Test
    void aTableWithoutAPassIsSkipped() {
        final AtomicInteger visits = new AtomicInteger();
        final ProgramTable<String> table = new ProgramTable<>((condition, availability) -> null);

        DeferredWorldRenderingPipeline.forEachInstancingTarget(table, (row, kind) -> visits.incrementAndGet());

        assertEquals(0, visits.get());
    }

    @Test
    void matrixBuiltinsAreDetectedByUseNotByMention() {
        assertTrue(DeferredWorldRenderingPipeline.referencesMvBuiltins("""
            #version 120
            varying vec3 wpos;
            void main() {
                wpos = (gl_ModelViewMatrix * gl_Vertex).xyz;
                gl_Position = ftransform();
            }
            """), "a vertex-stage matrix read must be detected");

        assertTrue(DeferredWorldRenderingPipeline.referencesMvBuiltins("""
            #version 330 core
            uniform mat4 modelViewMatrix;
            out vec3 wpos;
            void main() { wpos = (modelViewMatrix * vec4(0.0)).xyz; }
            """), "a core-profile matrix uniform read must be detected");

        assertFalse(DeferredWorldRenderingPipeline.referencesMvBuiltins("""
            #version 120
            // gl_ModelViewMatrix would break the instance layout here, but this is only a comment
            varying vec4 color;
            void main() { gl_FragData[0] = color; }
            """), "a mention in a comment is not a use");

        assertFalse(DeferredWorldRenderingPipeline.referencesMvBuiltins(null), "an absent stage references nothing");
    }

    @Test
    void declarationsAloneDoNotForceTheCpuPath() {
        assertFalse(DeferredWorldRenderingPipeline.referencesMvBuiltins("""
            #version 330 core
            uniform mat4 modelViewMatrix;
            out vec3 wpos;
            void main() { wpos = vec3(0.0); }
            """), "a matrix uniform the stage never reads does not need the per-draw matrix");
    }
}
