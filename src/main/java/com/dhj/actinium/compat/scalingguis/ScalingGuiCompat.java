package com.dhj.actinium.compat.scalingguis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import spazley.scalingguis.gui.guiconfig.GuiConfigSG;

public final class ScalingGuiCompat {
    private ScalingGuiCompat() {
    }

    public static void openConfigScreen(GuiScreen parentScreen) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiConfigSG(parentScreen, GuiConfigSG.MAIN_ID));
    }
}
