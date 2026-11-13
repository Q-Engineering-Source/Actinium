package net.coderbot.iris.pipeline.transform;

import net.coderbot.iris.gl.image.ImageInformation;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.coderbot.iris.pipeline.transform.parameter.Parameters;
import org.taumc.glsl.ShaderParser;
import org.taumc.glsl.Transformer;
import org.taumc.glsl.grammar.GLSLLexer;
import org.taumc.glsl.grammar.GLSLParser;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SamplerToStorageImageRewriter {


    public record Candidate(String samplerName, String imageName, String layoutQualifier, int dim, boolean signedInt) {}

    private static volatile Set<Candidate> activeCandidates = Set.of();

    public static void setActiveCandidates(Set<Candidate> candidates) {
        activeCandidates = (candidates == null) ? Set.of() : Set.copyOf(candidates);
    }

    public static Set<Candidate> activeCandidates() {
        return activeCandidates;
    }

    /** SDL_GPU forbids sampler usage on integer formats */
    public static Set<Candidate> buildCandidates(Iterable<ImageInformation> infos) {
        if (!BackendManager.RENDER_BACKEND.isSDLGPU()) return Set.of();
        final HashSet<Candidate> out = new HashSet<>();
        for (ImageInformation info : infos) {
            if (info.samplerName() == null || info.samplerName().isEmpty()) continue;
            if (!info.format().isInteger()) continue;
            final int dim = switch (info.target()) {
                case TEXTURE_1D -> 1;
                case TEXTURE_2D, TEXTURE_RECTANGLE -> 2;
                case TEXTURE_3D -> 3;
            };
            final InternalTextureFormat fmt = info.internalTextureFormat();
            final String layout = fmt.name().toLowerCase(Locale.ROOT);
            final boolean signed = fmt.name().endsWith("I") && !fmt.name().endsWith("UI");
            out.add(new Candidate(info.samplerName(), info.name(), layout, dim, signed));
        }
        return out;
    }

    private SamplerToStorageImageRewriter() {}

    public static void transformGrouped(Map<PatchShaderType, Transformer> trees, Parameters parameters) {
        final Set<Candidate> candidates = activeCandidates;
        if (candidates.isEmpty()) return;

        for (Candidate candidate : candidates) validateCandidate(trees, candidate);
        for (Candidate candidate : candidates) applyCandidate(trees, candidate);
    }

    private static int samplerType(Candidate candidate) {
        return candidate.signedInt ? lexerTypeForISampler(candidate.dim) : lexerTypeForUSampler(candidate.dim);
    }

    private static void validateCandidate(Map<PatchShaderType, Transformer> trees, Candidate candidate) {
        for (Transformer transformer : trees.values()) {
            if (transformer == null || transformer.findType(candidate.samplerName) != samplerType(candidate)) continue;
            transformer.mutateTree(tree -> {
                for (var call : GlslAstHelpers.collectAll(tree, GLSLParser.Postfix_expressionContext.class)) {
                    final String function = GlslAstHelpers.extractCallName(call);
                    if (function == null || !candidate.samplerName.equals(GlslAstHelpers.firstArgIdentifier(call))) continue;
                    final List<GLSLParser.Assignment_expressionContext> args = GlslAstHelpers.argsOf(call);
                    final int lodIndex = switch (function) {
                        case "texelFetch" -> 2;
                        case "textureSize" -> 1;
                        default -> throw new IllegalArgumentException("SDL_GPU integer image " + candidate.imageName
                            + " cannot use sampler operation " + function);
                    };
                    if (args.size() != lodIndex + 1 || !isZeroLod(args.get(lodIndex))) {
                        throw new IllegalArgumentException("SDL_GPU integer image " + candidate.imageName
                            + " requires LOD 0 for " + function);
                    }
                }
            });
        }
    }

    private static boolean isZeroLod(GLSLParser.Assignment_expressionContext argument) {
        final String expression = argument.getText().replace("(", "").replace(")", "");
        return expression.equals("0") || expression.equals("0u") || expression.equals("0U");
    }

    private static void applyCandidate(Map<PatchShaderType, Transformer> trees, Candidate candidate) {
        for (Transformer transformer : trees.values()) {
            if (transformer == null || transformer.findType(candidate.samplerName) != samplerType(candidate)) continue;
            final String imageType = (candidate.signedInt ? "iimage" : "uimage") + candidate.dim + "D";
            final int existing = transformer.findType(candidate.imageName);
            if (existing == 0) {
                transformer.variable = null;
                transformer.injectVariable("layout(" + candidate.layoutQualifier + ") readonly uniform " + imageType + " " + candidate.imageName + ";");
            } else {
                final int expected = ShaderParser.parseSnippet(imageType, GLSLParser::type_specifier_nonarray).getStart().getType();
                if (existing != expected) {
                    throw new IllegalArgumentException("SDL_GPU integer image " + candidate.imageName + " has an incompatible declaration");
                }
                transformer.mutateTree(tree -> {
                    for (var declaration : GlslAstHelpers.collectAll(tree, GLSLParser.Single_declarationContext.class)) {
                        if (declaration.typeless_declaration() == null || declaration.typeless_declaration().IDENTIFIER() == null
                            || !candidate.imageName.equals(declaration.typeless_declaration().IDENTIFIER().getText())) continue;
                        final var qualifiers = declaration.fully_specified_type().type_qualifier();
                        if (qualifiers != null) qualifiers.children.removeIf(qualifier -> qualifier.getText().equals("writeonly"));
                    }
                });
            }
            transformer.removeVariable(candidate.samplerName);
            transformer.rename(candidate.samplerName, candidate.imageName);
            rewriteImageCalls(transformer, candidate.imageName);
        }
    }

    private static int lexerTypeForUSampler(int dim) {
        return switch (dim) {
            case 1 -> GLSLLexer.USAMPLER1D;
            case 2 -> GLSLLexer.USAMPLER2D;
            case 3 -> GLSLLexer.USAMPLER3D;
            default -> 0;
        };
    }

    private static int lexerTypeForISampler(int dim) {
        return switch (dim) {
            case 1 -> GLSLLexer.ISAMPLER1D;
            case 2 -> GLSLLexer.ISAMPLER2D;
            case 3 -> GLSLLexer.ISAMPLER3D;
            default -> 0;
        };
    }

    private static void rewriteImageCalls(Transformer transformer, String imageName) {
        transformer.mutateTree(tree -> {
            for (var call : GlslAstHelpers.collectAll(tree, GLSLParser.Postfix_expressionContext.class)) {
                final String function = GlslAstHelpers.extractCallName(call);
                if (function == null || !imageName.equals(GlslAstHelpers.firstArgIdentifier(call))) continue;
                final String replacement = switch (function) {
                    case "texelFetch" -> "imageLoad(" + imageName + ", " + GlslAstHelpers.secondArgText(call) + ")";
                    case "textureSize" -> "imageSize(" + imageName + ")";
                    default -> null;
                };
                if (replacement != null) {
                    GlslAstHelpers.replaceNode(call, ShaderParser.parseSnippet(replacement, GLSLParser::postfix_expression));
                }
            }
        });
    }
}
