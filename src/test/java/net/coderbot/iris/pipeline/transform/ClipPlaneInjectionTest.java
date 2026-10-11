package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #214: the FFP clip-plane injection (gl_ClipDistance writes in gbuffers vertex shaders) must
 * only happen when the driver can compile gl_ClipDistance. On ES and on translation layers that
 * masquerade as desktop GL (MobileGlues) without GL_EXT_clip_cull_distance the injected code fails
 * compilation for every gbuffers/shadow program.
 */
class ClipPlaneInjectionTest {
    @BeforeAll
    static void provideHeadlessGlslCapability() {
        RenderSystem.initializeGlslCapabilityForTesting(460);
    }

    @AfterEach
    void resetClipCullDistance() {
        RenderSystem.initializeClipCullDistanceSupportForTesting(false);
        TransformPatcher.clearCache();
    }

    @Test
    void clipPlanesInjectedWhenDriverSupportsClipDistance() {
        RenderSystem.initializeClipCullDistanceSupportForTesting(true);
        TransformPatcher.clearCache();

        String vertex = """
            #version 120
            void main() {
                gl_Position = ftransform();
            }
            """;
        String fragment = """
            #version 120
            void main() {
                gl_FragColor = vec4(1.0);
            }
            """;

        Map<PatchShaderType, String> patched = TransformPatcher.patchAttributes(vertex, null, fragment,
            new InputAvailability(true, true, true));

        String patchedVertex = patched.get(PatchShaderType.VERTEX);
        assertNotNull(patchedVertex);
        assertTrue(patchedVertex.contains("uniform bool actinium_ClipPlanesEnabled"), patchedVertex);
        assertTrue(patchedVertex.contains("uniform vec4 actinium_ClipPlane [ 8 ]"), patchedVertex);
        assertTrue(patchedVertex.contains("gl_ClipDistance [ 0 ]"), patchedVertex);
    }

    @Test
    void clipPlanesOmittedWhenDriverLacksClipDistance() {
        RenderSystem.initializeClipCullDistanceSupportForTesting(false);
        TransformPatcher.clearCache();

        String vertex = """
            #version 120
            void main() {
                gl_Position = ftransform() * 1.0;
            }
            """;
        String fragment = """
            #version 120
            void main() {
                gl_FragColor = vec4(0.5);
            }
            """;

        Map<PatchShaderType, String> patched = TransformPatcher.patchAttributes(vertex, null, fragment,
            new InputAvailability(true, true, true));

        String patchedVertex = patched.get(PatchShaderType.VERTEX);
        assertNotNull(patchedVertex);
        assertFalse(patchedVertex.contains("actinium_ClipPlane"), patchedVertex);
        assertFalse(patchedVertex.contains("gl_ClipDistance"), patchedVertex);
        assertTrue(patchedVertex.contains("void main"), patchedVertex);
    }
}
