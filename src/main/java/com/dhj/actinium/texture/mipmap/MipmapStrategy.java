package com.dhj.actinium.texture.mipmap;

/**
 * How an atlas sprite's mip levels are built.
 *
 * <p>The modes differ in what they do about the fully transparent texels that surround a cutout shape.
 * Plain box filtering turns those into a faint halo of the shape's colour, which is why the cutout modes
 * exist at all.
 */
public enum MipmapStrategy {
    /** Let {@link MipmapGenerator} pick {@link #CUTOUT} or {@link #MEAN} from whether the sprite has any fully transparent texel. */
    AUTO,
    /** Plain alpha-weighted box filtering. Right for sprites that are opaque or genuinely translucent. */
    MEAN,
    /**
     * Box filtering, followed by a per-level rescale of the alpha channel so each level keeps the fraction of
     * the sprite that survives the 0.5-alpha test at the base level. Keeps a cutout silhouette from thinning
     * out as the level shrinks.
     */
    CUTOUT,
    /** {@link #CUTOUT} judging coverage against a lower alpha reference of 0.3, which suits thin shapes such as flowers. */
    STRICT_CUTOUT,
    /**
     * Transparent texels are repainted with three quarters of the sprite's darkest opaque colour before
     * downsampling, so the halo takes that colour instead of the shape's own. Meant for foliage, where the
     * halo otherwise reads as a bright rim.
     */
    DARK_CUTOUT;

    private static final MipmapStrategy[] VALUES = values();

    /** Parses the name used by a sprite's {@code mipmap_strategy} mcmeta key. Returns null when it is not a known mode. */
    public static MipmapStrategy byName(String name) {
        if (name == null) {
            return null;
        }
        for (MipmapStrategy strategy : VALUES) {
            if (strategy.name().equalsIgnoreCase(name)) {
                return strategy;
            }
        }
        return null;
    }

    /**
     * Which mode wins when more than one rule claims the same sprite. Meant to mirror upstream Angelica's
     * tie-break, where the mode that changes the image the most is the one that carries an explicit claim.
     */
    public int conflictPrecedence() {
        return switch (this) {
            case MEAN -> 4;
            case DARK_CUTOUT -> 3;
            case STRICT_CUTOUT -> 2;
            case CUTOUT -> 1;
            case AUTO -> 0;
        };
    }
}
