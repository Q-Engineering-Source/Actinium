package net.coderbot.iris.shaderpack.preprocessor;

import com.google.common.collect.ImmutableList;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import net.coderbot.iris.shaderpack.StringPair;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;
import net.coderbot.iris.shaderpack.include.IncludeGraph;
import net.coderbot.iris.shaderpack.option.ShaderPackOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The preprocessed-source cache must not answer from a key that ignores the macro environment: every define
 * handed to jcpp changes the output, so two packs (or two option sets) that differ only in a define have to
 * produce different entries.
 */
class PreprocessedSourceCacheTest {
    private static final String SOURCE = """
        #version 120
        #ifdef ENABLED
        void main() { gl_FragColor = vec4(1.0); }
        #else
        void main() { gl_FragColor = vec4(0.0); }
        #endif
        """;

    @TempDir
    Path cacheDir;

    @AfterEach
    void resetCache() {
        ShaderDiskCache.resetForTesting();
    }

    @Test
    void glslOutputSurvivesAReloadAndStillTracksDefines() {
        ShaderDiskCache.configure(cacheDir, "test");
        byte[] enabledHash = { 1 };
        byte[] disabledHash = { 2 };
        List<StringPair> enabled = List.of(new StringPair("ENABLED", ""));

        String enabledSource = JcppProcessor.glslPreprocessSource(enabledHash, () -> SOURCE, enabled);
        String disabledSource = JcppProcessor.glslPreprocessSource(disabledHash, () -> SOURCE, List.of());
        ShaderDiskCache.resetForTesting();
        ShaderDiskCache.configure(cacheDir, "test");

        assertEquals(enabledSource, JcppProcessor.glslPreprocessSource(enabledHash, () -> SOURCE, enabled));
        assertEquals(disabledSource, JcppProcessor.glslPreprocessSource(disabledHash, () -> SOURCE, List.of()));
        assertNotEquals(enabledSource, disabledSource);
    }

    @Test
    void contentHashKeySkipsSourceUntilTheCacheMisses() throws IOException {
        ShaderDiskCache.configure(cacheDir, "test");
        byte[] hash = { 1, 2, 3 };
        AtomicInteger evaluations = new AtomicInteger();
        Supplier<String> source = () -> {
            evaluations.incrementAndGet();
            return SOURCE;
        };

        String first = JcppProcessor.glslPreprocessSource(hash, source, List.of());
        String second = JcppProcessor.glslPreprocessSource(hash, source, List.of());

        assertEquals(1, evaluations.get());
        assertEquals(first, second);
        assertEquals(1, binCount());
    }

    @Test
    void propertiesOutputIsCachedPerDefineSet() throws IOException {
        ShaderDiskCache.configure(cacheDir, "test");
        ShaderPackOptions options = packOptions();

        String enabled = PropertiesPreprocessor.preprocessSource(SOURCE, options, List.of(new StringPair("ENABLED", "")));
        String disabled = PropertiesPreprocessor.preprocessSource(SOURCE, options, List.of());

        assertEquals(2, binCount());
        ShaderDiskCache.resetForTesting();
        ShaderDiskCache.configure(cacheDir, "test");
        assertEquals(enabled, PropertiesPreprocessor.preprocessSource(SOURCE, options, List.of(new StringPair("ENABLED", ""))));
        assertEquals(disabled, PropertiesPreprocessor.preprocessSource(SOURCE, options, List.of()));
    }

    private ShaderPackOptions packOptions() throws IOException {
        Files.writeString(cacheDir.resolve("entry.glsl"), "void main() {}\n");
        IncludeGraph graph = new IncludeGraph(cacheDir, ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/entry.glsl")));

        return new ShaderPackOptions(graph, Map.of());
    }

    @Test
    void disabledCacheRecomputesEveryTime() {
        byte[] hash = { 4, 5, 6 };
        AtomicInteger evaluations = new AtomicInteger();
        Supplier<String> source = () -> {
            evaluations.incrementAndGet();
            return SOURCE;
        };

        JcppProcessor.glslPreprocessSource(hash, source, List.of());
        JcppProcessor.glslPreprocessSource(hash, source, List.of());

        assertEquals(2, evaluations.get());
    }

    private long binCount() throws IOException {
        try (Stream<Path> walk = Files.walk(cacheDir)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".bin")).count();
        }
    }
}
