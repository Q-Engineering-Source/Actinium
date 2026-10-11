package dhj.embeddedt.embeddium.impl.texture;

import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import dhj.embeddedt.embeddium.impl.gui.options.TextProvider;

/**
 * How the block atlas is filtered when terrain is sampled at a distance.
 *
 * <p>Upstream also offers rotated-grid supersampling modes. Those drive a fragment shader branch that
 * reads a per-material mip bias, which Actinium's vendored terrain pipeline does not carry, so only the
 * modes Actinium can actually honour are listed here.
 */
public enum TextureFilterMode implements TextProvider {
    ANISOTROPIC("sodium.options.texture_filtering.anisotropic"),
    NONE("sodium.options.texture_filtering.none");

    private static final TextureFilterMode[] WITHOUT_ANISOTROPY = {NONE};

    private final TextComponent name;

    TextureFilterMode(String translationKey) {
        this.name = TextComponent.translatable(translationKey);
    }

    /**
     * Anisotropic filtering rides on an extension that isn't guaranteed in GL 3.3, so the mode that uses it
     * is only offered when the driver actually exposes it.
     */
    public static TextureFilterMode[] selectableValues(boolean anisotropySupported) {
        return anisotropySupported ? values() : WITHOUT_ANISOTROPY;
    }

    @Override
    public TextComponent getLocalizedName() {
        return this.name;
    }

    public boolean usesAnisotropy() {
        return this == ANISOTROPIC;
    }
}
