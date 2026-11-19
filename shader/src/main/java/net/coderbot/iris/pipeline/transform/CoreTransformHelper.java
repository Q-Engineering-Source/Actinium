package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement;
import com.gtnewhorizons.angelica.glsm.ffp.InstancedGlslHelpers;
import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import org.taumc.glsl.Transformer;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared core-profile transformation logic for matrix uniforms, texture matrices, and vertex attribute replacement.
 */
class CoreTransformHelper {

    /**
     * Inject matrix uniforms and rename FFP matrix builtins to iris_* equivalents.
     * Handles: ModelView, ModelViewInverse, Projection, ProjectionInverse, NormalMatrix, ModelViewProjectionMatrix, TextureMatrix[0..1], and LightmapTextureMatrix.
     */
    static void injectMatrixUniforms(Transformer transformer) {
        injectMatrixUniforms(transformer, Instancing.NONE);
    }

    /**
     * Declares the model-view and normal matrices as per-instance globals when the stage reads the
     * instance head, so the vertex stage can rebuild them from the instance rows. Every other stage
     * keeps them as uniforms, which the per-draw matrix upload still fills.
     */
    static void injectMatrixUniforms(Transformer transformer, Instancing instancing) {
        final boolean instanced = instancing != Instancing.NONE;
        // Rejects the layouts the attribute patch has no declarations for instead of emitting a
        // stage that references attributes nothing declares.
        if (instanced) {
            injectAttributeDecls(transformer, instancing);
        }
        if (instancing == Instancing.PARTICLE) {
            injectParticleInputs(transformer);
        }
        final boolean perInstanceMatrices = instancing.hasInstanceHead();

        transformer.injectVariable(perInstanceMatrices ? "mat4 iris_ModelViewMatrix;" : "uniform mat4 iris_ModelViewMatrix;");
        transformer.injectVariable(perInstanceMatrices ? "mat4 iris_ModelViewMatrixInverse;" : "uniform mat4 iris_ModelViewMatrixInverse;");
        transformer.injectVariable("uniform mat4 iris_ProjectionMatrix;");
        transformer.injectVariable("uniform mat4 iris_ProjectionMatrixInverse;");
        transformer.injectVariable(perInstanceMatrices ? "mat3 iris_NormalMatrix;" : "uniform mat3 iris_NormalMatrix;");
        transformer.injectVariable("uniform mat4 iris_LightmapTextureMatrix;");
        transformer.injectVariable("uniform mat4 iris_TextureMatrix;");

        if (perInstanceMatrices && !declaresInvariantPosition(transformer)) {
            transformer.injectVariable("invariant gl_Position;");
        }

        final Map<String, String> renames = new HashMap<>();
        renames.put("gl_ModelViewMatrix", "iris_ModelViewMatrix");
        renames.put("gl_ModelViewMatrixInverse", "iris_ModelViewMatrixInverse");
        renames.put("gl_ProjectionMatrix", "iris_ProjectionMatrix");
        renames.put("gl_ProjectionMatrixInverse", "iris_ProjectionMatrixInverse");
        renames.put("gl_NormalMatrix", "iris_NormalMatrix");
        transformer.rename(renames);

        final Map<String, String> replacements = new HashMap<>();
        replacements.put("gl_ModelViewProjectionMatrix", "(iris_ProjectionMatrix * iris_ModelViewMatrix)");
        replacements.put("gl_TextureMatrix[0]", "iris_TextureMatrix");
        replacements.put("gl_TextureMatrix[1]", "iris_LightmapTextureMatrix");
        replacements.forEach(transformer::replaceExpression);

        // Catch any remaining gl_TextureMatrix references (e.g. [2]-[7])
        transformer.replaceExpression("gl_TextureMatrix", "mat4[8](iris_TextureMatrix, iris_LightmapTextureMatrix, mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0))");
    }

    /**
     * Declares the two per-vertex inputs of the particle quad and the assembled vertex values the
     * rest of the patch reads. The quad carries only a corner offset and the corner's share of the
     * texture rectangle; position, colour, texture coordinate and lightmap are built in
     * {@code main} from the instance, so the stage must not declare the per-vertex attributes the
     * other layouts use.
     */
    private static void injectParticleInputs(Transformer transformer) {
        transformer.injectVariable("layout(location = " + VertexFormatElement.Usage.POSITION.getAttributeLocation() + ") in vec3 iris_ParticleOffset;");
        transformer.injectVariable("layout(location = " + VertexFormatElement.Usage.PRIMARY_UV.getAttributeLocation() + ") in vec2 iris_ParticleCorner;");
        transformer.injectVariable("vec4 iris_Vertex;");
        transformer.injectVariable("vec4 iris_Color;");
        transformer.injectVariable("vec4 iris_MultiTexCoord0;");
        transformer.injectVariable("vec4 iris_MultiTexCoord1;");
    }

    private static void injectAttributeDecls(Transformer transformer, Instancing instancing) {
        for (String decl : InstancedGlslHelpers.attributeDecls("iris_", instancing)) {
            transformer.injectVariable(decl);
        }
    }

    /** Whether the stage already declares {@code invariant gl_Position;}, which a second declaration rejects. */
    private static boolean declaresInvariantPosition(Transformer transformer) {
        final boolean[] found = new boolean[1];
        transformer.mutateTree(root -> {
            for (int i = 0; i < root.getChildCount(); i++) {
                if ("invariantgl_Position;".equals(root.getChild(i).getText())) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }

    /**
     * Inject vertex attributes and ftransform() replacement for composite/depth shaders. Uses locations matching FullScreenQuadRenderer's POSITION_TEXTURE VAO layout.
     */
    static void injectCompositeVertexAttributes(Transformer transformer) {
        transformer.injectVariable("layout(location = 0) in vec4 iris_Vertex;");
        transformer.injectVariable("layout(location = 2) in vec4 iris_MultiTexCoord0;");

        transformer.rename("gl_Vertex", "iris_Vertex");
        transformer.rename("gl_MultiTexCoord0", "iris_MultiTexCoord0");

        transformer.renameFunctionCall("ftransform", "iris_ftransform");
        transformer.injectFunction("vec4 iris_ftransform() { return (iris_ProjectionMatrix * iris_ModelViewMatrix) * iris_Vertex; }");

        ShaderTransformer.applyIntelHd4000Workaround(transformer);
    }
}
