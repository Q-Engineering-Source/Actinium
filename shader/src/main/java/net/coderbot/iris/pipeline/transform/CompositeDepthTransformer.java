package net.coderbot.iris.pipeline.transform;

import net.coderbot.iris.gl.shader.ShaderType;
import net.coderbot.iris.pipeline.transform.parameter.Parameters;
import org.taumc.glsl.Transformer;
import org.taumc.glsl.grammar.GLSLLexer;

class CompositeDepthTransformer {

	public static void transform(Transformer transformer, Parameters parameters, int glslVersion) {
		CommonTransformer.transform(transformer, parameters, true, glslVersion);

		CoreTransformHelper.injectMatrixUniforms(transformer);

		if (parameters.type == ShaderType.VERTEX) {
			CoreTransformHelper.injectCompositeVertexAttributes(transformer);
		}

		final int type = transformer.findType("centerDepthSmooth");
		// Only redirect when the pack relies on the engine-provided uniform. Packs like
		// Derivative declare their own `flat out/in float centerDepthSmooth` varying and
		// compute it themselves; rewriting those references would turn assignments into
		// `texture(...).r = ...`, which the driver rejects as a non-lvalue assignment.
		if (type != 0 && transformer.findQualifiers(GLSLLexer.UNIFORM).containsKey("centerDepthSmooth")) {
			transformer.injectVariable("uniform sampler2D iris_centerDepthSmooth;");
			transformer.replaceExpression("centerDepthSmooth", "texture(iris_centerDepthSmooth, vec2(0.5)).r");
		}
	}
}
