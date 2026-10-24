package dhj.embeddedt.embeddium.api.render.chunk;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.jetbrains.annotations.NotNull;

/**
 * A provider for per-section chunk animations.
 *
 * <p>Allows external mods (e.g. Chunk Animator) to apply per-section translations to the terrain
 * renderer, mirroring the per-RenderChunk {@code glTranslate} offset that vanilla chunk rendering
 * supports. A provider is queried on the render thread for every visible section on every render
 * pass; when it reports an offset, the section is removed from its region's multi-draw batch and
 * drawn separately with the offset added to the region model matrix.</p>
 *
 * <p>The vanilla equivalent is the Chunk Animator hook on {@code ChunkRenderContainer#preRenderChunk}
 * plus {@code RenderChunk#setOrigin}: this interface adapts both to the celeritas pipeline, where
 * vanilla {@code RenderChunk} objects are not created or drawn.</p>
 */
public interface ChunkAnimationProvider {
    /**
     * Called on the render thread when a render section is created (i.e. enters the render
     * distance). Implementations may record the animation start time here; the section itself
     * serves as the animation identity token.
     */
    default void onSectionAdded(@NotNull RenderSection section) {
    }

    /**
     * Queries the animation offset for a section on the render thread.
     *
     * @param out     a 3-element array receiving the offset in world-space blocks; only written
     *                when this method returns {@code true}
     * @return {@code true} if the section currently has an animation offset and should be drawn
     *         separately, {@code false} if it should be drawn as part of the region batch
     */
    boolean getSectionOffset(@NotNull RenderSection section, @NotNull float[] out);
}
