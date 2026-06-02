package net.irisshaders.iris.gui.element;

import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.ambience.AmbienceProfile;
import net.irisshaders.iris.gui.GuiUtil;
import net.irisshaders.iris.gui.screen.AmbienceProfileSelectionScreen;
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
import java.util.Optional;

public class AmbienceProfileSelectionList extends IrisObjectSelectionList<AmbienceProfileSelectionList.BaseEntry> {
	private static final Component PROFILE_LIST_LABEL = Component.translatable("pack.iris.ambience.profile.list.label").withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY);
	private static final Identifier MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_background.png");

	private final AmbienceProfileSelectionScreen screen;

	public AmbienceProfileSelectionList(AmbienceProfileSelectionScreen screen, Minecraft client, int width, int height, int top, int bottom, int left, int right) {
		super(client, width, bottom, top + 4, bottom, left, right, 32);
		this.screen = screen;
		refresh();
	}

	public void refresh() {
		clearEntries();
		Optional<AmbiencePackManager.LoadedAmbiencePack> loaded = screen.getLoadedPack();
		if (loaded.isEmpty()) {
			addLabelEntries(Component.empty(), Component.translatable("options.iris.wynncraftAmbienceNoPacks"));
			return;
		}

		List<AmbienceProfile> profiles = loaded.get().pack().profiles;
		if (profiles == null || profiles.isEmpty()) {
			addLabelEntries(Component.empty(), Component.translatable("options.iris.wynncraftAmbienceNoProfiles"));
			return;
		}

		for (int i = 0; i < profiles.size(); i++) {
			AmbienceProfile profile = profiles.get(i);
			if (profile != null && profile.id != null && !profile.id.isBlank()) {
				addEntry(new ProfileEntry(this.children().size(), this, profile));
			}
		}

		addLabelEntries(PROFILE_LIST_LABEL);
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

	public static class ProfileEntry extends BaseEntry {
		private final AmbienceProfileSelectionList list;
		private final AmbienceProfile profile;
		private final int index;
		private ScreenRectangle bounds = ScreenRectangle.empty();

		public ProfileEntry(int index, AmbienceProfileSelectionList list, AmbienceProfile profile) {
			this.index = index;
			this.list = list;
			this.profile = profile;
		}

		@Override
		public ScreenRectangle getRectangle() {
			return bounds;
		}

		@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean isHovered, float tickDelta) {
			int x = getContentX();
			int y = getContentY();
			int entryWidth = getContentWidth();
			int entryHeight = getContentHeight();
			this.bounds = new ScreenRectangle(x, y, entryWidth, entryHeight);

			if (isHovered || isSelected()) {
				GuiUtil.bindIrisWidgetsTexture();
				GuiUtil.drawButton(guiGraphics, x - 2, y - 2, entryWidth + 4, entryHeight + 4, isHovered, false);
			}

			Font font = Minecraft.getInstance().font;
			Optional<AmbiencePackManager.ResolvedProfile> resolved = list.screen.resolveProfile(profile.id);
			int optionCount = profile.options == null ? 0 : profile.options.size();
			String titleText = profile.id;
			if (font.width(titleText) > entryWidth - 8) {
				titleText = font.plainSubstrByWidth(titleText, entryWidth - 20) + "...";
			}

			MutableComponent title = Component.literal(titleText);
			if (isHovered) {
				title = title.withStyle(ChatFormatting.BOLD);
			}

			Component subtitle = resolved
				.<Component>map(value -> Component.literal(value.resolvedShaderPack()).withStyle(ChatFormatting.GRAY))
				.orElseGet(() -> Component.translatable("options.iris.wynncraftAmbienceProfileMissingShader").withStyle(ChatFormatting.RED));
			if (font.width(subtitle) > entryWidth - 96) {
				subtitle = Component.literal(font.plainSubstrByWidth(subtitle.getString(), entryWidth - 108) + "...").setStyle(subtitle.getStyle());
			}
			Component count = Component.translatable("options.iris.wynncraftAmbienceProfileOptionCount", optionCount);

			guiGraphics.drawString(font, title, x + 4, y + 4, resolved.isPresent() ? 0xFFFFFFFF : 0xFFA2A2A2);
			guiGraphics.drawString(font, subtitle, x + 4, y + 16, 0xFFAAAAAA);
			guiGraphics.drawString(font, count, x + entryWidth - font.width(count) - 6, y + 16, 0xFFCCCCCC);
		}

		public boolean isSelected() {
			return list.getSelected() == this;
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
			list.screen.openProfile(profile.id);
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
