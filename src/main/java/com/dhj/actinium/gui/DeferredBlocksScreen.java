package com.dhj.actinium.gui;

import com.dhj.actinium.rendering.BlockRenderSafetyRegistry;
import com.dhj.actinium.runtime.ActiniumRuntime;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Edits the block ids whose geometry must be captured on the render thread.
 *
 * <p>The list is a plain text line of {@code namespace:block} ids separated by commas or whitespace.
 * Saving validates the line, writes the configuration and reloads the chunk renderers so the new
 * criterion applies to the next rebuild; cancelling leaves the configuration untouched.</p>
 */
public final class DeferredBlocksScreen extends GuiScreen {
    private static final int SAVE_BUTTON_ID = 0;
    private static final int CANCEL_BUTTON_ID = 1;

    private static final int FIELD_MAX_LENGTH = 4096;
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_MAX_WIDTH = 400;
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;

    private final GuiScreen parent;

    private GuiTextField blockIdsField;
    @Nullable
    private String draft;
    @Nullable
    private String error;

    public DeferredBlocksScreen(GuiScreen parent) {
        this.parent = parent;
    }

    /** Opens the editor on top of {@code parent}. */
    public static void open(GuiScreen parent) {
        Minecraft.getMinecraft().displayGuiScreen(new DeferredBlocksScreen(parent));
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();

        int fieldWidth = Math.min(FIELD_MAX_WIDTH, this.width - 40);
        this.blockIdsField = new GuiTextField(0, this.fontRenderer,
                (this.width - fieldWidth) / 2, this.height / 2 - 30, fieldWidth, FIELD_HEIGHT);
        this.blockIdsField.setMaxStringLength(FIELD_MAX_LENGTH);
        // A resize recreates this screen, so unsaved edits come from the draft instead of the file.
        this.blockIdsField.setText(this.draft != null ? this.draft
                : BlockRenderSafetyRegistry.formatBlockIds(BlockRenderSafetyRegistry.configuredBlockIds()));
        this.blockIdsField.setFocused(true);

        int buttonY = this.height / 2 + 40;
        this.buttonList.add(new GuiButton(SAVE_BUTTON_ID, this.width / 2 - BUTTON_WIDTH - BUTTON_GAP, buttonY,
                BUTTON_WIDTH, BUTTON_HEIGHT, I18n.format("gui.actinium.deferred_blocks.save")));
        this.buttonList.add(new GuiButton(CANCEL_BUTTON_ID, this.width / 2 + BUTTON_GAP, buttonY,
                BUTTON_WIDTH, BUTTON_HEIGHT, I18n.format("gui.actinium.deferred_blocks.cancel")));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int centerX = this.width / 2;
        int titleY = this.height / 2 - 84;

        this.drawCenteredString(this.fontRenderer, I18n.format("gui.actinium.deferred_blocks.title"), centerX, titleY, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer, I18n.format("gui.actinium.deferred_blocks.description"), centerX, titleY + 20, 0xA0A0A0);
        this.drawCenteredString(this.fontRenderer, I18n.format("gui.actinium.deferred_blocks.hint"), centerX, titleY + 34, 0xA0A0A0);

        this.blockIdsField.drawTextBox();

        if (this.error != null) {
            this.drawCenteredString(this.fontRenderer, this.error, centerX, this.height / 2 + 70, 0xFF5555);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.blockIdsField.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.cancel();
            return;
        }

        if (this.blockIdsField.textboxKeyTyped(typedChar, keyCode)) {
            this.draft = this.blockIdsField.getText();
            this.error = null;
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.blockIdsField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == SAVE_BUTTON_ID) {
            this.save();
        } else if (button.id == CANCEL_BUTTON_ID) {
            this.cancel();
        } else {
            super.actionPerformed(button);
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private void save() {
        List<String> blockIds;

        try {
            blockIds = BlockRenderSafetyRegistry.parseBlockIds(this.blockIdsField.getText());
        } catch (IllegalArgumentException invalid) {
            this.error = I18n.format("gui.actinium.deferred_blocks.invalid", invalid.getMessage());
            return;
        }

        this.error = null;

        SodiumGameOptions options = ActiniumRuntime.options();

        if (!blockIds.equals(options.performance.deferredBlockIds)) {
            options.performance.deferredBlockIds = new ArrayList<>(blockIds);
            options.save();

            Minecraft minecraft = Minecraft.getMinecraft();

            // The video options screen is reachable from the main menu, where no chunk renderer exists.
            if (minecraft.renderGlobal != null) {
                minecraft.renderGlobal.loadRenderers();
            }
        }

        this.mc.displayGuiScreen(this.parent);
    }

    private void cancel() {
        this.mc.displayGuiScreen(this.parent);
    }
}
