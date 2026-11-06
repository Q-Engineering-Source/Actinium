package net.coderbot.iris.gui.screen;

import net.coderbot.iris.Iris;
import net.coderbot.iris.PrecompileQueue;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tree of the shaderpacks folder, the packs inside it and their shader folders. Starting a run hands the selected
 * folders to {@link PrecompileQueue}, which compiles one per rendered frame, so the screen only starts a run and
 * shows its progress.
 */
public class PrecompileScreen extends GuiScreen {
	private static final int ROW_HEIGHT = 20;
	private static final int LIST_TOP = 44;
	private static final int LIST_BOTTOM_MARGIN = 62;
	private static final int BOX_SIZE = 10;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int INDENT = 14;

	private static final int COLOR_SELECTED = 0xFFFFFF;
	private static final int COLOR_NORMAL = 0xA0A0A0;
	private static final int COLOR_DONE = 0x55FF55;
	private static final int COLOR_RUNNING = 0xFFD755;
	private static final int COLOR_PENDING = 0x909090;
	private static final int COLOR_FAILED = 0xFF5555;
	private static final int COLOR_ROW_HOVER = 0x30FFFFFF;

	private static final int ID_BACK = 0;
	private static final int ID_START = 1;

	private final GuiScreen parent;
	private final List<PackNode> packs = new ArrayList<>();
	private final List<Row> visibleRows = new ArrayList<>();

	private boolean rootExpanded = true;
	private int scroll;
	private boolean wasRunning;
	@Nullable
	private PrecompileQueue.Job activeJob;

	private GuiButton backButton;
	private GuiButton startButton;

	private static final class PackNode {
		private static String key(@Nullable String folder) {
			return folder == null ? "" : folder;
		}

		private final String packName;
		private boolean expanded;
		private boolean failed;
		private boolean defaultSelected = true;
		private final Map<String, Boolean> overrides = new HashMap<>();
		private @Nullable List<Iris.PrecompileEntry> folders;

		private PackNode(String packName) {
			this.packName = packName;
		}

		private boolean isSelected(@Nullable String folder) {
			final Boolean override = this.overrides.get(key(folder));
			return override != null ? override : this.defaultSelected;
		}

		private void setSelected(@Nullable String folder, boolean value) {
			this.overrides.put(key(folder), value);
		}

		private void setAll(boolean value) {
			this.defaultSelected = value;
			this.overrides.clear();
		}

		private boolean isWholePackSelected() {
			return this.defaultSelected && this.overrides.isEmpty();
		}

		private boolean isPackSelected() {
			if (this.folders == null || this.folders.isEmpty()) return isWholePackSelected();
			int selected = 0;
			for (Iris.PrecompileEntry entry : this.folders) {
				if (isSelected(entry.folder())) selected++;
			}
			return selected == this.folders.size();
		}

		private int compiledCount() {
			if (this.folders == null) return 0;
			int count = 0;
			for (Iris.PrecompileEntry entry : this.folders) {
				if (entry.compiled()) count++;
			}
			return count;
		}
	}

	private sealed interface Row permits RootRow, PackRow, FolderRow {}

	private record RootRow() implements Row {}

	private record PackRow(PackNode pack) implements Row {}

	private record FolderRow(PackNode pack, Iris.PrecompileEntry entry) implements Row {}

	public PrecompileScreen(@Nullable GuiScreen parent) {
		this.parent = parent;
	}

	@Override
	public void initGui() {
		this.packs.clear();
		for (String packName : Iris.listShaderPacks()) {
			this.packs.add(new PackNode(packName));
		}
		this.rootExpanded = true;
		this.scroll = 0;
		this.activeJob = null;
		this.wasRunning = PrecompileQueue.isRunning();
		rebuildRows();

		final int bottom = this.height - 27;
		final int center = this.width / 2;

		this.backButton = new GuiButton(ID_BACK, center - 101, bottom, 203, 20, I18n.format("options.actinium.precompile.back"));
		this.startButton = new GuiButton(ID_START, center - 101, bottom - 24, 203, 20, I18n.format("options.actinium.precompile.start"));

		this.buttonList.add(this.backButton);
		this.buttonList.add(this.startButton);

		updateButtonState();
	}

