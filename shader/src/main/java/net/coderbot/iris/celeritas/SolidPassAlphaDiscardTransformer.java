package net.coderbot.iris.celeritas;

import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import net.coderbot.iris.Iris;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.jetbrains.annotations.Nullable;
import org.taumc.glsl.ShaderParser;
import org.taumc.glsl.grammar.GLSLParser;
import org.taumc.glsl.grammar.GLSLParserBaseListener;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drops fragment alpha discards from the opaque terrain passes.
 *
 * <p>Vanilla brackets the solid terrain layer with {@code disableAlpha()} and only enables the
 * alpha test for the cutout layers, so leaves at the fast graphics level land in
 * {@code GBUFFER_SOLID} with their atlas alpha ignored. Shader packs read that same atlas alpha
 * and discard fragments below their own threshold, which opens holes vanilla never showed. Only
 * guards that read the alpha channel and whose branches hold nothing but {@code discard} and a
 * bare {@code return} are removed; the cutout passes keep the pack guard because vanilla applies
 * an alpha test there.</p>
 */
final class SolidPassAlphaDiscardTransformer {
    private static final String ALPHA_SWIZZLE = "a";
    private static final Pattern VERSION_DIRECTIVE = Pattern.compile("#version\\s+(\\d+)");

    private SolidPassAlphaDiscardTransformer() {
    }

    /**
     * Returns the fragment source the given pass must compile. Opaque passes lose their alpha
     * discards, every other pass receives the source unchanged.
     */
    static String forPass(IrisTerrainPass pass, @Nullable String fragmentSource) {
        if (fragmentSource == null || !pass.usesOpaqueAlpha()) {
            return fragmentSource;
        }
        return stripAlphaDiscards(pass, fragmentSource);
    }

    private static String stripAlphaDiscards(IrisTerrainPass pass, String fragmentSource) {
        final int version = readVersion(pass, fragmentSource);
        // Identifiers such as "sample" are keyword tokens for the grammar; renaming them before the
        // parse keeps a valid pipeline output from turning into a recovered, corrupted tree.
        final ShaderParser.ParsedShader parsed = parse(pass,
            GlslTransformUtils.renameReservedWords(fragmentSource, version));
        final List<GLSLParser.Selection_statementContext> guards = collectGuards(parsed.full());
        if (guards.isEmpty()) {
            return fragmentSource;
        }

        for (GLSLParser.Selection_statementContext guard : guards) {
            detachStatement(guard);
        }
        Iris.logger.debug("[SolidAlphaDiscard] pass={} removed={}", pass, guards.size());

        GlslTransformUtils.restoreReservedWordsInTree(parsed.full());
        final String header = GlslTransformUtils.restoreReservedWords(
            GlslTransformUtils.getFormattedShader(parsed.pre(), "").trim());
        if (!header.contains("#version")) {
            throw new IllegalStateException("Stripped fragment source has no #version directive: " + pass);
        }
        return GlslTransformUtils.getFormattedShader(parsed.full(), header);
    }

    private static int readVersion(IrisTerrainPass pass, String fragmentSource) {
        final Matcher version = VERSION_DIRECTIVE.matcher(fragmentSource);
        if (!version.find()) {
            throw new IllegalStateException("Fragment source for " + pass + " has no #version directive");
        }
        return Integer.parseInt(version.group(1));
    }

    /**
     * Parses the source and rejects any recovery tree: printing a partially recovered shader would
     * hand the driver broken GLSL instead of reporting the parse failure.
     */
    private static ShaderParser.ParsedShader parse(IrisTerrainPass pass, String source) {
        final ShaderParser.ParsedShader parsed = ShaderParser.parseShader(source);
        final int directiveErrors = parsed.preParser().getNumberOfSyntaxErrors();
        final int syntaxErrors = parsed.parser().getNumberOfSyntaxErrors();
        if (directiveErrors != 0 || syntaxErrors != 0) {
            throw new IllegalStateException("Fragment source for " + pass + " does not parse: "
                + syntaxErrors + " syntax error(s), " + directiveErrors + " directive error(s)");
        }
        return parsed;
    }

    private static List<GLSLParser.Selection_statementContext> collectGuards(GLSLParser.Translation_unitContext root) {
        final GuardCollector collector = new GuardCollector();
        ParseTreeWalker.DEFAULT.walk(collector, root);
        return collector.guards;
    }

