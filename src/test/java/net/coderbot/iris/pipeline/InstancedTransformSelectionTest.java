package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import net.coderbot.iris.shaderpack.loading.ProgramGroup;
import net.coderbot.iris.shaderpack.loading.ProgramId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstancedTransformSelectionTest {

    @Test
    void bothSharedLayoutsTransformTheBatchedPrograms() {
        final Set<ProgramId> template = new HashSet<>(Arrays.asList(DeferredWorldRenderingPipeline.attributeProgramIds(Instancing.TEMPLATE)));
        final Set<ProgramId> cube = new HashSet<>(Arrays.asList(DeferredWorldRenderingPipeline.attributeProgramIds(Instancing.CUBE)));

        assertEquals(template, cube, "the template and cube layouts share one program set");
        assertEquals(EnumSet.of(ProgramId.Block, ProgramId.BlockTrans, ProgramId.Entities, ProgramId.EntitiesTrans,
            ProgramId.Shadow, ProgramId.ShadowWater, ProgramId.ArmorGlint, ProgramId.SpiderEyes), template);

        for (ProgramId id : template) {
            assertFalse(id.getGroup() == ProgramGroup.Dh,
                "Distant Horizons programs are not part of the instance transform set");
        }
    }

    @Test
    void perVertexTransformsCoverEveryProgramAndUnsupportedLayoutsCoverNone() {
        assertEquals(ProgramId.values().length, DeferredWorldRenderingPipeline.attributeProgramIds(Instancing.NONE).length);

        // Both layouts have no transform site yet, so submitting nothing keeps their kinds from
        // reporting a variant the pipeline cannot build.
        assertEquals(0, DeferredWorldRenderingPipeline.attributeProgramIds(Instancing.PARTICLE).length);
        assertEquals(0, DeferredWorldRenderingPipeline.attributeProgramIds(Instancing.WEATHER).length);
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