	private void rebuildRows() {
		this.visibleRows.clear();
		this.visibleRows.add(new RootRow());
		if (!this.rootExpanded) return;
		for (PackNode pack : this.packs) {
			this.visibleRows.add(new PackRow(pack));
			if (!pack.expanded || pack.folders == null) continue;
			for (Iris.PrecompileEntry entry : pack.folders) {
				this.visibleRows.add(new FolderRow(pack, entry));
			}
		}
	}

	@Nullable
	private PackNode findPack(String packName) {
		for (PackNode pack : this.packs) {
			if (pack.packName.equals(packName)) return pack;
		}
		return null;
	}

	private void loadFolders(PackNode pack) {
		if (pack.folders != null) return;
		final List<Iris.PrecompileEntry> entries = Iris.packEntries(pack.packName);
		pack.failed = entries == null;
		pack.folders = entries == null ? List.of() : entries;
		if (entries == null) {
			Iris.logger.warn("Could not read the shader folders of pack '{}'", pack.packName);
		}
	}

	private void refreshCompiledState() {
		for (PackNode pack : this.packs) {
			if (pack.folders == null) continue;
			final List<Iris.PrecompileEntry> entries = Iris.packEntries(pack.packName);
			if (entries != null) pack.folders = entries;
		}
		rebuildRows();
	}

	private boolean allSelected() {
		for (PackNode pack : this.packs) {
			if (!pack.defaultSelected) return false;
			if (!pack.overrides.isEmpty()) return false;
		}
		return true;
	}

	private void setAllSelected(boolean value) {
		for (PackNode pack : this.packs) {
			pack.setAll(value);
		}
		updateButtonState();
	}

	private boolean hasAnySelection() {
		for (PackNode pack : this.packs) {
			if (pack.isWholePackSelected()) return true;
			if (pack.folders == null) continue;
			for (Iris.PrecompileEntry entry : pack.folders) {
				if (pack.isSelected(entry.folder())) return true;
			}
		}
		return false;
	}

	private void updateButtonState() {
		final boolean running = PrecompileQueue.isRunning();
		final boolean ready = !this.packs.isEmpty();
		this.backButton.visible = !running;
		this.backButton.enabled = !running;
		this.startButton.visible = true;
		this.startButton.enabled = running || ready && hasAnySelection();
		this.startButton.displayString = I18n.format(running
			? "options.actinium.precompile.stop"
			: "options.actinium.precompile.start");
	}

	@Override
	protected void actionPerformed(GuiButton button) throws IOException {
		switch (button.id) {
			case ID_BACK -> this.mc.displayGuiScreen(this.parent);
			case ID_START -> {
				if (PrecompileQueue.isRunning()) {
					PrecompileQueue.cancel();
					updateButtonState();
				} else {
					startRun();
				}
			}
			default -> { }
		}
	}

	private void startRun() {
		final List<PrecompileQueue.Job> jobs = new ArrayList<>();
		for (PackNode pack : this.packs) {
			if (pack.folders == null && pack.isWholePackSelected()) loadFolders(pack);
			if (pack.folders == null) continue;
			for (Iris.PrecompileEntry entry : pack.folders) {
				if (pack.isSelected(entry.folder())) {
					jobs.add(new PrecompileQueue.Job(pack.packName, entry.folder(), entry.displayName()));
				}
			}
		}
		if (jobs.isEmpty()) {
			Iris.logger.warn("Nothing to precompile: no folder is selected out of {} packs", this.packs.size());
			return;
		}
		PrecompileQueue.start(jobs);
		this.wasRunning = true;
		this.activeJob = null;
		updateButtonState();
	}

