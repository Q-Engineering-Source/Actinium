package net.coderbot.iris.gui.element.shaderoptions;

import net.coderbot.iris.Iris;
import net.coderbot.iris.gui.GuiUtil;
import net.coderbot.iris.gui.NavigationController;
import net.coderbot.iris.gui.element.IrisElementRow;
import net.coderbot.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import org.jetbrains.annotations.Nullable;

public class HeaderEntry extends BaseEntry {

    public static final String BACK_BUTTON_TEXT = TextFormatting.ITALIC + "< " + I18n.format("options.iris.back");
    public static final String RESET_BUTTON_TEXT_INACTIVE = TextFormatting.GRAY + I18n.format("options.iris.reset");
    public static final String RESET_BUTTON_TEXT_ACTIVE = TextFormatting.YELLOW + I18n.format("options.iris.reset");

    public static final String RESET_HOLD_SHIFT_TOOLTIP = TextFormatting.BOLD + I18n.format("options.iris.reset.tooltip.holdShift");
    public static final String RESET_TOOLTIP = TextFormatting.RED + I18n.format("options.iris.reset.tooltip");
    public static final String IMPORT_TOOLTIP = I18n.format("options.iris.importSettings.tooltip");
    public static final String EXPORT_TOOLTIP = I18n.format("options.iris.exportSettings.tooltip");

    private static final int MIN_SIDE_BUTTON_WIDTH = 42;
    private static final int BUTTON_HEIGHT = 16;

    private final ShaderPackScreen screen;
    private final @Nullable IrisElementRow backButton;
    private final IrisElementRow utilityButtons = new IrisElementRow();
    private final IrisElementRow.TextButtonElement resetButton;
    private final IrisElementRow.IconButtonElement importButton;
    private final IrisElementRow.IconButtonElement exportButton;
    private final String text;

    public HeaderEntry(ShaderPackScreen screen, NavigationController navigation, String text, boolean hasBackButton) {
        super(navigation);

        if (hasBackButton) {
            this.backButton = new IrisElementRow().add(new IrisElementRow.TextButtonElement(BACK_BUTTON_TEXT, this::backButtonClicked),
                Math.max(MIN_SIDE_BUTTON_WIDTH, Minecraft.getMinecraft().fontRenderer.getStringWidth(BACK_BUTTON_TEXT) + 8));
        } else {
            this.backButton = null;
        }

        this.resetButton = new IrisElementRow.TextButtonElement(RESET_BUTTON_TEXT_INACTIVE, this::resetButtonClicked);
        this.importButton = new IrisElementRow.IconButtonElement(GuiUtil.Icon.IMPORT, GuiUtil.Icon.IMPORT_COLORED, this::importSettingsButtonClicked);
        this.exportButton = new IrisElementRow.IconButtonElement(GuiUtil.Icon.EXPORT, GuiUtil.Icon.EXPORT_COLORED, this::exportSettingsButtonClicked);

        this.utilityButtons.add(this.importButton, 15).add(this.exportButton, 15)
            .add(this.resetButton, Math.max(MIN_SIDE_BUTTON_WIDTH, Minecraft.getMinecraft().fontRenderer.getStringWidth((RESET_BUTTON_TEXT_INACTIVE) + 8)));

        this.screen = screen;
        this.text = text;
    }

    @Override
    public void drawEntry(ShaderPackScreen screen, int index, int x, int y, int slotWidth, int slotHeight, Tessellator tessellator, int mouseX, int mouseY, boolean isMouseOver) {
        final int tickDelta = 0;
        final FontRenderer font = Minecraft.getMinecraft().fontRenderer;

        this.screen.drawCenteredString(font, text, x + (int) (slotWidth * 0.5), y + 5, 0xFFFFFF);

        GuiUtil.bindIrisWidgetsTexture();

        if (this.backButton != null) {
            backButton.drawScreen(x, y, BUTTON_HEIGHT, mouseX, mouseY, tickDelta, isMouseOver);
        }

        final boolean shiftDown = GuiScreen.isShiftKeyDown();

        this.resetButton.disabled = !shiftDown;
        this.resetButton.text = shiftDown ? RESET_BUTTON_TEXT_ACTIVE : RESET_BUTTON_TEXT_INACTIVE;

        this.utilityButtons.renderRightAligned((x + slotWidth) - 3, y, BUTTON_HEIGHT, mouseX, mouseY, tickDelta, isMouseOver);

        if (this.resetButton.isHovered()) {
            final String tooltip = shiftDown ? RESET_TOOLTIP : RESET_HOLD_SHIFT_TOOLTIP;
            queueBottomRightAnchoredTooltip(mouseX, mouseY, font, tooltip);
        }
        if (this.importButton.isHovered()) {
            queueBottomRightAnchoredTooltip(mouseX, mouseY, font, IMPORT_TOOLTIP);
        }
        if (this.exportButton.isHovered()) {
            queueBottomRightAnchoredTooltip(mouseX, mouseY, font, EXPORT_TOOLTIP);
        }
    }

    private void queueBottomRightAnchoredTooltip(int x, int y, FontRenderer font, String text) {
        ShaderPackScreen.TOP_LAYER_RENDER_QUEUE.add(() -> GuiUtil.drawTextPanel(font, text, x - (font.getStringWidth(text) + 10), y - 16));
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        final boolean backButtonResult = backButton != null && backButton.mouseClicked(mouseX, mouseY, button);
        final boolean utilButtonResult = utilityButtons.mouseClicked(mouseX, mouseY, button);

        return backButtonResult || utilButtonResult;
    }

    @Override
    public boolean mouseReleased(int mouseX, int mouseY, int button) {
        return false;
    }

    private boolean backButtonClicked(IrisElementRow.TextButtonElement button) {
        this.navigation.back();
        GuiUtil.playButtonClickSound();

        return true;
    }

    private boolean resetButtonClicked(IrisElementRow.TextButtonElement button) {
        if (GuiScreen.isShiftKeyDown()) {
            Iris.resetShaderPackOptionsOnNextReload();
            this.screen.applyChanges();
            GuiUtil.playButtonClickSound();

            return true;
        }

        return false;
    }

    private boolean importSettingsButtonClicked(IrisElementRow.IconButtonElement button) {
        //			// Displaying a dialog when the game is full-screened can cause severe issues
        //			// https://github.com/IrisShaders/Iris/issues/1258
        return true;
    }

    private boolean exportSettingsButtonClicked(IrisElementRow.IconButtonElement button) {

        return true;
    }
}
