package com.dhj.actinium.compat.fontrenderer;

import com.gtnewhorizon.gtnhlib.compat.Mods;
import net.minecraft.client.gui.FontRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Compatibility policy for deciding whether Actinium's font batcher can handle a renderer. */
public final class FontBatcherCompat {
    /** Logger for startup diagnostics about font renderer compatibility. */
    private static final Logger LOGGER = LogManager.getLogger("Actinium");
    /** Startup option that disables the batcher independently of installed mods. */
    private static final boolean DISABLED_BY_PROPERTY = Boolean.getBoolean("actinium.disableFontBatcher");
    /** Cached presence of NeoFontRender, whose renderer owns the glyph path. */
    private static final boolean NEO_FONT_RENDER_LOADED = resolveNeoFontRenderLoaded();
    /**
     * Cached presence of Goslingcord/Emojicord, which rewrites the vanilla {@link FontRenderer}
     * itself (emoji placeholder replacement in {@code renderStringAtPos}/{@code renderChar}), so
     * no renderer class name can be matched and the batcher must stand down globally.
     */
    private static final boolean EMOJICORD_LOADED = resolveEmojicordLoaded();

    /** Prevents instances because this class only exposes compatibility policy. */
    private FontBatcherCompat() {
    }

    /**
     * Returns whether the batcher must stand down for this renderer, including renderers with
     * mod-specific glyph logic that Actinium's batch path would bypass.
     */
    public static boolean isBatcherDisabledFor(Class<?> rendererType) {
        if (DISABLED_BY_PROPERTY || NEO_FONT_RENDER_LOADED || EMOJICORD_LOADED) {
            return true;
        }
        if (!Mods.DRAGONCORE) {
            return false;
        }

        return rendererType.getName().startsWith("eos.moe.dragoncore.")
            && FontRenderer.class.isAssignableFrom(rendererType);
    }

    /** Reads NeoFontRender presence once and records it when font diagnostics are enabled. */
    private static boolean resolveNeoFontRenderLoaded() {
        final boolean loaded = Mods.NEOFONTRENDER;
        if (Boolean.getBoolean("actinium.fontDebug")) {
            LOGGER.info("font-batcher-check neofontrender={} renderer={}", loaded, FontRenderer.class.getName());
        }
        return loaded;
    }

    /** Reads Goslingcord/Emojicord presence once and records it when font diagnostics are enabled. */
    private static boolean resolveEmojicordLoaded() {
        final boolean loaded = Mods.EMOJICORD;
        if (Boolean.getBoolean("actinium.fontDebug")) {
            LOGGER.info("font-batcher-check emojicord={} renderer={}", loaded, FontRenderer.class.getName());
        }
        return loaded;
    }
}
