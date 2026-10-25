package com.dhj.actinium.api.render.terrain;

import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry for {@link BlockQuadTransformer} instances used during chunk building.
 *
 * <p>Transformers are kept in a {@link CopyOnWriteArrayList} so multiple chunk
 * builder threads can read a stable registration snapshot while resource
 * reloads register or unregister addon hooks.</p>
 */
public final class BlockQuadTransformerHolder {
    /** Logger used when a registered transformer violates its contract or fails. */
    private static final Logger LOGGER = LogManager.getLogger("Actinium BlockQuadTransformer");

    /** Registered transformers in the order they should be applied. */
    private static final CopyOnWriteArrayList<BlockQuadTransformer> TRANSFORMERS =
            new CopyOnWriteArrayList<>();

    private BlockQuadTransformerHolder() {
    }

    /**
     * Registers a transformer at the end of the current registration order.
     *
     * @throws IllegalArgumentException if the transformer is null or the same
     *         instance is already registered
     */
    public static void register(BlockQuadTransformer transformer) {
        if (transformer == null) {
            throw new IllegalArgumentException("Block quad transformer must not be null");
        }
        if (TRANSFORMERS.stream().anyMatch(registered -> registered == transformer)) {
            throw new IllegalArgumentException(
                    "Block quad transformer is already registered: "
                            + transformer.getClass().getName());
        }
        TRANSFORMERS.add(transformer);
    }

    /**
     * Removes a previously registered transformer by instance identity.
     *
     * @throws IllegalArgumentException if the transformer is null
     */
    public static boolean unregister(BlockQuadTransformer transformer) {
        if (transformer == null) {
            throw new IllegalArgumentException("Block quad transformer must not be null");
        }
        return TRANSFORMERS.removeIf(registered -> registered == transformer);
    }

    /**
     * Applies all registered transformers in registration order.
     *
     * <p>When no transformer is registered, the original list is returned.
     * A transformer that returns {@code null} is treated as a contract
     * violation, logged, and rejected with {@link IllegalStateException}.
     * An empty result short-circuits the remaining transformers and is
     * returned immediately. A transformer that throws {@link RuntimeException}
     * is logged and rethrown.</p>
     *
     * @param blockAccess thread-safe access to the block state and neighbors
     * @param side the face these quads belong to, or {@code null} for unassigned quads
     */
    public static List<BakedQuad> transform(IBlockState state, BlockPos pos,
                                            ActiniumBlockAccess blockAccess,
                                            BlockRenderLayer layer, EnumFacing side,
                                            List<BakedQuad> quads) {
        if (TRANSFORMERS.isEmpty()) {
            return quads;
        }

        List<BakedQuad> result = quads;
        for (BlockQuadTransformer transformer : TRANSFORMERS) {
            try {
                result = transformer.transform(state, pos, blockAccess, layer, side, result);
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Block quad transformer '{}' failed for side '{}' at '{}'",
                        transformer.getClass().getName(), side, pos, exception);
                throw exception;
            }

            if (result == null) {
                IllegalStateException violation = new IllegalStateException(
                        "Block quad transformer returned null: "
                                + transformer.getClass().getName());
                LOGGER.error(
                        "Block quad transformer '{}' returned null for side '{}' at '{}'",
                        transformer.getClass().getName(), side, pos, violation);
                throw violation;
            }

            if (result.isEmpty()) {
                return result;
            }
        }
        return result;
    }
}
