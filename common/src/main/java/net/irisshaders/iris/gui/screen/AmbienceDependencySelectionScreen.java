package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.ambience.AmbienceDependency;
import net.irisshaders.iris.ambience.AmbienceDependencyCandidate;
import net.irisshaders.iris.ambience.AmbienceDependencyResolver;
import net.irisshaders.iris.gui.GuiUtil;
import net.irisshaders.iris.gui.element.IrisObjectSelectionList;
import net.irisshaders.iris.gui.element.screen.IrisButton;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

public class AmbienceDependencySelectionScreen extends Screen {
	private static final Identifier MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_background.png");
	private static final Component SUBTITLE = Component.translatable("options.iris.wynncraftAmbienceDependencySelectSubtitle").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	private final Screen parent;
	private final String localName;
	private final List<AmbienceDependencyCandidate> candidates;
	private final Consumer<AmbienceDependency> callback;
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private CandidateList candidateList;
	private Button useSelectedButton;
	private int selectedIndex;
	private float backgroundInit = 0.0f;

	public final SmoothedFloat listTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);
	public final SmoothedFloat buttonTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);

	public AmbienceDependencySelectionScreen(Screen parent, String localName, List<AmbienceDependencyCandidate> candidates, Consumer<AmbienceDependency> callback) {
		super(Component.translatable("options.iris.wynncraftAmbienceDependencySelectTitle"));
		this.parent = parent;
		this.localName = localName;
		this.candidates = candidates == null ? List.of() : candidates;
		this.callback = callback;
	}

	@Override
	protected void init() {
		super.init();
		this.clearWidgets();
		this.candidateList = new CandidateList(this.minecraft, this.width, this.height, 32, this.height - 58, 0, this.width);
		this.addRenderableWidget(candidateList);

		int bottomCenter = this.width / 2 - 50;
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_CANCEL, button -> this.minecraft.setScreen(parent), buttonTransition)
			.bounds(bottomCenter - 156, this.height - 27, 100, 20)
			.build());
		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceDependencyUseLocal"), button -> finish(AmbienceDependencyResolver.localDependency(localName)), buttonTransition)
			.bounds(bottomCenter - 52, this.height - 27, 100, 20)
			.build());
		this.useSelectedButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceDependencyUseSelected"), button -> finishSelected(), buttonTransition)
			.bounds(bottomCenter + 52, this.height - 27, 100, 20)
			.build());
		updateButtons();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		notifier.onNewFrame();
		backgroundInit = 1.0f;
		super.render(guiGraphics, mouseX, mouseY, delta);
		drawCenteredTruncated(guiGraphics, this.title, 8, 0xFFFFFFFF);
		drawCenteredTruncated(guiGraphics, SUBTITLE, 21, 0xFFFFFFFF);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			this.minecraft.setScreen(parent);
			return true;
		}
		return super.keyPressed(event);
	}

	private void select(int index) {
		selectedIndex = Math.max(0, Math.min(index, candidates.size() - 1));
		updateButtons();
	}

	private void finishSelected() {
		if (candidates.isEmpty()) {
			return;
		}
		finish(new AmbienceDependencyResolver().dependencyFromCandidate(candidates.get(selectedIndex)));
	}

	private void finish(AmbienceDependency dependency) {
		callback.accept(dependency);
	}

	private void updateButtons() {
		if (useSelectedButton != null) {
			useSelectedButton.active = !candidates.isEmpty();
		}
	}

	private void drawCenteredTruncated(GuiGraphics guiGraphics, Component component, int y, int color) {
		Component rendered = component;
		if (this.font.width(component) > this.width - 20) {
			rendered = Component.literal(this.font.plainSubstrByWidth(component.getString(), this.width - 32) + "...").setStyle(component.getStyle());
		}
		guiGraphics.drawCenteredString(this.font, rendered, (int) (this.width * 0.5), y, color);
	}

	private class CandidateList extends IrisObjectSelectionList<CandidateEntry> {
		CandidateList(Minecraft client, int width, int height, int top, int bottom, int left, int right) {
			super(client, width, bottom, top + 4, bottom, left, right, 36);
			for (int i = 0; i < candidates.size(); i++) {
				addEntry(new CandidateEntry(i));
			}
		}

		@Override
		protected void renderListBackground(GuiGraphics guiGraphics) {
			float transition = listTransition.getAsFloat();
			if (transition < 0.02f) {
				return;
			}
			guiGraphics.blit(RenderPipelines.GUI_TEXTURED,
				MENU_LIST_BACKGROUND,
				this.getX(), this.getY(), (float) this.getRight(), (float) (this.getBottom() + (int) this.scrollAmount()), this.getWidth(), this.getHeight(), 32, 32);
		}

		@Override
		protected void renderListSeparators(GuiGraphics guiGraphics) {
			float transition = listTransition.getAsFloat();
			if (transition < 0.02f) {
				return;
			}
			int col = ARGB.colorFromFloat(transition, 1.0f, 1.0f, 1.0f);
			guiGraphics.blit(RenderPipelines.GUI_TEXTURED, CreateWorldScreen.HEADER_SEPARATOR, this.getX(), this.getY() - 2, 0.0F, 0.0F, this.getWidth(), 2, 32, 2, col);
			guiGraphics.blit(RenderPipelines.GUI_TEXTURED, CreateWorldScreen.FOOTER_SEPARATOR, this.getX(), this.getBottom(), 0.0F, 0.0F, this.getWidth(), 2, 32, 2, col);
		}

		@Override
		public int getRowWidth() {
			return Math.min(420, width - 50);
		}
	}

	private class CandidateEntry extends AbstractSelectionList.Entry<CandidateEntry> {
		private final int index;
		private ScreenRectangle bounds = ScreenRectangle.empty();

		CandidateEntry(int index) {
			this.index = index;
		}

		@Override
		public ScreenRectangle getRectangle() {
			return bounds;
		}

		@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean isHovered, float tickDelta) {
			int x = getContentX();
			int y = getContentY();
			int width = getContentWidth();
			this.bounds = new ScreenRectangle(x, y, width, getContentHeight());
			boolean selected = index == selectedIndex;
			if (isHovered || selected) {
				GuiUtil.bindIrisWidgetsTexture();
				GuiUtil.drawButton(guiGraphics, x - 2, y - 2, width + 4, getContentHeight() + 4, isHovered, false);
			}

			Font font = Minecraft.getInstance().font;
			AmbienceDependencyCandidate candidate = candidates.get(index);
			Component title = Component.literal(candidate.displayName()).withStyle(selected ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
			Component subtitle = Component.literal(candidate.description == null ? "" : candidate.description).withStyle(ChatFormatting.GRAY);
			if (font.width(title) > width - 8) {
				title = Component.literal(font.plainSubstrByWidth(title.getString(), width - 20) + "...").setStyle(title.getStyle());
			}
			if (font.width(subtitle) > width - 8) {
				subtitle = Component.literal(font.plainSubstrByWidth(subtitle.getString(), width - 20) + "...").setStyle(subtitle.getStyle());
			}
			guiGraphics.drawString(font, title, x + 4, y + 4, 0xFFFFFFFF);
			guiGraphics.drawString(font, subtitle, x + 4, y + 18, 0xFFAAAAAA);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean repeat) {
			if (event.button() != 0) {
				return false;
			}
			select(index);
			GuiUtil.playButtonClickSound();
			return true;
		}

		@Nullable
		@Override
		public ComponentPath nextFocusPath(FocusNavigationEvent event) {
			return !isFocused() ? ComponentPath.leaf(this) : null;
		}

		public boolean isFocused() {
			return candidateList.getFocused() == this;
		}
	}
}
