package me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option;

import com.dhj.actinium.gui.rso.compat.Component;
import com.dhj.actinium.gui.rso.compat.CursorTypes;
import com.dhj.actinium.gui.rso.compat.GuiGraphicsExtractor;
import com.dhj.actinium.gui.rso.compat.KeyEvent;
import com.dhj.actinium.gui.rso.compat.MouseButtonEvent;
import com.dhj.actinium.gui.rso.compat.NarratedElementType;
import com.dhj.actinium.gui.rso.compat.NarrationElementOutput;
import me.flashyreese.mods.reeses_sodium_options.client.config.ReeseSodiumOptionsConfig;
import me.flashyreese.mods.reeses_sodium_options.client.gui.control.ControlGuide;
import me.flashyreese.mods.reeses_sodium_options.client.gui.layout.LayoutBounds;
import me.flashyreese.mods.reeses_sodium_options.client.gui.state.OptionStateStore;
import me.flashyreese.mods.reeses_sodium_options.client.gui.theme.GuiTheme;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.BaseWidget;

import me.flashyreese.mods.reeses_sodium_options.client.gui.option.RsoOption;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.MathHelper;
import org.jspecify.annotations.NonNull;

import java.util.List;

final class IntegerSliderOptionRow extends AbstractOptionRow {
    private static final int SLIDER_WIDTH = 90;
    private static final int TRACK_HEIGHT = 10;
    private static final int THUMB_WIDTH = 4;
    private static final int VALUE_GAP = 6;
    private static final int PRECISION_RANGE_RADIUS = 2;

    private final RsoOption option;
    private double thumbPosition;
    private double lastDragMouseX;
    private double dragAnchorMouseX;
    private int dragAnchorValue;
    private int precisionMin;
    private int precisionMax;
    private boolean sliderHeld;
    private boolean dragRelativeMode;
    private boolean precisionAdjusting;
    private boolean editMode;
    private boolean drawSlider;
    private int contentWidth;

    IntegerSliderOptionRow(LayoutBounds dim, GuiTheme theme, OptionStateStore optionStateStore, RsoOption option) {
        super(dim, theme, optionStateStore, option);
        this.option = option;
        this.thumbPosition = this.thumbPositionForValue((Integer) option.getPendingValue());
        this.contentWidth = this.valueWidth();
    }

    @Override
    public RsoOption getOption() {
        return this.option;
    }

    @Override
    protected void prepareRender(int mouseX, int mouseY, float delta) {
        if (this.sliderHeld) {
            this.updatePrecisionMode(GuiScreen.isShiftKeyDown(), this.lastDragMouseX);
        }

        boolean canDrawSlider = this.option.isEnabled() && !this.option.shouldHideControl();
        this.drawSlider = canDrawSlider && (this.isMouseOverRow(mouseX, mouseY) || this.isRowFocused() || this.sliderHeld);
        int valueWidth = this.valueWidth();
        this.contentWidth = this.drawSlider ? SLIDER_WIDTH + VALUE_GAP + valueWidth : valueWidth;
    }

    @Override
    protected int controlContentWidth() {
        return this.contentWidth;
    }

    @Override
    public List<ControlGuide> controlGuides() {
        if (!this.canShowControlGuide()) {
            return List.of();
        }

        return this.editMode
                ? List.of(ControlGuide.navigationLeftRight(Component.translatable("rso.controller.guide.adjust_value")), ControlGuide.press(Component.translatable("rso.controller.guide.done")))
                : List.of(ControlGuide.press(Component.translatable("rso.controller.guide.edit_slider")));
    }

    @Override
    protected void renderControl(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        if (this.option.shouldHideControl()) {
            return;
        }

        Component value = this.displayValue();
        int valueWidth = this.font.width(value);
        int sliderX = this.sliderX();
        int sliderY = this.sliderY();

        if (this.drawSlider) {
            if (!this.sliderHeld) {
                this.thumbPosition = this.thumbPositionForValue((Integer) this.option.getPendingValue());
            }

            int thumbX = sliderX + (int) (this.thumbPosition * SLIDER_WIDTH) - (THUMB_WIDTH / 2);
            int trackY = (int) (sliderY + (TRACK_HEIGHT / 2.0F) - 0.5D);

            this.drawRect(guiGraphics, sliderX, trackY, sliderX + SLIDER_WIDTH, trackY + 1, this.theme.themeLighter);
            this.drawRect(guiGraphics, thumbX, sliderY, thumbX + THUMB_WIDTH, sliderY + TRACK_HEIGHT, 0xFFFFFFFF);
            if (this.isRowFocused() && this.editMode && BaseWidget.isKeyboardFocusVisible()) {
                this.drawBorder(guiGraphics, thumbX - 1, sliderY - 1, thumbX + THUMB_WIDTH + 1, sliderY + TRACK_HEIGHT + 1, 0xFFFFFFFF);
            }

            this.drawString(guiGraphics, value, sliderX - valueWidth - VALUE_GAP, sliderY + (TRACK_HEIGHT / 2) - 4, 0xFFFFFFFF);
        } else {
            this.drawString(guiGraphics, value, sliderX + SLIDER_WIDTH - valueWidth, sliderY + (TRACK_HEIGHT / 2) - 4, 0xFFFFFFFF);
        }

        if (this.isMouseOverSlider(mouseX, mouseY)) {
            guiGraphics.requestCursor(this.sliderHeld ? CursorTypes.RESIZE_EW : CursorTypes.POINTING_HAND);
        }
    }

