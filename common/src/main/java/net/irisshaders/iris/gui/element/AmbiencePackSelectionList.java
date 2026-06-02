package net.irisshaders.iris.gui.element;

import net.irisshaders.iris.ambience.AmbienceDependencyStatus;
import net.irisshaders.iris.ambience.AmbiencePack;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.gui.GuiUtil;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.gui.screen.AmbiencePackScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class AmbiencePackSelectionList extends IrisObjectSelectionList<AmbiencePackSelectionList.BaseEntry> {
	private static final int BUTTON_HEIGHT = 20;
	private static final Identifier MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_background.png");

	private final AmbiencePackScreen screen;
	private final EnableAmbienceRowEntry enableRow;

	public AmbiencePackSelectionList(AmbiencePackScreen screen, Minecraft client, int width, int height, int top, int bottom, int left, int right) {
		super(client, width, bottom, top + 4, bottom, left, right, 24);
		this.screen = screen;
		this.enableRow = new EnableAmbienceRowEntry(this);
		refresh(screen.getPacks());
	}

	public void refresh(List<AmbiencePackManager.LoadedAmbiencePack> packs) {
		clearEntries();
		addEntry(enableRow);
		enableRow.allowEnableAmbienceButton = !packs.isEmpty();

		if (packs.isEmpty()) {
			addLabelEntries(
				Component.empty(),
				Component.translatable("options.iris.wynncraftAmbienceNoPacks"),
				Component.literal(screen.getAmbiencePackDirectory().toString()).withStyle(ChatFormatting.GRAY)
			);
			return;
		}

		for (int i = 0; i < packs.size(); i++) {
			AmbiencePackManager.LoadedAmbiencePack loaded = packs.get(i);
			AmbiencePackEntry entry = new AmbiencePackEntry(this.children().size(), this, loaded, i);
			if (loaded.pack().id.equals(IrisVideoSettings.wynncraftSelectedAmbiencePack)) {
				setSelected(entry);
				setFocused(entry);
				centerScrollOn(entry);
			}
			addEntry(entry);
		}

	}

	public void selectPackId(String id) {
		for (BaseEntry entry : children()) {
			if (entry instanceof AmbiencePackEntry packEntry && packEntry.pack().id.equals(id)) {
				setSelected(packEntry);
				centerScrollOn(packEntry);
				return;
			}
		}
	}

	public EnableAmbienceRowEntry getEnableRow() {
		return enableRow;
	}

	@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {
		float transition = screen.listTransition.getAsFloat();
		if (transition < 0.02f) {
			return;
		}
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED,
			MENU_LIST_BACKGROUND,
			this.getX(), this.getY(), (float) this.getRight(), (float) (this.getBottom() + (int) this.scrollAmount()), this.getWidth(), this.getHeight(), 32, 32);
	}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {
		float transition = screen.listTransition.getAsFloat();
		if (transition < 0.02f) {
			return;
		}
		int col = ARGB.colorFromFloat(transition, 1.0f, 1.0f, 1.0f);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, CreateWorldScreen.HEADER_SEPARATOR, this.getX(), this.getY() - 2, 0.0F, 0.0F, this.getWidth(), 2, 32, 2, col);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, CreateWorldScreen.FOOTER_SEPARATOR, this.getX(), this.getBottom(), 0.0F, 0.0F, this.getWidth(), 2, 32, 2, col);
	}

	@Override
	public int getRowWidth() {
		return Math.min(308, width - 50);
	}

	@Override
	public int getRowTop(int index) {
		return super.getRowTop(index) + 2;
	}

	private void addLabelEntries(Component... lines) {
		for (Component text : lines) {
			addEntry(new LabelEntry(text));
		}
	}

	public static abstract class BaseEntry extends AbstractSelectionList.Entry<BaseEntry> {
		protected BaseEntry() {
		}
	}

	public static class LabelEntry extends BaseEntry {
		private final Component label;

		public LabelEntry(Component label) {
			this.label = label;
		}

		@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean isHovered, float tickDelta) {
			int x = getContentX();
			int y = getContentY();
			int entryWidth = getContentWidth();
			int entryHeight = getContentHeight();
			Font font = Minecraft.getInstance().font;
			Component renderedLabel = label;
			if (font.width(label) > entryWidth - 8) {
				renderedLabel = Component.literal(font.plainSubstrByWidth(label.getString(), entryWidth - 20) + "...").setStyle(label.getStyle());
			}
			guiGraphics.drawCenteredString(font, renderedLabel, (x + entryWidth / 2) - 2, y + (entryHeight - 11) / 2, 0xFFC2C2C2);
		}
	}

	public static class EnableAmbienceRowEntry extends BaseEntry {
		private static final Component NONE_PRESENT_LABEL = Component.translatable("options.iris.ambience.nonePresent").withStyle(ChatFormatting.GRAY);
		private static final Component AMBIENCE_DISABLED_LABEL = Component.translatable("options.iris.ambience.disabled");
		private static final Component AMBIENCE_ENABLED_LABEL = Component.translatable("options.iris.ambience.enabled");

		private final AmbiencePackSelectionList list;
		public boolean allowEnableAmbienceButton = true;

		public EnableAmbienceRowEntry(AmbiencePackSelectionList list) {
			this.list = list;
		}

		@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean isHovered, float tickDelta) {
			int x = getContentX();
			int y = getContentY();
			int entryWidth = getContentWidth();

			GuiUtil.bindIrisWidgetsTexture();
			GuiUtil.drawButton(guiGraphics, x - 2, y - 2, entryWidth, BUTTON_HEIGHT + 2, isHovered, !allowEnableAmbienceButton);
			guiGraphics.drawCenteredString(Minecraft.getInstance().font, getEnableDisableLabel(), (x + entryWidth / 2) - 2, y + (BUTTON_HEIGHT - 11) / 2, 0xFFFFFFFF);
		}

		private Component getEnableDisableLabel() {
			return allowEnableAmbienceButton ? IrisVideoSettings.wynncraftAmbienceEnabled ? AMBIENCE_ENABLED_LABEL : AMBIENCE_DISABLED_LABEL : NONE_PRESENT_LABEL;
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean repeat) {
			if (event.button() != 0 || !allowEnableAmbienceButton) {
				return false;
			}
			list.screen.setAmbienceEnabled(!IrisVideoSettings.wynncraftAmbienceEnabled);
			GuiUtil.playButtonClickSound();
			return true;
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (!event.isConfirmation() || !allowEnableAmbienceButton) {
				return false;
			}
			list.screen.setAmbienceEnabled(!IrisVideoSettings.wynncraftAmbienceEnabled);
			GuiUtil.playButtonClickSound();
			return true;
		}

		@Nullable
		@Override
		public ComponentPath nextFocusPath(FocusNavigationEvent event) {
			return !isFocused() ? ComponentPath.leaf(this) : null;
		}

		public boolean isFocused() {
			return this.list.getFocused() == this;
		}
	}

	public static class AmbiencePackEntry extends BaseEntry {
		private final AmbiencePackSelectionList list;
		private final AmbiencePackManager.LoadedAmbiencePack loaded;
		private final int index;
		private final int packNumber;
		private ScreenRectangle bounds = ScreenRectangle.empty();

		public AmbiencePackEntry(int index, AmbiencePackSelectionList list, AmbiencePackManager.LoadedAmbiencePack loaded, int packNumber) {
			this.index = index;
			this.list = list;
			this.loaded = loaded;
			this.packNumber = packNumber;
		}

		@Override
		public ScreenRectangle getRectangle() {
			return bounds;
		}

		public AmbiencePack pack() {
			return loaded.pack();
		}

		public boolean isSelected() {
			return list.getSelected() == this;
		}

		@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean isHovered, float tickDelta) {
			int x = getContentX();
			int y = getContentY();
			int entryWidth = getContentWidth();
			this.bounds = new ScreenRectangle(x, y, entryWidth, BUTTON_HEIGHT);

			if (isHovered || isSelected()) {
				GuiUtil.bindIrisWidgetsTexture();
				GuiUtil.drawButton(guiGraphics, x - 2, y - 2, entryWidth, BUTTON_HEIGHT + 2, isHovered, false);
			}

			Font font = Minecraft.getInstance().font;
			AmbiencePack pack = loaded.pack();
			AmbienceDependencyStatus dependencyStatus = AmbiencePackManager.getInstance().dependencyStatus(pack);

			boolean missingDependencies = dependencyStatus.hasMissing();
			Component subtitle = missingDependencies
				? Component.translatable("options.iris.wynncraftAmbiencePackMissingShort", dependencyStatus.missing().size())
				: Component.translatable("options.iris.wynncraftAmbiencePackReady");
			int subtitleColor = missingDependencies ? 0xFFFFAAAA : 0xFFAAFFAA;
			int subtitleWidth = font.width(subtitle);

			String displayName = pack.displayName();
			int titleMaxWidth = Math.max(24, entryWidth - subtitleWidth - 18);
			if (font.width(displayName) > titleMaxWidth) {
				displayName = font.plainSubstrByWidth(displayName, titleMaxWidth - font.width("...")) + "...";
			}

			MutableComponent title = Component.literal(displayName);
			if (isHovered) {
				title = title.withStyle(ChatFormatting.BOLD);
			}

			int titleColor = 0xFFFFFFFF;
			if (IrisVideoSettings.wynncraftAmbienceEnabled && isSelected()) {
				titleColor = 0xFFFFF263;
			} else if (!IrisVideoSettings.wynncraftAmbienceEnabled && !isHovered) {
				titleColor = 0xFFA2A2A2;
			}

			int textY = y + (BUTTON_HEIGHT - 11) / 2;
			guiGraphics.drawString(font, title, x + 4, textY, titleColor);
			guiGraphics.drawString(font, subtitle, x + entryWidth - subtitleWidth - 6, textY, subtitleColor);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean repeat) {
			if (event.button() != 0) {
				return false;
			}
			return select();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (!event.isConfirmation()) {
				return false;
			}
			return select();
		}

		private boolean select() {
			if (!isSelected()) {
				list.select(index);
			}
			list.screen.selectPack(packNumber);
			return true;
		}

		@Nullable
		@Override
		public ComponentPath nextFocusPath(FocusNavigationEvent event) {
			return !isFocused() ? ComponentPath.leaf(this) : null;
		}

		public boolean isFocused() {
			return this.list.getFocused() == this;
		}
	}
}
