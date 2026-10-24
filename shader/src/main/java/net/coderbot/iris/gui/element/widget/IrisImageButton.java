package net.coderbot.iris.gui.element.widget;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.ResourceLocation;

import java.util.function.Consumer;

public class IrisImageButton extends IrisButton {
    private final ResourceLocation textureLocation;
    private final int xTexStart;
    private final int yTexStart;
    private final int yDiffTex;

    public IrisImageButton(int x, int y, int width, int height, int xTexStart, int yTexStart, int yDiffTex, ResourceLocation resourceLocation,Consumer<IrisButton> onPress) {
        super(x, y, width, height, "", onPress);

        this.textureLocation = resourceLocation;
        this.xTexStart = xTexStart;
        this.yTexStart = yTexStart;
        this.yDiffTex = yDiffTex;
    }


    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if(!this.visible) {
            return;
        }

        int yTex = this.yTexStart;
        this.hovered = mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;
        if(this.getHoverState(this.hovered) == 2) {
            yTex += this.yDiffTex;
        }

        GLStateManager.glBlendColor(1.0f, 1.0f, 1.0f, 1.0f);
        GLStateManager.enableBlend();

        GLStateManager.enableTexture();
        mc.getTextureManager().bindTexture(this.textureLocation);

        Gui.drawModalRectWithCustomSizedTexture(this.x, this.y, this.xTexStart, yTex, width, height, 256, 256);

    }

}
