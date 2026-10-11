package dhj.embeddedt.embeddium.impl.texture;

/**
 * Sprite-side half of {@link SpritePadding}.
 *
 * <p>The gutter is decided by the atlas that owns the sprite and then read by every other atlas that mirrors
 * the same layout, including the PBR atlases that live in a different module, so it is declared here rather
 * than on the vanilla-side sprite extension.
 */
public interface SpritePaddingExtension {

    /** Width of the replicated-edge border reserved around this sprite in its stitcher slot. */
    int angelica$getGutterWidth();

    void angelica$setGutterWidth(int gutter);
}
