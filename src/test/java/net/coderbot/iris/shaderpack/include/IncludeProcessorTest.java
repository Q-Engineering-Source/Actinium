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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncludeProcessorTest {
	@TempDir
	Path tempDir;

	@Test
	void recursiveHashChangesForIncludedContentAndAppliedOptions() throws IOException {
		Path program = tempDir.resolve("program.fsh");
		Path include = tempDir.resolve("shared.glsl");
		Files.writeString(program, "#include \"shared.glsl\"\nvoid main() {}\n", StandardCharsets.UTF_8);
		Files.writeString(include, "#define QUALITY 1 //[0 1]\nconst float INCLUDED_VALUE = 1.0;\n", StandardCharsets.UTF_8);

		byte[] initialHash = sourceHash(Map.of());

		Files.writeString(include, "#define QUALITY 1 //[0 1]\nconst float INCLUDED_VALUE = 2.0;\n", StandardCharsets.UTF_8);
		byte[] changedIncludeHash = sourceHash(Map.of());
		assertFalse(Arrays.equals(initialHash, changedIncludeHash));

		byte[] changedOptionHash = sourceHash(Map.of("QUALITY", "0"));
		assertFalse(Arrays.equals(changedIncludeHash, changedOptionHash));
	}

	@Test
	void sharedProcessorExpandsAndHashesConcurrentIncludeRequestsConsistently() throws Exception {
		Files.writeString(tempDir.resolve("program.fsh"),
			"#include \"first.glsl\"\n#include \"second.glsl\"\n", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("first.glsl"),
			"#include \"shared.glsl\"\nfirst\n", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("second.glsl"),
			"#include \"shared.glsl\"\nsecond\n", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("shared.glsl"), "shared\n", StandardCharsets.UTF_8);

		IncludeProcessor processor = new IncludeProcessor(new IncludeGraph(
			tempDir,
			ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/program.fsh"))
		));
		AbsolutePackPath path = AbsolutePackPath.fromAbsolutePath("/program.fsh");
		List<String> expectedLines = List.of("shared", "first", "shared", "second");
		byte[] expectedHash = processor.getIncludedFileHash(path);
		ExecutorService executor = Executors.newFixedThreadPool(8);

		try {
			List<Future<ImmutableList<String>>> lines = IntStream.range(0, 32)
				.mapToObj(index -> executor.submit(() -> processor.getIncludedFile(path)))
				.toList();
			List<Future<byte[]>> hashes = IntStream.range(0, 32)
				.mapToObj(index -> executor.submit(() -> processor.getIncludedFileHash(path)))
				.toList();

			for (Future<ImmutableList<String>> result : lines) {
				assertEquals(expectedLines, result.get());
			}
			for (Future<byte[]> result : hashes) {
				assertArrayEquals(expectedHash, result.get());
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void rejectsRecursiveIncludeCyclesDuringGraphConstruction() throws IOException {
		Files.writeString(tempDir.resolve("program.fsh"), "#include \"cycle.glsl\"\n", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("cycle.glsl"), "#include \"program.fsh\"\n", StandardCharsets.UTF_8);

		assertThrows(IllegalStateException.class, () -> new IncludeGraph(
			tempDir,
			ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/program.fsh"))
		));
	}

	private byte[] sourceHash(Map<String, String> changedOptions) throws IOException {
		IncludeGraph graph = new IncludeGraph(
			tempDir,
			ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/program.fsh"))
		);
		ShaderPackOptions options = new ShaderPackOptions(graph, changedOptions);
		IncludeProcessor processor = new IncludeProcessor(options.getIncludes());
		return processor.getIncludedFileHash(AbsolutePackPath.fromAbsolutePath("/program.fsh"));
	}
}