    @Override
    protected boolean controlMouseClicked(MouseButtonEvent event, boolean doubleClick) {
        this.stopSliderDrag();
        if (!this.option.isEnabled()
                || this.option.shouldHideControl()
                || event.button() != 0
                || !this.isMouseOverRow(event.x(), event.y())) {
            return false;
        }

        if (this.isMouseOverSlider(event.x(), event.y())) {
            this.setValueFromMouse(event.x());
            this.sliderHeld = true;
            this.lastDragMouseX = event.x();
            this.dragAnchorMouseX = event.x();
            this.dragAnchorValue = (Integer) this.option.getPendingValue();
            this.actionButtons.holdLayout(true);
            this.updatePrecisionMode(GuiScreen.isShiftKeyDown(), event.x());
        }

        return true;
    }

    @Override
    public boolean mouseDragged(@NonNull MouseButtonEvent event, double deltaX, double deltaY) {
        if (!this.sliderHeld || event.button() != 0 || !this.option.isEnabled()) {
            return false;
        }

        this.actionButtons.holdLayout(true);
        this.updatePrecisionMode(GuiScreen.isShiftKeyDown(), this.lastDragMouseX);
        if (this.dragRelativeMode) {
            this.setValueFromDrag(event.x());
        } else {
            this.setValueFromMouse(event.x());
        }
        this.lastDragMouseX = event.x();

        return true;
    }

    @Override
    public boolean mouseReleased(@NonNull MouseButtonEvent event) {
        if (event.button() != 0 || !this.sliderHeld) {
            return false;
        }

        this.stopSliderDrag();
        this.actionButtons.releaseLayoutHold();
        this.playClickSound();

        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!ReeseSodiumOptionsConfig.config().isShiftScrollSliderAdjustments()
                || !this.option.isEnabled()
                || this.option.shouldHideControl()
                || !GuiScreen.isShiftKeyDown()
                || !this.isMouseOverSlider(mouseX, mouseY)) {
            return false;
        }

