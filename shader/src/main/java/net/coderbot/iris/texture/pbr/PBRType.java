package net.coderbot.iris.texture.pbr;

import net.coderbot.iris.Iris;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Predicate;

public enum PBRType {
	NORMAL("_n", 0x7F7FFFFF),
	SPECULAR("_s", 0x00000000);

	// Many mods use "_n"/"_s"/"_e"/"_w" as cardinal-direction (north/south/east/west) suffixes for
	// oriented block faces. Those textures aren't PBR maps, but "_n"/"_s" collide with our suffixes.
	// If a file sitting next to a candidate PBR suffix has a sibling using one of the other compass
	// directions, treat the whole group as a directional texture set instead of a PBR pair - genuine
	// PBR resource packs essentially never ship "_e"/"_w" companions.
	private static final String[] NON_PBR_DIRECTIONAL_SUFFIXES = {"_e", "_w"};

	private static final PBRType[] VALUES = values();

	private final String suffix;
	private final int defaultValue;

	PBRType(String suffix, int defaultValue) {
		this.suffix = suffix;
		this.defaultValue = defaultValue;
	}

	public String getSuffix() {
		return suffix;
	}

	public int getDefaultValue() {
		return defaultValue;
	}

    public ResourceLocation appendToFileLocation(ResourceLocation location) {
        String domain = location.getNamespace();
        String path = location.getPath();

        try {
            URI uri = new URI(null, null, path, null);
            String cleanPath = uri.getPath();
            String newPath;

            int extensionIndex = safeIndexOfExtension(cleanPath);
            if (extensionIndex != -1) {
                newPath = cleanPath.substring(0, extensionIndex) + suffix + cleanPath.substring(extensionIndex);
            } else {
                newPath = cleanPath + suffix;
            }

            URI newUri = new URI(null, null, newPath, null);
            newPath = newUri.getPath();

            return new ResourceLocation(domain, newPath);
        } catch (URISyntaxException e) {
            Iris.logger.error("Failed to append PBR suffix to resource location for " + path, e);
            return location;
        }
    }

	/**
	 * Returns the PBR type corresponding to the suffix of the given file location.
	 *
	 * @param location The file location without an extension
	 * @return the PBR type
	 */
	@Nullable
	public static PBRType fromFileLocation(String location) {
		for (PBRType type : VALUES) {
			if (location.endsWith(type.getSuffix())) {
				return type;
			}
		}
		return null;
	}

	/**
	 * Checks whether the given (PBR-suffixed) location has a sibling texture using one of the other
	 * cardinal-direction suffixes, e.g. whether "block/buffer_side_n.png" has a "block/buffer_side_e.png"
	 * next to it. If so, "location" is almost certainly part of a directional texture set rather than an
	 * actual PBR map, and should not be treated as one.
	 *
	 * @param location The full location of the candidate PBR texture (with extension)
	 * @param exists Checks whether a candidate sibling location actually exists
	 * @return true if a directional sibling was found
	 */
	public static boolean hasDirectionalSiblings(ResourceLocation location, Predicate<ResourceLocation> exists) {
		String path = location.getPath();
		int extensionIndex = safeIndexOfExtension(path);
		String pathNoExtension = extensionIndex != -1 ? path.substring(0, extensionIndex) : path;
		String extension = extensionIndex != -1 ? path.substring(extensionIndex) : "";

		PBRType type = fromFileLocation(pathNoExtension);
		if (type == null) {
			return false;
		}

		String basePathNoExtension = pathNoExtension.substring(0, pathNoExtension.length() - type.getSuffix().length());
		for (String siblingSuffix : NON_PBR_DIRECTIONAL_SUFFIXES) {
			ResourceLocation sibling = new ResourceLocation(location.getNamespace(), basePathNoExtension + siblingSuffix + extension);
			if (exists.test(sibling)) {
				return true;
			}
		}

		return false;
	}

	public static boolean hasDirectionalSiblings(ResourceLocation location, IResourceManager resourceManager) {
		return hasDirectionalSiblings(location, sibling -> resourceExists(resourceManager, sibling));
	}

	// 1.12.2 has no resource existence query; a missing resource surfaces as FileNotFoundException.
	private static boolean resourceExists(IResourceManager resourceManager, ResourceLocation location) {
		try {
			resourceManager.getResource(location);
			return true;
		} catch (FileNotFoundException e) {
			return false;
		} catch (IOException e) {
			Iris.logger.warn("Failed to probe existence of resource '" + location + "'", e);
			return false;
		}
	}

    // Helper method to safely find the index of the file extension
    // Using this to avoid issues with ':' in paths (any block with meta).
    private static int safeIndexOfExtension(String input) {
        if (input == null) return -1;

        // Avoid dots in directory names or leading/trailing dots
        int lastSlash = Math.max(input.lastIndexOf('/'), input.lastIndexOf('\\'));
        int nameStart = lastSlash + 1;
        int lastDot = input.lastIndexOf('.');
        if (lastDot == -1 || lastDot <= nameStart || lastDot == input.length() - 1) {
            return -1;
        }
        return lastDot;
    }
}
