package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.ffp.CubeInstancedAttribs;
import com.gtnewhorizons.angelica.glsm.ffp.InstancedAttribs;
import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import com.gtnewhorizons.angelica.glsm.hooks.ImmediateExtendedAttribHandler;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.pipeline.transform.parameter.AttributeParameters;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taumc.glsl.ShaderParser;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.coderbot.iris.pipeline.transform.TransformTestSupport.TEX_LM;
import static net.coderbot.iris.pipeline.transform.TransformTestSupport.squash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeTransformerInstancedTest {

    private static final Pattern ATTRIBUTE_LOCATION = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*in\\s+\\w+\\s+(\\w+)");

    private static final String VERTEX_ENTITY_AND_COLOR = """
        #version 120
        uniform int entityId;
        uniform vec4 entityColor;
        varying vec4 vColor;
        void main() {
            vColor = entityColor * float(entityId);
            gl_Position = ftransform();
        }
        """;

    private static final String FRAGMENT_ENTITY_AND_COLOR = """
        #version 120
        uniform int entityId;
        uniform vec4 entityColor;
        varying vec4 vColor;
        void main() {
            gl_FragData[0] = vColor + entityColor + vec4(float(entityId));
        }
        """;

    private static final String VERTEX = """
        #version 120
        attribute vec4 mc_Entity;
        attribute vec4 mc_midTexCoord;
        varying vec2 texcoord;
        varying vec2 lmcoord;
        varying vec4 color;
        varying float mats;
        varying vec2 midtex;
        varying vec3 nrm;
        void main() {
            texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
            lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
            color = gl_Color;
            mats = mc_Entity.x;
            midtex = mc_midTexCoord.st;
            nrm = normalize(gl_NormalMatrix * gl_Normal);
            gl_Position = ftransform();
        }
        """;

    private static final String VERTEX_MV_INVERSE = """
        #version 120
        varying vec3 wpos;
        varying vec4 color;
        void main() {
            wpos = (gl_ModelViewMatrixInverse * (gl_ModelViewMatrix * gl_Vertex)).xyz;
            color = vec4(wpos, 1.0);
            gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
        }
        """;

    private static final String FRAGMENT = """
        #version 120
        varying vec4 color;
        void main() {
            gl_FragData[0] = color;
        }
        """;

    private static final String VERTEX_ENTITY = """
        #version 120
        uniform int entityId;
        uniform int blockEntityId;
        uniform int currentRenderedItemId;
        varying float idSum;
        void main() {
            idSum = float(entityId + blockEntityId + currentRenderedItemId);
            gl_Position = ftransform();
        }
        """;

    private static final String FRAGMENT_ENTITY = """
        #version 120
        uniform int entityId;
        varying float idSum;
        void main() {
            gl_FragData[0] = vec4(idSum, float(entityId), 0.0, 1.0);
        }
        """;

    private static final String VERTEX_OVERLAY = """
        #version 120
        uniform vec4 entityColor;
        varying vec4 overlay;
        void main() {
            overlay = entityColor;
            gl_Position = ftransform();
        }
        """;

    private static final String FRAGMENT_OVERLAY = """
        #version 120
        uniform vec4 entityColor;
        void main() {
            gl_FragData[0] = entityColor;
        }
        """;

    private static final String VERTEX_TANGENT = """
        #version 120
        attribute vec4 at_tangent;
        varying vec3 tang;
        void main() {
            tang = at_tangent.xyz * at_tangent.w;
            gl_Position = ftransform();
        }
        """;

    private static final String VERTEX_TANGENT_VEC3 = """
        #version 120
        attribute vec3 at_tangent;
        varying vec3 tang;
        void main() {
            tang = at_tangent;
            gl_Position = ftransform();
        }
        """;

    private static int previousMaxGlslVersion;

    @BeforeAll
    static void pinGlslVersion() {
        previousMaxGlslVersion = TransformTestSupport.pinGlslVersion(460);
    }

    @AfterAll
    static void restoreGlslVersion() {
        TransformTestSupport.restoreGlslVersion(previousMaxGlslVersion);
    }

    @BeforeEach
    void clearCaches() {
        TransformTestSupport.clearCaches();
    }

    private static void assertHas(String shader, String snippet) {
        assertTrue(squash(shader).contains(squash(snippet)), "expected snippet: " + snippet + "\nin:\n" + shader);
    }

    private static void assertLacks(String shader, String snippet) {
        assertFalse(squash(shader).contains(squash(snippet)), "unexpected snippet: " + snippet + "\nin:\n" + shader);
    }

    private static int indexOfSquashed(String shader, String snippet) {
        return squash(shader).indexOf(squash(snippet));
    }

    private static Map<Integer, String> attributeLocations(String shader) {
        final Matcher m = ATTRIBUTE_LOCATION.matcher(shader);
        final Map<Integer, String> byLocation = new HashMap<>();
        while (m.find()) {
            final int loc = Integer.parseInt(m.group(1));
            final String name = m.group(2);
            final String previous = byLocation.put(loc, name);
            assertNull(previous, "location " + loc + " declared twice: " + previous + " and " + name);
        }
        return byLocation;
    }

    private static int locationOf(Map<Integer, String> byLocation, String name) {
        for (final Map.Entry<Integer, String> e : byLocation.entrySet()) {
            if (e.getValue().equals(name)) return e.getKey();
        }
        throw new AssertionError("expected " + name + " to be declared at a layout location, found: " + byLocation);
    }

    private static String instancedVertex(String vertex) {
        return instancedVertex(vertex, Instancing.TEMPLATE);
    }

    private static String cubeVertex(String vertex) {
        return instancedVertex(vertex, Instancing.CUBE);
    }

    private static String instancedVertex(String vertex, Instancing instancing) {
        Map<PatchShaderType, String> result = TransformPatcher.patchAttributesInstanced(vertex, null, null, null, FRAGMENT, TEX_LM, instancing);
        assertNotNull(result);
        String v = result.get(PatchShaderType.VERTEX);
        assertNotNull(v);
        ShaderParser.parseShader(v);
        return v;
    }

    private static Map<PatchShaderType, String> instancedStages(String vertex, String fragment, Instancing instancing) {
        final Map<PatchShaderType, String> result = TransformPatcher.patchAttributesInstanced(vertex, null, null, null, fragment, TEX_LM, instancing);
        assertNotNull(result);
        assertNotNull(result.get(PatchShaderType.VERTEX));
        assertNotNull(result.get(PatchShaderType.FRAGMENT));
        ShaderParser.parseShader(result.get(PatchShaderType.VERTEX));
        ShaderParser.parseShader(result.get(PatchShaderType.FRAGMENT));
        return result;
    }

    @Test
    void declaresInstanceAttribsAtDistinctLocationsAndGlobalMatrices() {
        final String v = instancedVertex(VERTEX);
        attributeLocations(v);
        assertHas(v, "layout(location = " + InstancedAttribs.LOC_ROW0 + ") in vec4 iris_InstRow0");
        assertHas(v, "layout(location = " + InstancedAttribs.LOC_ROW1 + ") in vec4 iris_InstRow1");
        assertHas(v, "layout(location = " + InstancedAttribs.LOC_ROW2 + ") in vec4 iris_InstRow2");
        assertHas(v, "layout(location = " + InstancedAttribs.LOC_COLOR + ") in vec4 iris_InstColor");
        assertHas(v, "layout(location = " + InstancedAttribs.LOC_OVERLAY + ") in vec4 iris_InstOverlay");
        assertHas(v, "layout(location = " + InstancedAttribs.LOC_LIGHTMAP + ") in vec2 iris_InstLightmap");
        assertLacks(v, "uniform mat4 iris_ModelViewMatrix;");
        assertLacks(v, "uniform mat3 iris_NormalMatrix;");
        assertHas(v, "mat4 iris_ModelViewMatrix;");
        assertHas(v, "iris_ModelViewMatrix = mat4(vec4(iris_InstRow0.x, iris_InstRow1.x, iris_InstRow2.x, 0.0), vec4(iris_InstRow0.y, iris_InstRow1.y, iris_InstRow2.y, 0.0), vec4(iris_InstRow0.z, iris_InstRow1.z, iris_InstRow2.z, 0.0), vec4(iris_InstRow0.w, iris_InstRow1.w, iris_InstRow2.w, 1.0))");
        assertHas(v, "iris_NormalMatrix = mat3(normalize(vec3(iris_InstRow0.x, iris_InstRow1.x, iris_InstRow2.x)), normalize(vec3(iris_InstRow0.y, iris_InstRow1.y, iris_InstRow2.y)), normalize(vec3(iris_InstRow0.z, iris_InstRow1.z, iris_InstRow2.z)))");
    }

    @Test
    void entityIdsRideTheInstanceAttribute() {
        final Map<PatchShaderType, String> result = instancedStages(VERTEX_ENTITY, FRAGMENT_ENTITY, Instancing.TEMPLATE);
        final String v = result.get(PatchShaderType.VERTEX);
        final String f = result.get(PatchShaderType.FRAGMENT);

        assertLacks(v, "uniform int entityId;");
        assertLacks(v, "uniform int blockEntityId;");
        assertLacks(v, "uniform int currentRenderedItemId;");
        assertHas(v, "layout(location = " + InstancedAttribs.LOC_ENTITY + ") in vec4 iris_InstEntity;");
        assertLacks(v, "in vec4 iris_Entity;");
        assertHas(v, "flat out ivec3 iris_entityInfo;");
        assertHas(v, "ivec3 iris_ids = ivec3(round(iris_InstEntity.xyz * 65535.0));");

        assertLacks(f, "uniform int entityId;");
        assertHas(f, "flat in ivec3 iris_entityInfo;");
    }

    @Test
    void entityColorIsAVaryingFromTheOverlaySlot() {
        final Map<PatchShaderType, String> result = instancedStages(VERTEX_OVERLAY, FRAGMENT_OVERLAY, Instancing.TEMPLATE);
        final String v = result.get(PatchShaderType.VERTEX);
        final String f = result.get(PatchShaderType.FRAGMENT);

        assertLacks(v, "uniform vec4 entityColor;");
        assertHas(v, "out vec4 entityColor;");
        assertHas(v, "entityColor = iris_InstOverlay;");
        assertHas(v, "entityColor.rgb *= float(entityColor.a != 0.0);");

        assertLacks(f, "uniform vec4 entityColor;");
        assertHas(f, "in vec4 entityColor;");
    }

    @Test
    void lightmapComesFromInstanceAttribute() {
        final String v = instancedVertex(VERTEX);
        assertLacks(v, "layout(location = 3)");
        assertHas(v, "iris_MultiTexCoord1 = vec4(iris_InstLightmap, 0.0, 1.0)");
        assertHas(v, "iris_LightmapTextureMatrix * iris_MultiTexCoord1");
    }

    @Test
    void colorComposesInstanceColor() {
        assertHas(instancedVertex(VERTEX), "iris_Color * iris_ColorModulator * iris_InstColor");
    }

    @Test
    void foldsEntityAttributeButKeepsMidTexCoord() {
        final String v = instancedVertex(VERTEX);
        assertLacks(v, "mc_Entity");
        assertHas(v, "vec4(-1.0, -1.0, 0.0, 1.0)");
        assertHas(v, "mc_midTexCoord");
        assertLacks(v, "vec4(0.5, 0.5, 0.0, 1.0)");
    }

    @Test
    void mvInverseOnlyWhenReferenced() {
        assertLacks(instancedVertex(VERTEX), "inverse(iris_ModelViewMatrix)");

        final String with = instancedVertex(VERTEX_MV_INVERSE);
        assertHas(with, "iris_ModelViewMatrixInverse = inverse(iris_ModelViewMatrix)");
        assertLacks(with, "uniform mat4 iris_ModelViewMatrixInverse;");
    }

    @Test
    void assignmentBlockRunsFirstInMain() {
        final String v = instancedVertex(VERTEX);
        final int mainIdx = indexOfSquashed(v, "void main()");
        final int assignIdx = indexOfSquashed(v, "iris_ModelViewMatrix = mat4(");
        final int bodyUseIdx = indexOfSquashed(v, "iris_TextureMatrix * iris_MultiTexCoord0");
        assertTrue(mainIdx >= 0 && assignIdx > mainIdx);
        assertTrue(bodyUseIdx > assignIdx);
    }

    @Test
    void cubeVariantDeclaresTheCubeNetInsteadOfAUv() {
        final String v = cubeVertex(VERTEX);
        assertHas(v, "layout(location = 2) in vec4 iris_CubeMid");
        assertHas(v, "layout(location = 3) in vec4 iris_CubeDelta");
        assertHas(v, "layout(location = " + CubeInstancedAttribs.LOC_CUBE_TEX + ") in vec4 iris_CubeTex");
        assertHas(v, "layout(location = " + CubeInstancedAttribs.LOC_LIGHTMAP_SCALE + ") in vec4 iris_InstLightmapScale");
        assertHas(v, "layout(location = " + InstancedAttribs.LOC_ENTITY + ") in vec4 iris_InstEntity;");
        assertLacks(v, "in vec4 iris_Entity;");
        assertLacks(v, "layout(location = 2) in vec4 iris_MultiTexCoord0");
        assertHas(v, "vec4 iris_MultiTexCoord0;");
        assertHas(v, "iris_MultiTexCoord1 = vec4(iris_InstLightmapScale.xy, 0.0, 1.0)");
        assertLacks(v, "in vec4 mc_midTexCoord");

        final int uvAssign = indexOfSquashed(v, "iris_MultiTexCoord0 = ");
        final int uvUse = indexOfSquashed(v, "iris_TextureMatrix * iris_MultiTexCoord0");
        assertTrue(uvAssign >= 0 && uvUse > uvAssign, "the synthesized uv must be assigned before it is read");

        final int midAssign = indexOfSquashed(v, "mc_midTexCoord = (");
        final int midUse = indexOfSquashed(v, "mc_midTexCoord.st");
        assertTrue(midAssign >= 0 && midUse > midAssign, "the synthesized mid uv must be assigned before it is read");

        attributeLocations(v);
    }

    @Test
    void cubeVariantFeedsTangentFromTheCube() {
        final String v = cubeVertex(VERTEX_TANGENT);
        assertHas(v, "layout(location = 13) in vec4 iris_CubeTangent");
        assertLacks(v, "in vec4 at_tangent");
        assertLacks(v, "attribute vec4 at_tangent");
        assertHas(v, "vec4 at_tangent;");
        final int assign = indexOfSquashed(v, "at_tangent = (iris_CubeTangent * (1.0 - 2.0 * iris_cubeMirror));");
        final int use = indexOfSquashed(v, "at_tangent.xyz * at_tangent.w");
        assertTrue(assign >= 0, "the cube variant must synthesize at_tangent");
        assertTrue(use > assign, "the synthesized tangent must be assigned before it is read");

        final String v3 = cubeVertex(VERTEX_TANGENT_VEC3);
        assertHas(v3, "vec3 at_tangent;");
        assertHas(v3, "at_tangent = (iris_CubeTangent * (1.0 - 2.0 * iris_cubeMirror)).xyz;");
    }

    @Test
    void modesProduceDistinctCacheKeys() {
        final InputAvailability avail = TEX_LM;
        final AttributeParameters cube = new AttributeParameters(Patch.ATTRIBUTES, false, avail, Instancing.CUBE);
        final AttributeParameters cubeAgain = new AttributeParameters(Patch.ATTRIBUTES, false, avail, Instancing.CUBE);
        final AttributeParameters template = new AttributeParameters(Patch.ATTRIBUTES, false, avail, Instancing.TEMPLATE);
        assertEquals(cube, cubeAgain);
        assertFalse(cube.equals(template));
        assertFalse(new AttributeParameters(Patch.ATTRIBUTES, false, avail, Instancing.NONE).equals(new AttributeParameters(Patch.ATTRIBUTES, false, avail, Instancing.CUBE)));
        assertFalse(cube.hashCode() == template.hashCode());
    }

    @Test
    void noneLayoutKeepsThePerVertexAttributes() {
        final Map<PatchShaderType, String> result = instancedStages(VERTEX_ENTITY_AND_COLOR, FRAGMENT_ENTITY_AND_COLOR, Instancing.NONE);
        final String v = result.get(PatchShaderType.VERTEX);
        final String f = result.get(PatchShaderType.FRAGMENT);

        assertLacks(v, "iris_InstRow0");
        assertLacks(v, "iris_InstColor");
        assertLacks(v, "iris_InstOverlay");
        assertLacks(v, "iris_InstEntity");
        assertHas(v, "layout(location = " + ImmediateExtendedAttribHandler.LOC_ENTITY_IDS + ") in vec4 iris_Entity;");
        assertHas(v, "uniform vec4 entityColor;");
        assertHas(f, "uniform vec4 entityColor;");
    }

    @Test
    void standardPathUnchanged() {
        final Map<PatchShaderType, String> result = TransformPatcher.patchAttributes(VERTEX, null, null, null, FRAGMENT, TEX_LM);
        assertNotNull(result);
        final String v = result.get(PatchShaderType.VERTEX);
        assertHas(v, "uniform mat4 iris_ModelViewMatrix;");
        assertHas(v, "layout(location = 3) in vec4 iris_MultiTexCoord1");
        assertHas(v, "mc_Entity");
        assertLacks(v, "iris_InstRow0");

        final Map<PatchShaderType, String> entityResult = TransformPatcher.patchAttributes(VERTEX_ENTITY, null, null, null, FRAGMENT_ENTITY, TEX_LM);
        assertNotNull(entityResult);
        assertHas(entityResult.get(PatchShaderType.VERTEX), "layout(location = " + ImmediateExtendedAttribHandler.LOC_ENTITY_IDS + ") in vec4 iris_Entity;");
    }

    @Test
    void instancedOutputDiffersFromThePerVertexOne() {
        final String plain = TransformPatcher.patchAttributes(VERTEX, null, null, null, FRAGMENT, TEX_LM).get(PatchShaderType.VERTEX);
        final String template = instancedVertex(VERTEX);
        final String cube = cubeVertex(VERTEX);

        assertFalse(squash(plain).equals(squash(template)), "the template layout must not reuse the per-vertex transform");
        assertFalse(squash(template).equals(squash(cube)), "the cube layout must not reuse the template transform");
        assertHas(template, "layout(location = 2) in vec4 iris_MultiTexCoord0");
        assertLacks(cube, "layout(location = 2) in vec4 iris_MultiTexCoord0");
    }

    @Test
    void locationOfInstanceHeadMatchesTheGlslHelper() {
        final String v = instancedVertex(VERTEX);
        final Map<Integer, String> byLocation = attributeLocations(v);
        assertEquals(InstancedAttribs.LOC_ROW0, locationOf(byLocation, "iris_InstRow0"));
        assertEquals(InstancedAttribs.LOC_LIGHTMAP, locationOf(byLocation, "iris_InstLightmap"));
        assertEquals(InstancedAttribs.LOC_ENTITY, locationOf(byLocation, "iris_InstEntity"));
    }

    @Test
    void lightmapOnlyAvailabilityStillBuildsAnInstancedVariant() {
        final Map<PatchShaderType, String> result = TransformPatcher.patchAttributesInstanced(
            VERTEX, null, null, null, FRAGMENT, InputAvailability.of(false, true), Instancing.TEMPLATE);
        assertNotNull(result, "the lightmap-only availability is queried by untextured batched draws");

        final String v = result.get(PatchShaderType.VERTEX);
        assertHas(v, "iris_MultiTexCoord1 = vec4(iris_InstLightmap, 0.0, 1.0)");
        assertHas(v, "layout(location = " + InstancedAttribs.LOC_LIGHTMAP + ") in vec2 iris_InstLightmap");
        assertHas(v, "iris_TextureMatrix * vec4(240.0, 240.0, 0.0, 1.0)");
        assertLacks(v, "iris_TextureMatrix * iris_MultiTexCoord0");

        final Map<PatchShaderType, String> textured = TransformPatcher.patchAttributesInstanced(
            VERTEX, null, null, null, FRAGMENT, TEX_LM, Instancing.TEMPLATE);
        assertFalse(squash(v).equals(squash(textured.get(PatchShaderType.VERTEX))),
            "the lightmap-only key must not reuse the textured transform");
    }

    private static final String TES_VERTEX = """
        #version 150
        uniform int entityId;
        uniform vec4 entityColor;
        varying vec4 tint;
        void main() {
            tint = entityColor + vec4(float(entityId));
            gl_Position = ftransform();
        }
        """;

    private static final String TES_CONTROL = """
        #version 400
        layout(vertices = 3) out;
        in vec4 tint[];
        out vec4 tintTCS[];
        void main() {
            gl_TessLevelOuter[0] = 1.0;
            gl_TessLevelOuter[1] = 1.0;
            gl_TessLevelOuter[2] = 1.0;
            gl_TessLevelInner[0] = 1.0;
            tintTCS[gl_InvocationID] = tint[gl_InvocationID];
            gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
        }
        """;

    private static final String TES_EVAL = """
        #version 400
        layout(triangles) in;
        in vec4 tintTCS[];
        out vec4 tintTES;
        void main() {
            gl_Position = gl_in[0].gl_Position;
            tintTES = tintTCS[0];
        }
        """;

    private static final String TES_FRAGMENT = """
        #version 400
        uniform int entityId;
        uniform vec4 entityColor;
        in vec4 tintTES;
        void main() {
            gl_FragData[0] = tintTES + entityColor + vec4(float(entityId));
        }
        """;

    @Test
    void tessellationStagesCarryTheEntityVaryingsWithoutAGeometryStage() {
        final Map<PatchShaderType, String> result = TransformPatcher.patchAttributesInstanced(
            TES_VERTEX, null, TES_CONTROL, TES_EVAL, TES_FRAGMENT, TEX_LM, Instancing.TEMPLATE);
        assertNotNull(result);
        for (PatchShaderType type : new PatchShaderType[] {
                PatchShaderType.VERTEX, PatchShaderType.TESS_CONTROL, PatchShaderType.TESS_EVAL, PatchShaderType.FRAGMENT}) {
            assertNotNull(result.get(type));
            ShaderParser.parseShader(result.get(type));
        }

        final String tcs = result.get(PatchShaderType.TESS_CONTROL);
        assertNotNull(tcs);
        assertHas(tcs, "patch out vec4 entityColorTCS;");
        assertHas(tcs, "entityColorTCS = entityColor[gl_InvocationID];");
        assertHas(tcs, "flat out ivec3 iris_entityInfoTCS[];");

        final String tes = result.get(PatchShaderType.TESS_EVAL);
        assertNotNull(tes);
        assertHas(tes, "out vec4 entityColorTES;");
        assertHas(tes, "patch in vec4 entityColorTCS;");
        assertHas(tes, "entityColorTES = entityColorTCS;");

        final String fragment = result.get(PatchShaderType.FRAGMENT);
        assertHas(fragment, "in vec4 entityColorTES;");
        assertLacks(fragment, "in vec4 entityColor;");
        assertHas(fragment, "flat in ivec3 iris_entityInfoTES;");
        assertLacks(fragment, "flat in ivec3 iris_entityInfoGS;");
    }
}
