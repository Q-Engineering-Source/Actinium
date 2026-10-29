package net.coderbot.iris.shaderpack.preprocessor;

import com.google.common.collect.ImmutableList;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import net.coderbot.iris.shaderpack.StringPair;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;
import net.coderbot.iris.shaderpack.include.IncludeGraph;
import net.coderbot.iris.shaderpack.include.IncludeProcessor;
import net.coderbot.iris.shaderpack.option.ShaderPackOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JcppProcessorTest {
    @TempDir
    Path tempDir;

    @Test
    void removesNulCharactersBeforePreprocessing() {
        String result = JcppProcessor.glslPreprocessSource("void main() {\u0000}\n", List.<StringPair>of());

        assertEquals("void main() {}\n\n", result);
    }

    @Test
    void diskCacheReusesPreprocessingAndInvalidatesEveryInput() throws IOException {
        Path cacheRoot = Files.createDirectory(tempDir.resolve("cache"));
        Path packRoot = Files.createDirectory(tempDir.resolve("pack"));
        Path include = packRoot.resolve("shared.glsl");
        Path program = packRoot.resolve("program.fsh");
        Files.writeString(program, "#version 120\n#include \"shared.glsl\"\n#if FAST\nvoid main() { gl_FragColor = vec4(QUALITY); }\n#else\nvoid main() { gl_FragColor = vec4(0.0); }\n#endif\n", StandardCharsets.UTF_8);
        Files.writeString(include, "#define QUALITY 1 //[0 1]\nconst float INCLUDED_VALUE = 1.0;\n", StandardCharsets.UTF_8);
        ShaderDiskCache.configure(cacheRoot, "preprocess-test");

        try {
            AtomicInteger sourceComputations = new AtomicInteger();
            List<StringPair> fastEnabled = List.of(new StringPair("FAST", "1"));
            PreparedSource initial = prepare(packRoot, Map.of());
            String cold = preprocess(initial, fastEnabled, sourceComputations);
            String hot = preprocess(initial, fastEnabled, sourceComputations);

            assertEquals(cold, hot);
            assertEquals(1, sourceComputations.get());

            Files.writeString(include, "#define QUALITY 1 //[0 1]\nconst float INCLUDED_VALUE = 2.0;\n", StandardCharsets.UTF_8);
            PreparedSource changedInclude = prepare(packRoot, Map.of());
            String includeChanged = preprocess(changedInclude, fastEnabled, sourceComputations);
            assertNotEquals(cold, includeChanged);
            assertEquals(2, sourceComputations.get());

            PreparedSource changedOption = prepare(packRoot, Map.of("QUALITY", "0"));
            String optionChanged = preprocess(changedOption, fastEnabled, sourceComputations);
            assertNotEquals(includeChanged, optionChanged);
            assertEquals(3, sourceComputations.get());

            List<StringPair> fastDisabled = List.of(new StringPair("FAST", "0"));
            String macroChanged = preprocess(changedOption, fastDisabled, sourceComputations);
            assertNotEquals(optionChanged, macroChanged);
            assertEquals(4, sourceComputations.get());

            ShaderDiskCache.Key key = ShaderDiskCache.key("iris-preprocess")
                .str("glsl")
                .bytes(changedOption.contentHash())
                .str("FAST")
                .str("0");
            Path cacheEntry = cacheRoot.resolve("iris-preprocess").resolve(key.hex() + ".bin");
            assertTrue(Files.exists(cacheEntry));
            Files.write(cacheEntry, new byte[] {0x00, 0x01, 0x02});

            String recomputed = preprocess(changedOption, fastDisabled, sourceComputations);
            assertEquals(macroChanged, recomputed);
            assertEquals(5, sourceComputations.get());
            assertTrue(Files.size(cacheEntry) > 3);
            assertEquals(macroChanged, ShaderDiskCache.getString(key));
        } finally {
            ShaderDiskCache.disable();
        }
    }

    private PreparedSource prepare(Path packRoot, Map<String, String> changedOptions) throws IOException {
        AbsolutePackPath programPath = AbsolutePackPath.fromAbsolutePath("/program.fsh");
        IncludeGraph graph = new IncludeGraph(packRoot, ImmutableList.of(programPath));
        ShaderPackOptions options = new ShaderPackOptions(graph, changedOptions);
        IncludeProcessor processor = new IncludeProcessor(options.getIncludes());
        return new PreparedSource(processor, programPath, processor.getIncludedFileHash(programPath));
    }

    private String preprocess(PreparedSource prepared, Iterable<StringPair> defines, AtomicInteger sourceComputations) {
        return JcppProcessor.glslPreprocessSource(prepared.contentHash(), () -> {
            sourceComputations.incrementAndGet();
            ImmutableList<String> lines = prepared.processor().getIncludedFile(prepared.path());
            StringBuilder source = new StringBuilder();
            for (String line : lines) {
                source.append(line).append('\n');
            }
            return source.toString();
        }, defines);
    }

    private record PreparedSource(IncludeProcessor processor, AbsolutePackPath path, byte[] contentHash) {}
}