	/** Mirrors the queue state onto the tree: expands the pack being compiled and refreshes when the run ends. */
	private void syncQueue() {
		final boolean running = PrecompileQueue.isRunning();
		if (this.wasRunning && !running) {
			this.wasRunning = false;
			if (this.activeJob != null) {
				final PackNode pack = findPack(this.activeJob.packName());
				if (pack != null) pack.expanded = false;
				this.activeJob = null;
				rebuildRows();
			}
			refreshCompiledState();
		}
		if (running) {
			this.wasRunning = true;
			final PrecompileQueue.Job job = PrecompileQueue.currentJob();
			if (job != null && !job.equals(this.activeJob)) {
				if (this.activeJob == null || !job.packName().equals(this.activeJob.packName())) {
					expandPack(job);
				} else {
					this.activeJob = job;
					scrollTo(job);
				}
			}
		}
		updateButtonState();
	}

	private void expandPack(PrecompileQueue.Job job) {
		if (this.activeJob != null) {
			final PackNode previous = findPack(this.activeJob.packName());
			if (previous != null) previous.expanded = false;
		}
		this.activeJob = job;
		this.rootExpanded = true;
		final PackNode pack = findPack(job.packName());
		if (pack == null) return;
		loadFolders(pack);
		pack.expanded = true;
		rebuildRows();
		scrollTo(job);
	}

	private void scrollTo(PrecompileQueue.Job job) {
		int packRow = -1;
		for (int i = 0; i < this.visibleRows.size(); i++) {
			final Row visible = this.visibleRows.get(i);
			if (visible instanceof PackRow row && row.pack().packName.equals(job.packName())) {
				packRow = i;
			} else if (visible instanceof FolderRow row
				&& row.pack().packName.equals(job.packName())
				&& Objects.equals(row.entry().folder(), job.folder())) {
				scrollToRow(i);
				return;
			}
		}
		if (packRow >= 0) scrollToRow(packRow);
	}

	private void scrollToRow(int row) {
		final int visibleCount = Math.max(1, listRowArea() / ROW_HEIGHT);
		if (row < this.scroll) {
			this.scroll = row;
		} else if (row >= this.scroll + visibleCount) {
			this.scroll = row - visibleCount + 1;
		}
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		syncQueue();

		final int visibleCount = Math.max(1, (this.height - LIST_BOTTOM_MARGIN - LIST_TOP) / ROW_HEIGHT);
		final int maxScroll = Math.max(0, this.visibleRows.size() - visibleCount);
		if (this.scroll > maxScroll) this.scroll = maxScroll;

		drawCenteredString(this.fontRenderer, I18n.format("options.actinium.precompile.title"), this.width / 2, 12, 0xFFFFFF);
		if (PrecompileQueue.isRunning()) {
			final String progress = I18n.format("options.actinium.precompile.progress",
				PrecompileQueue.done(), PrecompileQueue.total());
			drawCenteredString(this.fontRenderer, progress, this.width / 2, 28, COLOR_RUNNING);
		}

		final int listTop = LIST_TOP;
		final int listBottom = this.height - LIST_BOTTOM_MARGIN;
		final int left = 30;
		final int right = this.width - 30;

		GlStateManager.enableBlend();
		for (int i = 0; i < visibleCount; i++) {
			final int rowIndex = this.scroll + i;
			if (rowIndex >= this.visibleRows.size()) break;
			final int y = listTop + i * ROW_HEIGHT;
			final boolean hovered = mouseX >= left && mouseX <= right && mouseY >= y && mouseY < y + ROW_HEIGHT;
			switch (this.visibleRows.get(rowIndex)) {
				case RootRow ignored -> drawRootRow(left, y, right, hovered);
				case PackRow row -> drawPackRow(row.pack(), left, y, right, hovered);
				case FolderRow row -> drawFolderRow(row, left, y, right, hovered);
			}
		}
		GlStateManager.disableBlend();

		if (maxScroll > 0) {
			drawScrollbar(listTop, listBottom, visibleCount, maxScroll);
		}

		super.drawScreen(mouseX, mouseY, partialTicks);
	}

