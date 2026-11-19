package com.dhj.actinium.gui;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.rendering.tesr.EntityShadowReplay;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Edits the entity classes that re-run their renderer in the main view instead of replaying the draws recorded in the
 * shader shadow pass.
 *
 * <p>The list is a plain text line of binary class names separated by commas or whitespace. Saving validates the
 * line, writes the configuration and drops the exclusion decisions the renderer cached per class so the new list
 * applies without a restart; cancelling leaves the configuration untouched. Which classes count as excluded on top of
 * the listed names, such as players, is decided by {@link EntityShadowReplay} alone.</p>
 */
public final class EntityShadowReplayExcludedClassesScreen extends GuiScreen {
    private static final int SAVE_BUTTON_ID = 0;
    private static final int CANCEL_BUTTON_ID = 1;

    private static final int FIELD_MAX_LENGTH = 4096;
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_MAX_WIDTH = 400;
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;

    /** Separators accepted between two class names in the options screen. */
    private static final Pattern CLASS_NAME_SEPARATOR = Pattern.compile("[,\\s]+");
    private static final Pattern CLASS_NAME_SEGMENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private final GuiScreen parent;

    private GuiTextField classNamesField;
    @Nullable
    private String draft;
    @Nullable
    private String error;

    public EntityShadowReplayExcludedClassesScreen(GuiScreen parent) {
        this.parent = parent;
    }

    /** Opens the editor on top of {@code parent}. */
    public static void open(GuiScreen parent) {
        Minecraft.getMinecraft().displayGuiScreen(new EntityShadowReplayExcludedClassesScreen(parent));
    }

    /** {@return the excluded class names as one editable line for the options screen} */
    public static String formatClassNames(List<String> classNames) {
        return String.join(", ", classNames);
    }

    /**
     * Parses the edited class name text: names are separated by commas or whitespace, duplicates keep their first
     * position, and anything that is not a binary class name is rejected.
     *
     * @param text the edited line, blank when no class is listed
     * @return the validated names in the order they were entered
     * @throws IllegalArgumentException when a token is not a binary class name
     */
    public static List<String> parseClassNames(@Nullable String text) {
        if (text == null) {
            throw new IllegalArgumentException("Class name list must not be null");
        }

        LinkedHashSet<String> classNames = new LinkedHashSet<>();

        for (String token : CLASS_NAME_SEPARATOR.split(text)) {
            if (token.isEmpty()) {
                continue;
            }

            validateClassName(token);
            classNames.add(token);
        }

        return new ArrayList<>(classNames);
    }

    private static void validateClassName(String className) {
        for (String segment : className.split("\\.", -1)) {
            if (!CLASS_NAME_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(
                        "Invalid class name '" + className + "', expected a binary name such as com.example.Foo");
            }
        }
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();

        int fieldWidth = Math.min(FIELD_MAX_WIDTH, this.width - 40);
        this.classNamesField = new GuiTextField(0, this.fontRenderer,
                (this.width - fieldWidth) / 2, this.height / 2 - 30, fieldWidth, FIELD_HEIGHT);
        this.classNamesField.setMaxStringLength(FIELD_MAX_LENGTH);
        // A resize recreates this screen, so unsaved edits come from the draft instead of the file.
        this.classNamesField.setText(this.draft != null ? this.draft
                : formatClassNames(ActiniumRuntime.options().performance.entityShadowReplayExcludedClasses));
        this.classNamesField.setFocused(true);

        int buttonY = this.height / 2 + 40;
        this.buttonList.add(new GuiButton(SAVE_BUTTON_ID, this.width / 2 - BUTTON_WIDTH - BUTTON_GAP, buttonY,
                BUTTON_WIDTH, BUTTON_HEIGHT, I18n.format("gui.actinium.entity_shadow_replay_excluded_classes.save")));
        this.buttonList.add(new GuiButton(CANCEL_BUTTON_ID, this.width / 2 + BUTTON_GAP, buttonY,
                BUTTON_WIDTH, BUTTON_HEIGHT, I18n.format("gui.actinium.entity_shadow_replay_excluded_classes.cancel")));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int centerX = this.width / 2;
        int titleY = this.height / 2 - 84;

        this.drawCenteredString(this.fontRenderer,
                I18n.format("gui.actinium.entity_shadow_replay_excluded_classes.title"), centerX, titleY, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer,
                I18n.format("gui.actinium.entity_shadow_replay_excluded_classes.description"), centerX, titleY + 20, 0xA0A0A0);
        this.drawCenteredString(this.fontRenderer,
                I18n.format("gui.actinium.entity_shadow_replay_excluded_classes.hint"), centerX, titleY + 34, 0xA0A0A0);

        this.classNamesField.drawTextBox();

        if (this.error != null) {
            this.drawCenteredString(this.fontRenderer, this.error, centerX, this.height / 2 + 70, 0xFF5555);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.classNamesField.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.cancel();
            return;
        }

        if (this.classNamesField.textboxKeyTyped(typedChar, keyCode)) {
            this.draft = this.classNamesField.getText();
            this.error = null;
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.classNamesField.mouseClicked(mouseX, mouseY, mouseButton);
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
        List<String> classNames;

        try {
            classNames = parseClassNames(this.classNamesField.getText());
        } catch (IllegalArgumentException invalid) {
            this.error = I18n.format("gui.actinium.entity_shadow_replay_excluded_classes.invalid", invalid.getMessage());
            return;
        }

        this.error = null;

        SodiumGameOptions options = ActiniumRuntime.options();

        if (!classNames.equals(options.performance.entityShadowReplayExcludedClasses)) {
            options.performance.entityShadowReplayExcludedClasses = new ArrayList<>(classNames);
            options.save();

            // The renderer keeps the exclusion decision per class, so the edited list only applies once they are dropped.
            EntityShadowReplay.clear();
        }

        this.mc.displayGuiScreen(this.parent);
    }

    private void cancel() {
        this.mc.displayGuiScreen(this.parent);
    }
}
