package dhj.embeddedt.embeddium.impl.texture;

import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import dhj.embeddedt.embeddium.impl.gui.options.TextProvider;

/**
 * How the block atlas is filtered when terrain is sampled at a distance.
 *
 * <p>{@code RGSS} modes hand sampling to the terrain fragment shader, which takes four rotated-grid
 * taps per fragment and switches back to plain texel-snapped sampling while a texel is still larger
 * than a pixel. {@code ANISOTROPIC} keeps the plain shader path and asks the terrain sampler for an
 * anisotropic footprint instead, which the shader bounds against the atlas gutter so the taps cannot
 * cross into the neighbouring sprite. The two coincide in the combined mode.
 */
public enum TextureFilterMode implements TextProvider {
    RGSS("sodium.options.texture_filtering.rgss"),
    ANISOTROPIC("sodium.options.texture_filtering.anisotropic"),
    RGSS_ANISOTROPIC("sodium.options.texture_filtering.rgss_anisotropic"),
    NONE("sodium.options.texture_filtering.none");

    private static final TextureFilterMode[] WITHOUT_ANISOTROPY = {RGSS, NONE};

    private final TextComponent name;

    TextureFilterMode(String translationKey) {
        this.name = TextComponent.translatable(translationKey);
    }

    /**
     * Anisotropic filtering rides on an extension that isn't guaranteed in GL 3.3, so the modes that
     * use it are only offered when the driver actually exposes it.
     */
    public static TextureFilterMode[] selectableValues(boolean anisotropySupported) {
        return anisotropySupported ? values() : WITHOUT_ANISOTROPY;
    }

    @Override
    public TextComponent getLocalizedName() {
        return this.name;
    }

    /** Whether the terrain fragment shader should take the rotated-grid supersampling path. */
    public boolean usesRgss() {
        return this == RGSS || this == RGSS_ANISOTROPIC;
    }

    public boolean usesAnisotropy() {
        return this == ANISOTROPIC || this == RGSS_ANISOTROPIC;
    }
}
