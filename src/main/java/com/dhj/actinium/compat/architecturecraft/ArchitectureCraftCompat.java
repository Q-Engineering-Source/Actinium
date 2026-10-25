package com.dhj.actinium.compat.architecturecraft;

import com.gtnewhorizon.gtnhlib.compat.Mods;
import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;

/**
 * Compatibility for ArchitectureCraft (mod id {@code architecturecraft}); fixes #101.
 *
 * <p>ArchitectureCraft's blockstates only publish a {@code normal} variant, so every
 * {@code facing=*} state resolves to Forge's {@code FancyMissingModel} and the real geometry is
 * emitted by the {@code BlockRendererDispatcher} replacement its {@code CustomBlockDispatcher
 * .inject()} installs. The vanilla chunk rebuild calls that entry point and the blocks stay
 * visible; Actinium's fast chunk path reads {@code BlockModelShapes.getModelForState} directly,
 * hits the missing model and skips the block. ArchitectureCraft blocks are therefore forced down
 * the vanilla dispatcher fallback, whose {@code RenderTargetWorld} target writes into a
 * {@code BufferBuilder} without touching GL, which keeps it safe on mesh worker threads.</p>
 */
public final class ArchitectureCraftCompat {
    /**
     * The mod ID used by both maintained ArchitectureCraft releases.
     */
    public static final String MODID = "architecturecraft";
    public static final boolean IS_LOADED = Mods.ARCHITECTURECRAFT;

    private ArchitectureCraftCompat() {
    }

    /**
     * Returns whether {@code block} belongs to ArchitectureCraft and must be rendered through the
     * vanilla {@code BlockRendererDispatcher}, which ArchitectureCraft replaces with its own
     * dispatcher providing the real shape geometry.
     */
    public static boolean shouldForceVanillaRender(Block block) {
        if (!IS_LOADED) {
            return false;
        }
        return ArchitectureCraftRenderRouting.isArchitectureCraftNamespace(block.getRegistryName());
    }
}
