package com.dhj.actinium.texture;

import com.gtnewhorizons.angelica.mixins.interfaces.IPatchedTextureAtlasSprite;
import net.coderbot.iris.texture.pbr.PBRSpriteHolder;
import net.coderbot.iris.texture.pbr.TextureAtlasSpriteExtension;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Carries a base sprite's visibility mark over to the PBR sprites generated from it.
 *
 * <p>Normal and specular sprites are sampled through the base texture's UVs, so nothing ever marks
 * them visible on their own. Their mark is set when the base sprite consumes one, which keeps the
 * PBR frame counters in step with the base animation while hidden sprites skip their upload.
 */
public final class PBRAnimationMarks {

    private PBRAnimationMarks() {
    }

    /**
     * Marks the PBR sprites held by the given base sprite, if it has any.
     */
    public static void markFromBaseSprite(TextureAtlasSprite baseSprite) {
        if (!(baseSprite instanceof TextureAtlasSpriteExtension extension)) {
            return;
        }

        final PBRSpriteHolder pbrHolder = extension.getPBRHolder();
        if (pbrHolder == null) {
            return;
        }

        markSibling(pbrHolder.getNormalSprite());
        markSibling(pbrHolder.getSpecularSprite());
    }

    private static void markSibling(TextureAtlasSprite sibling) {
        if (sibling != null) {
            ((IPatchedTextureAtlasSprite) sibling).angelica$markNeedsAnimationUpdate();
        }
    }
}
