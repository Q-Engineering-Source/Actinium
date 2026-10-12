package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.PerFrameUniformBlock;
import com.gtnewhorizons.angelica.glsm.hooks.PerFrameUniformBlock.Member;
import com.gtnewhorizons.angelica.glsm.shader.GlslVulkanPreprocess;
import com.gtnewhorizons.angelica.glsm.shader.UniformType;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import net.coderbot.iris.pipeline.transform.parameter.CeleritasTerrainParameters;
import net.coderbot.iris.pipeline.transform.parameter.TextureStageParameters;
import net.coderbot.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the artifact the transformer hands the shader backend against the source it printed.
 *
 * <p>The backend skips its own parse when it is given the tree the printed body came from and the
 * length of the header in front of that body. Both halves have to line up exactly: a header length
 * that is off by a byte, or a tree whose token offsets were not rebased onto the printed body, sends
 * every edit the backend applies to the wrong offset. The check is the consumer's own output, fed
 * the artifact, against the same consumer re-parsing the source.</p>
 */
class ShaderTransformArtifactTest {

    private static final String VERTEX = """
        #version 120
        varying vec2 texcoord;
        void main() {
            texcoord = gl_MultiTexCoord0.xy;
            gl_Position = ftransform();
        }
        """;

    private static final String FRAGMENT = """
        #version 120
        varying vec2 texcoord;
        uniform sampler2D u_Unused;
        uniform float frameTimeCounter;
        void main() {
            gl_FragColor = vec4(texcoord, frameTimeCounter, 1.0);
        }
        """;

    @BeforeAll
    static void provideHeadlessGlslCapability() {
        RenderSystem.initializeGlslCapabilityForTesting(460);
    }

    @AfterEach
    void clearUniformBlocks() {
        GLSMHooks.perFrameUniformBlock = null;
        GLSMHooks.perPassUniformBlock = null;
    }

    /**
     * The pre-processing edits the two stages of this pack exercise: an unused sampler to strip, a
     * per-frame uniform to move into a block, and the clip-space remap the vertex stage gets.
     */
    private static void usePackWithEdits() {
        GLSMHooks.perFrameUniformBlock = new PerFrameUniformBlock(List.of(
            new Member("cameraPosition", UniformType.VEC3),
            new Member("frameTimeCounter", UniformType.FLOAT)));
    }

    @Test
    void everyStageCarriesItsTreeAndHeaderLength() {
        usePackWithEdits();

        final EnumMap<PatchShaderType, ShaderTransformer.StageArtifact> artifacts = new EnumMap<>(PatchShaderType.class);
        final Map<PatchShaderType, String> transformed = transform(artifacts);

        for (Map.Entry<PatchShaderType, String> entry : transformed.entrySet()) {
            final PatchShaderType stage = entry.getKey();
            final String source = entry.getValue();
            if (source == null) {
                continue;
            }

            final ShaderTransformer.StageArtifact artifact = artifacts.get(stage);
            assertNotNull(artifact, "no artifact was handed over for " + stage);
            final String header = source.substring(0, artifact.headerLen());
            assertTrue(header.startsWith("#version"), "the artifact must slice at the end of the header, got: " + header);
            assertTrue(source.length() > artifact.headerLen(), "the artifact must leave a body behind for " + stage);
        }
    }

    @Test
    void theHandedTreePrewarmsToTheSameSourceAsReparsing() {
        usePackWithEdits();

        final EnumMap<PatchShaderType, ShaderTransformer.StageArtifact> artifacts = new EnumMap<>(PatchShaderType.class);
        final Map<PatchShaderType, String> transformed = transform(artifacts);

        for (Map.Entry<PatchShaderType, String> entry : transformed.entrySet()) {
            final PatchShaderType stage = entry.getKey();
            final String source = entry.getValue();
            if (source == null) {
                continue;
            }
            final ShaderTransformer.StageArtifact artifact = artifacts.get(stage);
            assertNotNull(artifact, "no artifact was handed over for " + stage);

            GlslVulkanPreprocess.clearCache();
            final ShaderManager.PrewarmTransformResult reparsed = ShaderManager.applyPrewarmTransformsFull(
                source, stage.glShaderType.id, GLSMHooks.perFrameUniformBlock, GLSMHooks.perPassUniformBlock);

            GlslVulkanPreprocess.clearCache();
            final ShaderManager.PrewarmTransformResult handedOver = ShaderManager.applyPrewarmTransformsFull(
                source, artifact.tree(), artifact.headerLen(), stage.glShaderType.id,
                GLSMHooks.perFrameUniformBlock, GLSMHooks.perPassUniformBlock);

            assertEquals(reparsed.source(), handedOver.source(), "tree handoff diverged from re-parsing " + stage);
            assertEquals(reparsed.boolUniforms(), handedOver.boolUniforms(), "bool uniforms diverged for " + stage);
        }
    }

    /**
     * The terrain stage prints a header its tree cannot describe (the injected chunk vertex include),
     * so its body offsets are not handable and the stage is left for the backend to parse.
     */
    @Test
    void theTerrainVertexStageIsLeftToTheBackendToParse() {
        final EnumMap<PatchShaderType, ShaderTransformer.StageArtifact> artifacts = new EnumMap<>(PatchShaderType.class);
        final Map<PatchShaderType, String> transformed = ShaderTransformer.transform(VERTEX, null, null, null, FRAGMENT,
            new CeleritasTerrainParameters(Patch.CELERITAS_TERRAIN), artifacts);

        assertNotNull(transformed.get(PatchShaderType.VERTEX));
        assertNull(artifacts.get(PatchShaderType.VERTEX), "the terrain vertex stage must not hand over a tree");
        assertNotNull(artifacts.get(PatchShaderType.FRAGMENT), "the terrain fragment stage still hands over its tree");
    }

    private static Map<PatchShaderType, String> transform(EnumMap<PatchShaderType, ShaderTransformer.StageArtifact> artifacts) {
        return ShaderTransformer.transform(VERTEX, null, null, null, FRAGMENT,
            new TextureStageParameters(Patch.COMPOSITE, TextureStage.COMPOSITE_AND_FINAL, null), artifacts);
    }
}
