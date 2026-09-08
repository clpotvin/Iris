package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.ambience.AmbienceDependency;
import net.irisshaders.iris.ambience.AmbienceDependencyCandidate;
import net.irisshaders.iris.ambience.AmbienceDependencyResolver;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.ambience.AmbienceProfile;
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
import net.minecraft.client.gui.components.EditBox;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class AmbiencePresetCreateScreen extends Screen {
	private static final Identifier MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_background.png");
	private static final Component SUBTITLE = Component.translatable("options.iris.wynncraftAmbienceProfileCreateSubtitle").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	private final AmbienceProfileSelectionScreen parent;
	private final String packId;
	private final String profileIdToChange;
	private final AmbiencePackManager manager = AmbiencePackManager.getInstance();
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private final List<String> shaderPacks = new ArrayList<>();
	private EditBox nameBox;
	private ShaderPackList shaderPackList;
	private Button createButton;
	private Component status = Component.empty();
	private String selectedShaderPack = "";
	private float backgroundInit = 0.0f;

	public final SmoothedFloat listTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);
	public final SmoothedFloat buttonTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);

	public AmbiencePresetCreateScreen(AmbienceProfileSelectionScreen parent, String packId) {
		this(parent, packId, null);
	}

	public AmbiencePresetCreateScreen(AmbienceProfileSelectionScreen parent, String packId, String profileIdToChange) {
		super(Component.translatable(profileIdToChange == null ? "options.iris.wynncraftAmbienceProfileCreateTitle" : "options.iris.wynncraftAmbienceProfileChangeShaderTitle"));
		this.parent = parent;
		this.packId = packId;
		this.profileIdToChange = profileIdToChange;
	}

	@Override
	protected void init() {
		super.init();
		reloadShaderPacks();
		this.clearWidgets();

		int fieldWidth = Math.min(308, this.width - 50);
		int fieldLeft = this.width / 2 - fieldWidth / 2;
		if (!isChangingShader()) {
			this.nameBox = new EditBox(this.font, fieldLeft, 54, fieldWidth, 20, Component.translatable("options.iris.wynncraftAmbienceProfileName"));
			this.nameBox.setMaxLength(64);
			if (this.nameBox.getValue().isBlank()) {
				this.nameBox.setValue(uniqueDefaultName());
			}
			this.addRenderableWidget(this.nameBox);
			this.setInitialFocus(this.nameBox);
		}

		int listTop = isChangingShader() ? 48 : 84;
		this.shaderPackList = new ShaderPackList(this.minecraft, this.width, this.height, listTop, this.height - 70, 0, this.width);
		this.addRenderableWidget(this.shaderPackList);

		int bottomCenter = this.width / 2 - 50;
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_CANCEL, button -> this.minecraft.setScreen(parent), buttonTransition)
			.bounds(bottomCenter - 52, this.height - 31, 100, 20)
			.build());
		this.createButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable(isChangingShader() ? "options.iris.apply" : "options.iris.create"), button -> createPreset(), buttonTransition)
			.bounds(bottomCenter + 52, this.height - 31, 100, 20)
			.build());
		updateCreateButton();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		notifier.onNewFrame();
		backgroundInit = 1.0f;

		super.render(guiGraphics, mouseX, mouseY, delta);

		drawCenteredTruncated(guiGraphics, this.title, 8, 0xFFFFFFFF);
		drawCenteredTruncated(guiGraphics, status == null || status.getString().isBlank() ? SUBTITLE : status, 21, 0xFFFFFFFF);
		if (!isChangingShader() && this.nameBox != null) {
			guiGraphics.drawString(this.font, Component.translatable("options.iris.wynncraftAmbienceProfileName"), this.nameBox.getX(), 42, 0xFFCCCCCC);
		}
	}

	@Override
	public void tick() {
		super.tick();
		updateCreateButton();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			this.minecraft.setScreen(parent);
			return true;
		}
		return super.keyPressed(event);
	}

	private void reloadShaderPacks() {
		shaderPacks.clear();
		try {
			shaderPacks.addAll(Iris.getShaderpacksDirectoryManager().enumerate());
		} catch (Throwable e) {
			Iris.logger.warn("Failed to enumerate shader packs for ambience preset creation", e);
		}
		selectedShaderPack = shaderPacks.isEmpty() ? "" : shaderPacks.getFirst();
	}

	private void createPreset() {
		String presetId = isChangingShader() ? profileIdToChange : nameBox.getValue().trim();
		if (presetId.isBlank() || selectedShaderPack.isBlank()) {
			updateCreateButton();
			return;
		}
		String shaderPack = selectedShaderPack;
		createButton.active = false;
		status = Component.translatable("options.iris.wynncraftAmbienceDependencyResolving", shaderPack).withStyle(ChatFormatting.GRAY);
		CompletableFuture.supplyAsync(() -> {
			AmbienceDependencyResolver resolver = new AmbienceDependencyResolver();
			try {
				Path shaderPackPath = Iris.getShaderpacksDirectory().resolve(shaderPack);
				Optional<AmbienceDependencyCandidate> exact = Files.isRegularFile(shaderPackPath)
					? resolver.findExactDependency(shaderPackPath, shaderPack)
					: Optional.empty();
				if (exact.isPresent()) {
					return new DependencyResult(resolver.dependencyFromCandidate(exact.get()), null);
				}
				List<AmbienceDependencyCandidate> candidates = resolver.searchDependencies(shaderPack, shaderPack);
				return new DependencyResult(null, candidates);
			} catch (Exception e) {
				Iris.logger.warn("Failed to resolve ambience shader dependency for {}", shaderPack, e);
				return new DependencyResult(AmbienceDependencyResolver.localDependency(shaderPack), null);
			}
		}).orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenComplete((result, error) -> {
			Minecraft.getInstance().execute(() -> {
				if (error != null || result == null) {
					finishWithDependency(presetId, shaderPack, AmbienceDependencyResolver.localDependency(shaderPack));
				} else if (result.dependency != null) {
					finishWithDependency(presetId, shaderPack, result.dependency);
				} else if (result.candidates != null && !result.candidates.isEmpty()) {
					this.minecraft.setScreen(new AmbienceDependencySelectionScreen(this, shaderPack, result.candidates, dependency -> finishWithDependency(presetId, shaderPack, dependency)));
				} else {
					finishWithDependency(presetId, shaderPack, AmbienceDependencyResolver.localDependency(shaderPack));
				}
			});
		});
	}

	private record DependencyResult(AmbienceDependency dependency, List<AmbienceDependencyCandidate> candidates) {}

	private void finishWithDependency(String presetId, String shaderPack, AmbienceDependency dependency) {
		try {
			AmbienceProfile profile = isChangingShader()
				? manager.changeProfileShader(packId, presetId, shaderPack, dependency)
				: manager.addProfile(packId, presetId, shaderPack, dependency);
			if (isChangingShader()) {
				parent.onPresetChanged(profile.id, Component.translatable("options.iris.wynncraftAmbienceProfileShaderChanged", profile.id).withStyle(ChatFormatting.YELLOW));
			} else {
				parent.onPresetCreated(profile.id);
			}
			this.minecraft.setScreen(parent);
		} catch (IOException e) {
			Iris.logger.warn("Failed to create ambience preset", e);
			status = Component.literal(e.getMessage() == null ? "Save failed" : e.getMessage()).withStyle(ChatFormatting.RED);
			updateCreateButton();
		}
	}

	private void updateCreateButton() {
		if (createButton != null) {
			createButton.active = (isChangingShader() || (nameBox != null && !nameBox.getValue().trim().isBlank())) && selectedShaderPack != null && !selectedShaderPack.isBlank();
		}
	}

	private boolean isChangingShader() {
		return profileIdToChange != null && !profileIdToChange.isBlank();
	}

	private String uniqueDefaultName() {
		String base = "new_preset";
		if (parent.getLoadedPack().isEmpty()) {
			return base;
		}
		int suffix = 1;
		String candidate = base;
		while (parent.getLoadedPack().get().profilesById().containsKey(candidate)) {
			candidate = base + "_" + ++suffix;
		}
		return candidate;
	}

	private void drawCenteredTruncated(GuiGraphics guiGraphics, Component component, int y, int color) {
		Component rendered = component;
		if (this.font.width(component) > this.width - 20) {
			rendered = Component.literal(this.font.plainSubstrByWidth(component.getString(), this.width - 32) + "...").setStyle(component.getStyle());
		}
		guiGraphics.drawCenteredString(this.font, rendered, (int) (this.width * 0.5), y, color);
	}

	private class ShaderPackList extends IrisObjectSelectionList<ShaderPackEntry> {
		ShaderPackList(Minecraft client, int width, int height, int top, int bottom, int left, int right) {
			// Height must be the widget's HEIGHT, not the bottom coordinate.
			// Passing `bottom` here made the widget rectangle extend past the
			// screen bottom, swallowing clicks meant for the Cancel/Create
			// buttons below the list (they never received the events).
			super(client, width, bottom - top - 4, top + 4, bottom, left, right, 20);
			refresh();
		}

		void refresh() {
			clearEntries();
			if (shaderPacks.isEmpty()) {
				addEntry(new ShaderPackEntry(Component.translatable("options.iris.wynncraftAmbienceNoShaderPacks").withStyle(ChatFormatting.RED), ""));
				return;
			}
			for (String shaderPack : shaderPacks) {
				addEntry(new ShaderPackEntry(Component.literal(shaderPack), shaderPack));
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
			return Math.min(308, width - 50);
		}

		@Override
		public int getRowTop(int index) {
			return super.getRowTop(index) + 2;
		}
	}

	private class ShaderPackEntry extends AbstractSelectionList.Entry<ShaderPackEntry> {
		private final Component label;
		private final String shaderPackName;
		private ScreenRectangle bounds = ScreenRectangle.empty();

		ShaderPackEntry(Component label, String shaderPackName) {
			this.label = label;
			this.shaderPackName = shaderPackName;
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

			boolean selected = !shaderPackName.isBlank() && shaderPackName.equals(selectedShaderPack);
			if (isHovered || selected) {
				GuiUtil.bindIrisWidgetsTexture();
				GuiUtil.drawButton(guiGraphics, x - 2, y - 2, entryWidth + 4, entryHeight + 4, isHovered, false);
			}

			Font font = Minecraft.getInstance().font;
			Component renderedLabel = label;
			if (font.width(renderedLabel) > entryWidth - 8) {
				renderedLabel = Component.literal(font.plainSubstrByWidth(renderedLabel.getString(), entryWidth - 20) + "...").setStyle(label.getStyle());
			}
			int color = selected ? 0xFFFFF263 : 0xFFFFFFFF;
			guiGraphics.drawCenteredString(font, renderedLabel, (x + entryWidth / 2) - 2, y + (entryHeight - 11) / 2, color);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean repeat) {
			if (event.button() != 0 || shaderPackName.isBlank()) {
				return false;
			}
			selectedShaderPack = shaderPackName;
			GuiUtil.playButtonClickSound();
			updateCreateButton();
			return true;
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (!event.isConfirmation() || shaderPackName.isBlank()) {
				return false;
			}
			selectedShaderPack = shaderPackName;
			GuiUtil.playButtonClickSound();
			updateCreateButton();
			return true;
		}

		@Nullable
		@Override
		public ComponentPath nextFocusPath(FocusNavigationEvent event) {
			return !isFocused() ? ComponentPath.leaf(this) : null;
		}

		public boolean isFocused() {
			return shaderPackList.getFocused() == this;
		}
	}
}
