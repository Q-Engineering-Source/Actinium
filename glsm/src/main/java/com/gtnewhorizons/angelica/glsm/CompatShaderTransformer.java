package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.taumc.glsl.ShaderParser;
import org.taumc.glsl.Transformer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * Compat transformation for mod shaders running under core profile.
 *
 * <p>Handles:
 * <ul>
 *   <li>Matrix builtin replacement (gl_ModelViewMatrix, gl_ProjectionMatrix, etc.)</li>
 *   <li>Vertex attribute replacement (gl_Vertex, gl_Color, gl_MultiTexCoord0/1, gl_Normal)</li>
 *   <li>gl_TexCoord[N] varying array → per-index in/out declarations</li>
 *   <li>Texture function renames (texture2D → texture, etc.), including pre-parse renames for
 *   the legacy builtins the grammar rejects in call position (textureCube, texture1D, …)</li>
 *   <li>Fragment output handling (gl_FragColor → layout-qualified out declarations)</li>
 *   <li>Fog builtins (gl_Fog, gl_FogFragCoord)</li>
 *   <li>gl_FrontColor → local variable in vertex shaders</li>
 *   <li>shadow1D/shadow2D family → texture/textureLod/textureProj with vec4 wrapping</li>
 *   <li>Version upgrade to 330 core minimum</li>
 *   <li>Reserved word pre-parse renaming (texture-as-variable, sample, etc.)</li>
 * </ul>
 */
public class CompatShaderTransformer {

    private static final Logger LOGGER = LogManager.getLogger(CompatShaderTransformer.class);

    private static final Pattern VERSION_PATTERN = Pattern.compile("#version[ \\t]+(\\d+)(?:[ \\t]+(\\w+))?");

    private static final Pattern GLES_IFDEF_PATTERN = Pattern.compile(
        "^[ \\t]*#[ \\t]*ifdef[ \\t]+GL_ES[ \\t]*(?://.*)?$"
    );
    private static final Pattern ENDIF_PATTERN = Pattern.compile("^[ \\t]*#[ \\t]*endif[ \\t]*(?://.*)?$");
    private static final Pattern PRECISION_DECLARATION_PATTERN = Pattern.compile(
        "^[ \\t]*precision[ \\t]+(?:lowp|mediump|highp)[ \\t]+(?:float|int)[ \\t]*;[ \\t]*(?://.*)?$"
    );
    private static final Pattern MAIN_VOID_PARAMETERS_PATTERN = Pattern.compile(
        "\\bmain[ \\t\\r\\n]*\\([ \\t\\r\\n]*void[ \\t\\r\\n]*\\)"
    );
    private static final Pattern DIRECTIVE_NAME_PATTERN = Pattern.compile("[ \\t]*([A-Za-z]+)");
    private static final Pattern MACRO_NAME_PATTERN = Pattern.compile("[ \\t]*(?:define|undef)[ \\t]+([A-Za-z_][A-Za-z0-9_]*)");

    /** Compat builtins that trigger AST transformation. */
    private static final Set<String> COMPAT_BUILTINS = Set.of(
        "gl_ModelView", "gl_Projection", "gl_NormalMatrix", "gl_TextureMatrix",
        "gl_FragColor", "gl_Fog", "gl_FrontColor", "gl_Color",
        "gl_Vertex", "gl_MultiTexCoord", "gl_TexCoord", "gl_Normal", "ftransform",
        "texture2D", "texture3D", "texelFetch2D", "texelFetch3D", "textureSize2D",
        "shadow2D", "texture1D", "textureCube", "shadow1D", "gl_FrontLightModelProduct",
        "gl_LightSource", "gl_FrontMaterial"
    );

    private static final Pattern NEEDS_TRANSFORM_PATTERN = Pattern.compile(
        String.join("|", COMPAT_BUILTINS) + "|\\b(?:attribute|varying)\\b"
    );

    private static final Map<String, String> MATRIX_RENAMES = Map.of(
        "gl_ModelViewMatrix", "actinium_ModelViewMatrix",
        "gl_ModelViewMatrixInverse", "actinium_ModelViewMatrixInverse",
        "gl_ProjectionMatrix", "actinium_ProjectionMatrix",
        "gl_ProjectionMatrixInverse", "actinium_ProjectionMatrixInverse",
        "gl_NormalMatrix", "actinium_NormalMatrix"
    );

    private static final Path DUMP_DIR;
    private static final AtomicInteger dumpCounter = new AtomicInteger(0);

    static {
        DUMP_DIR = Boolean.parseBoolean(System.getProperty("angelica.dumpShaders", "false")) ? Paths.get("compat_shaders") : null;
    }

