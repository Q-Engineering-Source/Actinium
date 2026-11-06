package net.coderbot.iris.gl.shader;

import net.coderbot.iris.Iris;
import net.coderbot.iris.debug.IrisDebugOptions;
import com.google.common.collect.ImmutableList;
import com.gtnewhorizons.angelica.Tags;
import net.coderbot.iris.compat.dh.DHCompat;
import net.coderbot.iris.parsing.BiomeCategories;
import net.coderbot.iris.pipeline.HandRenderer;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.coderbot.iris.shaderpack.StringPair;
import net.coderbot.iris.texture.format.TextureFormat;
import net.coderbot.iris.texture.format.TextureFormatLoader;
import net.coderbot.iris.uniforms.VanillaBiomeList;
import net.minecraft.init.Biomes;
import net.minecraft.world.biome.Biome;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.minecraftforge.fml.common.Loader;
import org.lwjgl.LWJGLUtil;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;

public class StandardMacros {
	private static final Pattern SEMVER_PATTERN = Pattern.compile("(?<major>\\d+)\\.(?<minor>\\d+)\\.*(?<bugfix>\\d*)(.*)");

	private static void define(List<StringPair> defines, String key) {
		defines.add(new StringPair(key, ""));
	}

	private static void define(List<StringPair> defines, String key, String value) {
		defines.add(new StringPair(key, value));
	}

    // Only a leading channel prefix plus a numeric triplet may contribute to the version; anything
    // after it (branch name, commit distance, git sha, dirty marker) is ignored so that extra tags
    // and dev-build suffixes never leak digits into ACTINIUM_VERSION.
    private static final Pattern ACTINIUM_VERSION_PATTERN =
            Pattern.compile("^(?:[a-z]+-)?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(?:alpha|beta|pre|rc)(\\d{1,3}))?");

    private static final String UNPARSEABLE_VERSION = "99999999";

    private static String makeActiniumVersion()
    {
        final String version = Tags.VERSION;
        final String formatted = parseActiniumVersion(version);
        if (formatted == null) {
            Iris.logger.warn("Could not parse Actinium version \"" + version
                    + "\"; defining ACTINIUM_VERSION as " + UNPARSEABLE_VERSION);
            return UNPARSEABLE_VERSION;
        }
        return formatted;
    }

    /**
     * Encodes a version string (e.g. "alpha-0.0.14", "alpha-0.0.5-beta2",
     * "alpha-0.0.14-feat-shader-preprocess-warmup.26+32aa1c9a-dirty") into a numeric macro value.
     * Unparseable input yields {@link #UNPARSEABLE_VERSION}. Package-visible for testing.
     */
    static String formatActiniumVersion(String version)
    {
        final String formatted = parseActiniumVersion(version);
        return formatted != null ? formatted : UNPARSEABLE_VERSION;
    }