	private void drawRootRow(int left, int y, int right, boolean hovered) {
		if (hovered) {
			drawRect(left, y, right, y + ROW_HEIGHT - 2, COLOR_ROW_HOVER);
		}
		if (PrecompileQueue.isRunning()) {
			drawRowProgress(left, y, right, PrecompileQueue.overallProgress());
		}

		this.fontRenderer.drawString(this.rootExpanded ? "v" : ">", left + 3, y + 5, COLOR_SELECTED);

		final int boxX = left + 14;
		final int boxY = y + (ROW_HEIGHT - BOX_SIZE) / 2 - 1;
		drawCheckbox(boxX, boxY, allSelected());

		this.fontRenderer.drawString(I18n.format("options.actinium.precompile.root"), boxX + BOX_SIZE + 6, y + 5, COLOR_SELECTED);

		int compiled = 0;
		int total = 0;
		for (PackNode pack : this.packs) {
			compiled += pack.compiledCount();
			total += pack.folders == null ? 0 : pack.folders.size();
		}
		final String summary = I18n.format("options.actinium.precompile.packSummary", compiled, total);
		this.fontRenderer.drawString(summary, right - 8 - this.fontRenderer.getStringWidth(summary), y + 5,
			compiled == total && total > 0 ? COLOR_DONE : COLOR_PENDING);
	}

	private void drawPackRow(PackNode pack, int left, int y, int right, boolean hovered) {
		final int base = left + INDENT;
		if (hovered) {
			drawRect(base, y, right, y + ROW_HEIGHT - 2, COLOR_ROW_HOVER);
		}
		if (PrecompileQueue.isRunning()) {
			drawRowProgress(base, y, right, PrecompileQueue.packProgress(pack.packName));
		}

		this.fontRenderer.drawString(pack.expanded ? "v" : ">", base + 3, y + 5, COLOR_SELECTED);

		final int boxX = base + INDENT;
		final int boxY = y + (ROW_HEIGHT - BOX_SIZE) / 2 - 1;
		drawCheckbox(boxX, boxY, pack.isPackSelected());

		this.fontRenderer.drawString(pack.packName, boxX + BOX_SIZE + 6, y + 5, COLOR_SELECTED);

		final String summary;
		final int summaryColor;
		if (pack.failed) {
			summary = I18n.format("options.actinium.precompile.status.unreadable");
			summaryColor = COLOR_FAILED;
		} else if (pack.folders == null) {
			summary = I18n.format("options.actinium.precompile.status.folded");
			summaryColor = COLOR_PENDING;
		} else {
			summary = I18n.format("options.actinium.precompile.packSummary", pack.compiledCount(), pack.folders.size());
			summaryColor = pack.compiledCount() == pack.folders.size() ? COLOR_DONE : COLOR_PENDING;
		}
		this.fontRenderer.drawString(summary, right - 8 - this.fontRenderer.getStringWidth(summary), y + 5, summaryColor);
	}

	private void drawFolderRow(FolderRow row, int left, int y, int right, boolean hovered) {
		final int base = left + INDENT * 2;
		final PrecompileQueue.Job job = PrecompileQueue.currentJob();
		final boolean current = job != null
			&& job.packName().equals(row.pack().packName)
			&& Objects.equals(job.folder(), row.entry().folder());
		final float progress = PrecompileQueue.folderProgress(row.pack().packName, row.entry().folder());

		if (hovered) {
			drawRect(base, y, right, y + ROW_HEIGHT - 2, COLOR_ROW_HOVER);
		}
		if (PrecompileQueue.isRunning()) {
			drawRowProgress(base, y, right, progress);
		}

		final int boxX = base + 4;
		final int boxY = y + (ROW_HEIGHT - BOX_SIZE) / 2 - 1;
		drawCheckbox(boxX, boxY, row.pack().isSelected(row.entry().folder()));

		this.fontRenderer.drawString(row.entry().displayName(), boxX + BOX_SIZE + 6, y + 5,
			row.pack().isSelected(row.entry().folder()) ? COLOR_SELECTED : COLOR_NORMAL);

		final String status;
		final int statusColor;
		if (current) {
			status = I18n.format("options.actinium.precompile.status.running");
			statusColor = COLOR_RUNNING;
		} else if (row.entry().compiled() || PrecompileQueue.isRunning() && progress >= 1.0F) {
			status = I18n.format("options.actinium.precompile.status.compiled");
			statusColor = COLOR_DONE;
		} else {
			status = I18n.format("options.actinium.precompile.status.pending");
			statusColor = COLOR_PENDING;
		}
		this.fontRenderer.drawString(status, right - 8 - this.fontRenderer.getStringWidth(status), y + 5, statusColor);
	}