    private static final int CACHE_SIZE = 32;
    private record CacheKey(String source, boolean isFragment) {}
    private static final Map<CacheKey, String> cache = Collections.synchronizedMap(
        new LinkedHashMap<>(32, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<CacheKey, String> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    public static void clearCache() {
        cache.clear();
    }

    /**
     * Transform a mod shader source for core profile compatibility.
     *
     * <p>Deliberate divergence from upstream Angelica: Angelica additionally re-emits the
     * transformed 330-core GLSL as GLSL ES 320 on GLES contexts ({@code toGLES(...)} via
     * {@code SpirvShaderTranslator.glslToGlslEs}, shaderc + SPIRV-Cross). That path is not wired
     * here because the shaderc/spvc natives are compileOnly in glsm and are not shipped; the
     * {@code GLES_IFDEF} handling below is a different, desktop-side concern (stripping ES
     * precision guard blocks so ES-style mod shaders parse as desktop GLSL). GLES output
     * re-emission remains a follow-up for the WP that ships the natives.
     */
    public static String transform(String source, boolean isFragment) {
        final boolean needsTransform = needsTransformation(source);
        String result;
        if (!needsTransform) {
            result = fixupVersion(source);
        } else {
            final CacheKey key = new CacheKey(source, isFragment);
            final String cached = cache.get(key);
            if (cached != null) {
                dumpShader(source, cached, isFragment, needsTransform);
                return cached;
            }

            try {
                result = transformInternal(source, isFragment);
                cache.put(key, result);
            } catch (Exception e) {
                LOGGER.warn("CompatShaderTransformer: AST transformation failed, falling back to version fixup only", e);
                result = fixupVersion(source);
            }
        }

        dumpShader(source, result, isFragment, needsTransform);
        return result;
    }

    private static boolean needsTransformation(String source) {
        // Shaders already at 330+ core don't need compat transformation
        final Matcher vm = VERSION_PATTERN.matcher(source);
        if (vm.find()) {
            final int version = Integer.parseInt(vm.group(1));
            if (version >= 330 && "core".equals(vm.group(2))) return false;
        }

        return NEEDS_TRANSFORM_PATTERN.matcher(source).find();
    }

    private static String transformInternal(String source, boolean isFragment) {
        final Matcher versionMatcher = VERSION_PATTERN.matcher(source);
        int declaredVersion = 110;
        if (versionMatcher.find()) {
            declaredVersion = Integer.parseInt(versionMatcher.group(1));
        }

        final int targetVersion = Math.max(declaredVersion, RENDER_BACKEND != null ? RENDER_BACKEND.getMinGLSLVersion() : 330);

        source = stripGlesPrecisionGuards(source);
        final SeparatedSource separatedSource = separatePreprocessorPreamble(source, targetVersion);
        source = MAIN_VOID_PARAMETERS_PATTERN.matcher(separatedSource.shader()).replaceAll("main()");

        // Pre-parse reserved word renaming — prevents ANTLR parse failures
        source = GlslTransformUtils.replaceTexture(source);
        source = GlslTransformUtils.renameParseBreakingTextureFunctions(source);
        source = GlslTransformUtils.renameReservedWords(source, targetVersion);

        final ShaderParser.ParsedShader parsedShader = ShaderParser.parseShader(source);
        if (parsedShader.preParser().getNumberOfSyntaxErrors() > 0
                || parsedShader.parser().getNumberOfSyntaxErrors() > 0) {
            // ANTLR recovers from syntax errors silently; transforming the recovered tree emits
            // broken GLSL that the driver then rejects. Fail fast so transform() falls back to
            // version fixup instead.
            throw new IllegalStateException("GLSL parse failed (shader: "
                    + parsedShader.parser().getNumberOfSyntaxErrors() + " syntax errors, preprocessor: "
                    + parsedShader.preParser().getNumberOfSyntaxErrors() + ")");
        }
        final Transformer transformer = new Transformer(parsedShader.full());

        injectMatrixUniforms(transformer);

        transformFog(transformer, isFragment, source);

        // gl_FrontLightModelProduct.sceneColor → actinium_SceneColor uniform
        if (source.contains("gl_FrontLightModelProduct")) {
            transformer.injectVariable("uniform vec4 actinium_SceneColor;");
            transformer.replaceExpression("gl_FrontLightModelProduct.sceneColor", "actinium_SceneColor");
        }

        // gl_LightSource[i] → struct + uniform array (both via injectFunction to keep struct before uniform)
        if (source.contains("gl_LightSource")) {
            transformer.injectFunction("struct actinium_LightSourceParameters {"
                    + "vec4 ambient;vec4 diffuse;vec4 specular;vec4 position;vec4 halfVector;"
                    + "vec3 spotDirection;float spotExponent;float spotCutoff;float spotCosCutoff;"
                    + "float constantAttenuation;float linearAttenuation;float quadraticAttenuation;"
                    + "};");
            transformer.injectFunction("uniform actinium_LightSourceParameters actinium_LightSource[2];");
            transformer.rename("gl_LightSource", "actinium_LightSource");
        }

        // gl_FrontMaterial → struct + uniform (both via injectFunction to keep struct before uniform)
        if (source.contains("gl_FrontMaterial")) {
            transformer.injectFunction("struct actinium_MaterialParameters {"
                    + "vec4 emission;vec4 ambient;vec4 diffuse;vec4 specular;float shininess;"
                    + "};");
            transformer.injectFunction("uniform actinium_MaterialParameters actinium_FrontMaterial;");
            transformer.rename("gl_FrontMaterial", "actinium_FrontMaterial");
        }

        // gl_FrontColor (vertex) → gl_Color (fragment) varying chain
        // Vertex side is unconditional: fragment may read gl_Color without vertex writing gl_FrontColor.
        // Uses actinium_ prefix to avoid colliding with user-declared varyings (e.g. "v_Color").
        if (!isFragment) {
            transformer.injectVariable("out vec4 actinium_FrontColor;");
            transformer.rename("gl_FrontColor", "actinium_FrontColor");
            transformer.prependMain("actinium_FrontColor = vec4(1.0);");

            // Vertex attributes — replaces removed FFP vertex inputs with explicit in declarations
            transformVertexAttributes(transformer, source);
        } else {
            if (source.contains("gl_Color")) {
                transformer.injectVariable("in vec4 actinium_FrontColor;");
                transformer.rename("gl_Color", "actinium_FrontColor");
            }
        }

        // gl_TexCoord[N] varying array → per-index in/out declarations
        final Set<Integer> texCoordIndices = new HashSet<>();
        transformer.renameArray("gl_TexCoord", "actinium_TexCoord", texCoordIndices);
        for (Integer i : texCoordIndices) {
            final String qualifier = isFragment ? "in" : "out";
            transformer.injectVariable(qualifier + " vec4 actinium_TexCoord" + i + ";");
        }

        // Fragment output handling + alpha test discard
        if (isFragment) {
            transformFragmentOutputs(transformer);
        }

        // texture-as-variable collision handling
        if (transformer.containsCall("texture") && transformer.hasVariable("texture")) {
            transformer.rename("texture", "gtexture");
        }
        if (transformer.hasVariable("actinium_renamed_texture")) {
            transformer.rename("actinium_renamed_texture", "gtexture");
        }

        transformer.renameFunctionCall(GlslTransformUtils.TEXTURE_RENAMES);

        transformer.renameAndWrapShadow("shadow2D", "texture");
        transformer.renameAndWrapShadow("shadow2DLod", "textureLod");
        transformer.renameAndWrapShadow("shadow1D", "texture");
        transformer.renameAndWrapShadow("shadow1DProj", "textureProj");
        transformer.renameAndWrapShadow("shadow2DProj", "textureProj");
        transformer.renameAndWrapShadow("shadow1DLod", "textureLod");

        final String versionDirective = "#version " + targetVersion + " core\n";
        final String preprocessor = separatedSource.preprocessor().trim();
        final String header = versionDirective + (preprocessor.isEmpty() ? "" : "\n" + preprocessor + "\n");
        final StringBuilder result = new StringBuilder();
        transformer.mutateTree(tree -> result.append(GlslTransformUtils.getFormattedShader(tree, header)));

        // Restore pre-parse renames
        String output = GlslTransformUtils.restoreReservedWords(result.toString());

        // Core profile: attribute → in, varying → out (vertex) / in (fragment)
        output = fixupQualifiers(output, isFragment);

        return output;
    }

    /**
     * Remove OpenGL ES default precision blocks that are invalid and unnecessary in desktop core shaders.
     * Other {@code GL_ES} conditionals are preserved because they can contain declarations with desktop alternatives.
     */
    private static String stripGlesPrecisionGuards(String source) {
        final String[] lines = source.split("\\R", -1);
        final StringBuilder result = new StringBuilder(source.length());

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (GLES_IFDEF_PATTERN.matcher(lines[lineIndex]).matches()) {
                int endIndex = lineIndex + 1;
                boolean foundPrecision = false;
                boolean onlyPrecision = true;

                while (endIndex < lines.length && !ENDIF_PATTERN.matcher(lines[endIndex]).matches()) {
                    final String line = lines[endIndex];
                    if (PRECISION_DECLARATION_PATTERN.matcher(line).matches()) {
                        foundPrecision = true;
                    } else if (!line.isBlank() && !line.stripLeading().startsWith("//")) {
                        onlyPrecision = false;
                    }
                    endIndex++;
                }

                if (foundPrecision && onlyPrecision && endIndex < lines.length) {
                    lineIndex = endIndex;
                    continue;
                }
            }

            result.append(lines[lineIndex]);
            if (lineIndex < lines.length - 1) {
                result.append('\n');
            }
        }

        return result.toString();
    }

    /**
     * Keep a leading directive-only preamble out of the GLSL AST parser. Moving directives after shader tokens, or
     * moving shader tokens out of a conditional block, would change preprocessing semantics and is therefore rejected.
     */
    private static SeparatedSource separatePreprocessorPreamble(String source, int targetVersion) {
        try {
            return separatePreprocessorPreambleStrict(source);
        } catch (IllegalArgumentException e) {
            // Mid-body conditional blocks that select between GLSL token groups (a common pattern in
            // multi-backend mod shaders) are resolvable by evaluating the conditionals up front; every
            // other unsafe layout keeps its original failure.
            if (!e.getMessage().contains("conditional directive controls GLSL tokens")
                    && !e.getMessage().contains("directive appears after GLSL tokens")) {
                throw e;
            }
            return separatePreprocessorWithEvaluatedConditionals(source, targetVersion);
        }
    }

    private static SeparatedSource separatePreprocessorPreambleStrict(String source) {
        final String[] lines = source.split("\\R", -1);
        final StringBuilder shader = new StringBuilder(source.length());
        final StringBuilder preprocessor = new StringBuilder();
        final StringBuilder leadingComments = new StringBuilder();
        boolean continuation = false;
        boolean inBlockComment = false;
        boolean shaderStarted = false;
        boolean macroDirectiveSeen = false;
        int conditionalDepth = 0;

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            final String line = lines[lineIndex];
            final LineClassification classification = classifyLine(line, inBlockComment);
            inBlockComment = classification.inBlockComment();

            if (continuation) {
                preprocessor.append(line).append('\n');
                continuation = classification.trailingBackslash();
            } else if (classification.directive()) {
                final String directiveName = directiveName(line, classification.directiveOffset());
                if (shaderStarted && !canRelocateLateMacro(
                    line,
                    classification.directiveOffset(),
                    directiveName,
                    shader,
                    macroDirectiveSeen
                )) {
                    throw unsafePreprocessorLayout(lineIndex, "directive appears after GLSL tokens");
                }
                if ("line".equals(directiveName)) {
                    throw unsafePreprocessorLayout(lineIndex, "#line cannot be relocated without changing line semantics");
                }
                if ("version".equals(directiveName)) {
                    if (conditionalDepth != 0) {
                        throw unsafePreprocessorLayout(lineIndex, "#version appears inside a conditional block");
                    }
                } else {
                    preprocessor.append(line).append('\n');
                }

                conditionalDepth = updateConditionalDepth(directiveName, conditionalDepth, lineIndex);
                macroDirectiveSeen |= isMacroDirective(directiveName);
                continuation = classification.trailingBackslash();
            } else {
                if (classification.shaderToken()) {
                    if (conditionalDepth != 0) {
                        throw unsafePreprocessorLayout(lineIndex, "conditional directive controls GLSL tokens");
                    }
                    if (!shaderStarted) {
                        // Comments and blank lines collected before the first token move in front of
                        // the body: the GLSL transformer library fails to place injected declarations
                        // on an AST whose only leading nodes are comments.
                        preprocessor.append(leadingComments);
                        leadingComments.setLength(0);
                    }
                    shaderStarted = true;
                }
                if (shaderStarted) {
                    shader.append(line);
                } else {
                    leadingComments.append(line);
                }
            }

            if (lineIndex < lines.length - 1) {
                if (shaderStarted) {
                    shader.append('\n');
                } else {
                    leadingComments.append('\n');
                }
            }
        }

        if (continuation) {
            throw unsafePreprocessorLayout(lines.length - 1, "unterminated directive continuation");
        }
        if (conditionalDepth != 0) {
            throw unsafePreprocessorLayout(lines.length - 1, "unterminated conditional directive");
        }

        preprocessor.append(leadingComments);

        return new SeparatedSource(shader.toString(), preprocessor.toString());
    }

