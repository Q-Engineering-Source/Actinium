package net.coderbot.iris.texture;

/**
 * Reports whether the current animation frame advances only the sprites that were marked visible.
 *
 * <p>The PBR atlas runs its own animation pass after the base sprite pass and has to apply the same
 * decision. The owner of the sprite atlas resolves the setting once per frame and exposes it here, so
 * both passes agree and the shader module never depends on the host's configuration.
 */
public interface SpriteAnimationGate {
    boolean isAnimatingOnlyVisibleSprites();
}
