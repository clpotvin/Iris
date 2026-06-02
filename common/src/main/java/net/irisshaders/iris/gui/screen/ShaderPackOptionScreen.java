package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.gui.element.widget.AbstractElementWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public interface ShaderPackOptionScreen {
	float iris$getListTransition();

	Component iris$getOptionMenuTitle();

	boolean isDisplayingComment();

	void setElementHoveredStatus(AbstractElementWidget<?> widget, boolean hovered);

	void displayNotification(Component component);

	void applyChanges();

	void resetShaderPackOptions();

	void importPackOptions(Path settingFile);

	void exportPackOptions(Path settingFile);

	Path getDefaultShaderPackOptionsPath();

	static void renderTopLayerQueue() {
		for (Runnable render : ShaderPackScreen.TOP_LAYER_RENDER_QUEUE) {
			render.run();
		}
		ShaderPackScreen.TOP_LAYER_RENDER_QUEUE.clear();
	}
}
