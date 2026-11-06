package net.coderbot.iris.gui.screen;

import net.coderbot.iris.Iris;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets the player pick which shader folders of the loaded pack are compiled ahead of time and written to the
 * program cache. Each folder compiles on its own, one per client tick, so the screen stays responsive while the
 * driver links programs.
 */
public class PrecompileScreen extends GuiScreen {
	private static final int ROW_HEIGHT = 20;
	private static final int LIST_TOP = 44;
	private static final int LIST_BOTTOM_MARGIN = 62;
	private static final int BOX_SIZE = 10;
	private static final int SCROLLBAR_WIDTH = 6;

	private static final int COLOR_SELECTED = 0xFFFFFF;
	private static final int COLOR_NORMAL = 0xA0A0A0;
	private static final int COLOR_DONE = 0x55FF55;
	private static final int COLOR_RUNNING = 0xFFD755;
	private static final int COLOR_PENDING = 0x909090;
	private static final int COLOR_ROW_HOVER = 0x30FFFFFF;

	private static final int ID_BACK = 0;
	private static final int ID_SELECT_ALL = 1;
	private static final int ID_CLEAR_ALL = 2;
	private static final int ID_START = 3;
	private static final int ID_STOP = 4;

	private final GuiScreen parent;
	private final List<Row> rows = new ArrayList<>();

	private int scroll;
	private boolean compiling;
	private boolean cancelled;
	private int runIndex;
	private int runTotal;

	private GuiButton backButton;
	private GuiButton selectAllButton;
	private GuiButton clearAllButton;
	private GuiButton startButton;
	private GuiButton stopButton;

	private static final class Row {
		private final @Nullable String folder;
		private final String displayName;
		private boolean compiled;
		private boolean selected = true;
		private boolean running;
		private boolean processed;

		private Row(Iris.PrecompileEntry entry) {
			this.folder = entry.folder();
			this.displayName = entry.displayName();
			this.compiled = entry.compiled();
		}
	}

	public PrecompileScreen(@Nullable GuiScreen parent) {
		this.parent = parent;
	}

	@Override
	public void initGui() {
		reloadRows();
		this.scroll = 0;
		this.compiling = false;
		this.cancelled = false;
		this.runIndex = 0;
		this.runTotal = 0;

		final int bottom = this.height - 27;
		final int center = this.width / 2;

		this.backButton = new GuiButton(ID_BACK, center - 154, bottom, 100, 20, I18n.format("options.actinium.precompile.back"));
		this.selectAllButton = new GuiButton(ID_SELECT_ALL, center - 51, bottom, 100, 20, I18n.format("options.actinium.precompile.selectAll"));
		this.clearAllButton = new GuiButton(ID_CLEAR_ALL, center + 52, bottom, 100, 20, I18n.format("options.actinium.precompile.clearAll"));
		this.startButton = new GuiButton(ID_START, center - 51, bottom - 24, 203, 20, I18n.format("options.actinium.precompile.start"));
		this.stopButton = new GuiButton(ID_STOP, center - 51, bottom - 24, 203, 20, I18n.format("options.actinium.precompile.stop"));

		this.buttonList.add(this.backButton);
		this.buttonList.add(this.selectAllButton);
		this.buttonList.add(this.clearAllButton);
		this.buttonList.add(this.startButton);
		this.buttonList.add(this.stopButton);

		updateButtonState();
	}

	private void reloadRows() {
		this.rows.clear();
		for (Iris.PrecompileEntry entry : Iris.precompileFolders()) {
			this.rows.add(new Row(entry));
		}
	}

	private void refreshCompiledState() {
		for (Row row : this.rows) {
			row.compiled = Iris.isFolderCompiled(row.folder);
			row.running = false;
			row.processed = false;
		}
	}

