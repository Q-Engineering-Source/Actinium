package net.coderbot.iris.shaderpack.preprocessor;

import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import net.coderbot.iris.shaderpack.StringPair;
import org.jetbrains.annotations.Nullable;

/** Disk cache for preprocessor output keyed by its source and every macro input. */
final class PreprocessedSourceCache {
	private PreprocessedSourceCache() {}

	@Nullable
	static ShaderDiskCache.Key key(String kind, String source, Iterable<StringPair> defines) {
		if (!ShaderDiskCache.isEnabled()) {
			return null;
		}

		return withDefines(ShaderDiskCache.key("iris-preprocess").str(kind).str(source), defines);
	}

	@Nullable
	static ShaderDiskCache.Key key(String kind, byte[] contentHash, Iterable<StringPair> defines) {
		if (!ShaderDiskCache.isEnabled()) {
			return null;
		}

		return withDefines(ShaderDiskCache.key("iris-preprocess").str(kind).bytes(contentHash), defines);
	}

	private static ShaderDiskCache.Key withDefines(ShaderDiskCache.Key key, Iterable<StringPair> defines) {
		for (StringPair define : defines) {
			key.str(define.getKey()).str(define.getValue());
		}

		return key;
	}

	@Nullable
	static String get(@Nullable ShaderDiskCache.Key key) {
		return key == null ? null : ShaderDiskCache.getString(key);
	}

	static void put(@Nullable ShaderDiskCache.Key key, String preprocessed) {
		if (key != null) {
			ShaderDiskCache.putString(key, preprocessed);
		}
	}
}
