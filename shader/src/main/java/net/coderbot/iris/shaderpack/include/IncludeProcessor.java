package net.coderbot.iris.shaderpack.include;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class IncludeProcessor {
	private final IncludeGraph graph;
	private final Map<AbsolutePackPath, ImmutableList<String>> cache;
	private final Map<AbsolutePackPath, byte[]> hashes;

	public IncludeProcessor(IncludeGraph graph) {
		this.graph = graph;
		this.cache = new ConcurrentHashMap<>();
		this.hashes = new ConcurrentHashMap<>();
	}

	/**
	 * Returns a content hash for a file and every recursively included file.
	 *
	 * <p>The graph is already transformed by shader pack options when this is used for
	 * preprocessing, so option changes invalidate the resulting hash.</p>
	 */
	public byte[] getIncludedFileHash(AbsolutePackPath path) {
		byte[] hash = hashes.get(path);

		if (hash == null) {
			hash = hash(path);
			if (hash == null) {
				return null;
			}

			byte[] previous = hashes.putIfAbsent(path, hash);
			if (previous != null) {
				hash = previous;
			}
		}

		return hash.clone();
	}

	private byte[] hash(AbsolutePackPath path) {
		FileNode fileNode = graph.getNodes().get(path);

		if (fileNode == null) {
			return null;
		}

		final MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required for shader source hashing", e);
		}

		ImmutableList<String> lines = fileNode.getLines();
		ImmutableMap<Integer, AbsolutePackPath> includes = fileNode.getIncludes();

		for (int i = 0; i < lines.size(); i++) {
			AbsolutePackPath include = includes.get(i);
			if (include != null) {
				digest.update((byte) 1);
				digest.update(Objects.requireNonNull(getIncludedFileHash(include)));
			} else {
				digest.update((byte) 0);
				digest.update(lines.get(i).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) '\n');
			}
		}

		return digest.digest();
	}

	public ImmutableList<String> getIncludedFile(AbsolutePackPath path) {
		ImmutableList<String> lines = cache.get(path);

		if (lines == null) {
			lines = process(path);
			if (lines != null) {
				ImmutableList<String> previous = cache.putIfAbsent(path, lines);
				if (previous != null) {
					lines = previous;
				}
			}
		}

		return lines;
	}

	private ImmutableList<String> process(AbsolutePackPath path) {
		FileNode fileNode = graph.getNodes().get(path);

		if (fileNode == null) {
			return null;
		}

		ImmutableList.Builder<String> builder = ImmutableList.builder();

		ImmutableList<String> lines = fileNode.getLines();
		ImmutableMap<Integer, AbsolutePackPath> includes = fileNode.getIncludes();

		for (int i = 0; i < lines.size(); i++) {
			AbsolutePackPath include = includes.get(i);

			if (include != null) {
				// IncludeGraph validates cycles before recursive expansion begins.
				builder.addAll(Objects.requireNonNull(getIncludedFile(include)));
			} else {
				builder.add(lines.get(i));
			}
		}

		return builder.build();
	}
}
