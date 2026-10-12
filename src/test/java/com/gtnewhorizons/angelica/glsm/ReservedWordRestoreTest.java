package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.Test;
import org.taumc.glsl.grammar.GLSLParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the in-tree reserved-word restore against the string one it replaced on the iris path.
 *
 * <p>A transformed stage is printed from its tree and handed to the SDL backend as that same tree, so
 * the renamed identifiers have to be undone on the tokens rather than on the printed text: a text
 * replacement after printing would move every offset the tree records, and the backend applies its
 * edits at those offsets. The two forms therefore have to agree on the words they both handle.</p>
 */
class ReservedWordRestoreTest {

    private static final String PREFIX = GlslTransformUtils.RENAMED_PREFIX;

    private static void assertTreeRestoreMatchesStringRestore(String label, String renamedSource) {
        final GLSLParser.Translation_unitContext treeA = GlslTransformUtils.parseFullQuiet(renamedSource);
        final String legacy = GlslTransformUtils.restoreReservedWords(GlslTransformUtils.getFormattedShader(treeA, ""));

        final GLSLParser.Translation_unitContext treeB = GlslTransformUtils.parseFullQuiet(renamedSource);
        GlslTransformUtils.restoreReservedWordsInTree(treeB);
        final String inTree = GlslTransformUtils.getFormattedShader(treeB, "");

        assertEquals(legacy, inTree, "in-tree restore diverged from string restore for " + label);
    }

    @Test
    void theRenamesBothFormsHandleRestoreIdentically() {
        final String src = "float " + PREFIX + "sample = 1.0;\n"
            + "uniform sampler2D " + PREFIX + "sampler;\n"
            + "vec4 " + PREFIX + "texture = vec4(0.0);\n"
            + "void main() {\n"
            + "    vec4 c = " + PREFIX + "texture + vec4(" + PREFIX + "sample);\n"
            + "}\n";
        assertTreeRestoreMatchesStringRestore("all-handled", src);
    }

    @Test
    void renamedNewStaysRenamed() {
        final String src = "float " + PREFIX + "new = 2.0;\n"
            + "void main() { float x = " + PREFIX + "new; }\n";

        final GLSLParser.Translation_unitContext tree = GlslTransformUtils.parseFullQuiet(src);
        GlslTransformUtils.restoreReservedWordsInTree(tree);
        final String out = GlslTransformUtils.getFormattedShader(tree, "");

        assertTrue(out.contains(PREFIX + "new"), "new is reserved in every GLSL version and must stay renamed");
    }

    @Test
    void renamedTextureFunctionCallRestores() {
        final String src = "uniform sampler2D tex;\n"
            + "void main() {\n"
            + "    vec4 c = " + PREFIX + "texture2D(tex, vec2(0.5));\n"
            + "}\n";
        assertTreeRestoreMatchesStringRestore("texture2D-call", src);

        final GLSLParser.Translation_unitContext tree = GlslTransformUtils.parseFullQuiet(src);
        GlslTransformUtils.restoreReservedWordsInTree(tree);
        final String out = GlslTransformUtils.getFormattedShader(tree, "");

        assertTrue(out.contains("texture2D"), "the suffixed rename must strip only the prefix");
        assertFalse(out.contains(PREFIX), "no renamed remnants expected");
    }

    @Test
    void untouchedSourcePrintsIdentically() {
        final String src = "layout(location = 0) out vec4 fragColor;\n"
            + "void main() { fragColor = vec4(1.0); }\n";
        assertTreeRestoreMatchesStringRestore("no-renames", src);
    }
}
