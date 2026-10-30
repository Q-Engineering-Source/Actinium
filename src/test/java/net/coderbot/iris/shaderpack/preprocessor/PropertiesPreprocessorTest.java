package net.coderbot.iris.shaderpack.preprocessor;

import com.google.common.collect.ImmutableList;
import net.coderbot.iris.shaderpack.StringPair;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;
import net.coderbot.iris.shaderpack.include.IncludeGraph;
import net.coderbot.iris.shaderpack.option.ShaderPackOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that lexer warnings while adding property macros are not fatal.
 *
 * <p>jcpp lexes macro values inside {@code addMacro} through an internal lexer source that has
 * no listener attached, so warnings (e.g. "Decimal constant starts with 0, but not octal") throw
 * {@code LexerException} from {@code addMacro} itself — the preprocessor listener installed for
 * {@code process()} is never consulted there. Such an exception used to fail the whole shader
 * pack load: Complementary Unbound r5.8.1 defines zero-prefixed decimals in string option values
 * (issue #31), and the ACTINIUM_VERSION environment define hit the same crash whenever the
 * encoded mod version started with 0 and contained an 8/9 digit (production:
 * alpha-0.0.5-da83c59 -&gt; "000058359").</p>
 */
class PropertiesPreprocessorTest {
    @TempDir
    Path tempDir;

    @Test
    void zeroPrefixedDecimalInEnvironmentDefineValueIsNotFatal() {
        // The macro value is lexed inside addMacro, which throws on warnings (issue #31).
        assertDoesNotThrow(() -> PropertiesPreprocessor.preprocessSource(
            "someKey = someValue\n",
            List.of(new StringPair("COMPLEMENTARY", "00002178"))
        ));
    }

    @Test
    void zeroPrefixedDecimalInEnvironmentDefineValueIsNotFatalWithPackOptions() throws IOException {
        // The three-arg overload feeds ACTINIUM_VERSION (e.g. "000058359" from version
        // alpha-0.0.5-da83c59) into addMacro; jcpp rejects it as a malformed octal constant
        // and the exception used to abort the whole shader pack load.
        Path entry = tempDir.resolve("entry.glsl");
        Files.writeString(entry, "void main() {}\n");
        IncludeGraph graph = new IncludeGraph(
            tempDir,
            ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/entry.glsl"))
        );
        ShaderPackOptions options = new ShaderPackOptions(graph, Map.of());

        assertDoesNotThrow(() -> PropertiesPreprocessor.preprocessSource(
            "someKey = someValue\n",
            options,
            List.of(new StringPair("ACTINIUM_VERSION", "000058359"))
        ));
    }

    @Test
    void zeroPrefixedDecimalInSourceContentIsNotFatal() {
        // Lexing the source itself goes through the collecting listener; warnings are logged, not thrown.
        String result = PropertiesPreprocessor.preprocessSource(
            "profile.TEST = SHADOW_QUALITY=00002178\nnextKey = nextValue\n",
            List.of()
        );

        // The line survives preprocessing (the token stream is not interrupted by the warning).
        assertEquals(true, result.contains("SHADOW_QUALITY=00002178"));
        assertEquals(true, result.contains("nextKey = nextValue"));
    }
}