	private void updateButtonState() {
		final boolean ready = canCompile();
		this.backButton.visible = !this.compiling;
		this.selectAllButton.visible = !this.compiling;
		this.clearAllButton.visible = !this.compiling;
		this.startButton.visible = !this.compiling;
		this.stopButton.visible = this.compiling;
		this.selectAllButton.enabled = ready;
		this.clearAllButton.enabled = ready;
		this.startButton.enabled = ready && anySelected();
		this.stopButton.enabled = this.compiling;
	}

	private boolean canCompile() {
		return !this.rows.isEmpty() && Iris.currentPackSettings() != null;
	}

	private boolean anySelected() {
		for (Row row : this.rows) {
			if (row.selected) return true;
		}
		return false;
	}

	@Override
	protected void actionPerformed(GuiButton button) throws IOException {
		switch (button.id) {
			case ID_BACK -> this.mc.displayGuiScreen(this.parent);
			case ID_SELECT_ALL -> {
				for (Row row : this.rows) row.selected = true;
				updateButtonState();
			}
			case ID_CLEAR_ALL -> {
				for (Row row : this.rows) row.selected = false;
				updateButtonState();
			}
			case ID_START -> startRun();
			case ID_STOP -> this.cancelled = true;
			default -> { }
		}
	}

	private void startRun() {
		this.compiling = true;
		this.cancelled = false;
		this.runIndex = 0;
		this.runTotal = 0;
		for (Row row : this.rows) {
			row.processed = false;
			if (row.selected) this.runTotal++;
		}
		updateButtonState();
	}

	@Override
	public void updateScreen() {
		if (!this.compiling) return;
		if (this.cancelled || this.runIndex >= this.runTotal) {
			finishRun();
			return;
		}

		final Row row = nextPendingRow();
		if (row == null) {
			finishRun();
			return;
		}

		row.running = true;
		try {
			if (Iris.isFolderCompiled(row.folder)) {
				Iris.forgetFolderCompilation(row.folder);
			}
			row.compiled = Iris.compileFolder(row.folder);
		} finally {
			row.running = false;
			row.processed = true;
		}
		this.runIndex++;
	}

	private @Nullable Row nextPendingRow() {
		for (Row row : this.rows) {
			if (row.selected && !row.processed) return row;
		}
		return null;
	}

	private void finishRun() {
		this.compiling = false;
		this.cancelled = false;
		this.runIndex = 0;
		this.runTotal = 0;
		refreshCompiledState();
		updateButtonState();
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();

		drawCenteredString(this.fontRenderer, I18n.format("options.actinium.precompile.title"), this.width / 2, 12, 0xFFFFFF);
		final String packName = Iris.getCurrentPackName();
		final String subtitle = packName == null
			? I18n.format("options.actinium.precompile.noPack")
			: I18n.format("options.actinium.precompile.pack", packName);
		drawCenteredString(this.fontRenderer, subtitle, this.width / 2, 26, COLOR_NORMAL);

		final int listTop = LIST_TOP;
		final int listBottom = this.height - LIST_BOTTOM_MARGIN;

		if (!canCompile() && !this.rows.isEmpty()) {
			drawCenteredString(this.fontRenderer, I18n.format("options.actinium.precompile.cacheOff"),
				this.width / 2, (listTop + listBottom) / 2, 0xFF5555);
		}

		final int left = 30;
		final int right = this.width - 30;
		final int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		final int maxScroll = Math.max(0, this.rows.size() - visibleRows);
		if (this.scroll > maxScroll) this.scroll = maxScroll;

		GlStateManager.enableBlend();
		for (int i = 0; i < visibleRows; i++) {
			final int rowIndex = this.scroll + i;
			if (rowIndex >= this.rows.size()) break;
			final Row row = this.rows.get(rowIndex);
			final int y = listTop + i * ROW_HEIGHT;
			final boolean hovered = mouseX >= left && mouseX <= right && mouseY >= y && mouseY < y + ROW_HEIGHT;
			drawRow(row, left, y, right, hovered);
		}
		GlStateManager.disableBlend();

		if (maxScroll > 0) {
			drawScrollbar(listTop, listBottom, visibleRows, maxScroll);
		}

		if (this.compiling) {
			final String progress = I18n.format("options.actinium.precompile.progress", this.runIndex, this.runTotal);
			drawCenteredString(this.fontRenderer, progress, this.width / 2, this.height - 52, COLOR_RUNNING);
		}

		super.drawScreen(mouseX, mouseY, partialTicks);
	}

