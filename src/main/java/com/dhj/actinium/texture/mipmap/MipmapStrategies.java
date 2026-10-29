package com.dhj.actinium.texture.mipmap;

import java.util.List;

/**
 * Fallback {@link MipmapStrategy} assignments for sprites that carry no metadata.
 *
 * <p>Upstream Angelica derives these from the block class that registered the sprite, which it can do
 * because 1.7.10 registers block icons through {@code Block.registerBlockIcons} and its {@code TextureMap}
 * implements {@code IIconRegister}. That whole mechanism is gone from 1.12.2: the interface and the method
 * were removed when block models took over in 1.8, and {@code TextureMap} no longer even carries a
 * {@code textureType}, so there is no point at which a sprite can be attributed to a block class.
 *
 * <p>What survives is the naming family each of upstream's rules was written for: vanilla block sprites
 * live under {@code blocks/} and are named after the block family, so the same five rules are expressed as
 * path markers instead. Anything a resource pack wants to override it can say so per texture through the
 * {@code mipmap_strategy} key of the sprite's {@code .mcmeta} file.
 */
public final class MipmapStrategies {

    private static final String BLOCK_PATH_PREFIX = "blocks/";

    /**
     * One fallback rule: a sprite whose path contains {@code marker} gets {@code strategy}.
     *
     * <p>Each marker is the name family of one of upstream's block-class rules.
     */
    private record Rule(String marker, MipmapStrategy strategy) {}

    private static final List<Rule> RULES = List.of(
        // BlockLeaves
        new Rule("leaves", MipmapStrategy.DARK_CUTOUT),
        // BlockFlower
        new Rule("flower", MipmapStrategy.STRICT_CUTOUT),
        // BlockMushroom
        new Rule("mushroom", MipmapStrategy.STRICT_CUTOUT),
        // BlockGlass
        new Rule("glass", MipmapStrategy.MEAN),
        // BlockRedstoneWire
        new Rule("redstone_dust", MipmapStrategy.MEAN)
    );

    private MipmapStrategies() {
    }

    /**
     * The fallback strategy for a sprite with no declared one.
     *
     * <p>Block and item sprites share a single atlas in 1.12.2, so the rules are scoped to the
     * {@code blocks/} path: an item sprite such as {@code items/glass_bottle} must not be caught by the
     * glass rule.
     *
     * @return the highest-precedence matching rule's strategy, or null to let the generator resolve
     *         {@link MipmapStrategy#AUTO}
     */
    public static MipmapStrategy strategyFor(String iconName) {
        if (iconName == null) {
            return null;
        }

        final String path = iconName.substring(iconName.indexOf(':') + 1);
        if (!path.startsWith(BLOCK_PATH_PREFIX)) {
            return null;
        }

        MipmapStrategy best = null;
        for (Rule rule : RULES) {
            if (!path.contains(rule.marker())) {
                continue;
            }
            if (best == null || rule.strategy().conflictPrecedence() > best.conflictPrecedence()) {
                best = rule.strategy();
            }
        }
        return best;
    }
}
