package net.coderbot.iris.shaderpack.include;

import com.google.common.collect.ImmutableList;
import net.coderbot.iris.shaderpack.option.ShaderPackOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class IncludeProcessorTest {
    private static final AbsolutePackPath PROGRAM = AbsolutePackPath.fromAbsolutePath("/program.fsh");
    private static final AbsolutePackPath SHARED = AbsolutePackPath.fromAbsolutePath("/shared.glsl");

    @TempDir
    Path tempDir;

    @Test
    void hashChangesWhenAnIncludedFileChanges() throws IOException {
        Files.writeString(tempDir.resolve("program.fsh"), "#include \"shared.glsl\"\nvoid main() {}\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("shared.glsl"), "const float INCLUDED_VALUE = 1.0;\n", StandardCharsets.UTF_8);
        byte[] before = new IncludeProcessor(graph()).getIncludedFileHash(PROGRAM);

        Files.writeString(tempDir.resolve("shared.glsl"), "const float INCLUDED_VALUE = 2.0;\n", StandardCharsets.UTF_8);
        byte[] after = new IncludeProcessor(graph()).getIncludedFileHash(PROGRAM);

        assertNotNull(before);
        assertFalse(Arrays.equals(before, after));
    }

    @Test
    void hashChangesWhenAnAppliedOptionChanges() throws IOException {
        Files.writeString(tempDir.resolve("program.fsh"), "#include \"shared.glsl\"\nvoid main() {}\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("shared.glsl"), "#define QUALITY 1 //[0 1]\nvoid main() {}\n", StandardCharsets.UTF_8);
        IncludeGraph base = graph();

        byte[] defaults = new IncludeProcessor(new ShaderPackOptions(base, Map.of()).getIncludes()).getIncludedFileHash(PROGRAM);
        byte[] changed = new IncludeProcessor(new ShaderPackOptions(base, Map.of("QUALITY", "0")).getIncludes()).getIncludedFileHash(PROGRAM);

        assertNotNull(defaults);
        assertFalse(Arrays.equals(defaults, changed));
    }

    @Test
    void missingFileHasNoHash() throws IOException {
        Files.writeString(tempDir.resolve("program.fsh"), "void main() {}\n", StandardCharsets.UTF_8);

        assertNull(new IncludeProcessor(graph()).getIncludedFileHash(SHARED));
    }

    @Test
    void hashIsStableAcrossProcessorsAndDependsOnTheWholeIncludeChain() throws IOException {
        Files.writeString(tempDir.resolve("program.fsh"), "#include \"middle.glsl\"\nvoid main() {}\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("middle.glsl"), "#include \"leaf.glsl\"\nvoid main() {}\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("leaf.glsl"), "const int LEAF = 1;\n", StandardCharsets.UTF_8);
        IncludeGraph graph = new IncludeGraph(tempDir, ImmutableList.of(PROGRAM));

        byte[] first = new IncludeProcessor(graph).getIncludedFileHash(PROGRAM);
        byte[] second = new IncludeProcessor(graph).getIncludedFileHash(PROGRAM);

        assertArrayEquals(first, second);

        // A change two levels down still has to invalidate the entry of the program that includes it.
        Files.writeString(tempDir.resolve("leaf.glsl"), "const int LEAF = 2;\n", StandardCharsets.UTF_8);
        byte[] deeper = new IncludeProcessor(new IncludeGraph(tempDir, ImmutableList.of(PROGRAM))).getIncludedFileHash(PROGRAM);

        assertFalse(Arrays.equals(first, deeper));
    }

    @Test
    void concurrentHashRequestsAgree() throws Exception {
        Files.writeString(tempDir.resolve("program.fsh"), "#include \"shared.glsl\"\nvoid main() {}\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("shared.glsl"), "void main() {}\n", StandardCharsets.UTF_8);
        IncludeProcessor processor = new IncludeProcessor(graph());
        byte[] expected = processor.getIncludedFileHash(PROGRAM);
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            List<Future<byte[]>> hashes = IntStream.range(0, 32)
                .mapToObj(index -> executor.submit(() -> processor.getIncludedFileHash(PROGRAM)))
                .toList();

            for (Future<byte[]> result : hashes) {
                assertArrayEquals(expected, result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private IncludeGraph graph() {
        return new IncludeGraph(tempDir, ImmutableList.of(PROGRAM));
    }
}
