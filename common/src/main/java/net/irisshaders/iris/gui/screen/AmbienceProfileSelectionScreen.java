package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.ambience.AmbiencePack;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.gui.element.AmbienceProfileSelectionList;
import net.irisshaders.iris.gui.element.screen.IrisButton;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Optional;

public class AmbienceProfileSelectionScreen extends Screen {
	private static final Component SELECT_TITLE = Component.translatable("pack.iris.ambience.profile.select.title").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	private final Screen parent;
	private final String packId;
	private final AmbiencePackManager manager = AmbiencePackManager.getInstance();
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private Optional<AmbiencePackManager.LoadedAmbiencePack> loadedPack = Optional.empty();
	private AmbienceProfileSelectionList profileList;
	private Component status = Component.empty();
	private float backgroundInit = 0.0f;

	public final SmoothedFloat listTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);
	public final SmoothedFloat buttonTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);

	public AmbienceProfileSelectionScreen(Screen parent, String packId) {
		super(Component.translatable("options.iris.wynncraftAmbienceProfileSelection.title"));
		this.parent = parent;
		this.packId = packId;
	}

	@Override
	protected void init() {
		super.init();
		reloadPack();

		this.removeWidget(this.profileList);
		this.profileList = new AmbienceProfileSelectionList(this, this.minecraft, this.width, this.height, 32, this.height - 70, 0, this.width);

		this.clearWidgets();
		this.addRenderableWidget(profileList);

		int bottomRowWidth = 100;
		int bottomRowGap = 4;
		int bottomLeft = this.width / 2 - ((bottomRowWidth * 4) + (bottomRowGap * 3)) / 2;
		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.refresh"), button -> reloadProfiles(), buttonTransition)
			.bounds(bottomLeft, this.height - 31, 100, 20)
			.build());
		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceProfileCreate"), button -> openCreatePreset(), buttonTransition)
			.bounds(bottomLeft + 104, this.height - 31, 100, 20)
			.build());
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_BACK, button -> this.minecraft.setScreen(parent), buttonTransition)
			.bounds(bottomLeft + 208, this.height - 31, 100, 20)
			.build());
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_DONE, button -> this.minecraft.setScreen(parent), buttonTransition)
			.bounds(bottomLeft + 312, this.height - 31, 100, 20)
			.build());
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		notifier.onNewFrame();
		backgroundInit = 1.0f;

		super.render(guiGraphics, mouseX, mouseY, delta);

		drawCenteredTruncated(guiGraphics, this.title, 8, 0xFFFFFFFF);
		if (status != null && !status.getString().isBlank()) {
			drawCenteredTruncated(guiGraphics, status, 21, 0xFFFFFFFF);
		} else {
			drawCenteredTruncated(guiGraphics, SELECT_TITLE, 21, 0xFFFFFFFF);
		}
	}

	public Optional<AmbiencePackManager.LoadedAmbiencePack> getLoadedPack() {
		return loadedPack;
	}

	public Optional<AmbiencePackManager.ResolvedProfile> resolveProfile(String profileId) {
		return manager.resolveProfile(packId, profileId);
	}

	public void openProfile(String profileId) {
		Optional<AmbiencePackManager.ResolvedProfile> resolved = resolveProfile(profileId);
		if (resolved.isEmpty()) {
			status = Component.translatable("options.iris.wynncraftAmbienceProfileMissingShader").withStyle(ChatFormatting.RED);
			return;
		}
		this.minecraft.setScreen(new AmbienceProfileSettingsScreen(this, packId, profileId));
	}

	public void reloadProfiles() {
		manager.reload();
		reloadPack();
		if (profileList != null) {
			profileList.refresh();
		}
		status = Component.translatable("options.iris.wynncraftAmbienceReloaded");
	}

	public void onPresetCreated(String profileId) {
		reloadProfiles();
		status = Component.translatable("options.iris.wynncraftAmbienceProfileCreated", profileId).withStyle(ChatFormatting.YELLOW);
	}

	private void openCreatePreset() {
		if (loadedPack.isEmpty()) {
			return;
		}
		this.minecraft.setScreen(new AmbiencePresetCreateScreen(this, packId));
	}

	private void reloadPack() {
		loadedPack = manager.getPack(packId);
		if (loadedPack.isPresent()) {
			AmbiencePack pack = loadedPack.get().pack();
			status = Component.translatable("options.iris.wynncraftAmbienceEditingPack", pack.displayName());
		} else {
			status = Component.translatable("options.iris.wynncraftAmbienceSelectedMissing", packId).withStyle(ChatFormatting.YELLOW);
		}
	}

	private void drawCenteredTruncated(GuiGraphics guiGraphics, Component component, int y, int color) {
		Component rendered = component;
		if (this.font.width(component) > this.width - 20) {
			rendered = Component.literal(this.font.plainSubstrByWidth(component.getString(), this.width - 32) + "...").setStyle(component.getStyle());
		}
		guiGraphics.drawCenteredString(this.font, rendered, (int) (this.width * 0.5), y, color);
	}
}
