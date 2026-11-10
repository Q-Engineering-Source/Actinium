package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import net.coderbot.iris.gl.shader.ShaderType;
import net.coderbot.iris.pipeline.transform.parameter.AttributeParameters;
import org.taumc.glsl.Transformer;

import java.util.HashMap;
import java.util.Map;

/**
 * Implements AttributeShaderTransformer using glsl-transformer AST
 * transformation methods.
 */
class AttributeTransformer {
	public static void transform(Transformer transformer, AttributeParameters parameters, int version) {
		// Always core profile — minimum GLSL version is 330 (see ShaderTransformer.getStageMinimumVersion)
		CommonTransformer.transform(transformer, parameters, true, version);

		// Entity ID and overlay color patching (uniform path)
		EntityPatcher.patchEntityId(transformer, parameters);
		EntityPatcher.patchOverlayColor(transformer, parameters);

		transformCore(transformer, parameters);
	}

	private static void transformCore(Transformer transformer, AttributeParameters parameters) {
		CoreTransformHelper.injectMatrixUniforms(transformer);

		if (parameters.instancing == Instancing.WEATHER) {
			if (parameters.type == ShaderType.VERTEX) {
				transformWeatherVertex(transformer, parameters);
			}
			return;
		}

		if (parameters.type == ShaderType.VERTEX) {
			transformer.injectVariable("layout(location = 0) in vec4 iris_Vertex;");
			transformer.injectVariable("layout(location = 2) in vec4 iris_MultiTexCoord0;");
			transformer.injectVariable("layout(location = 3) in vec4 iris_MultiTexCoord1;");
			transformer.injectVariable("layout(location = 4) in vec3 iris_Normal;");
			if (parameters.inputs.color) {
				transformer.injectVariable("layout(location = 1) in vec4 iris_Color;");
				transformer.rename("gl_Color", "iris_Color");
			} else {
				transformer.injectVariable("uniform vec4 iris_ColorModulator;");
				transformer.rename("gl_Color", "iris_ColorModulator");
			}

			transformer.rename("gl_Vertex", "iris_Vertex");
			transformer.rename("gl_Normal", "iris_Normal");

			// ftransform() = gl_ModelViewProjectionMatrix * gl_Vertex
			transformer.renameFunctionCall("ftransform", "iris_ftransform");
			transformer.injectFunction("vec4 iris_ftransform() { return (iris_ProjectionMatrix * iris_ModelViewMatrix) * iris_Vertex; }");

			// gl_MultiTexCoord1 and gl_MultiTexCoord2 are both lightmap
			if (parameters.inputs.lightmap) {
				transformer.rename("gl_MultiTexCoord2", "gl_MultiTexCoord1");
			}

			Map<String, String> texCoordReplacements = new HashMap<>();
			if (!parameters.inputs.lightmap) {
				texCoordReplacements.put("gl_MultiTexCoord1", "vec4(240.0, 240.0, 0.0, 1.0)");
				texCoordReplacements.put("gl_MultiTexCoord2", "vec4(240.0, 240.0, 0.0, 1.0)");
			}
			if (!parameters.inputs.texture) {
				texCoordReplacements.put("gl_MultiTexCoord0", "vec4(240.0, 240.0, 0.0, 1.0)");
			}
			texCoordReplacements.forEach(transformer::replaceExpression);

			// Rename remaining gl_MultiTexCoord references to iris_ versions
			transformer.rename("gl_MultiTexCoord0", "iris_MultiTexCoord0");
			transformer.rename("gl_MultiTexCoord1", "iris_MultiTexCoord1");

			if (transformer.hasVariable("gl_MultiTexCoord3") && !transformer.hasVariable("mc_midTexCoord")) {
				transformer.rename("gl_MultiTexCoord3", "mc_midTexCoord");
				transformer.injectVariable("in vec4 mc_midTexCoord;");
			}
		}
	}

	/**
	 * Vertex stage of the weather instanced variant.
	 *
	 * <p>The quad corner and the instance record are the only inputs; everything the shader reads as
	 * {@code gl_Vertex}, {@code gl_Color} or {@code gl_MultiTexCoord0/1} is computed by the prologue
	 * into plain variables, so those names must not be declared as attributes.</p>
	 */
	private static void transformWeatherVertex(Transformer transformer, AttributeParameters parameters) {
		CoreTransformHelper.injectWeatherInstancing(transformer, parameters.instancing);

		transformer.rename("gl_Vertex", "iris_Vertex");
		transformer.rename("gl_Normal", "iris_Normal");
		transformer.rename("gl_Color", "iris_Color");
		transformer.rename("gl_MultiTexCoord0", "iris_MultiTexCoord0");
		transformer.rename("gl_MultiTexCoord1", "iris_MultiTexCoord1");
		transformer.rename("gl_MultiTexCoord2", "iris_MultiTexCoord1");

		transformer.renameFunctionCall("ftransform", "iris_ftransform");
		transformer.injectFunction("vec4 iris_ftransform() { return (iris_ProjectionMatrix * iris_ModelViewMatrix) * iris_Vertex; }");

		if (transformer.hasVariable("gl_MultiTexCoord3") && !transformer.hasVariable("mc_midTexCoord")) {
			transformer.rename("gl_MultiTexCoord3", "mc_midTexCoord");
			transformer.injectVariable("in vec4 mc_midTexCoord;");
		}

		CoreTransformHelper.injectWeatherPrologue(transformer);
	}

}
