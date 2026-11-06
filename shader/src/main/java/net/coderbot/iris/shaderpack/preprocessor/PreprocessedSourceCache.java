package net.coderbot.iris.shaderpack.preprocessor;

import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import net.coderbot.iris.shaderpack.StringPair;
import org.jetbrains.annotations.Nullable;

/**
 * Disk cache of preprocessor output.
 *
 * <p>An entry is only valid for the exact input text and the complete set of macros handed to jcpp, so both are
 * folded into the key. The two preprocessing paths differ only in how the input is identified: .properties files
 * are keyed on their text, GLSL sources on the recursive content hash of the file and everything it includes.
 */
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

	/**
	 * Folds an input that changes the output but is not a macro into the key, such as the GLSL version the shader
	 * pack workarounds are selected by. Without it a cache built on one driver would answer for another.
	 */
	static void variant(@Nullable ShaderDiskCache.Key key, int value) {
		if (key != null) {
			key.i(value);
		}
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