    /**
     * Returns the statement that owns the guard, or {@code null} when the guard is not a complete
     * statement of a statement list. An unbraced {@code if} that is the whole body of another
     * {@code if} would take that outer condition down with it, so it is left in place.
     */
    @Nullable
    private static ParserRuleContext owningStatement(GLSLParser.Selection_statementContext guard) {
        final ParserRuleContext simpleStatement = guard.getParent();
        if (!(simpleStatement instanceof GLSLParser.Simple_statementContext)) {
            return null;
        }
        final ParserRuleContext statement = simpleStatement.getParent();
        if (!(statement instanceof GLSLParser.StatementContext)) {
            return null;
        }
        return statement.getParent() instanceof GLSLParser.Statement_listContext ? statement : null;
    }

    private static void detachStatement(GLSLParser.Selection_statementContext guard) {
        final ParserRuleContext statement = owningStatement(guard);
        if (statement == null) {
            throw new IllegalStateException("Alpha discard guard is not a standalone statement");
        }
        final ParserRuleContext statements = statement.getParent();
        for (int index = 0; index < statements.getChildCount(); index++) {
            if (statements.getChild(index) == statement) {
                statements.children.remove(index);
                return;
            }
        }
        throw new IllegalStateException("Alpha discard guard is detached from its statement list");
    }

    private static boolean isAlphaDiscardGuard(GLSLParser.Selection_statementContext guard) {
        if (!referencesAlpha(guard.expression())) {
            return false;
        }
        final GLSLParser.Selection_rest_statementContext rest = guard.selection_rest_statement();
        if (!isDiscardBranch(rest.statement(0))) {
            return false;
        }
        return rest.statement().size() == 1 || isDiscardBranch(rest.statement(1));
    }

    private static boolean referencesAlpha(GLSLParser.ExpressionContext condition) {
        final AlphaReferenceListener listener = new AlphaReferenceListener();
        ParseTreeWalker.DEFAULT.walk(listener, condition);
        return listener.found;
    }

    private static boolean isDiscardBranch(GLSLParser.StatementContext statement) {
        final List<GLSLParser.Jump_statementContext> jumps = new ArrayList<>();
        return collectJumpStatements(statement, jumps) && jumps.stream().anyMatch(jump -> jump.DISCARD() != null);
    }

    private static boolean collectJumpStatements(GLSLParser.StatementContext statement,
                                                 List<GLSLParser.Jump_statementContext> jumps) {
        final GLSLParser.Compound_statementContext compound = statement.compound_statement();
        if (compound == null) {
            return collectJumpStatement(statement.simple_statement(), jumps);
        }
        final GLSLParser.Statement_listContext statements = compound.statement_list();
        if (statements == null) {
            return false;
        }
        for (GLSLParser.StatementContext child : statements.statement()) {
            if (!collectJumpStatements(child, jumps)) {
                return false;
            }
        }
        return true;
    }

    private static boolean collectJumpStatement(GLSLParser.Simple_statementContext statement,
                                               List<GLSLParser.Jump_statementContext> jumps) {
        final GLSLParser.Jump_statementContext jump = statement.jump_statement();
        if (jump == null || (jump.DISCARD() == null && !isBareReturn(jump))) {
            return false;
        }
        jumps.add(jump);
        return true;
    }

    private static boolean isBareReturn(GLSLParser.Jump_statementContext jump) {
        return jump.RETURN() != null && jump.expression() == null;
    }

    private static final class GuardCollector extends GLSLParserBaseListener {
        private final List<GLSLParser.Selection_statementContext> guards = new ArrayList<>();

        @Override
        public void enterSelection_statement(GLSLParser.Selection_statementContext context) {
            if (owningStatement(context) != null && isAlphaDiscardGuard(context)) {
                guards.add(context);
            }
        }
    }

    private static final class AlphaReferenceListener extends GLSLParserBaseListener {
        private boolean found;

        @Override
        public void enterField_selection(GLSLParser.Field_selectionContext context) {
            found |= ALPHA_SWIZZLE.equals(context.getText());
        }

        @Override
        public void enterVariable_identifier(GLSLParser.Variable_identifierContext context) {
            final String name = context.getText();
            found |= name.equals("alpha") || name.endsWith("Alpha") || name.endsWith("_alpha");
        }
    }
}
