package com.dhj.actinium.gui;

import com.dhj.actinium.hud.layout.IndicatorLayout;
import com.dhj.actinium.hud.layout.IndicatorRowType;
import com.dhj.actinium.runtime.ActiniumRuntime;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions.PerformanceIndicatorSettings.refreshMaxMillis;
import static dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions.PerformanceIndicatorSettings.refreshMinMillis;
import static dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions.PerformanceIndicatorSettings.refreshStepMillis;

/**
 * Arranges the rows of the performance indicator and sets how often it refreshes.
 *
 * <p>The left column holds the rows in display order, the right column offers the remaining rows in
 * declaration order. Dragging a row into the left column enables or moves it, dragging it back into
 * the right column removes it. The layout and the refresh interval are written to the configuration
 * only when the screen is saved; cancelling leaves the stored layout untouched.
 */
public final class IndicatorScreen extends GuiScreen {
    private static final int ROW_HEIGHT = 20;
    private static final int LIST_TOP = 48;
    private static final int FOOTER_HEIGHT = 62;
    private static final int COLUMN_MARGIN = 30;
    private static final int COLUMN_GAP = 16;
    private static final int HEADER_OFFSET = 12;
    private static final int DRAG_THRESHOLD = 4;
    private static final int AUTO_SCROLL_MARGIN = 12;
    private static final int SCROLLBAR_WIDTH = 3;

    private static final int SLIDER_MAX_WIDTH = 220;
    private static final int SLIDER_KNOB_WIDTH = 8;
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;

    private static final int SAVE_BUTTON_ID = 0;
    private static final int CANCEL_BUTTON_ID = 1;
    private static final int REFRESH_SLIDER_ID = 2;

    private static final int COLOR_TITLE = 0xFFFFFF;
    private static final int COLOR_HEADER = 0xA0A0A0;
    private static final int COLOR_ROW_ENABLED = 0xFFFFFF;
    private static final int COLOR_ROW_AVAILABLE = 0xC0C0C0;
    private static final int COLOR_ROW_DRAGGING = 0x60FFFFFF;
    private static final int COLOR_COLUMN_BACKGROUND = 0x60000000;
    private static final int COLOR_ROW_HOVER_BACKGROUND = 0x30FFFFFF;
    private static final int COLOR_SCROLLBAR_TRACK = 0x20FFFFFF;
    private static final int COLOR_SCROLLBAR_THUMB = 0xB0B0B0;
    private static final int COLOR_DROP_BACKGROUND = 0x404CD964;
    private static final int COLOR_INSERT_LINE = 0xFF4CD964;
    private static final int COLOR_DRAG_PREVIEW = 0xC0202020;
    private static final int COLOR_DRAG_PREVIEW_TEXT = 0xFFFFFF;
    private static final int COLOR_SLIDER_TRACK = 0xFF808080;
    private static final int COLOR_SLIDER_KNOB = 0xFFC0C0C0;
    private static final int COLOR_SLIDER_KNOB_ACTIVE = 0xFFFFFFFF;

    private enum Column {
        ENABLED,
        AVAILABLE
    }

    private enum DragState {
        IDLE,
        PENDING,
        DRAGGING
    }

    private final GuiScreen parent;

    @Nullable
    private IndicatorLayout draft;
    private int draftRefreshMillis;

    private DragState dragState = DragState.IDLE;
    @Nullable
    private IndicatorRowType dragRow;
    @Nullable
    private Column dragSource;
    @Nullable
    private Column dropColumn;
    /** Boundary between displayed rows of {@link #dropColumn}, in the row order that column shows. */
    private int dropIndex;
    private int pressX;
    private int pressY;
    private int enabledScroll;
    private int availableScroll;

    public IndicatorScreen(GuiScreen parent) {
        this.parent = parent;
    }

    /** Opens the editor on top of {@code parent}. */
    public static void open(GuiScreen parent) {
        Minecraft.getMinecraft().displayGuiScreen(new IndicatorScreen(parent));
    }

