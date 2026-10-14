package com.dhj.actinium.gui;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.texture.atlas.AtlasPackPolicy;
import net.coderbot.iris.gui.element.IrisGuiSlot;
import net.coderbot.iris.gui.element.widget.IrisButton;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

/**
 * Lists the resource pack classes the atlas loader has seen, so the player can decide which of them may be
 * read on worker threads. Every change is written to the options on close.
 */
public class AtlasPackScreen extends GuiScreen {

    private static final String ENABLED_PREFIX = TextFormatting.GREEN + "[x] " + TextFormatting.WHITE;
    private static final String DISABLED_PREFIX = TextFormatting.DARK_GRAY + "[ ] " + TextFormatting.GRAY;

    private final GuiScreen parent;
    private final List<String> packs;
    private boolean dirty;

    private PackRowList rowList;

    public AtlasPackScreen(GuiScreen parent) {
        this.parent = parent;
        this.packs = AtlasPackPolicy.knownPackClasses();
    }

    @Override
    public void initGui() {
        this.rowList = new PackRowList();
        this.buttonList.add(new IrisButton(this.width / 2 - 75, this.height - 27, 150, 20,
            I18n.format("gui.done"), button -> this.onClose()));
    }

    private void onClose() {
        if (this.dirty) {
            ActiniumRuntime.options().save();
            this.dirty = false;
        }
        this.mc.displayGuiScreen(this.parent);
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.enabled && guiButton instanceof IrisButton irisButton) {
            irisButton.onPress();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.rowList.drawScreen(mouseX, mouseY, partialTicks);
        super.drawScreen(mouseX, mouseY, partialTicks);

        this.drawCenteredString(this.fontRenderer, I18n.format("sodium.options.atlas_packs.title"), this.width / 2, 8, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer, I18n.format("sodium.options.atlas_packs.info"), this.width / 2, 20, 0xA0A0A0);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (!this.rowList.mouseClicked(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (!this.rowList.mouseReleased(mouseX, mouseY, state)) {
            super.mouseReleased(mouseX, mouseY, state);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.onClose();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();

        final int wheelDelta = Mouse.getEventDWheel();
        if (wheelDelta == 0) {
            return;
        }

        final int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        final int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        this.rowList.mouseScrolled(mouseX, mouseY, wheelDelta);
    }

    private class PackRowList extends IrisGuiSlot {

        PackRowList() {
            super(AtlasPackScreen.this.mc, AtlasPackScreen.this.width, AtlasPackScreen.this.height, 36,
                AtlasPackScreen.this.height - 44, 18);
            this.setRenderBackground(false);
        }

        @Override
        protected int getSize() {
            return packs.size();
        }

        @Override
        protected boolean isSelected(int index) {
            return false;
        }

        @Override
        protected void drawBackground() {
        }

        @Override
        public int getListWidth() {
            return Math.min(400, AtlasPackScreen.this.width - 40);
        }

        @Override
        protected void elementClicked(int index, boolean doubleClick, int mouseX, int mouseY) {
        }

        @Override
        protected boolean elementClicked(int index, boolean doubleClick, int mouseX, int mouseY, int button) {
            if (button != 0 || index < 0 || index >= packs.size()) {
                return false;
            }

            final String name = packs.get(index);
            AtlasPackPolicy.setTrusted(name, !AtlasPackPolicy.isTrusted(name));
            dirty = true;
            return true;
        }

        @Override
        protected void drawSlot(int index, int x, int y, int slotHeight, int mouseX, int mouseY, float partialTicks) {
            final String name = packs.get(index);
            final boolean trusted = AtlasPackPolicy.isTrusted(name);

            final int simpleStart = name.lastIndexOf('.') + 1;
            final String packageName = simpleStart > 0 ? name.substring(0, simpleStart - 1) : "";
            String simpleName = name.substring(simpleStart);
            final int innerStart = simpleName.lastIndexOf('$') + 1;
            if (innerStart > 0) {
                simpleName = simpleName.substring(innerStart);
            }

            final String label = (trusted ? ENABLED_PREFIX : DISABLED_PREFIX) + simpleName
                + TextFormatting.DARK_GRAY + " (" + packageName + ")";

            drawString(fontRenderer, fontRenderer.trimStringToWidth(label, this.getListWidth() - 8), x, y + 5, 0xFFFFFF);
        }
    }
}