	private void drawRow(Row row, int left, int y, int right, boolean hovered) {
		if (hovered) {
			drawRect(left, y, right, y + ROW_HEIGHT - 2, COLOR_ROW_HOVER);
		}

		final int boxY = y + (ROW_HEIGHT - BOX_SIZE) / 2 - 1;
		drawRect(left + 2, boxY, left + 2 + BOX_SIZE, boxY + BOX_SIZE, 0xFF000000);
		drawRect(left + 3, boxY + 1, left + 1 + BOX_SIZE, boxY + BOX_SIZE - 1, 0xFF8B8B8B);
		if (row.selected) {
			drawRect(left + 4, boxY + 2, left + BOX_SIZE, boxY + BOX_SIZE - 2, 0xFF4CD964);
		}

		final int textColor = row.selected ? COLOR_SELECTED : COLOR_NORMAL;
		this.fontRenderer.drawString(row.displayName, left + 2 + BOX_SIZE + 6, y + 5, textColor);

		final String status;
		final int statusColor;
		if (row.running) {
			status = I18n.format("options.actinium.precompile.status.running");
			statusColor = COLOR_RUNNING;
		} else if (row.compiled) {
			status = I18n.format("options.actinium.precompile.status.compiled");
			statusColor = COLOR_DONE;
		} else {
			status = I18n.format("options.actinium.precompile.status.pending");
			statusColor = COLOR_PENDING;
		}
		this.fontRenderer.drawString(status, right - 8 - this.fontRenderer.getStringWidth(status), y + 5, statusColor);
	}

	private void drawScrollbar(int listTop, int listBottom, int visibleRows, int maxScroll) {
		final int x = this.width - 22;
		final int trackHeight = listBottom - listTop;
		drawRect(x, listTop, x + SCROLLBAR_WIDTH, listBottom, 0x60000000);
		final int thumbHeight = Math.max(16, trackHeight * visibleRows / this.rows.size());
		final int thumbY = listTop + (trackHeight - thumbHeight) * this.scroll / maxScroll;
		drawRect(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xFFB0B0B0);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
		super.mouseClicked(mouseX, mouseY, mouseButton);
		if (this.compiling || mouseButton != 0) return;

		final int left = 30;
		final int right = this.width - 30;
		final int listTop = LIST_TOP;
		final int listBottom = this.height - LIST_BOTTOM_MARGIN;
		if (mouseY < listTop || mouseY >= listBottom || mouseX < left || mouseX > right) return;

		final int rowIndex = this.scroll + (mouseY - listTop) / ROW_HEIGHT;
		if (rowIndex < 0 || rowIndex >= this.rows.size()) return;

		final Row row = this.rows.get(rowIndex);
		row.selected = !row.selected;
		updateButtonState();
	}

	@Override
	public void handleMouseInput() throws IOException {
		super.handleMouseInput();
		final int wheel = Mouse.getDWheel();
		if (wheel == 0 || this.rows.isEmpty()) return;

		final int visibleRows = Math.max(1, (this.height - LIST_BOTTOM_MARGIN - LIST_TOP) / ROW_HEIGHT);
		final int maxScroll = Math.max(0, this.rows.size() - visibleRows);
		this.scroll = Math.max(0, Math.min(maxScroll, this.scroll - Integer.signum(wheel)));
	}

	@Override
	public void onGuiClosed() {
		if (this.compiling) this.cancelled = true;
	}
}