        return this.adjustValue((int) verticalAmount);
    }

    @Override
    protected boolean controlKeyPressed(KeyEvent event) {
        if (this.sliderHeld && event.hasShiftDown()) {
            this.updatePrecisionMode(true, this.lastDragMouseX);
        }

        if (!this.isRowFocused()) {
            return false;
        }

        if (event.isSelection()) {
            this.editMode = !this.editMode;
            return true;
        }

        if (this.editMode) {
            if (event.isLeft()) {
                return this.adjustValue(-1);
            } else if (event.isRight()) {
                return this.adjustValue(1);
            }
        }

        return false;
    }

    @Override
    protected boolean activateControl() {
        this.editMode = !this.editMode;

        return true;
    }

    @Override
    public boolean handleBackNavigation() {
        if (!this.editMode) {
            return false;
        }

        this.editMode = false;
        return true;
    }

    @Override
    protected void releaseMouseHold() {
        this.stopSliderDrag();
    }

    @Override
    protected void onControlFocusLost() {
        this.editMode = false;
    }

    private Component displayValue() {
        Component value = Component.from(this.option.formatSliderValue(this.option.getPendingValue()));

        return this.option.isEnabled() ? value : this.formatDisabledControlValue(value);
    }

    @Override
    protected Component narrationValue() {
        return !this.option.shouldHideControl() ? Component.from(this.option.formatSliderValue(this.option.getPendingValue())) : null;
    }

    @Override
    protected void updateControlNarration(NarrationElementOutput builder) {
        if (!this.option.isEnabled()) {
            builder.add(NarratedElementType.HINT, Component.translatable("rso.narration.option_unavailable"));
            return;
        }

        if (this.option.shouldHideControl()) {
            return;
        }

        if (this.isFocused()) {
            builder.add(NarratedElementType.USAGE, Component.translatable(this.editMode
                    ? "narration.slider.usage.focused"
                    : "narration.slider.usage.focused.keyboard_cannot_change_value"));
        } else if (this.isHovered()) {
            builder.add(NarratedElementType.USAGE, Component.translatable("narration.slider.usage.hovered"));
        }
    }

    private int valueWidth() {
        return this.font.width(this.displayValue());
    }

    private int sliderX() {
        return this.rightAlignedControlX(SLIDER_WIDTH);
    }

    private int sliderY() {
        return this.getDimensions().getCenterY() - TRACK_HEIGHT / 2;
    }

    private boolean isMouseOverSlider(double mouseX, double mouseY) {
        int sliderX = this.sliderX();
        int sliderY = this.sliderY();

        return mouseX >= sliderX
                && mouseX < sliderX + SLIDER_WIDTH
                && mouseY >= sliderY
                && mouseY < sliderY + TRACK_HEIGHT;
    }

    private double thumbPositionForValue(int value) {
        int min = this.sliderMin();
        int max = this.sliderMax();

        if (max == min) {
            return 0.0D;
        }

        return MathHelper.clamp(((double) value - min) / ((double) max - min), 0.0D, 1.0D);
    }

    private int valueForThumbPosition() {
        int min = this.sliderMin();
        int max = this.sliderMax();
        int step = this.sliderInterval();

        double value = min + this.thumbPosition * ((double) max - min);
        return this.snapValue(value, min, max, step);
    }

    private void setValueFromMouse(double mouseX) {
        this.thumbPosition = MathHelper.clamp((mouseX - this.sliderX()) / (double) SLIDER_WIDTH, 0.0D, 1.0D);
        this.option.modifyValue(this.valueForThumbPosition());
    }

    private void setValueFromDrag(double mouseX) {
        int min = this.sliderMin();
        int max = this.sliderMax();
        double value = this.dragAnchorValue
                + (mouseX - this.dragAnchorMouseX) * (((double) max - min) / SLIDER_WIDTH);
        this.option.modifyValue(this.snapValue(value, min, max, this.sliderInterval()));
        this.thumbPosition = this.thumbPositionForValue((Integer) this.option.getPendingValue());
    }

    private void updatePrecisionMode(boolean shiftDown, double mouseX) {
        if (!this.sliderHeld || shiftDown == this.precisionAdjusting) {
            return;
        }

        this.precisionAdjusting = shiftDown;
        this.dragRelativeMode = true;
        this.dragAnchorMouseX = mouseX;
        this.dragAnchorValue = (Integer) this.option.getPendingValue();
        if (shiftDown) {
            this.precisionMin = (int) Math.max(
                    this.option.sliderMin(), (long) this.dragAnchorValue - PRECISION_RANGE_RADIUS);
            this.precisionMax = (int) Math.min(
                    this.option.sliderMax(), (long) this.dragAnchorValue + PRECISION_RANGE_RADIUS);
        }

        this.thumbPosition = this.thumbPositionForValue(this.dragAnchorValue);
    }

    private void stopSliderDrag() {
        this.sliderHeld = false;
        this.dragRelativeMode = false;
        this.precisionAdjusting = false;
        this.thumbPosition = this.thumbPositionForValue((Integer) this.option.getPendingValue());
    }

    private int sliderMin() {
        return this.precisionAdjusting ? this.precisionMin : this.option.sliderMin();
    }

    private int sliderMax() {
        return this.precisionAdjusting ? this.precisionMax : this.option.sliderMax();
    }

    private int sliderInterval() {
        return this.precisionAdjusting ? 1 : this.option.sliderInterval();
    }

    private int snapValue(double value, int min, int max, int interval) {
        double clampedValue = Math.max(min, Math.min(max, value));
        long stepCount = Math.round((clampedValue - min) / interval);
        long snappedValue = (long) min + stepCount * interval;
        return (int) Math.max(min, Math.min(max, snappedValue));
    }

    private boolean adjustValue(int direction) {
        if (direction == 0) {
            return false;
        }

        int step = this.sliderInterval();
        int value = (Integer) this.option.getPendingValue();
        int min = this.sliderMin();
        int max = this.sliderMax();
        int nextValue = (int) Math.max(min, Math.min(max, (long) value + (long) step * direction));

        if (nextValue == value) {
            return false;
        }

        this.option.modifyValue(nextValue);
        this.thumbPosition = this.thumbPositionForValue((Integer) this.option.getPendingValue());

        return true;
    }
}
