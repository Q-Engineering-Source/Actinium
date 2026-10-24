package net.coderbot.iris.pipeline.transform;

import net.coderbot.iris.gl.shader.ShaderType;
import net.coderbot.iris.pipeline.transform.parameter.Parameters;
import org.antlr.v4.runtime.tree.ParseTree;
import org.taumc.glsl.ShaderPrinter;
import org.taumc.glsl.Transformer;
import org.taumc.glsl.grammar.GLSLLexer;
import org.taumc.glsl.grammar.GLSLParser;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CompatibilityTransformer {

    private static final ShaderType[] pipeline = {ShaderType.VERTEX, ShaderType.GEOMETRY, ShaderType.FRAGMENT};

    private static final Pattern CLOUD_RGB_SKYHOLE = Pattern.compile(
        "VolumetricClouds\\.rgb\\s*\\*=\\s*1\\.0\\s*-\\s*skyhole\\s*;"
    );
    private static final Pattern CLOUD_ALPHA_SKYHOLE = Pattern.compile(
        "VolumetricClouds\\.a\\s*=\\s*mix\\s*\\(\\s*VolumetricClouds\\.a\\s*,\\s*1\\.0\\s*,\\s*skyhole\\s*\\)\\s*;"
    );
    private static final Pattern SKY_COLOR_SKYHOLE = Pattern.compile(
        "isSky\\s*\\?\\s*skyhole\\s*\\*\\s*caveDetection\\s*\\*\\s*caveFactor\\s*:\\s*0\\.0"
    );
    private static final Pattern VLC_REFERENCE_DISTANCE = Pattern.compile(
        "float\\s+lViewPosM\\s*=\\s*length\\s*\\(\\s*(viewPos|FragPosition)\\s*\\)\\s*<\\s*maxdist"
            + "\\s*\\?\\s*length\\s*\\(\\s*(?:viewPos|FragPosition)\\s*\\)\\s*-\\s*1\\.0\\s*:\\s*100000000\\.0\\s*;"
    );
    private static final Pattern DEPTHTEX0_DECLARATION = Pattern.compile(
        "uniform\\s+sampler2D\\s+depthtex0"
    );
    private static final Pattern DEPTHTEX1_DECLARATION = Pattern.compile(
        "uniform\\s+sampler2D\\s+depthtex1"
    );
    private static final Pattern DEPTHTEX1_USAGE = Pattern.compile(
        "texelFetch(?:2D)?\\s*\\(\\s*depthtex1"
    );
    private static final Pattern DH_DEPTHTEX_DECLARATION = Pattern.compile(
        "uniform\\s+sampler2D\\s+dhDepthTex"
    );
    private static final Pattern DH_DEPTHTEX_USAGE = Pattern.compile(
        "texelFetch(?:2D)?\\s*\\(\\s*(?:dhVoxyDepthTex|dhDepthTex)"
    );
    private static final Pattern DH_DEPTHTEX1_DECLARATION = Pattern.compile(
        "uniform\\s+sampler2D\\s+dhDepthTex1"
    );
    private static final Pattern DH_DEPTHTEX1_USAGE = Pattern.compile(
        "texelFetch(?:2D)?\\s*\\(\\s*(?:dhVoxyDepthTex1|dhDepthTex1)"
    );
    private static final Pattern VERSION_DIRECTIVE = Pattern.compile(
        "(?m)^\\s*#version\\s+\\d+(?:\\s+\\w+)?"
    );
    private static final Pattern CLOUD_MOVEMENT_TIME = Pattern.compile(
        "float\\s+cloud_movement\\s*=\\s*\\(\\s*(?:worldTime|worldTimeSmooth)"
            + "\\s*\\+\\s*mod\\s*\\(\\s*worldDay\\s*,\\s*100\\s*\\)\\s*\\*\\s*24000(?:\\.0)?"
            + "\\s*\\)\\s*/\\s*24(?:\\.0)?\\s*\\*\\s*Cloud_Speed\\s*;"
    );
    private static final String CLOUD_MOVEMENT_TIME_REPLACEMENT =
        "float cloud_movement = (iris_worldTimeSmooth + mod(worldDay,100)*24000.0) / 24.0 * Cloud_Speed;";

    static String patchCaveSkyholeClouds(String fragment) {
        String patched = CLOUD_RGB_SKYHOLE.matcher(fragment).replaceAll("VolumetricClouds.rgb *= 1.0;");
        patched = CLOUD_ALPHA_SKYHOLE.matcher(patched).replaceAll("VolumetricClouds.a = mix(VolumetricClouds.a, 1.0, 0.0);");
        return SKY_COLOR_SKYHOLE.matcher(patched).replaceAll("isSky ? 0.0 : 0.0");
    }

    static String patchVolumetricCloudReferenceDistance(String fragment) {
        Matcher referenceMatcher = VLC_REFERENCE_DISTANCE.matcher(fragment);
        if (!referenceMatcher.find()) {
            return fragment;
        }

        String position = referenceMatcher.group(1);
        String replacement;
        if (DEPTHTEX0_DECLARATION.matcher(fragment).find()) {
            final StringBuilder depthSamples = new StringBuilder();
            if (DEPTHTEX1_DECLARATION.matcher(fragment).find() && DEPTHTEX1_USAGE.matcher(fragment).find()) {
                depthSamples.append("_irisCloudDepth = min(_irisCloudDepth, texelFetch(depthtex1, _irisCloudTexel, 0).x);\n");
            }
            if (DH_DEPTHTEX_DECLARATION.matcher(fragment).find() && DH_DEPTHTEX_USAGE.matcher(fragment).find()) {
                depthSamples.append("_irisCloudDepth = min(_irisCloudDepth, texelFetch(dhDepthTex, _irisCloudTexel, 0).x);\n");
            }
            if (DH_DEPTHTEX1_DECLARATION.matcher(fragment).find() && DH_DEPTHTEX1_USAGE.matcher(fragment).find()) {
                depthSamples.append("_irisCloudDepth = min(_irisCloudDepth, texelFetch(dhDepthTex1, _irisCloudTexel, 0).x);\n");
            }
            String depthReplacement = """
                float lViewPosM = length(%s) < maxdist ? length(%s) - 1.0 : 100000000.0;
                ivec2 _irisCloudTexel = ivec2(floor(gl_FragCoord.xy) * 2.0 + 0.5);
                float _irisCloudDepth = texelFetch(depthtex0, _irisCloudTexel, 0).x;
                """ + depthSamples + """
                if (_irisCloudDepth < 1.0 - 1e-5) {
                    lViewPosM = length(%s) - 1.0;
                } else if (length(%s) >= far - 1.0) {
                    lViewPosM = 100000000.0;
                }
                """;
            replacement = depthReplacement.formatted(position, position, position, position);
        } else {
            replacement = """
                float lViewPosM = length(%s) >= far - 1.0 ? 100000000.0
                    : length(%s) < maxdist ? length(%s) - 1.0 : 100000000.0;
                """.formatted(position, position, position);
        }
        return referenceMatcher.replaceAll(matchResult -> replacement);
    }

    static String patchCloudMovementTime(String fragment) {
        Matcher cloudMatcher = CLOUD_MOVEMENT_TIME.matcher(fragment);
        if (!cloudMatcher.find()) {
            return fragment;
        }

        String patched = cloudMatcher.replaceAll(CLOUD_MOVEMENT_TIME_REPLACEMENT);
        if (patched.contains("uniform float iris_worldTimeSmooth;")) {
            return patched;
        }

        Matcher versionMatcher = VERSION_DIRECTIVE.matcher(patched);
        if (versionMatcher.find()) {
            int versionEnd = versionMatcher.end();
            return patched.substring(0, versionEnd)
                + "\nuniform float iris_worldTimeSmooth;"
                + patched.substring(versionEnd);
        }

        return "uniform float iris_worldTimeSmooth;\n" + patched;
    }

    public static void transformEach(Transformer transformer, Parameters parameters) {
        if (parameters.type == ShaderType.VERTEX) {
            // TODO: sildur's jankness
            // This is a hacky patch for sildur's shaders that changes the way it does it's waving water to make it work better?
            // Why is this the GLSL transformation code in Iris, tell sildur to fix it and remove this?
            // it's still there in current, modern Iris
            // See https://github.com/IrisShaders/Iris/issues/509
            transformer.replaceExpression("fract(worldpos.y + 0.001)", "fract(worldpos.y + 0.01)");
        }

        /**
         * Removes const storage qualifier from declarations in functions if they are
         * initialized with const parameters. Const parameters are immutable parameters
         * and can't be used to initialize const declarations because they expect
         * constant, not just immutable, expressions. This varies between drivers and
         * versions. Also removes the const qualifier from declarations that use the
         * identifiers from which the declaration was removed previously.
         * See https://wiki.shaderlabs.org/wiki/Compiler_Behavior_Notes
         */
        transformer.removeUnusedFunctions();
        transformer.removeConstAssignment();

        // TODO: glsl-transformation-lib doesn't have a way to identify empty declarations
        // this transformation is not done in current versions of Iris, so not sure it's necessary
    }

	// does transformations that require cross-shader type data
    public static void transformGrouped(Map<PatchShaderType, Transformer> trees, Parameters parameters) {
		/**
		 * find attributes that are declared as "in" in geometry or fragment but not
		 * declared as "out" in the previous stage. The missing "out" declarations for
		 * these attributes are added and initialized.
		 *
		 * It doesn't bother with array specifiers because they are only legal in
		 * geometry shaders, but then also only as an in declaration. The out
		 * declaration in the vertex shader is still just a single value. Missing out
		 * declarations in the geometry shader are also just normal.
		 *
		 * TODO:
		 * - fix issues where Iris' own declarations are detected and patched like
		 * iris_FogFragCoord if there are geometry shaders present
		 * - improved geometry shader support? They use funky declarations
		 */
        ShaderType prevType = null;
        for (ShaderType type : pipeline) {
            PatchShaderType[] patchTypes = PatchShaderType.fromGlShaderType(type);

            // check if the patch types have sources and continue if not
            boolean hasAny = false;
            for (PatchShaderType currentType : patchTypes) {
                if (trees.get(currentType) != null) {
                    hasAny = true;
                }
            }
            if (!hasAny) {
                continue;
            }

            // if the current type has sources but the previous one doesn't, set the
            // previous one and continue
            if (prevType == null) {
                prevType = type;
                continue;
            }

            PatchShaderType prevPatchTypes = PatchShaderType.fromGlShaderType(prevType)[0];
            Transformer prevTransformer = trees.get(prevPatchTypes);

            // find out declarations
            Map<String, GLSLParser.Single_declarationContext> outDec = prevTransformer.findQualifiers(GLSLLexer.OUT);
            for (PatchShaderType currentType : patchTypes) {
                Transformer currentTransformer = trees.get(currentType);

                if (currentTransformer == null) {
                    continue;
                }

                Map<String, GLSLParser.Single_declarationContext> inDec = currentTransformer.findQualifiers(GLSLLexer.IN);
                for (String in : inDec.keySet()) {
                    if (in.startsWith("gl_")) {
                        continue;
                    }

                    if (!outDec.containsKey(in)) {
                        if (!currentTransformer.containsCall(in)) {
                            continue;
                        }

                        String outDeclaration = ShaderPrinter.getFormattedShader(inDec.get(in).fully_specified_type())
                            + " " + in + ";";
                        prevTransformer.injectVariable(outDeclaration.replaceFirst("\\bin\\b", "out"));

                        if (!prevTransformer.hasAssigment(in)) {
                            prevTransformer.initialize(inDec.get(in), in);
                        }
                    } else {
                        ParseTree outType = outDec.get(in).fully_specified_type().type_specifier().type_specifier_nonarray().children.get(0);
                        ParseTree inType = inDec.get(in).fully_specified_type().type_specifier().type_specifier_nonarray().children.get(0);

                        if (outDec.get(in).fully_specified_type().type_specifier().array_specifier() != null) {
                            continue;
                        }

                        if (inType.getText().equals(outType.getText())) {
                            if (!prevTransformer.hasAssigment(in)) {
                                prevTransformer.initialize(inDec.get(in), in);
                            }
                        }
                    }
                }
            }
            prevType = type;
        }
    }
}
