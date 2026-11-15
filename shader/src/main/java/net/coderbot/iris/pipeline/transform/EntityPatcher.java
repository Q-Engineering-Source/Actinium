package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.hooks.ImmediateExtendedAttribHandler;
import net.coderbot.iris.gl.shader.ShaderType;
import net.coderbot.iris.pipeline.transform.parameter.AttributeParameters;
import org.taumc.glsl.Transformer;

/**
 * Turns the per-draw entity uniforms into values read from the {@code iris_Entity} vertex attribute, so a
 * single draw can carry a different entity, block entity and item id for every vertex.
 *
 * <p>The vertex stage unpacks the attribute into a flat {@code iris_entityInfo} varying; the other stages
 * declare the same varying and copy it into locals with the names the pack uses.</p>
 */
class EntityPatcher {

    private static final String[] ID_NAMES = { "entityId", "blockEntityId", "currentRenderedItemId" };
    private static final String[] ID_COMPONENTS = { ".x", ".y", ".z" };

    public static void patchEntityId(Transformer transformer, AttributeParameters parameters) {
        final boolean vertexStage = parameters.type == ShaderType.VERTEX;
        final boolean arrayVarying = !vertexStage && (parameters.hasGeometry
            || parameters.type == ShaderType.GEOMETRY
            || parameters.type == ShaderType.TESSELATION_CONTROL
            || parameters.type == ShaderType.TESSELATION_EVAL);
        final String info = arrayVarying ? "iris_entityInfo[0]" : "iris_entityInfo";

        final StringBuilder assignments = new StringBuilder();
        for (int i = 0; i < ID_NAMES.length; i++) {
            final String name = ID_NAMES[i];
            if (!transformer.containsCall(name)) continue;
            if (transformer.hasVariable(name)) transformer.removeVariable(name);
            transformer.injectVariable("int " + name + ";");
            assignments.append(name).append(" = ").append(info).append(ID_COMPONENTS[i]).append("; ");
        }

        if (vertexStage) {
            transformer.injectVariable("layout(location = " + ImmediateExtendedAttribHandler.LOC_ENTITY_IDS
                + ") in vec4 iris_Entity;");
            transformer.injectVariable("flat out ivec3 iris_entityInfo;");
            transformer.prependMain("{ ivec3 iris_ids = ivec3(round(iris_Entity.xyz * 65535.0)); "
                + "iris_entityInfo = ivec3(iris_ids.x == 65535 ? -1 : iris_ids.x, "
                + "iris_ids.y == 65535 ? -1 : iris_ids.y, iris_ids.z == 65535 ? -1 : iris_ids.z); "
                + assignments + "}");
            return;
        }

        transformer.injectVariable(arrayVarying ? "flat in ivec3 iris_entityInfo[];" : "flat in ivec3 iris_entityInfo;");
        if (assignments.length() > 0) {
            transformer.prependMain("{ " + assignments + "}");
        }
    }

    public static void patchOverlayColor(Transformer transformer, AttributeParameters parameters) {
        injectUniformIfUndeclared(transformer, "entityColor", "uniform vec4 entityColor;");
    }

    private static void injectUniformIfUndeclared(Transformer transformer, String name, String declaration) {
        if (transformer.containsCall(name) && !transformer.hasVariable(name)) {
            transformer.injectVariable(declaration);
        }
    }
}