    private static final class ConditionalState {
        boolean active;
        boolean taken;

        ConditionalState(boolean active, boolean taken) {
            this.active = active;
            this.taken = taken;
        }
    }

    /**
     * Preprocessor separation for shaders whose conditional blocks contain GLSL tokens (e.g. a
     * {@code #if BP_NATIVE_ES / #else / #endif} pair selecting between a GLES and a desktop body).
     * The strict scan rejects those because the AST parser cannot represent the untaken branch, so
     * here the conditionals are evaluated up front against the macros defined so far (plus
     * {@code __VERSION__}) and only the taken branches reach the output. C preprocessing rules
     * apply: undefined identifiers are 0 in {@code #if} expressions and a branch whose expression
     * cannot be evaluated fails the transform (falling back to version fixup, as before).
     */
    private static SeparatedSource separatePreprocessorWithEvaluatedConditionals(String source, int targetVersion) {
        final String[] lines = source.split("\\R", -1);
        final StringBuilder shader = new StringBuilder(source.length());
        final StringBuilder preprocessor = new StringBuilder();
        final StringBuilder leadingComments = new StringBuilder();
        boolean inBlockComment = false;
        boolean shaderStarted = false;
        boolean macroDirectiveSeen = false;
        final Deque<ConditionalState> conditionals = new ArrayDeque<>();
        final Map<String, String> macros = new HashMap<>();

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            final String line = lines[lineIndex];
            final LineClassification classification = classifyLine(line, inBlockComment);
            inBlockComment = classification.inBlockComment();

            if (classification.trailingBackslash()) {
                throw unsafePreprocessorLayout(lineIndex, "unterminated directive continuation");
            }

            final boolean live = conditionals.isEmpty() || conditionals.peek().active;

            if (classification.directive()) {
                final String directiveName = directiveName(line, classification.directiveOffset());
                switch (directiveName) {
                    case "if", "ifdef", "ifndef" -> {
                        if (live) {
                            final boolean active = evaluateConditionalDirective(
                                    line, directiveName, classification.directiveOffset(), macros, targetVersion);
                            conditionals.push(new ConditionalState(active, active));
                        } else {
                            conditionals.push(new ConditionalState(false, true));
                        }
                    }
                    case "elif" -> {
                        final ConditionalState state = requireConditionalState(conditionals, lineIndex);
                        if (state.taken) {
                            state.active = false;
                        } else {
                            state.active = evaluateConditionalDirective(
                                    line, directiveName, classification.directiveOffset(), macros, targetVersion);
                            state.taken = state.active;
                        }
                    }
                    case "else" -> {
                        final ConditionalState state = requireConditionalState(conditionals, lineIndex);
                        state.active = !state.taken;
                        state.taken = true;
                    }
                    case "endif" -> conditionals.pop();
                    case "line" -> throw unsafePreprocessorLayout(
                            lineIndex, "#line cannot be relocated without changing line semantics");
                    case "version" -> {
                        if (!conditionals.isEmpty()) {
                            throw unsafePreprocessorLayout(lineIndex, "#version appears inside a conditional block");
                        }
                        // Emitted by the transformer itself.
                    }
                    case "define", "undef" -> {
                        if (live) {
                            applyMacroDirective(line, classification.directiveOffset(), directiveName, macros);
                            if (shaderStarted && !canRelocateLateMacro(
                                    line, classification.directiveOffset(), directiveName, shader, macroDirectiveSeen)) {
                                throw unsafePreprocessorLayout(lineIndex, "directive appears after GLSL tokens");
                            }
                            preprocessor.append(line).append('\n');
                            macroDirectiveSeen = true;
                        }
                    }
                    default -> {
                        if (live) {
                            if (shaderStarted) {
                                throw unsafePreprocessorLayout(lineIndex, "directive appears after GLSL tokens");
                            }
                            preprocessor.append(line).append('\n');
                        }
                    }
                }
            } else if (live) {
                if (classification.shaderToken() && !shaderStarted) {
                    // Comments and blank lines collected before the first token move in front of
                    // the body: the GLSL transformer library fails to place injected declarations
                    // on an AST whose only leading nodes are comments.
                    preprocessor.append(leadingComments);
                    leadingComments.setLength(0);
                    shaderStarted = true;
                }
                if (shaderStarted) {
                    shader.append(line);
                } else {
                    leadingComments.append(line);
                }
            }
            // Lines of dead branches are dropped entirely.

            if (lineIndex < lines.length - 1) {
                if (shaderStarted) {
                    shader.append('\n');
                } else {
                    leadingComments.append('\n');
                }
            }
        }

