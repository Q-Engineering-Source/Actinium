package com.dhj.actinium.mixin.vintage.gui;

import com.gtnewhorizons.angelica.iris.ShaderWarmup;
import com.gtnewhorizons.angelica.render.PanoramaRenderer;
import net.coderbot.iris.Iris;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiMainMenu.class)
public abstract class MixinGuiMainMenu extends GuiScreen {
    @Shadow
    private float panoramaTimer;

    /**
     * The title screen is the first place the player sits still long enough to be worth reading the selected
     * shader pack on a background thread.
     */
    @Inject(method = "initGui", at = @At("HEAD"))
    private void actinium$readShaderpackAhead(CallbackInfo ci) {
        if (Iris.enabled) {
            ShaderWarmup.start();
        }
    }

    /**
     * The panorama paths rendered on the title screen. Read at render time instead of
     * hard-coding the vanilla paths so mods that replace this field (e.g. Biomes O'
     * Plenty swaps it for its own panorama textures, issue #32) keep their title
     * screen background under the GTNH/Angelica skybox renderer.
     */
    @Shadow
    private static ResourceLocation[] TITLE_PANORAMA_PATHS;

    /**
     * @author Actinium
     * @reason Replace the vanilla panorama path with the GTNH/Angelica implementation.
     */
    @Overwrite
    public void renderSkybox(int mouseX, int mouseY, float partialTicks) {
        PanoramaRenderer.getInstance()
            .renderSkybox(
                (int) this.panoramaTimer,
                partialTicks,
                TITLE_PANORAMA_PATHS,
                this.mc,
                this.width,
                this.height,
                this.zLevel
            );
    }
}