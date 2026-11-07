package net.coderbot.iris.texture;

/**
 * Sprite whose animation frame is advanced by a gated atlas animation pass.
 *
 * <p>The shader module needs this contract because it owns the PBR atlas animation loop, while the
 * sprite mixin that provides it lives in the host mod. Implementing it lets the pass decide per
 * sprite whether the new frame is uploaded or only advanced.
 */
public interface SpriteAnimationTicker {
    /**
     * Advances one animation tick.
     *
     * @param onlyVisible when true, sprites that were not marked visible skip the upload and only
     *                    advance their frame counters
     * @return true when the new frame was uploaded, false when the tick was a dry run
     */
    boolean angelica$tickAnimation(boolean onlyVisible);
}