    @Override
    public void initGui() {
        this.buttonList.clear();

        // A resize recreates this screen, so the drafts survive instead of being read back from the config.
        if (this.draft == null) {
            SodiumGameOptions.PerformanceIndicatorSettings settings = ActiniumRuntime.options().performanceIndicator;
            this.draft = IndicatorLayout.fromConfig(settings.rows);
            this.draftRefreshMillis = clampRefreshMillis(settings.refreshIntervalMillis);
        }

        cancelDrag();
        clampScrolls();

        int sliderWidth = Math.min(SLIDER_MAX_WIDTH, this.width - 2 * COLUMN_MARGIN);
        this.buttonList.add(new RefreshSlider(REFRESH_SLIDER_ID,
                (this.width - sliderWidth) / 2, this.height - 56, sliderWidth, BUTTON_HEIGHT));

        int buttonY = this.height - 28;
        this.buttonList.add(new GuiButton(SAVE_BUTTON_ID,
                this.width / 2 - BUTTON_WIDTH - BUTTON_GAP, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                I18n.format("gui.actinium.indicator.save")));
        this.buttonList.add(new GuiButton(CANCEL_BUTTON_ID,
                this.width / 2 + BUTTON_GAP, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                I18n.format("gui.actinium.indicator.cancel")));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        clampScrolls();

        if (this.dragState == DragState.DRAGGING) {
            autoScroll(mouseX, mouseY);
            updateDropTarget(mouseX, mouseY);
        }

        this.drawCenteredString(this.fontRenderer, I18n.format("gui.actinium.indicator.title"),
                this.width / 2, 16, COLOR_TITLE);

        drawColumn(Column.ENABLED, enabledColumnX(), mouseX, mouseY);
        drawColumn(Column.AVAILABLE, availableColumnX(), mouseX, mouseY);
        drawDragPreview(mouseX, mouseY);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();

        int wheel = Mouse.getDWheel();
        if (wheel == 0) {
            return;
        }

        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        Column column = columnAt(mouseX, mouseY);
        if (column != null) {
            scrollColumn(column, -Integer.signum(wheel));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.cancel();
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton != 0) {
            return;
        }

        cancelDrag();

        Column column = columnAt(mouseX, mouseY);
        if (column == null) {
            return;
        }

        List<IndicatorRowType> rows = rowsOf(column);
        int offset = (mouseY - LIST_TOP) / ROW_HEIGHT;
        if (offset >= visibleRowCount()) {
            return;
        }

        int rowIndex = scrollOf(column) + offset;
        if (rowIndex >= rows.size()) {
            return;
        }

        this.dragRow = rows.get(rowIndex);
        this.dragSource = column;
        this.dragState = DragState.PENDING;
        this.pressX = mouseX;
        this.pressY = mouseY;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton != 0 || this.dragState == DragState.IDLE) {
            return;
        }

        if (this.dragState == DragState.PENDING) {
            int dx = mouseX - this.pressX;
            int dy = mouseY - this.pressY;
            if (dx * dx + dy * dy <= DRAG_THRESHOLD * DRAG_THRESHOLD) {
                return;
            }
            this.dragState = DragState.DRAGGING;
        }

        updateDropTarget(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);

        if (state != 0) {
            return;
        }

        if (this.dragState == DragState.DRAGGING) {
            updateDropTarget(mouseX, mouseY);
            commitDrop();
        }

