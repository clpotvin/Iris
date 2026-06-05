package net.irisshaders.iris.gui.element;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;

public class IrisObjectSelectionList<E extends AbstractSelectionList.Entry<E>> extends AbstractSelectionList<E> {
	public IrisObjectSelectionList(Minecraft client, int width, int height, int top, int bottom, int left, int right, int itemHeight) {
		super(client, width, height, top, itemHeight);
	}

	@Override
	protected int scrollBarX() {
		// Position the scrollbar at the rightmost edge of the screen.
		// By default, the scrollbar is positioned moderately offset from the center.
		return width - 6;
	}

	public void select(int entry) {
		setSelected(this.children().get(entry));
	}

	@Override
	protected void renderSelection(GuiGraphics guiGraphics, E entry, int color) {
		int x = entry.getContentX() - 2;
		int y = entry.getContentY() - 2;
		int right = x + entry.getContentWidth();
		int bottom = y + entry.getContentHeight() + 2;
		guiGraphics.fill(x, y, right, bottom, color);
		guiGraphics.fill(x + 1, y + 1, right - 1, bottom - 1, 0xFF000000);
	}

	@Override
	public void updateWidgetNarration(NarrationElementOutput p0) {

	}
}
