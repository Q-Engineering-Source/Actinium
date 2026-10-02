package me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option;

import com.dhj.actinium.gui.rso.compat.Component;
import com.dhj.actinium.gui.rso.compat.GuiGraphicsExtractor;
import com.dhj.actinium.gui.rso.compat.NarratedElementType;
import com.dhj.actinium.gui.rso.compat.NarrationElementOutput;
import com.dhj.actinium.gui.rso.compat.Style;
import me.flashyreese.mods.reeses_sodium_options.client.gui.layout.LayoutBounds;
import me.flashyreese.mods.reeses_sodium_options.client.gui.state.OptionStateStore;
import me.flashyreese.mods.reeses_sodium_options.client.gui.theme.GuiTheme;
import me.flashyreese.mods.reeses_sodium_options.client.gui.option.RsoOption;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextFormatting;

final class ExternalButtonOptionRow extends AbstractOptionRow {
    private static final int CONTENT_WIDTH = 65;
    private static final Component BASE_BUTTON_TEXT = Component.translatable("sodium.options.open_external_page_button");

    private final GuiScreen screen;
    private final RsoOption option;

    ExternalButtonOptionRow(GuiScreen screen, LayoutBounds dim, GuiTheme theme, OptionStateStore optionStateStore, RsoOption option) {
        super(dim, theme, optionStateStore, option);
        this.screen = screen;
        this.option = option;
    }

    @Override
    public RsoOption getOption() {
        return this.option;
    }

    @Override
    protected int controlContentWidth() {
        return CONTENT_WIDTH;
    }

    @Override
    protected void renderControl(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        Component text = this.buttonText();
        int x = this.rightAlignedControlX(this.font.width(text));
        int y = this.centeredTextY();

        this.drawString(guiGraphics, text, x, y, 0xFFFFFFFF);

        if (this.option.isEnabled()) {
            this.requestPointerCursorIfHovered(guiGraphics);
        }
    }

    @Override
    protected boolean activateControl() {
        if (!this.option.isEnabled()) {
            return false;
        }

        this.option.getCurrentScreenConsumer().accept(this.screen);
        this.playClickSound();

        return true;
    }

    private Component buttonText() {
        Component label = this.option.externalButtonLabel();
        if (label == null) {
            label = BASE_BUTTON_TEXT;
        }

        if (!this.option.isEnabled()) {
            return label.copy().withStyle(TextFormatting.STRIKETHROUGH, TextFormatting.GRAY);
        }

        return Component.empty()
                .append(label.copy().withStyle(TextFormatting.UNDERLINE))
                .append(Component.literal(" >").withStyle(Style.EMPTY.withColor(this.theme.theme)));
    }

    @Override
    protected void updateControlNarration(NarrationElementOutput builder) {
        if (!this.option.isEnabled()) {
            builder.add(NarratedElementType.HINT, Component.translatable("rso.narration.option_unavailable"));
            return;
        }

        this.addUsageNarration(builder, "narration.link.usage.focused", "narration.link.usage.hovered");
    }
}
