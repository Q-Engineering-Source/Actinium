package com.dhj.actinium.rendering;

import com.dhj.actinium.runtime.ActiniumRuntime;
import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which blocks the deferred meshing path may build on a build thread.
 *
 * <p>Actinium routes every block through the baked-model or the fast block renderer, both of which
 * already run on build threads, so a block keeps its off-thread build unless the player listed it.
 * Listing a block moves its geometry to the render-thread capture, which is what a block needs when
 * it reaches render state that only exists on the render thread. The list is a local player
 * criterion: no mod registration and no renderer annotation is involved.</p>
 *
 * <p>The list lives in {@code performance.deferred_block_ids} of the Actinium options file and is
 * edited through the deferred blocks screen of the video options.</p>
 */
public final class BlockRenderSafetyRegistry {
    private static final Logger LOGGER = LogManager.getLogger("Actinium/BlockRenderSafetyRegistry");

    /** Separators accepted between two block ids in the options screen. */
    private static final Pattern BLOCK_ID_SEPARATOR = Pattern.compile("[,\\s]+");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    private static volatile ResolvedBlockIds resolved = new ResolvedBlockIds(List.of(), Set.of());

    private BlockRenderSafetyRegistry() {
    }

    /**
     * {@return whether {@code block} may be meshed on a build thread}, which is the default for every
     * block the player did not list
     */
    public static boolean canRenderOffThread(Block block) {
        Set<ResourceLocation> deferred = resolvedBlockIds();

        if (deferred.isEmpty()) {
            return true;
        }

        ResourceLocation name = block.getRegistryName();
        return name == null || !deferred.contains(name);
    }

    /** {@return a copy of the configured block ids, in file order} */
    public static List<String> configuredBlockIds() {
        return List.copyOf(ActiniumRuntime.options().performance.deferredBlockIds);
    }

    /**
     * {@return whether any block is configured for the render-thread capture}, which lets the mesher
     * skip its section prescan while the list is empty
     */
    public static boolean hasDeferredBlocks() {
        return !resolvedBlockIds().isEmpty();
    }

    /** {@return the configured block ids as one editable line for the options screen} */
    public static String formatBlockIds(List<String> blockIds) {
        return String.join(", ", blockIds);
    }

    /**
     * Parses the edited block id text: ids are separated by commas or whitespace, duplicates keep
     * their first position, and anything that is not {@code namespace:block} is rejected.
     *
     * @param text the edited line, blank when no block is configured
     * @return the validated ids in the order they were entered
     * @throws IllegalArgumentException when a token is not a valid block id
     */
    public static List<String> parseBlockIds(@Nullable String text) {
        if (text == null) {
            throw new IllegalArgumentException("Block id list must not be null");
        }

        LinkedHashSet<String> blockIds = new LinkedHashSet<>();

        for (String token : BLOCK_ID_SEPARATOR.split(text)) {
            if (token.isEmpty()) {
                continue;
            }

            validateBlockId(token);
            blockIds.add(token);
        }

        return new ArrayList<>(blockIds);
    }

    /**
     * {@return the configured ids as lookup keys}. The set is rebuilt only when the configuration
     * hands out a different list instance, which is what the options screen does when it saves, so the
     * mesher never formats a registry name string or scans the list per block.
     */
    private static Set<ResourceLocation> resolvedBlockIds() {
        List<String> configured = ActiniumRuntime.options().performance.deferredBlockIds;
        ResolvedBlockIds current = resolved;

        if (current.source() == configured && current.source().size() == configured.size()) {
            return current.ids();
        }

        Set<ResourceLocation> ids = new HashSet<>();

        for (String blockId : configured) {
            String[] parts = splitBlockId(blockId);

            if (parts != null) {
                ids.add(new ResourceLocation(parts[0], parts[1]));
            } else {
                LOGGER.warn("Ignoring invalid deferred block id '{}', expected namespace:block", blockId);
            }
        }

        Set<ResourceLocation> immutable = Set.copyOf(ids);
        resolved = new ResolvedBlockIds(configured, immutable);
        return immutable;
    }

    /**
     * Splits a configured id into its namespace and path.
     *
     * @return the two parts, or {@code null} when a hand-edited id is not {@code namespace:block}
     */
    @Nullable
    private static String[] splitBlockId(String blockId) {
        int separator = blockId.indexOf(':');

        if (separator <= 0 || separator != blockId.lastIndexOf(':') || separator == blockId.length() - 1) {
            return null;
        }

        return new String[] { blockId.substring(0, separator), blockId.substring(separator + 1) };
    }

    private static void validateBlockId(String blockId) {
        String[] parts = splitBlockId(blockId);
        boolean valid = parts != null
                && NAMESPACE.matcher(parts[0]).matches()
                && PATH.matcher(parts[1]).matches();

        if (!valid) {
            throw new IllegalArgumentException("Invalid block id '" + blockId + "', expected namespace:block");
        }
    }

    /** {@return the lookup view of one configuration list; the same list instance keeps the same keys} */
    private record ResolvedBlockIds(List<String> source, Set<ResourceLocation> ids) {
    }
}
