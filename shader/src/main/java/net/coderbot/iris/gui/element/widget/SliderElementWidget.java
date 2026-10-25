package net.coderbot.iris.gui.element.widget;

import net.coderbot.iris.gui.GuiUtil;
import net.coderbot.iris.shaderpack.option.menu.OptionMenuStringOptionElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;

public class SliderElementWidget extends StringElementWidget {
	private static final int PREVIEW_SLIDER_WIDTH = 4;

	private boolean mouseDown = false;

	public SliderElementWidget(OptionMenuStringOptionElement element) {
		super(element);
	}

	@Override
	public void drawScreen(int x, int y, int width, int height, int mouseX, int mouseY, float tickDelta, boolean hovered) {
		this.updateRenderParams(width, 35);

		if (!hovered) {
			this.renderOptionWithValue(x, y, width, height, false, (float)valueIndex / (valueCount - 1), PREVIEW_SLIDER_WIDTH);
		} else {
			this.renderSlider(x, y, width, height, mouseX, mouseY, tickDelta);
		}

		if (GuiScreen.isShiftKeyDown()) {
			renderTooltip(SET_TO_DEFAULT, mouseX, mouseY, hovered);
		} else if (!this.screen.isDisplayingComment()) {
			renderTooltip(this.unmodifiedLabel, mouseX, mouseY, hovered);
		}

		if (this.mouseDown) {
			// The drag keeps tracking the cursor even when it leaves the row or widget,
			// so the handle follows the mouse one-to-one; it only ends on mouse release
			// (release events are broadcast to every widget, see ElementRowEntry).
			whileDragging(x, width, mouseX);
		}
	}

	private void renderSlider(int x, int y, int width, int height, int mouseX, int mouseY, float tickDelta) {
		GuiUtil.bindIrisWidgetsTexture();

		GuiUtil.drawButton(x, y, width, height, false, false);
		GuiUtil.drawButton(x + 2, y + 2, width - 4, height - 4, false, true);

		final int sliderPos = SliderGeometry.sliderXForIndex(x, width, valueIndex, valueCount);
		GuiUtil.drawButton(sliderPos, y + 4, SliderGeometry.ACTIVE_SLIDER_WIDTH, height - 8, this.mouseDown, false);

		final FontRenderer font = Minecraft.getMinecraft().fontRenderer;
		font.drawStringWithShadow(this.valueLabel, (int)(x + (width * 0.5)) - (int)(font.getStringWidth(this.valueLabel) * 0.5), y + 7, 0xFFFFFF);
	}

	private void whileDragging(int x, int width, int mouseX) {
		final int newValueIndex = SliderGeometry.valueIndexForMouseX(mouseX, x, width, valueCount);

		if (valueIndex != newValueIndex) {
			this.valueIndex = newValueIndex;

			this.updateLabels();
		}
	}

	private void onReleased() {
		mouseDown = false;

		this.queue();
		this.navigation.refresh();

		GuiUtil.playButtonClickSound();
	}

	@Override
	public boolean mouseClicked(int mouseX, int mouseY, int button) {
		if (button == 0) {
			if (GuiScreen.isShiftKeyDown()) {
				if (this.applyOriginalValue()) {
					this.navigation.refresh();
				}
				GuiUtil.playButtonClickSound();

				return true;
			}

			mouseDown = true;
			GuiUtil.playButtonClickSound();

			return true;
		}

		// Do not use base widget's button click behavior
		return false;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (button == 0 && this.mouseDown) {
			this.onReleased();

			return true;
		}
		return super.mouseReleased(mx, my, button);
	}
}
