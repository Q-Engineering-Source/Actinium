package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement;

/**
 * Generates GLSL 330 core vertex shaders for FFP emulation.
 * Follows Mesa's ffvertex_prog.c build order.
 *
 * Attribute locations are sourced from {@link VertexFormatElement.Usage} to stay in sync with GTNHLib's vertex format setup.
 */
public final class VertexShaderGenerator {

    private VertexShaderGenerator() {}

    public static String generate(VertexKey key) {
        final StringBuilder sb = new StringBuilder(2048);
        sb.append("#version 330 core\n\n");

        emitAttributes(sb, key);
        emitUniforms(sb, key);
        emitOutputs(sb, key);

        sb.append("void main() {\n");
        if (key.instancing() == Instancing.WEATHER) {
            sb.append("  ").append(InstancedGlslHelpers.weatherPrologue("u_", "a_", "a_TexCoord0",
                "vec4 weatherVertex", "vec4 weatherColor", "vec4 weatherTex0", "vec4 weatherTex1",
                "\n  ")).append('\n');
        }
        emitPositionTransform(sb, key);
        if (key.instancing() == Instancing.WEATHER) {
            // The weather quad carries its own colour and alpha; the lighting state of the pass it is
            // drawn in must not replace them, so the variant never runs the lighting branch.
            emitColorPassthrough(sb, key);
        } else if (key.lightingEnabled()) {
            emitNormalTransform(sb, key);
            emitLighting(sb, key);
        } else {
            emitColorPassthrough(sb, key);
        }
        emitTexCoordPassthrough(sb, key);
        if (key.lineStipple()) {
            emitLineStart(sb, key.lineQuads() ? "lineCenter" : "gl_Position");
        }
        if (key.fogEnabled()) {
            emitFogDistance(sb, key);
        }
        if (key.clipPlanesEnabled()) {
            emitClipDistances(sb, key);
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static void emitAttributes(StringBuilder sb, VertexKey key) {
        sb.append("// Vertex attributes\n");
        sb.append("layout(location = ").append(VertexFormatElement.Usage.POSITION.getAttributeLocation()).append(") in vec3 a_Position;\n");
        if (key.hasVertexColor()) {
            sb.append("layout(location = ").append(VertexFormatElement.Usage.COLOR.getAttributeLocation()).append(") in vec4 a_Color;\n");
        }
        // Per-unit texture coordinates. Every UV set is a vec4 so a 2-float UV attribute still
        // yields q == 1.0 (OpenGL fills the missing z/w with 0/1); the fragment stage divides
        // coord.st / q, so a wrong q would be fatal. Units 2/3 (issue #175) get their own attribute
        // slot so they are no longer a per-draw constant.
        if (key.hasVertexTexCoord()) {
            sb.append("layout(location = ").append(VertexFormatElement.Usage.PRIMARY_UV.getAttributeLocation()).append(") in vec4 a_TexCoord0;\n");
        }
        if (key.hasVertexLightmap()) {
            sb.append("layout(location = ").append(VertexFormatElement.Usage.SECONDARY_UV.getAttributeLocation(1)).append(") in vec4 a_TexCoord1;\n");
        }
        for (int i = 2; i < VertexKey.MAX_UNITS; i++) {
            if (key.hasUnitVertexTexCoord(i)) {
                sb.append("layout(location = ").append(VertexFormatElement.Usage.SECONDARY_UV.getAttributeLocation(i)).append(") in vec4 a_TexCoord").append(i).append(";\n");
            }
        }
        if (key.hasVertexNormal() || key.lineQuads()) {
            if (key.hasVertexNormal()) {
                sb.append("layout(location = ").append(VertexFormatElement.Usage.NORMAL.getAttributeLocation()).append(") in vec3 a_Normal;\n");
            } else {
                // Line quads carry the segment's other endpoint in the normal slot
                sb.append("layout(location = ").append(VertexFormatElement.Usage.NORMAL.getAttributeLocation()).append(") in vec3 a_LineOtherEnd;\n");
            }
        }
        for (String decl : InstancedGlslHelpers.attributeDecls("a_", key.instancing())) {
            sb.append(decl);
        }
        sb.append('\n');
    }

    private static void emitLineStart(StringBuilder sb, String clipPosition) {
        // Screen-space position of this line's start vertex; the fragment stage measures
        // each fragment's distance from it to step through the stipple pattern.
        sb.append("  vec2 ndc = ").append(clipPosition).append(".xy / ").append(clipPosition).append(".w;\n");
        sb.append("  v_LineStart = u_Viewport.xy + (ndc * 0.5 + 0.5) * u_Viewport.zw;\n\n");
    }

    private static void emitUniforms(StringBuilder sb, VertexKey key) {
        sb.append("// Matrices\n");
        sb.append("uniform mat4 u_ModelViewMatrix;\n");
        sb.append("uniform mat4 u_ProjectionMatrix;\n");
        sb.append("uniform mat4 u_MVPMatrix;\n");

        if (key.lightingEnabled()) {
            sb.append("uniform mat3 u_NormalMatrix;\n");
        }

        if (key.unitTexMatEnabled(0)) {
            sb.append("uniform mat4 u_TextureMatrix0;\n");
        }
        for (int i = 2; i < VertexKey.MAX_UNITS; i++) {
            if (key.unitTexCoordEnabled(i) && !key.unit23UvFromUnit0() && key.unitTexMatEnabled(i)) {
                sb.append("uniform mat4 u_TextureMatrix").append(i).append(";\n");
            }
        }

        if (key.lineStipple()) {
            sb.append("uniform vec4 u_Viewport;\n");
        }
        if (key.lineQuads()) {
            sb.append("uniform vec2 u_ViewportSize;\n");
            sb.append("uniform float u_LineWidth;\n");
        }

        // TexGen plane uniforms
        if (key.texGenModeS() == VertexKey.TG_OBJ_LINEAR) sb.append("uniform vec4 u_TexGenObjPlaneS;\n");
        if (key.texGenModeS() == VertexKey.TG_EYE_LINEAR) sb.append("uniform vec4 u_TexGenEyePlaneS;\n");
        if (key.texGenModeT() == VertexKey.TG_OBJ_LINEAR) sb.append("uniform vec4 u_TexGenObjPlaneT;\n");
        if (key.texGenModeT() == VertexKey.TG_EYE_LINEAR) sb.append("uniform vec4 u_TexGenEyePlaneT;\n");
        if (key.texGenModeR() == VertexKey.TG_OBJ_LINEAR) sb.append("uniform vec4 u_TexGenObjPlaneR;\n");
        if (key.texGenModeR() == VertexKey.TG_EYE_LINEAR) sb.append("uniform vec4 u_TexGenEyePlaneR;\n");
        if (key.texGenModeQ() == VertexKey.TG_OBJ_LINEAR) sb.append("uniform vec4 u_TexGenObjPlaneQ;\n");
        if (key.texGenModeQ() == VertexKey.TG_EYE_LINEAR) sb.append("uniform vec4 u_TexGenEyePlaneQ;\n");

        if (!key.hasVertexNormal() && key.lightingEnabled()) {
            sb.append("uniform vec3 u_CurrentNormal;\n");
        }

        if (!key.hasVertexColor()) {
            sb.append("uniform vec4 u_CurrentColor;\n");
        }

        if (!key.hasVertexTexCoord() && key.unitTexCoordEnabled(0)) {
            sb.append("uniform vec4 u_CurrentTexCoord;\n");
        }

        for (int i = 2; i < VertexKey.MAX_UNITS; i++) {
            if (key.unitTexCoordEnabled(i) && !key.unit23UvFromUnit0() && !key.hasUnitVertexTexCoord(i)) {
                sb.append("uniform vec4 u_CurrentTexCoord").append(i).append(";\n");
            }
        }

        if (key.lightmapEnabled() && !key.hasVertexLightmap()) {
            sb.append("uniform vec2 u_CurrentLightmapCoord;\n");
        }

        if (key.lightmapEnabled()) {
            sb.append("uniform mat4 u_LightmapTextureMatrix;\n");
        }

        if (key.lightingEnabled()) {
            emitLightingUniforms(sb, key);
        }

        if (key.lightingEnabled() && (key.normalizeEnabled() || key.rescaleNormalsEnabled())) {
            // normalize is done in shader; rescale uses normalScale
            if (key.rescaleNormalsEnabled() && !key.normalizeEnabled()) {
                sb.append("uniform float u_NormalScale;\n");
            }
        }

        if (key.clipPlanesEnabled()) {
            sb.append("uniform vec4 u_ClipPlane[8];\n");
        }

        if (key.instancing() == Instancing.WEATHER) {
            sb.append("\n// Weather instancing\n");
            sb.append("uniform vec4 u_WeatherParams0;\n");
            sb.append("uniform vec4 u_WeatherParams1;\n");
            sb.append("uniform vec4 u_WeatherParams2;\n");
        }

        sb.append('\n');
    }

    private static void emitLightingUniforms(StringBuilder sb, VertexKey key) {
        sb.append("\n// Lighting\n");

        if (key.colorMaterialEnabled()) {
            // When color material is active, we can't pre-compute light products so we upload raw light and material values
            sb.append("uniform vec4 u_LightModelAmbient;\n");
            sb.append("uniform vec4 u_MaterialEmission;\n");
            sb.append("uniform vec4 u_MaterialAmbient;\n");
            sb.append("uniform vec4 u_MaterialDiffuse;\n");
            sb.append("uniform vec4 u_MaterialSpecular;\n");
            sb.append("uniform float u_MaterialShininess;\n");

            if (key.lightEnabled(0)) {
                sb.append("uniform vec4 u_Light0Ambient;\n");
                sb.append("uniform vec4 u_Light0Diffuse;\n");
                sb.append("uniform vec4 u_Light0Specular;\n");
                sb.append("uniform vec4 u_Light0Position;\n");
            }
            if (key.lightEnabled(1)) {
                sb.append("uniform vec4 u_Light1Ambient;\n");
                sb.append("uniform vec4 u_Light1Diffuse;\n");
                sb.append("uniform vec4 u_Light1Specular;\n");
                sb.append("uniform vec4 u_Light1Position;\n");
            }
        } else {
            // Pre-computed: sceneColor + lightProducts
            sb.append("uniform vec4 u_SceneColor;\n");

            if (key.lightEnabled(0)) {
                sb.append("uniform vec4 u_Light0Position;\n");
                sb.append("uniform vec3 u_LightProd0Ambient;\n");
                sb.append("uniform vec3 u_LightProd0Diffuse;\n");
                sb.append("uniform vec3 u_LightProd0Specular;\n");
            }
            if (key.lightEnabled(1)) {
                sb.append("uniform vec4 u_Light1Position;\n");
                sb.append("uniform vec3 u_LightProd1Ambient;\n");
                sb.append("uniform vec3 u_LightProd1Diffuse;\n");
                sb.append("uniform vec3 u_LightProd1Specular;\n");
            }
            sb.append("uniform float u_MaterialShininess;\n");
        }
    }

    private static void emitOutputs(StringBuilder sb, VertexKey key) {
        sb.append("// Outputs\n");
        sb.append("out vec4 v_Color;\n");
        if (key.lineStipple()) {
            sb.append("flat out vec2 v_LineStart;\n");
        }
        if (key.separateSpecular()) {
            sb.append("out vec3 v_SpecularColor;\n");
        }
        // Per-unit varying outputs. Each enabled texture unit (and texgen, which targets unit 0) gets its own v_TexCoord<i>.
        if (key.unitTexCoordEnabled(0) || key.hasVertexTexCoord() || key.texGenEnabled()) {
            sb.append("out vec4 v_TexCoord0;\n");
        }
        if (key.lightmapEnabled()) {
            sb.append("out vec4 v_TexCoord1;\n");
        }
        if (key.unitTexCoordEnabled(2)) sb.append("out vec4 v_TexCoord2;\n");
        if (key.unitTexCoordEnabled(3)) sb.append("out vec4 v_TexCoord3;\n");
        if (key.fogEnabled()) {
            sb.append("out float v_FogCoord;\n");
        }
        sb.append('\n');
    }

    private static void emitPositionTransform(StringBuilder sb, VertexKey key) {
        sb.append("  // Position transform\n");
        if (key.instancing() == Instancing.WEATHER) {
            sb.append("  vec4 pos4 = weatherVertex;\n");
        } else {
            sb.append("  vec4 pos4 = vec4(a_Position, 1.0);\n");
        }
        sb.append("  gl_Position = u_MVPMatrix * pos4;\n");
        if (key.lineQuads()) {
            emitLineQuadWidening(sb);
        }

        // Eye position needed for lighting, fog, EYE_LINEAR texgen, and clip planes
        if (key.lightingEnabled() || key.fogEnabled() || texGenNeedsEyePos(key) || key.clipPlanesEnabled()) {
            sb.append("  vec4 eyePos = u_ModelViewMatrix * pos4;\n");
        }
        if (key.lineQuads() && (key.fogEnabled() || key.clipPlanesEnabled())) {
            sb.append("  vec4 lineEyeOther = u_ModelViewMatrix * vec4(a_LineOtherEnd, 1.0);\n");
        }
        sb.append('\n');
    }

    /**
     * Widens the segment in the vertex stage: the six corners of a segment are laid out as the triangle strip
     * A A B A B B, and each corner is pushed half a line width off the segment along the screen-space axis the
     * segment runs least along. Corners are shifted by a quarter pixel so a wide line covers the same pixels a
     * native one does at its first endpoint and stops short of its last.
     */
    private static void emitLineQuadWidening(StringBuilder sb) {
        sb.append("  int lineCorner = gl_VertexID % 6;\n");
        sb.append("  bool lineStart = lineCorner == 0 || lineCorner == 1 || lineCorner == 3;\n");
        sb.append("  vec4 lineOther = u_MVPMatrix * vec4(a_LineOtherEnd, 1.0);\n");
        sb.append("  vec4 lineC0 = lineStart ? gl_Position : lineOther;\n");
        sb.append("  vec4 lineC1 = lineStart ? lineOther : gl_Position;\n");
        sb.append("  vec4 lineCenter = lineC0;\n");
        sb.append("  float lineT = 0.0;\n");
        sb.append("  float lineD0 = lineC0.z + lineC0.w;\n");
        sb.append("  float lineD1 = lineC1.z + lineC1.w;\n");
        sb.append("  if (lineD0 < 0.0 && lineD1 < 0.0) {\n");
        sb.append("    gl_Position = vec4(0.0, 0.0, 2.0, 1.0);\n");
        sb.append("  } else {\n");
        // Clip the segment against the near plane before widening so both endpoints stay projectable
        sb.append("    float lineT0 = lineD0 < 0.0 ? lineD0 / (lineD0 - lineD1) : 0.0;\n");
        sb.append("    float lineT1 = lineD1 < 0.0 ? lineD0 / (lineD0 - lineD1) : 1.0;\n");
        sb.append("    lineT = lineStart ? lineT0 : lineT1;\n");
        sb.append("    vec4 lineP0 = mix(lineC0, lineC1, lineT0);\n");
        sb.append("    vec4 lineP1 = mix(lineC0, lineC1, lineT1);\n");
        sb.append("    vec2 lineN0 = lineP0.xy / lineP0.w;\n");
        sb.append("    vec2 lineN1 = lineP1.xy / lineP1.w;\n");
        sb.append("    vec2 linePixel = 2.0 / u_ViewportSize;\n");
        sb.append("    float lineHalfWidth = 0.5 * u_LineWidth;\n");
        sb.append("    vec2 lineOffset;\n");
        sb.append("    vec2 lineShift;\n");
        sb.append("    vec2 lineSpan = abs(lineN1 - lineN0) * u_ViewportSize;\n");
        sb.append("    if (lineSpan.x > lineSpan.y) {\n");
        sb.append("      lineOffset = vec2(0.0, lineHalfWidth * linePixel.y);\n");
        sb.append("      lineShift = vec2(lineN0.x < lineN1.x ? -0.5 : 0.5, -0.125) * linePixel;\n");
        sb.append("    } else {\n");
        sb.append("      lineOffset = vec2(lineHalfWidth * linePixel.x, 0.0);\n");
        sb.append("      lineShift = vec2(0.125, lineN0.y < lineN1.y ? -0.5 : 0.5) * linePixel;\n");
        sb.append("    }\n");
        sb.append("    vec4 lineP = lineStart ? lineP0 : lineP1;\n");
        sb.append("    vec2 lineN = lineStart ? lineN0 : lineN1;\n");
        sb.append("    gl_Position = vec4((lineN + (lineCorner % 2 == 0 ? lineOffset : -lineOffset) + lineShift) * lineP.w, lineP.z, lineP.w);\n");
        sb.append("  }\n");
    }

    private static void emitNormalTransform(StringBuilder sb, VertexKey key) {
        sb.append("  // Normal transform\n");
        if (key.hasVertexNormal()) {
            sb.append("  vec3 normal = u_NormalMatrix * a_Normal;\n");
        } else {
            sb.append("  vec3 normal = u_NormalMatrix * u_CurrentNormal;\n");
        }

        if (key.normalizeEnabled()) {
            sb.append("  normal = normalize(normal);\n");
        } else if (key.rescaleNormalsEnabled()) {
            sb.append("  normal *= u_NormalScale;\n");
        }
        sb.append('\n');
    }

    private static void emitLighting(StringBuilder sb, VertexKey key) {
        sb.append("  // Gouraud lighting\n");

        if (key.colorMaterialEnabled()) {
            // Color material: vertex color replaces specific material properties per glColorMaterial mode (Mesa ffvertex_prog.c get_material() bitmask approach)
            sb.append("  vec4 matColor = ");
            sb.append(key.hasVertexColor() ? "a_Color" : "u_CurrentColor");
            sb.append(";\n");

            // Emission source: vertex color for CM_EMISSION, else material uniform
            final String emSrc = key.cmReplacesEmission() ? "matColor.rgb" : "u_MaterialEmission.rgb";
            // Ambient source for scene color: vertex color for CM_AMBIENT / CM_AMBIENT_AND_DIFFUSE
            final String amSrc = key.cmReplacesAmbient() ? "matColor.rgb" : "u_MaterialAmbient.rgb";
            sb.append("  vec3 sceneColor = ").append(emSrc).append(" + ").append(amSrc).append(" * u_LightModelAmbient.rgb;\n");

            // Alpha from diffuse source (per GL spec)
            sb.append("  float outAlpha = ").append(key.cmReplacesDiffuse() ? "matColor.a" : "u_MaterialDiffuse.a").append(";\n");
        } else {
            // Pre-computed scene color
            sb.append("  vec3 sceneColor = u_SceneColor.rgb;\n");
            sb.append("  float outAlpha = u_SceneColor.a;\n");
        }

        sb.append("  vec3 color0 = sceneColor;\n");
        if (key.separateSpecular()) {
            sb.append("  vec3 specAccum = vec3(0.0);\n");
        }

        // Per-light accumulation
        for (int i = 0; i < VertexKey.FFP_LIGHT_COUNT; i++) {
            if (key.lightEnabled(i)) {
                emitLightContribution(sb, key, i);
            }
        }

        sb.append("  v_Color = vec4(clamp(color0, 0.0, 1.0), outAlpha);\n");
        if (key.separateSpecular()) {
            sb.append("  v_SpecularColor = clamp(specAccum, 0.0, 1.0);\n");
        }
        sb.append('\n');
    }

    private static void emitLightContribution(StringBuilder sb, VertexKey key, int lightIndex) {
        final String li = String.valueOf(lightIndex);
        final boolean directional = key.lightDirectional(lightIndex);
        final String posUniform = "u_Light" + li + "Position";

        sb.append("  { // Light ").append(li).append('\n');

        // Light vector
        if (directional) {
            // Directional: position is already normalized direction
            sb.append("    vec3 VPpli = normalize(").append(posUniform).append(".xyz);\n");
        } else {
            // Positional light
            sb.append("    vec3 VPpli = ").append(posUniform).append(".xyz - eyePos.xyz;\n");
            sb.append("    VPpli = normalize(VPpli);\n");
        }

        // Dot products
        sb.append("    float NdotVP = max(dot(normal, VPpli), 0.0);\n");

        // Half vector for specular (infinite viewer: H = normalize(L + (0,0,1)))
        sb.append("    vec3 halfVec = normalize(VPpli + vec3(0.0, 0.0, 1.0));\n");
        sb.append("    float NdotHV = max(dot(normal, halfVec), 0.0);\n");

        // Specular power
        sb.append("    float spec = (NdotVP > 0.0 && u_MaterialShininess > 0.0) ? pow(NdotHV, u_MaterialShininess) : 0.0;\n");

        // Accumulate — per-property source depends on color material mode
        if (key.colorMaterialEnabled()) {
            final String ambU = "u_Light" + li + "Ambient";
            final String difU = "u_Light" + li + "Diffuse";
            final String speU = "u_Light" + li + "Specular";
            // Ambient product: light.ambient * (vertex color or material)
            final String ambMat = key.cmReplacesAmbient() ? "matColor.rgb" : "u_MaterialAmbient.rgb";
            sb.append("    vec3 ambient = ").append(ambU).append(".rgb * ").append(ambMat).append(";\n");
            // Diffuse product
            final String difMat = key.cmReplacesDiffuse() ? "matColor.rgb" : "u_MaterialDiffuse.rgb";
            sb.append("    vec3 diffuse = ").append(difU).append(".rgb * ").append(difMat).append(";\n");
            // Specular product
            final String speMat = key.cmReplacesSpecular() ? "matColor.rgb" : "u_MaterialSpecular.rgb";
            sb.append("    vec3 specular = ").append(speU).append(".rgb * ").append(speMat).append(";\n");
        } else {
            sb.append("    vec3 ambient = u_LightProd").append(li).append("Ambient;\n");
            sb.append("    vec3 diffuse = u_LightProd").append(li).append("Diffuse;\n");
            sb.append("    vec3 specular = u_LightProd").append(li).append("Specular;\n");
        }

        sb.append("    color0 += ambient + NdotVP * diffuse;\n");
        if (key.separateSpecular()) {
            sb.append("    specAccum += spec * specular;\n");
        } else {
            sb.append("    color0 += spec * specular;\n");
        }

        sb.append("  }\n");
    }

    private static void emitColorPassthrough(StringBuilder sb, VertexKey key) {
        sb.append("  // Color passthrough (no lighting)\n");
        if (key.instancing() == Instancing.WEATHER) {
            if (key.separateSpecular()) {
                sb.append("  v_SpecularColor = vec3(0.0);\n");
            }
            sb.append("  v_Color = weatherColor;\n");
        } else if (key.hasVertexColor()) {
            sb.append("  v_Color = a_Color;\n");
        } else {
            sb.append("  v_Color = u_CurrentColor;\n");
        }
        sb.append('\n');
    }

    private static void emitTexCoordPassthrough(StringBuilder sb, VertexKey key) {
        if (key.instancing() == Instancing.WEATHER) {
            sb.append("  // Texture coordinates - weather\n");
            sb.append("  v_TexCoord0 = weatherTex0;\n");
            if (key.lightmapEnabled()) {
                sb.append("  v_TexCoord1 = u_LightmapTextureMatrix * weatherTex1;\n");
            }
            sb.append('\n');
            return;
        }
        if (key.texGenEnabled()) {
            emitTexGenCoordGeneration(sb, key);
        } else if (key.unitTexCoordEnabled(0) || key.hasVertexTexCoord()) {
            sb.append("  // Texture coordinates - unit 0\n");
            final String src = key.hasVertexTexCoord() ? "a_TexCoord0" : "u_CurrentTexCoord";
            if (key.unitTexMatEnabled(0)) {
                sb.append("  v_TexCoord0 = u_TextureMatrix0 * ").append(src).append(";\n");
            } else {
                sb.append("  v_TexCoord0 = ").append(src).append(";\n");
            }
        }
        if (key.lightmapEnabled()) {
            if (key.hasVertexLightmap()) {
                sb.append("  v_TexCoord1 = u_LightmapTextureMatrix * a_TexCoord1;\n");
            } else {
                sb.append("  v_TexCoord1 = u_LightmapTextureMatrix * vec4(u_CurrentLightmapCoord, 0.0, 1.0);\n");
            }
        }
        for (int i = 2; i < VertexKey.MAX_UNITS; i++) {
            if (!key.unitTexCoordEnabled(i)) continue;
            sb.append("  // Texture coordinates - unit ").append(i).append('\n');
            // Source priority: immediate-mode glMultiTexCoord folded into unit 0's attribute, then
            // the unit's own per-vertex attribute, then the per-draw constant fallback (issue #175).
            final String src;
            if (key.unit23UvFromUnit0()) {
                src = "a_TexCoord0";
            } else if (key.hasUnitVertexTexCoord(i)) {
                src = "a_TexCoord" + i;
            } else {
                src = "u_CurrentTexCoord" + i;
            }
            if (!key.unit23UvFromUnit0() && key.unitTexMatEnabled(i)) {
                sb.append("  v_TexCoord").append(i).append(" = u_TextureMatrix").append(i).append(" * ").append(src).append(";\n");
            } else {
                sb.append("  v_TexCoord").append(i).append(" = ").append(src).append(";\n");
            }
        }
    }

    /**
     * Emit per-coordinate texgen code following Mesa's ffvertex_prog.c build order.
     * OBJ_LINEAR: dot(pos4, objPlane), EYE_LINEAR: dot(eyePos, eyePlane).
     * Coordinates without texgen use vertex attrib or default (0,0,0,1).
     */
    private static void emitTexGenCoordGeneration(StringBuilder sb, VertexKey key) {
        sb.append("  // TexGen coordinate generation\n");
        // Single-expression constructor: per-component writes to a pre-initialized vec4 let
        // some drivers (NVIDIA) dead-code-eliminate the uniform feeding a lone component
        // (observed: u_TexGenEyePlaneS dropped while T/R survive, breaking end-portal texgen).
        sb.append("  vec4 texGenCoord = vec4(")
            .append(texGenComponentExpr(key.texGenModeS(), "S")).append(", ")
            .append(texGenComponentExpr(key.texGenModeT(), "T")).append(", ")
            .append(texGenComponentExpr(key.texGenModeR(), "R")).append(", ")
            .append(texGenComponentExpr(key.texGenModeQ(), "Q")).append(");\n");

        // Always apply texture matrix when texgen is active (forced on in VertexKey)
        sb.append("  v_TexCoord0 = u_TextureMatrix0 * texGenCoord;\n");
    }

    private static String texGenComponentExpr(int mode, String coordName) {
        return switch (mode) {
            case VertexKey.TG_OBJ_LINEAR -> "dot(pos4, u_TexGenObjPlane" + coordName + ")";
            case VertexKey.TG_EYE_LINEAR -> "dot(eyePos, u_TexGenEyePlane" + coordName + ")";
            // TG_NONE: default (0 for s/t/r, 1 for q) — matches the old texGenCoord init
            default -> "Q".equals(coordName) ? "1.0" : "0.0";
        };
    }

    private static boolean texGenNeedsEyePos(VertexKey key) {
        return key.texGenModeS() == VertexKey.TG_EYE_LINEAR
            || key.texGenModeT() == VertexKey.TG_EYE_LINEAR
            || key.texGenModeR() == VertexKey.TG_EYE_LINEAR
            || key.texGenModeQ() == VertexKey.TG_EYE_LINEAR;
    }

    private static void emitFogDistance(StringBuilder sb, VertexKey key) {
        sb.append("  // Fog distance\n");
        final String own = fogDistance(key, "eyePos");
        // A widened segment spans two endpoints; interpolate the fog distance along the clipped segment so the
        // two ends of a long line do not share one endpoint's fog.
        final String fog = key.lineQuads() ? acrossLine(own, fogDistance(key, "lineEyeOther")) : own;
        sb.append("  v_FogCoord = ").append(fog).append(";\n");
    }

    // Mesa ffvertex_prog.c build_fog(): FDM_EYE_RADIAL/EYE_PLANE/EYE_PLANE_ABS
    private static String fogDistance(VertexKey key, String eye) {
        return switch (key.fogDistanceMode()) {
            case 0 -> "length(" + eye + ".xyz)"; // FDM_EYE_RADIAL: Euclidean distance
            case 1 -> eye + ".z";                // FDM_EYE_PLANE: raw Z (can be negative, clamped by fog factor)
            default -> "abs(" + eye + ".z)";     // FDM_EYE_PLANE_ABS: absolute Z
        };
    }

    private static void emitClipDistances(StringBuilder sb, VertexKey key) {
        sb.append("  // Clip distances\n");
        for (int i = 0; i < 8; i++) {
            final String own = "dot(u_ClipPlane[" + i + "], eyePos)";
            final String distance = key.lineQuads() ? acrossLine(own, "dot(u_ClipPlane[" + i + "], lineEyeOther)") : own;
            sb.append("  gl_ClipDistance[").append(i).append("] = ").append(distance).append(";\n");
        }
    }

    /**
     * Picks the endpoint value that matches this corner: corners owned by A take A's value, the rest take B's,
     * and {@code lineT} moves the boundary to where the segment was clipped against the near plane.
     */
    private static String acrossLine(String own, String other) {
        return "mix(lineStart ? " + own + " : " + other + ", lineStart ? " + other + " : " + own + ", lineT)";
    }
}
