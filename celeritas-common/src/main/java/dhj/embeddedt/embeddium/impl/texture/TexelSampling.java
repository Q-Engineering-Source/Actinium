package dhj.embeddedt.embeddium.impl.texture;

import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import dhj.embeddedt.embeddium.impl.gui.options.TextProvider;

/**
 * Terrain sampler min/mag filter.
 */
public enum TexelSampling implements TextProvider {
    LINEAR("sodium.options.texel_sampling.linear"),
    NEAREST("sodium.options.texel_sampling.nearest");

    private final TextComponent name;

    TexelSampling(String translationKey) {
        this.name = TextComponent.translatable(translationKey);
    }

    @Override
    public TextComponent getLocalizedName() {
        return this.name;
    }

    public boolean isNearest() {
        return this == NEAREST;
    }
}