        if (!conditionals.isEmpty()) {
            throw unsafePreprocessorLayout(lines.length - 1, "unterminated conditional directive");
        }

        preprocessor.append(leadingComments);

        return new SeparatedSource(shader.toString(), preprocessor.toString());
    }

    private static ConditionalState requireConditionalState(Deque<ConditionalState> conditionals, int lineIndex) {
        final ConditionalState state = conditionals.peek();
        if (state == null) {
            throw unsafePreprocessorLayout(lineIndex, "conditional branch has no opening directive");
        }
        return state;
    }

    private static boolean evaluateConditionalDirective(
            String line,
            String directiveName,
            int directiveOffset,
            Map<String, String> macros,
            int targetVersion
    ) {
        final String expression = switch (directiveName) {
            case "ifdef" -> "defined(" + macroOperand(line, directiveOffset) + ")";
            case "ifndef" -> "!defined(" + macroOperand(line, directiveOffset) + ")";
            default -> line.substring(directiveOffset + 1 + directiveName.length());
        };
        return new GlslConditionalExpression(expression, macros, targetVersion).evaluate() != 0;
    }

    private static String macroOperand(String line, int directiveOffset) {
        final Matcher matcher = Pattern.compile("[ \\t]*([A-Za-z_][A-Za-z0-9_]*)")
                .matcher(line.substring(directiveOffset + 1));
        if (!matcher.lookingAt()) {
            throw new IllegalArgumentException("Malformed macro operand in directive: " + line.trim());
        }
        return matcher.group(1);
    }

    private static void applyMacroDirective(
            String line,
            int directiveOffset,
            String directiveName,
            Map<String, String> macros
    ) {
        final Matcher matcher = MACRO_NAME_PATTERN.matcher(line.substring(directiveOffset + 1));
        if (!matcher.lookingAt()) {
            return;
        }
        if ("undef".equals(directiveName)) {
            macros.remove(matcher.group(1));
            return;
        }
        final String body = line.substring(directiveOffset + 1 + matcher.end());
        // Value-less macros exist for defined()/ifdef only; function-like macros stay opaque (their
        // use inside an #if expression will then fail evaluation and fall back, as before).
        final String trimmed = body.trim();
        if (trimmed.startsWith("(")) {
            return;
        }
        macros.put(matcher.group(1), trimmed.isEmpty() ? null : trimmed);
    }

    /**
     * Integer evaluator for the subset of C preprocessor expression syntax mod shaders use:
     * {@code defined(X)}, macro references (recursively expanded, undefined = 0),
     * {@code __VERSION__}, integer literals with suffixes, parentheses, {@code ! - + * / %},
     * {@code + -}, comparisons and {@code && ||}.
     */
    private static final class GlslConditionalExpression {
        private final String source;
        private final Map<String, String> macros;
        private final int targetVersion;
        private int pos;

        GlslConditionalExpression(String source, Map<String, String> macros, int targetVersion) {
            this.source = source;
            this.macros = macros;
            this.targetVersion = targetVersion;
        }

        long evaluate() {
            final long value = parseOr();
            skipWhitespace();
            if (pos != source.length()) {
                throw new IllegalArgumentException("Trailing tokens in #if expression: " + source.substring(pos));
            }
            return value;
        }

        private long parseOr() {
            long value = parseAnd();
            while (true) {
                skipWhitespace();
                if (match("||")) {
                    final long right = parseAnd();
                    value = (value != 0 || right != 0) ? 1 : 0;
                } else {
                    return value;
                }
            }
        }

        private long parseAnd() {
            long value = parseEquality();
            while (true) {
                skipWhitespace();
                if (match("&&")) {
                    final long right = parseEquality();
                    value = (value != 0 && right != 0) ? 1 : 0;
                } else {
                    return value;
                }
            }
        }

        private long parseEquality() {
            long value = parseRelational();
            while (true) {
                skipWhitespace();
                if (match("==")) {
                    value = value == parseRelational() ? 1 : 0;
                } else if (match("!=")) {
                    value = value != parseRelational() ? 1 : 0;
                } else {
                    return value;
                }
            }
        }

        private long parseRelational() {
            long value = parseAdditive();
            while (true) {
                skipWhitespace();
                if (match(">=")) {
                    value = value >= parseAdditive() ? 1 : 0;
                } else if (match("<=")) {
                    value = value <= parseAdditive() ? 1 : 0;
                } else if (match(">")) {
                    value = value > parseAdditive() ? 1 : 0;
                } else if (match("<")) {
                    value = value < parseAdditive() ? 1 : 0;
                } else {
                    return value;
                }
            }
        }

        private long parseAdditive() {
            long value = parseMultiplicative();
            while (true) {
                skipWhitespace();
                if (match("+")) {
                    value += parseMultiplicative();
                } else if (match("-")) {
                    value -= parseMultiplicative();
                } else {
                    return value;
                }
            }
        }

        private long parseMultiplicative() {
            long value = parseUnary();
            while (true) {
                skipWhitespace();
                if (match("*")) {
                    value *= parseUnary();
                } else if (match("/")) {
                    final long divisor = parseUnary();
                    if (divisor == 0) {
                        throw new IllegalArgumentException("Division by zero in #if expression");
                    }
                    value /= divisor;
                } else if (match("%")) {
                    final long divisor = parseUnary();
                    if (divisor == 0) {
                        throw new IllegalArgumentException("Modulo by zero in #if expression");
                    }
                    value %= divisor;
                } else {
                    return value;
                }
            }
        }

        private long parseUnary() {
            skipWhitespace();
            if (match("!")) {
                return parseUnary() == 0 ? 1 : 0;
            }
            if (match("-")) {
                return -parseUnary();
            }
            if (match("+")) {
                return parseUnary();
            }
            return parsePrimary();
        }

        private long parsePrimary() {
            skipWhitespace();
            if (match("(")) {
                final long value = parseOr();
                skipWhitespace();
                if (!match(")")) {
                    throw new IllegalArgumentException("Missing closing parenthesis in #if expression");
                }
                return value;
            }
            if (tryMatch("defined")) {
                skipWhitespace();
                final boolean parenthesized = match("(");
                final String name = parseIdentifier();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("defined() without identifier in #if expression");
                }
                if (parenthesized) {
                    skipWhitespace();
                    if (!match(")")) {
                        throw new IllegalArgumentException("Missing closing parenthesis after defined(" + name + ")");
                    }
                }
                return macros.containsKey(name) ? 1 : 0;
            }
            // Integer literals before identifiers: parseIdentifier accepts digits, so a literal
            // like 130 would otherwise be treated as an (undefined) macro name and evaluate to 0.
            final String number = parseIntegerLiteral();
            if (number != null) {
                return Long.parseLong(number);
            }
            final String identifier = parseIdentifier();
            if (!identifier.isEmpty()) {
                return expandIdentifier(identifier);
            }
            throw new IllegalArgumentException("Unexpected token in #if expression at: " + source.substring(pos));
        }

        private long expandIdentifier(String name) {
            if ("__VERSION__".equals(name)) {
                return targetVersion;
            }
            final String body = macros.get(name);
            if (body == null) {
                return 0; // C rule: undefined identifiers evaluate to 0 in #if
            }
            if (body.isBlank()) {
                throw new IllegalArgumentException("Value-less macro '" + name + "' used in #if expression");
            }
            final GlslConditionalExpression nested = new GlslConditionalExpression(body, macros, targetVersion);
            return nested.evaluate();
        }

        private String parseIdentifier() {
            final int start = pos;
            while (pos < source.length()
                    && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_')) {
                pos++;
            }
            return source.substring(start, pos);
        }

        private String parseIntegerLiteral() {
            final int start = pos;
            while (pos < source.length()
                    && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '\'')) {
                pos++;
            }
            final String token = source.substring(start, pos);
            if (token.isEmpty() || !Character.isDigit(token.charAt(0))) {
                pos = start;
                return null;
            }
            // Strip optional integer suffixes (1u, 10l, 0x1Full).
            final String digits = token.replaceAll("[uUlL]+$", "").replace("'", "");
            try {
                Long.parseLong(digits, token.startsWith("0x") || token.startsWith("0X") ? 16
                        : token.length() > 1 && token.startsWith("0") ? 8 : 10);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Malformed integer literal '" + token + "' in #if expression");
            }
            return digits;
        }

        private boolean match(String token) {
            if (source.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private boolean tryMatch(String token) {
            final int start = pos;
            if (match(token)
                    && (pos >= source.length()
                        || !Character.isLetterOrDigit(source.charAt(pos)) && source.charAt(pos) != '_')) {
                return true;
            }
            pos = start;
            return false;
        }

        private void skipWhitespace() {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
                pos++;
            }
        }
    }

    private static int updateConditionalDepth(String directiveName, int depth, int lineIndex) {
        if ("if".equals(directiveName) || "ifdef".equals(directiveName) || "ifndef".equals(directiveName)) {
            return depth + 1;
        }
        if ("else".equals(directiveName) || "elif".equals(directiveName)) {
            if (depth == 0) {
                throw unsafePreprocessorLayout(lineIndex, "conditional branch has no opening directive");
            }
            return depth;
        }
        if ("endif".equals(directiveName)) {
            if (depth == 0) {
                throw unsafePreprocessorLayout(lineIndex, "#endif has no opening directive");
            }
            return depth - 1;
        }
        return depth;
    }

    private static String directiveName(String line, int directiveOffset) {
        final Matcher matcher = DIRECTIVE_NAME_PATTERN.matcher(line.substring(directiveOffset + 1));
        return matcher.lookingAt() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static boolean canRelocateLateMacro(
        String line,
        int directiveOffset,
        String directiveName,
        CharSequence precedingShader,
        boolean macroDirectiveSeen
    ) {
        if (macroDirectiveSeen || !isMacroDirective(directiveName)) {
            return false;
        }

        final Matcher matcher = MACRO_NAME_PATTERN.matcher(line.substring(directiveOffset + 1));
        if (!matcher.lookingAt()) {
            return false;
        }

        final Pattern previousUse = Pattern.compile("\\b" + Pattern.quote(matcher.group(1)) + "\\b");
        return !previousUse.matcher(precedingShader).find();
    }

    private static boolean isMacroDirective(String directiveName) {
        return "define".equals(directiveName) || "undef".equals(directiveName);
    }

    private static IllegalArgumentException unsafePreprocessorLayout(int lineIndex, String reason) {
        return new IllegalArgumentException("Unsafe shader preprocessor layout at line " + (lineIndex + 1) + ": " + reason);
    }

    private static LineClassification classifyLine(String line, boolean startsInBlockComment) {
        boolean inBlockComment = startsInBlockComment;
        boolean inString = false;
        char quote = 0;
        boolean escaped = false;
        int directiveOffset = -1;
        int lastOutsideComment = -1;
        boolean shaderToken = false;

        for (int index = 0; index < line.length(); index++) {
            final char current = line.charAt(index);
            final char next = index + 1 < line.length() ? line.charAt(index + 1) : 0;

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    index++;
                }
                continue;
            }
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    inString = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                break;
            }
            if (current == '/' && next == '*') {
                inBlockComment = true;
                index++;
                continue;
            }
            if (current == '\"' || current == '\'') {
                inString = true;
                quote = current;
                continue;
            }
            if (!Character.isWhitespace(current)) {
                lastOutsideComment = index;
                if (directiveOffset < 0 && !shaderToken) {
                    if (current == '#') {
                        directiveOffset = index;
                    } else {
                        shaderToken = true;
                    }
                }
            }
        }

        final int lastNonWhitespace = lastNonWhitespace(line);
        final boolean trailingBackslash = lastNonWhitespace >= 0
            && lastNonWhitespace == lastOutsideComment
            && line.charAt(lastNonWhitespace) == '\\';
        return new LineClassification(directiveOffset >= 0, shaderToken, inBlockComment, trailingBackslash, directiveOffset);
    }

    private static int lastNonWhitespace(String line) {
        for (int index = line.length() - 1; index >= 0; index--) {
            if (!Character.isWhitespace(line.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private record SeparatedSource(String shader, String preprocessor) {}

    private record LineClassification(
        boolean directive,
        boolean shaderToken,
        boolean inBlockComment,
        boolean trailingBackslash,
        int directiveOffset
    ) {}

    /**
     * Inject matrix uniforms and rename compat builtins.
     */
    private static void injectMatrixUniforms(Transformer transformer) {
        transformer.injectVariable("uniform mat4 actinium_ModelViewMatrix;");
        transformer.injectVariable("uniform mat4 actinium_ModelViewMatrixInverse;");
        transformer.injectVariable("uniform mat4 actinium_ProjectionMatrix;");
        transformer.injectVariable("uniform mat4 actinium_ProjectionMatrixInverse;");
        transformer.injectVariable("uniform mat3 actinium_NormalMatrix;");
        transformer.injectVariable("uniform mat4 actinium_LightmapTextureMatrix;");

        transformer.rename(MATRIX_RENAMES);

        // Expression replacements
        transformer.replaceExpression("gl_ModelViewProjectionMatrix", "(actinium_ProjectionMatrix * actinium_ModelViewMatrix)");
        transformer.replaceExpression("gl_TextureMatrix[0]", "mat4(1.0)");
        transformer.replaceExpression("gl_TextureMatrix[1]", "actinium_LightmapTextureMatrix");
        transformer.replaceExpression(
                "gl_TextureMatrix",
                "mat4[8](mat4(1.0), actinium_LightmapTextureMatrix, mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0))");
    }

    /**
     * Transform fragment outputs for core profile.
     */
    private static void transformFragmentOutputs(Transformer transformer) {
        if (transformer.containsCall("gl_FragColor")) {
            transformer.replaceExpression("gl_FragColor", "gl_FragData[0]");
        }

        final Set<Integer> found = new HashSet<>();
        transformer.renameArray("gl_FragData", "actinium_FragData", found);

        for (Integer i : found) {
            transformer.injectVariable("layout (location = " + i + ") out vec4 actinium_FragData" + i + ";");
        }

        // Core profile: GL_ALPHA_TEST is removed - inject runtime discard using GLSM-tracked alpha reference (uploaded by CompatUniformManager).
        if (found.contains(0)) {
            transformer.injectVariable("uniform float actinium_currentAlphaTest;");
            transformer.appendMain("if (actinium_FragData0.a <= actinium_currentAlphaTest) discard;");
        }
    }

    private static void transformFog(Transformer transformer, boolean isFragment, String source) {
        // Vertex side is unconditional: fragment may read gl_FogFragCoord without vertex writing it
        transformer.rename("gl_FogFragCoord", "actinium_FogFragCoord");
        if (!isFragment) {
            transformer.injectVariable("out float actinium_FogFragCoord;");
            transformer.prependMain("actinium_FogFragCoord = 0.0;");
        } else {
            if (source.contains("gl_FogFragCoord")) {
                transformer.injectVariable("in float actinium_FogFragCoord;");
            }
        }

        transformer.rename("gl_Fog", "actinium_Fog");
        transformer.injectVariable("uniform float actinium_FogDensity;");
        transformer.injectVariable("uniform float actinium_FogStart;");
        transformer.injectVariable("uniform float actinium_FogEnd;");
        transformer.injectVariable("uniform vec4 actinium_FogColor;");
        transformer.injectFunction("struct actinium_FogParameters {vec4 color;float density;float start;float end;float scale;};");
        transformer.injectFunction("actinium_FogParameters actinium_Fog = actinium_FogParameters("
                + "actinium_FogColor, actinium_FogDensity, actinium_FogStart, actinium_FogEnd, "
                + "1.0 / (actinium_FogEnd - actinium_FogStart));");
    }

    /**
     * Replace removed FFP vertex attributes with explicit {@code in} declarations at core profile attribute locations.
     */
    private static void transformVertexAttributes(Transformer transformer, String source) {
        if (source.contains("gl_Vertex") || source.contains("ftransform")) {
            transformer.injectVariable("layout(location = 0) in vec4 actinium_Vertex;");
            transformer.rename("gl_Vertex", "actinium_Vertex");
        }
        // gl_Color in vertex shaders is the per-vertex color attribute, distinct from the fragment gl_Color (interpolated gl_FrontColor) handled above
        if (source.contains("gl_Color")) {
            transformer.injectVariable("layout(location = 1) in vec4 actinium_Color;");
            transformer.rename("gl_Color", "actinium_Color");
        }
        if (source.contains("gl_MultiTexCoord0")) {
            transformer.injectVariable("layout(location = 2) in vec4 actinium_MultiTexCoord0;");
            transformer.rename("gl_MultiTexCoord0", "actinium_MultiTexCoord0");
        }
        if (source.contains("gl_MultiTexCoord1")) {
            transformer.injectVariable("layout(location = 3) in vec4 actinium_MultiTexCoord1;");
            transformer.rename("gl_MultiTexCoord1", "actinium_MultiTexCoord1");
        }
        if (source.contains("gl_Normal")) {
            transformer.injectVariable("layout(location = 4) in vec3 actinium_Normal;");
            transformer.rename("gl_Normal", "actinium_Normal");
        }
        if (source.contains("ftransform")) {
            transformer.replaceExpression("ftransform()", "(actinium_ProjectionMatrix * actinium_ModelViewMatrix * actinium_Vertex)");
        }
    }

    private static void dumpShader(String original, String transformed, boolean isFragment, boolean wasTransformed) {
        if (DUMP_DIR == null) return;
        final int id = dumpCounter.getAndIncrement();
        final String suffix = isFragment ? ".frag.glsl" : ".vert.glsl";
        try {
            Files.createDirectories(DUMP_DIR);
            final String caller = identifyCaller();
            final String header = "// Compat shader dump #" + id + " (" + (isFragment ? "fragment" : "vertex") + ")"
                + "\n// Transformed: " + wasTransformed
                + "\n// Caller: " + caller + "\n\n";
            Files.writeString(DUMP_DIR.resolve(id + "_original" + suffix), header + original, StandardCharsets.UTF_8);
            Files.writeString(DUMP_DIR.resolve(id + "_transformed" + suffix), header + transformed, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to dump compat shader: {}", e.getMessage());
        }
    }

    private static String identifyCaller() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            final String cls = frame.getClassName();
            if (!cls.startsWith("com.gtnewhorizons.angelica.glsm.")
                && !cls.startsWith("java.")
                && !cls.equals("org.lwjgl.opengl.GL20")) {
                return cls + "." + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "unknown";
    }

    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("\\battribute\\b");
    private static final Pattern VARYING_PATTERN = Pattern.compile("\\bvarying\\b");

    /** Replace legacy storage qualifiers removed in core profile. Safe on AST-serialized output (no comments). */
    public static String fixupQualifiers(String source, boolean isFragment) {
        source = ATTRIBUTE_PATTERN.matcher(source).replaceAll("in");
        source = VARYING_PATTERN.matcher(source).replaceAll(isFragment ? "in" : "out");
        return source;
    }

    // Patterns for parsing fragment shader in declarations to generate passthrough vertex shaders
    private static final Pattern FRAG_IN_COLOR = Pattern.compile("\\bin\\s+vec4\\s+actinium_FrontColor\\b");
    private static final Pattern FRAG_IN_TEXCOORD = Pattern.compile("\\bin\\s+vec4\\s+actinium_TexCoord(\\d+)\\b");
    private static final Pattern FRAG_IN_FOGCOORD = Pattern.compile("\\bin\\s+float\\s+actinium_FogFragCoord\\b");

    /**
     * Generate a passthrough vertex shader for a fragment-only program.
     */
    public static String generatePassthroughVertexShader(String fragmentSource) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("#version 330 core\n\n");

        // Always have position
        sb.append("layout(location = 0) in vec4 a_Position;\n");

        // Matrix uniforms for position transform
        sb.append("uniform mat4 actinium_ModelViewMatrix;\n");
        sb.append("uniform mat4 actinium_ProjectionMatrix;\n");

        final StringBuilder varyings = new StringBuilder();
        final StringBuilder assignments = new StringBuilder();
        if (FRAG_IN_COLOR.matcher(fragmentSource).find()) {
            sb.append("layout(location = 1) in vec4 a_Color;\n");
            varyings.append("out vec4 actinium_FrontColor;\n");
            assignments.append("  actinium_FrontColor = a_Color;\n");
        }

        final Matcher texCoordMatcher = FRAG_IN_TEXCOORD.matcher(fragmentSource);
        final Set<Integer> texCoordIndices = new HashSet<>();
        while (texCoordMatcher.find()) {
            texCoordIndices.add(Integer.parseInt(texCoordMatcher.group(1)));
        }
        for (int i : texCoordIndices) {
            // Each texcoord index is a legacy texture unit; map it to its own attribute slot
            // (0→2, 1→3, 2→5, 3→6) so multiple UV sets never collide (issue #175).
            final int location = Usage.uvAttributeLocation(i);
            if (location < 0) {
                // Units beyond 3 have no attribute slot in the fixed-function pipeline, so the data
                // cannot be sourced at all. Skipping is deliberate and loud: the fragment stage's
                // actinium_TexCoord<i> input is left without a matching output, so the program fails
                // to link instead of silently sampling a constant texel (issue #175's failure mode).
                LOGGER.warn(
                    "Passthrough vertex shader requested actinium_TexCoord{} but only legacy texture units 0..3 have a UV attribute slot; skipping it, so the program will fail to link rather than sample wrong coordinates",
                    i);
                continue;
            }
            sb.append("layout(location = ").append(location).append(") in vec4 a_TexCoord").append(i).append(";\n");
            varyings.append("out vec4 actinium_TexCoord").append(i).append(";\n");
            assignments.append("  actinium_TexCoord").append(i).append(" = a_TexCoord").append(i).append(";\n");
        }

        if (FRAG_IN_FOGCOORD.matcher(fragmentSource).find()) {
            varyings.append("out float actinium_FogFragCoord;\n");
            assignments.append("  actinium_FogFragCoord = abs((actinium_ModelViewMatrix * a_Position).z);\n");
        }

        sb.append(varyings);
        sb.append("\nvoid main() {\n");
        sb.append("  gl_Position = actinium_ProjectionMatrix * actinium_ModelViewMatrix * a_Position;\n");
        sb.append(assignments);
        sb.append("}\n");

        return sb.toString();
    }

    /** Ensure #version is at least 330 core, strip 'compatibility' profile. */
    private static String fixupVersion(String source) {
        final Matcher m = VERSION_PATTERN.matcher(source);
        if (!m.find()) {
            return "#version 330 core\n" + source;
        }

        final int version = Integer.parseInt(m.group(1));
        final String profile = m.group(2);

        if (version >= 330 && !"compatibility".equals(profile)) {
            if ("core".equals(profile)) return source;
            return m.replaceFirst("#version " + version + " core");
        }

        final int targetVersion = Math.max(version, 330);
        return m.replaceFirst("#version " + targetVersion + " core");
    }
}
