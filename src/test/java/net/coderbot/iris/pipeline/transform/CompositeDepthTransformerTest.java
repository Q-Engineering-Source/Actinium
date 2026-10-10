package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeDepthTransformerTest {
    @BeforeAll
    static void provideHeadlessGlslCapability() {
        RenderSystem.initializeGlslCapabilityForTesting(460);
    }

    @Test
    void packOwnedCenterDepthSmoothVaryingIsNotRewritten() {
        // Derivative's composite3 (Temporal.vert/frag) declares its own
        // `flat out/in float centerDepthSmooth` varying and computes it in the vertex
        // stage via a colortex5 feedback loop. Redirecting that name to the engine
        // sampler turns the assignment into `texture(...).r = ...` (non-lvalue) and
        // the driver rejects the shader with "assignment to non-lvalue".
        String vertex = """
            #version 450 compatibility
            flat out float centerDepthSmooth;
            uniform sampler2D colortex5;
            uniform float frameTime;
            void main() {
                gl_Position = vec4(gl_Vertex.xy * 2.0 - 1.0, 0.0, 1.0);
                float centerDepth = texelFetch(colortex5, ivec2(0), 0).a;
                centerDepthSmooth = mix(centerDepth, 1.0, frameTime);
            }
            """;
        String fragment = """
            #version 450 compatibility
            flat in float centerDepthSmooth;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(centerDepthSmooth);
            }
            """;

        Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(vertex, null, fragment);

        assertNotNull(patched);
        String patchedVertex = patched.get(PatchShaderType.VERTEX).replaceAll("\\s+", " ");
        assertFalse(patchedVertex.contains("iris_centerDepthSmooth"), patchedVertex);
        assertTrue(patchedVertex.contains("flat out float centerDepthSmooth ;"), patchedVertex);
        assertTrue(patchedVertex.contains("centerDepthSmooth = mix ("), patchedVertex);

        String patchedFragment = patched.get(PatchShaderType.FRAGMENT).replaceAll("\\s+", " ");
        assertFalse(patchedFragment.contains("iris_centerDepthSmooth"), patchedFragment);
        assertTrue(patchedFragment.contains("flat in float centerDepthSmooth ;"), patchedFragment);
        assertTrue(patchedFragment.contains("fragColor = vec4 ( centerDepthSmooth )"), patchedFragment);
    }

    @Test
    void engineProvidedCenterDepthSmoothUniformIsStillRedirected() {
        // OptiFine-convention packs declare `uniform float centerDepthSmooth;` and expect
        // the engine to provide the value. Those reads must keep being redirected to the
        // iris_centerDepthSmooth sampler.
        String vertex = """
            #version 450 compatibility
            uniform float centerDepthSmooth;
            flat out float focusDepth;
            void main() {
                gl_Position = vec4(gl_Vertex.xy * 2.0 - 1.0, 0.0, 1.0);
                focusDepth = centerDepthSmooth;
            }
            """;
        String fragment = """
            #version 450 compatibility
            uniform float centerDepthSmooth;
            flat in float focusDepth;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(centerDepthSmooth + focusDepth);
            }
            """;

        Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(vertex, null, fragment);

        assertNotNull(patched);
        String patchedVertex = patched.get(PatchShaderType.VERTEX).replaceAll("\\s+", " ");
        assertTrue(patchedVertex.contains("uniform sampler2D iris_centerDepthSmooth ;"), patchedVertex);
        assertTrue(patchedVertex.contains("focusDepth = texture ( iris_centerDepthSmooth , vec2 ( 0.5 ) ) . r ;"), patchedVertex);

        String patchedFragment = patched.get(PatchShaderType.FRAGMENT).replaceAll("\\s+", " ");
        assertTrue(patchedFragment.contains("uniform sampler2D iris_centerDepthSmooth ;"), patchedFragment);
        assertTrue(patchedFragment.contains("vec4 ( texture ( iris_centerDepthSmooth , vec2 ( 0.5 ) ) . r + focusDepth )"), patchedFragment);
    }

    @Test
    void localCenterDepthSmoothVariableIsNotRewritten() {
        // A function-local variable that happens to share the name must never be
        // redirected to the engine sampler.
        String vertex = """
            #version 450 compatibility
            void main() {
                gl_Position = vec4(gl_Vertex.xy * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
        String fragment = """
            #version 450 compatibility
            layout(location = 0) out vec4 fragColor;
            void main() {
                float centerDepthSmooth = 0.5;
                fragColor = vec4(centerDepthSmooth);
            }
            """;

        Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(vertex, null, fragment);

        assertNotNull(patched);
        String patchedFragment = patched.get(PatchShaderType.FRAGMENT).replaceAll("\\s+", " ");
        assertFalse(patchedFragment.contains("iris_centerDepthSmooth"), patchedFragment);
        assertTrue(patchedFragment.contains("float centerDepthSmooth = 0.5 ;"), patchedFragment);
        assertTrue(patchedFragment.contains("fragColor = vec4 ( centerDepthSmooth )"), patchedFragment);
    }
}
