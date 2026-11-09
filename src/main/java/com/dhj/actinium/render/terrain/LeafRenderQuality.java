package com.dhj.actinium.render.terrain;

import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions.GraphicsQuality;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.init.Blocks;

/**
 * Applies the quality page's graphics defaults to the vanilla renderer state.
 *
 * <p>Vanilla's {@code RenderGlobal.loadRenderers()} only feeds {@code fancyGraphics} into
 * {@code BlockLeaves.setGraphicsLevel}, so a per-option choice has to be written after that call.
 * {@code DEFAULT} keeps the vanilla result, while {@code FANCY} and {@code FAST} force the
 * corresponding render layer regardless of the graphics option.</p>
 */
public final class LeafRenderQuality {
    private LeafRenderQuality() {
    }

    /**
     * Pins the vanilla graphics option to fancy so the renderer initialises every quality effect on
     * the fancy path.
     *
     * @param gameSettings the live vanilla game settings
     */
    public static void pinFancyGraphics(GameSettings gameSettings) {
        gameSettings.fancyGraphics = true;
    }

    /**
     * Writes the leaves render level for both vanilla leaf blocks.
     *
     * @param leavesQuality the selected leaves quality
     * @param fancyGraphics the live vanilla graphics option
     */
    public static void apply(GraphicsQuality leavesQuality, boolean fancyGraphics) {
        final boolean fancy = leavesQuality.isFancy(fancyGraphics);
        Blocks.LEAVES.setGraphicsLevel(fancy);
        Blocks.LEAVES2.setGraphicsLevel(fancy);
    }
}