    /** Returns the encoded value, or null when the string carries no version triplet. */
    private static String parseActiniumVersion(String version)
    {
        final Matcher matcher = ACTINIUM_VERSION_PATTERN.matcher(version);
        if (!matcher.find()) {
            return null;
        }
        final int major = Integer.parseInt(matcher.group(1));
        final int minor = Integer.parseInt(matcher.group(2));
        final int patch = Integer.parseInt(matcher.group(3));
        final int sub = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
        final String encoded = String.format("%d%02d%02d%03d", major, minor, patch, sub);
        // jcpp lexes macro values as C constants: a multi-digit value starting with '0' is
        // treated as octal, and an 8/9 digit then raises a LexerException that fails the whole
        // shader pack load (production: alpha-0.0.5-da83c59 -> "000058359"). Strip the leading
        // zeros; the numeric value is unchanged.
        final String stripped = encoded.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

	public static Iterable<StringPair> createStandardEnvironmentDefines() {
		ArrayList<StringPair> standardDefines = new ArrayList<>();

		define(standardDefines, "MC_VERSION", getMcVersion());
		define(standardDefines, "MC_GL_VERSION", getGlVersion(GL11.GL_VERSION));
		define(standardDefines, "MC_GLSL_VERSION", getGlVersion(GL20.GL_SHADING_LANGUAGE_VERSION));
		define(standardDefines, getOsString());
		define(standardDefines, getVendor());
		define(standardDefines, getRenderer());

		for (String glExtension : getGlExtensions()) {
			define(standardDefines, glExtension);
		}

		define(standardDefines, "MC_NORMAL_MAP");
		define(standardDefines, "MC_SPECULAR_MAP");
		define(standardDefines, "MC_RENDER_QUALITY", "1.0");
		define(standardDefines, "MC_SHADOW_QUALITY", "1.0");
		define(standardDefines, "IS_ACTINIUM");
		if (IrisDebugOptions.defineIsIris()) {
			define(standardDefines, "IS_IRIS");
		}
		define(standardDefines, "IRIS_TAG_SUPPORT", "2");

		if (DHCompat.hasRenderingEnabled()) {
			define(standardDefines, "DISTANT_HORIZONS");
		}

		define(standardDefines, "DH_BLOCK_UNKNOWN", String.valueOf(0));
		define(standardDefines, "DH_BLOCK_LEAVES", String.valueOf(1));
		define(standardDefines, "DH_BLOCK_STONE", String.valueOf(2));
		define(standardDefines, "DH_BLOCK_WOOD", String.valueOf(3));
		define(standardDefines, "DH_BLOCK_METAL", String.valueOf(4));
		define(standardDefines, "DH_BLOCK_DIRT", String.valueOf(5));
		define(standardDefines, "DH_BLOCK_LAVA", String.valueOf(6));
		define(standardDefines, "DH_BLOCK_DEEPSLATE", String.valueOf(7));
		define(standardDefines, "DH_BLOCK_SNOW", String.valueOf(8));
		define(standardDefines, "DH_BLOCK_SAND", String.valueOf(9));
		define(standardDefines, "DH_BLOCK_TERRACOTTA", String.valueOf(10));
		define(standardDefines, "DH_BLOCK_NETHER_STONE", String.valueOf(11));
		define(standardDefines, "DH_BLOCK_WATER", String.valueOf(12));
		define(standardDefines, "DH_BLOCK_GRASS", String.valueOf(13));
		define(standardDefines, "DH_BLOCK_AIR", String.valueOf(14));
		define(standardDefines, "DH_BLOCK_ILLUMINATED", String.valueOf(15));

        define(standardDefines, "ACTINIUM_VERSION", makeActiniumVersion());
		define(standardDefines, "MC_HAND_DEPTH", Float.toString(HandRenderer.DEPTH));

		TextureFormat textureFormat = TextureFormatLoader.getFormat();
		if (textureFormat != null) {
			for (String define : textureFormat.getDefines()) {
				define(standardDefines, define);
			}
		}

		getRenderStages().forEach((stage, index) -> define(standardDefines, stage, index));

		for (String irisDefine : getIrisDefines()) {
			define(standardDefines, irisDefine);
		}

		getBiomeCategoryDefines().forEach((category, ordinal) -> define(standardDefines, category, ordinal));
		getVanillaBiomeDefines().forEach((biome, id) -> define(standardDefines, biome, id));

		return ImmutableList.copyOf(standardDefines);
	}

	/**
	 * Gets the current mc version String in a 5 digit format
	 *
	 * @return mc version string
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L696-L699">Optifine Doc</a>
	 */
	public static String getMcVersion() {
		final String version = Loader.MC_VERSION;

		String[] splitVersion = version.split("\\.");

		if (splitVersion.length < 2) {
			throw new IllegalStateException("Could not parse game version \"" + version +  "\"");
		}

		String major = splitVersion[0];
		String minor = splitVersion[1];
		String bugfix;

		if (splitVersion.length < 3) {
			bugfix = "00";
		} else {
			bugfix = splitVersion[2];
		}

		if (minor.length() == 1) {
			minor = "0" + minor;
		}
		if (bugfix.length() == 1) {
			bugfix = "0" + bugfix;
		}

		return major + minor + bugfix;
	}

	/**
	 * Returns the current GL Version using regex
	 *
	 * @return current gl version stripped of semantic versioning
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L701-L703">Optifine Doc for GL Version</a>
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L705-L707">Optifine Doc for GLSL Version</a>
	 */
	public static String getGlVersion(int name) {
		final String info = GLSMQueries.glGetString(name);

		Matcher matcher = SEMVER_PATTERN.matcher(Objects.requireNonNull(info));

		if (!matcher.matches()) {
			throw new IllegalStateException("Could not parse GL version from \"" + info + "\"");
		}

		String major = group(matcher, "major");
		String minor = group(matcher, "minor");
		String bugfix = group(matcher, "bugfix");

		if (bugfix == null) {
			// if bugfix is not there, it is 0
			bugfix = "0";
		}

		if (major == null || minor == null) {
			throw new IllegalStateException("Could not parse GL version from \"" + info + "\"");
		}

		return major + minor + bugfix;
	}

	/**
	 * Expanded version of {@link Matcher#group(String)} that does not throw an exception.
	 * If the argument is incorrect (normally resulting in an exception), it returns null
	 *
	 * @param matcher matcher to check the group by
	 * @param name    name of the group
	 * @return the section of the matcher that is a group, or null, if that matcher does not contain said group
	 */
	public static String group(Matcher matcher, String name) {
		try {
			return matcher.group(name);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			return null;
		}
	}

	/**
	 * Returns the current OS String
	 *
	 * @return the string based on the current OS
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L709-L714">Optifine Doc</a>
	 */
	public static String getOsString() {
        return switch (LWJGLUtil.getPlatform()) {
            case LWJGLUtil.PLATFORM_MACOSX -> "MC_OS_MAC";
            case LWJGLUtil.PLATFORM_LINUX -> "MC_OS_LINUX";
            case LWJGLUtil.PLATFORM_WINDOWS -> "MC_OS_WINDOWS";
            default -> "MC_OS_UNKNOWN";
        };
	}

	/**
	 * Returns a string indicating the graphics card being used
	 *
	 * @return the graphics card prefixed with "MC_GL_VENDOR_"
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L716-L723">Optifine Doc</a>
	 */
	public static String getVendor() {
		String vendor = Objects.requireNonNull(GLSMQueries.glGetString(GL11.GL_VENDOR)).toLowerCase(Locale.ROOT);
		if (vendor.startsWith("ati")) {
			return "MC_GL_VENDOR_ATI";
		} else if (vendor.startsWith("intel")) {
			return "MC_GL_VENDOR_INTEL";
		} else if (vendor.startsWith("nvidia")) {
			return "MC_GL_VENDOR_NVIDIA";
		} else if (vendor.startsWith("amd")) {
			return "MC_GL_VENDOR_AMD";
		} else if (vendor.startsWith("x.org")) {
			return "MC_GL_VENDOR_XORG";
		}
		return "MC_GL_VENDOR_OTHER";
	}

	/**
	 * Returns the graphics driver being used
	 *
	 * @return graphics driver prefixed with "MC_GL_RENDERER_"
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L725-L733">Optifine Doc</a>
	 */
	public static String getRenderer() {
		String renderer = Objects.requireNonNull(GLSMQueries.glGetString(GL11.GL_RENDERER)).toLowerCase(Locale.ROOT);
		if (renderer.startsWith("amd")) {
			return "MC_GL_RENDERER_RADEON";
		} else if (renderer.startsWith("ati")) {
			return "MC_GL_RENDERER_RADEON";
		} else if (renderer.startsWith("radeon")) {
			return "MC_GL_RENDERER_RADEON";
		} else if (renderer.startsWith("gallium")) {
			return "MC_GL_RENDERER_GALLIUM";
		} else if (renderer.startsWith("intel")) {
			return "MC_GL_RENDERER_INTEL";
		} else if (renderer.startsWith("geforce")) {
			return "MC_GL_RENDERER_GEFORCE";
		} else if (renderer.startsWith("nvidia")) {
			return "MC_GL_RENDERER_GEFORCE";
		} else if (renderer.startsWith("quadro")) {
			return "MC_GL_RENDERER_QUADRO";
		} else if (renderer.startsWith("nvs")) {
			return "MC_GL_RENDERER_QUADRO";
		} else if (renderer.startsWith("mesa")) {
			return "MC_GL_RENDERER_MESA";
		}
		return "MC_GL_RENDERER_OTHER";
	}

	/**
	 * Returns the list of currently enabled GL extensions
	 * This is done by calling {@link GL11#glGetString} with the arg {@link GL11#GL_EXTENSIONS}
	 *
	 * @return list of activated extensions prefixed with "MC_"
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L735-L738">Optifine Doc</a>
	 */
	public static Set<String> getGlExtensions() {
		int numExtensions = GLSMQueries.glGetInteger(GL30.GL_NUM_EXTENSIONS);
		String[] extensions = new String[numExtensions];
		for (int i = 0; i < numExtensions; i++) {
			extensions[i] = GLSMQueries.glGetStringi(GL11.GL_EXTENSIONS, i);
		}

		// TODO note that we do not add extensions based on if the shader uses them and if they are supported
		// see https://github.com/sp614x/optifine/blob/master/OptiFineDoc/doc/shaders.txt#L738

		// NB: Use Collectors.toSet(). In some cases, there are duplicate extensions in the extension list.
		// RenderDoc is one example - it causes the GL_KHR_debug extension to appear twice:

		return Arrays.stream(extensions).map(s -> "MC_" + s).collect(Collectors.toSet());
	}

	public static Map<String, String> getRenderStages() {
		Map<String, String> stages = new HashMap<>();
		for (WorldRenderingPhase phase : WorldRenderingPhase.values()) {
			stages.put("MC_RENDER_STAGE_" + phase.name(), String.valueOf(phase.ordinal()));
		}
		return stages;
	}

	/**
	 * Returns the list of Iris-exclusive uniforms supported in the current version of Iris.
	 *
	 * @return List of definitions corresponding to the uniform names prefixed with "MC_"
	 */
	public static List<String> getIrisDefines() {
		List<String> defines = new ArrayList<>();
		// All Iris-exclusive uniforms should have a corresponding definition here. Example:
		// defines.add("MC_UNIFORM_DRAGON_DEATH_PROGRESS");

		return defines;
	}

	/**
	 * Returns biome category defines for use in shaders.
	 * Generates #define CAT_<CATEGORY_NAME> <ordinal> for each BiomeCategories enum value.
	 *
	 * @return Map of biome category defines and their ordinal values
	 */
	public static Map<String, String> getBiomeCategoryDefines() {
		Map<String, String> defines = new HashMap<>();
		for (BiomeCategories category : BiomeCategories.values()) {
			defines.put("CAT_" + category.name(), String.valueOf(category.ordinal()));
		}
		return defines;
	}

	/**
	 * Returns vanilla biome ID defines for use in shaders.
	 * Generates #define BIOME_<BIOME_NAME> <biomeID> for all vanilla biomes.
	 *
	 * @return Map of vanilla biome defines and their IDs
	 */
	public static Map<String, String> getVanillaBiomeDefines() {
		final Map<String, String> defines = new HashMap<>();

		for (VanillaBiomeList.BiomeEntry entry : VanillaBiomeList.getVanillaBiomes()) {
			if (entry.biome != null) {
				defines.put("BIOME_" + entry.name, String.valueOf(Biome.getIdForBiome(entry.biome)));
			}
		}

		// Modern biome name aliases - map modern names to 1.7.10 equivalents(ish)
        addModernBiomeAlias(defines, "SWAMP", Biomes.SWAMPLAND);
        addModernBiomeAlias(defines, "SWAMP_HILLS", Biomes.SWAMPLAND); // No hills variant in 1.12

		// Modern biomes that don't exist in 1.7.10 - add dummy IDs that will never match
		// This allows shader expressions to parse without errors while always evaluating to false/0
		// Using negative IDs to avoid conflicts with actual biome IDs
		defines.put("BIOME_NETHER_WASTES", "-1000");
		defines.put("BIOME_SOUL_SAND_VALLEY", "-1001");
		defines.put("BIOME_CRIMSON_FOREST", "-1002");
		defines.put("BIOME_WARPED_FOREST", "-1003");
		defines.put("BIOME_BASALT_DELTAS", "-1004");
		defines.put("BIOME_LUSH_CAVES", "-1005");
		defines.put("BIOME_PALE_GARDEN", "-1006");

		return defines;
	}

    private static void addModernBiomeAlias(Map<String, String> defines, String modernName, Biome biome) {
        if (biome != null) {
            defines.put("BIOME_" + modernName, String.valueOf(Biome.getIdForBiome(biome)));
        }
    }
}