	private void drawRowProgress(int left, int y, int right, float progress) {
		if (progress <= 0.0F) return;
		final int filled = (int) ((right - left) * Math.min(progress, 1.0F));
		drawRect(left, y, left + filled, y + ROW_HEIGHT - 2, 0x804CD964);
	}

	private void drawCheckbox(int boxX, int boxY, boolean checked) {
		drawRect(boxX, boxY, boxX + BOX_SIZE, boxY + BOX_SIZE, 0xFF000000);
		drawRect(boxX + 1, boxY + 1, boxX + BOX_SIZE - 1, boxY + BOX_SIZE - 1, 0xFF8B8B8B);
		if (checked) {
			drawRect(boxX + 2, boxY + 2, boxX + BOX_SIZE - 2, boxY + BOX_SIZE - 2, 0xFF4CD964);
		}
	}

	private void drawScrollbar(int listTop, int listBottom, int visibleCount, int maxScroll) {
		final int x = this.width - 22;
		final int trackHeight = listBottom - listTop;
		drawRect(x, listTop, x + SCROLLBAR_WIDTH, listBottom, 0x60000000);
		final int thumbHeight = Math.max(16, trackHeight * visibleCount / this.visibleRows.size());
		final int thumbY = listTop + (trackHeight - thumbHeight) * this.scroll / maxScroll;
		drawRect(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xFFB0B0B0);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
		super.mouseClicked(mouseX, mouseY, mouseButton);
		if (PrecompileQueue.isRunning() || mouseButton != 0) return;

		final int left = 30;
		final int right = this.width - 30;
		final int listTop = LIST_TOP;
		final int listBottom = this.height - LIST_BOTTOM_MARGIN;
		if (mouseY < listTop || mouseY >= listBottom || mouseX < left || mouseX > right) return;

		final int rowIndex = this.scroll + (mouseY - listTop) / ROW_HEIGHT;
		if (rowIndex < 0 || rowIndex >= this.visibleRows.size()) return;

		switch (this.visibleRows.get(rowIndex)) {
			case RootRow ignored -> {
				final int boxX = left + 14;
				if (mouseX >= boxX && mouseX < boxX + BOX_SIZE + 4) {
					setAllSelected(!allSelected());
				} else {
					this.rootExpanded = !this.rootExpanded;
					rebuildRows();
				}
			}
			case PackRow row -> {
				final PackNode pack = row.pack();
				final int boxX = left + INDENT + INDENT;
				if (mouseX >= boxX && mouseX < boxX + BOX_SIZE + 4) {
					pack.setAll(!pack.isPackSelected());
					updateButtonState();
				} else {
					pack.expanded = !pack.expanded;
					if (pack.expanded) loadFolders(pack);
					rebuildRows();
				}
			}
			case FolderRow row -> {
				row.pack().setSelected(row.entry().folder(), !row.pack().isSelected(row.entry().folder()));
				updateButtonState();
			}
		}
	}

	@Override
	public void handleMouseInput() throws IOException {
		super.handleMouseInput();
		final int wheel = Mouse.getDWheel();
		if (wheel == 0 || this.visibleRows.isEmpty()) return;

		final int visibleCount = Math.max(1, listRowArea() / ROW_HEIGHT);
		final int maxScroll = Math.max(0, this.visibleRows.size() - visibleCount);
		this.scroll = Math.max(0, Math.min(maxScroll, this.scroll - Integer.signum(wheel)));
	}

	private int listRowArea() {
		return this.height - LIST_BOTTOM_MARGIN - LIST_TOP;
	}

	@Override
	public void onGuiClosed() {
		if (PrecompileQueue.isRunning()) {
			Iris.logger.info("Precompile screen closed while the queue is still running; it keeps going in the background");
		}
	}
}