        cancelDrag();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case SAVE_BUTTON_ID -> this.save();
            case CANCEL_BUTTON_ID -> this.cancel();
            default -> { }
        }
    }

    private void save() {
        SodiumGameOptions options = ActiniumRuntime.options();
        options.performanceIndicator.rows = new ArrayList<>(this.draft.toConfig());
        options.performanceIndicator.refreshIntervalMillis = this.draftRefreshMillis;
        options.save();
        this.mc.displayGuiScreen(this.parent);
    }

    private void cancel() {
        this.mc.displayGuiScreen(this.parent);
    }

    private void commitDrop() {
        if (this.dropColumn == null || this.dragRow == null) {
            return;
        }

        if (this.dropColumn == Column.ENABLED) {
            int index = this.dropIndex;
            if (this.dragSource == Column.ENABLED) {
                // The row leaves its own column first, so positions behind it shift down by one.
                int current = this.draft.enabledRows().indexOf(this.dragRow);
                if (current >= 0 && current < index) {
                    index--;
                }
            }
            this.draft = this.draft.insertEnabled(this.dragRow, index);
        } else if (this.dragSource == Column.ENABLED) {
            this.draft = this.draft.removeEnabled(this.dragRow);
        }

        clampScrolls();
    }

    private void updateDropTarget(int mouseX, int mouseY) {
        this.dropColumn = columnAt(mouseX, mouseY);
        this.dropIndex = this.dropColumn == Column.ENABLED ? enabledInsertIndex(mouseY) : 0;
    }

    private int enabledInsertIndex(int mouseY) {
        int rowCount = this.draft.enabledRows().size();
        return clamp(this.enabledScroll + (mouseY - LIST_TOP) / ROW_HEIGHT, 0, rowCount);
    }

    private void autoScroll(int mouseX, int mouseY) {
        Column column = columnAt(mouseX, mouseY);
        if (column == null) {
            return;
        }

        if (mouseY < LIST_TOP + AUTO_SCROLL_MARGIN) {
            scrollColumn(column, -1);
        } else if (mouseY >= listBottom() - AUTO_SCROLL_MARGIN) {
            scrollColumn(column, 1);
        }
    }

    private void scrollColumn(Column column, int rows) {
        int maxScroll = maxScroll(rowsOf(column).size());
        if (column == Column.ENABLED) {
            this.enabledScroll = clamp(this.enabledScroll + rows, 0, maxScroll);
        } else {
            this.availableScroll = clamp(this.availableScroll + rows, 0, maxScroll);
        }
    }

    private void cancelDrag() {
        this.dragState = DragState.IDLE;
        this.dragRow = null;
        this.dragSource = null;
        this.dropColumn = null;
        this.dropIndex = 0;
    }

    private void clampScrolls() {
        this.enabledScroll = clamp(this.enabledScroll, 0, maxScroll(this.draft.enabledRows().size()));
        this.availableScroll = clamp(this.availableScroll, 0, maxScroll(this.draft.availableRows().size()));
    }

    private void drawColumn(Column column, int left, int mouseX, int mouseY) {
        int top = LIST_TOP;
        int bottom = listBottom();
        int right = left + columnWidth();
        int scroll = scrollOf(column);
        List<IndicatorRowType> rows = rowsOf(column);
        boolean dropTarget = this.dragState == DragState.DRAGGING && this.dropColumn == column;

        drawRect(left, top, right, bottom, COLOR_COLUMN_BACKGROUND);
        this.drawString(this.fontRenderer, columnHeader(column), left + 4, top - HEADER_OFFSET, COLOR_HEADER);

        if (dropTarget && column == Column.AVAILABLE) {
            drawRect(left, top, right, bottom, COLOR_DROP_BACKGROUND);
        }

        int visibleRows = visibleRowCount();
        for (int i = 0; i < visibleRows; i++) {
            int rowIndex = scroll + i;
            if (rowIndex >= rows.size()) {
                break;
            }

            int rowTop = top + i * ROW_HEIGHT;
            IndicatorRowType row = rows.get(rowIndex);
            boolean hovered = this.dragState == DragState.IDLE
                    && mouseX >= left && mouseX < right
                    && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;
            if (hovered) {
                drawRect(left, rowTop, right, rowTop + ROW_HEIGHT - 2, COLOR_ROW_HOVER_BACKGROUND);
            }

            this.drawString(this.fontRenderer, rowLabel(row), left + 6, rowTop + 6,
                    rowTextColor(column, row));
        }

        drawScrollbar(left, right, top, bottom, scroll, rows.size());

        if (dropTarget && column == Column.ENABLED) {
            drawInsertLine(left, right, top, bottom, scroll);
        }
    }

    private int rowTextColor(Column column, IndicatorRowType row) {
        if (this.dragState == DragState.DRAGGING && this.dragRow == row && this.dragSource == column) {
            return COLOR_ROW_DRAGGING;
        }
        return column == Column.ENABLED ? COLOR_ROW_ENABLED : COLOR_ROW_AVAILABLE;
    }

    private void drawInsertLine(int left, int right, int top, int bottom, int scroll) {
        int lineY = clamp(top + (this.dropIndex - scroll) * ROW_HEIGHT, top, bottom - 2);
        drawRect(left, lineY, right, lineY + 2, COLOR_INSERT_LINE);
    }

    private void drawScrollbar(int left, int right, int top, int bottom, int scroll, int rowCount) {
        int visibleRows = visibleRowCount();
        if (rowCount <= visibleRows) {
            return;
        }

        int trackHeight = bottom - top;
        int thumbHeight = Math.max(12, trackHeight * visibleRows / rowCount);
        int thumbY = top + (trackHeight - thumbHeight) * scroll / (rowCount - visibleRows);
        int x = right - SCROLLBAR_WIDTH - 2;
        drawRect(x, top, x + SCROLLBAR_WIDTH, bottom, COLOR_SCROLLBAR_TRACK);
        drawRect(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, COLOR_SCROLLBAR_THUMB);
    }

    private void drawDragPreview(int mouseX, int mouseY) {
        if (this.dragState != DragState.DRAGGING || this.dragRow == null) {
            return;
        }

        int width = columnWidth();
        int left = clamp(mouseX - width / 2, 0, Math.max(0, this.width - width));
        int top = mouseY - ROW_HEIGHT / 2;
        drawRect(left, top, left + width, top + ROW_HEIGHT - 2, COLOR_DRAG_PREVIEW);
        this.drawString(this.fontRenderer, rowLabel(this.dragRow), left + 6, top + 6, COLOR_DRAG_PREVIEW_TEXT);
    }

    @Nullable
    private Column columnAt(int mouseX, int mouseY) {
        if (mouseY < LIST_TOP || mouseY >= listBottom()) {
            return null;
        }

        int width = columnWidth();
        if (mouseX >= enabledColumnX() && mouseX < enabledColumnX() + width) {
            return Column.ENABLED;
        }
        if (mouseX >= availableColumnX() && mouseX < availableColumnX() + width) {
            return Column.AVAILABLE;
        }
        return null;
    }

    private List<IndicatorRowType> rowsOf(Column column) {
        return column == Column.ENABLED ? this.draft.enabledRows() : this.draft.availableRows();
    }

    private int scrollOf(Column column) {
        return column == Column.ENABLED ? this.enabledScroll : this.availableScroll;
    }

    private int listBottom() {
        return this.height - FOOTER_HEIGHT;
    }

    private int columnWidth() {
        return Math.max(60, (this.width - 2 * COLUMN_MARGIN - COLUMN_GAP) / 2);
    }

    private int enabledColumnX() {
        return COLUMN_MARGIN;
    }

    private int availableColumnX() {
        return COLUMN_MARGIN + columnWidth() + COLUMN_GAP;
    }

    private int visibleRowCount() {
        return Math.max(1, (listBottom() - LIST_TOP) / ROW_HEIGHT);
    }

    private int maxScroll(int rowCount) {
        return Math.max(0, rowCount - visibleRowCount());
    }

    private static String columnHeader(Column column) {
        return I18n.format(column == Column.ENABLED
                ? "gui.actinium.indicator.enabled"
                : "gui.actinium.indicator.available");
    }

    private static String rowLabel(IndicatorRowType type) {
        return I18n.format("gui.actinium.indicator.row." + type.id());
    }

    private static String refreshLabel(int millis) {
        return I18n.format("gui.actinium.indicator.refresh", millis);
    }

    private static int clampRefreshMillis(int millis) {
        return clamp(millis, refreshMinMillis(), refreshMaxMillis());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class RefreshSlider extends GuiButton {
        private boolean dragging;

        private RefreshSlider(int buttonId, int x, int y, int width, int height) {
            super(buttonId, x, y, width, height, "");
            this.displayString = refreshLabel(IndicatorScreen.this.draftRefreshMillis);
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
            if (!this.visible) {
                return;
            }

            this.hovered = mouseX >= this.x && mouseY >= this.y
                    && mouseX < this.x + this.width && mouseY < this.y + this.height;

            if (this.dragging) {
                updateFromMouse(mouseX);
            }

            int centerY = this.y + this.height / 2;
            drawRect(this.x, centerY - 1, this.x + this.width, centerY + 1, COLOR_SLIDER_TRACK);

            int knobX = knobX();
            drawRect(knobX - SLIDER_KNOB_WIDTH / 2, this.y + 2, knobX + SLIDER_KNOB_WIDTH / 2,
                    this.y + this.height - 2, this.hovered || this.dragging ? COLOR_SLIDER_KNOB_ACTIVE : COLOR_SLIDER_KNOB);

            this.drawCenteredString(mc.fontRenderer, this.displayString, this.x + this.width / 2,
                    this.y + (this.height - 8) / 2, COLOR_ROW_ENABLED);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!super.mousePressed(mc, mouseX, mouseY)) {
                return false;
            }

            this.dragging = true;
            updateFromMouse(mouseX);
            return true;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            this.dragging = false;
        }

        private void updateFromMouse(int mouseX) {
            int left = this.x + SLIDER_KNOB_WIDTH / 2;
            int right = this.x + this.width - SLIDER_KNOB_WIDTH / 2;
            float fraction = (clamp(mouseX, left, right) - left) / (float) (right - left);
            int steps = Math.round(fraction * (refreshMaxMillis() - refreshMinMillis()) / refreshStepMillis());
            IndicatorScreen.this.draftRefreshMillis = refreshMinMillis() + steps * refreshStepMillis();
            this.displayString = refreshLabel(IndicatorScreen.this.draftRefreshMillis);
        }

        private int knobX() {
            int left = this.x + SLIDER_KNOB_WIDTH / 2;
            int right = this.x + this.width - SLIDER_KNOB_WIDTH / 2;
            float fraction = (IndicatorScreen.this.draftRefreshMillis - refreshMinMillis())
                    / (float) (refreshMaxMillis() - refreshMinMillis());
            return Math.round(left + fraction * (right - left));
        }
    }
}
