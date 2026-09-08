package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.ambience.AmbiencePack;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.ambience.AmbienceProfile;
import net.irisshaders.iris.gui.element.AmbienceProfileSelectionList;
import net.irisshaders.iris.gui.element.screen.IrisButton;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
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
	private String selectedProfileId = "";
	private Button settingsButton;
	private Button renameButton;
	private Button duplicateButton;
	private Button shaderButton;
	private Button defaultButton;
	private Button deleteButton;
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
		this.profileList = new AmbienceProfileSelectionList(this, this.minecraft, this.width, this.height, 32, this.height - 58 - 36, 0, this.width);

		this.clearWidgets();
		this.addRenderableWidget(profileList);

		int bottomRowWidth = 100;
		int bottomRowGap = 4;
		int topLeft = this.width / 2 - ((bottomRowWidth * 4) + (bottomRowGap * 3)) / 2;
		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceProfileCreate"), button -> openCreatePreset(), buttonTransition)
			.bounds(topLeft, this.height - 51, 100, 20)
			.build());
		settingsButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceProfileSettingsShort"), button -> openSelectedProfile(), buttonTransition)
			.bounds(topLeft + 104, this.height - 51, 100, 20)
			.build());
		renameButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.rename"), button -> renameSelectedProfile(), buttonTransition)
			.bounds(topLeft + 208, this.height - 51, 100, 20)
			.build());
		duplicateButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.duplicate"), button -> duplicateSelectedProfile(), buttonTransition)
			.bounds(topLeft + 312, this.height - 51, 100, 20)
			.build());

		int bottomLeft = this.width / 2 - ((bottomRowWidth * 4) + (bottomRowGap * 3)) / 2;
		shaderButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceProfileChangeShader"), button -> changeSelectedShader(), buttonTransition)
			.bounds(bottomLeft, this.height - 27, 100, 20)
			.build());
		defaultButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceProfileSetDefault"), button -> setSelectedDefault(), buttonTransition)
			.bounds(bottomLeft + 104, this.height - 27, 100, 20)
			.build());
		deleteButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.delete"), button -> deleteSelectedProfile(), buttonTransition)
			.bounds(bottomLeft + 208, this.height - 27, 100, 20)
			.build());
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_DONE, button -> this.minecraft.setScreen(parent), buttonTransition)
			.bounds(bottomLeft + 312, this.height - 27, 100, 20)
			.build());
		updateButtons();
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

	public String getSelectedProfileId() {
		return selectedProfileId;
	}

	public void selectProfile(String profileId) {
		selectedProfileId = profileId == null ? "" : profileId;
		status = Component.translatable("options.iris.wynncraftAmbienceProfileSelected", selectedProfileId);
		updateButtons();
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

	private void openSelectedProfile() {
		if (!selectedProfileId.isBlank()) {
			openProfile(selectedProfileId);
		}
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
		selectedProfileId = profileId;
		status = Component.translatable("options.iris.wynncraftAmbienceProfileCreated", profileId).withStyle(ChatFormatting.YELLOW);
		updateButtons();
	}

	public void onPresetChanged(String profileId, Component message) {
		reloadProfiles();
		selectedProfileId = profileId == null ? "" : profileId;
		status = message;
		updateButtons();
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
			if (selectedProfileId == null || selectedProfileId.isBlank() || !loadedPack.get().profilesById().containsKey(selectedProfileId)) {
				selectedProfileId = pack.defaultProfile == null ? "" : pack.defaultProfile;
			}
			status = Component.translatable("options.iris.wynncraftAmbienceEditingPack", pack.displayName());
		} else {
			selectedProfileId = "";
			status = Component.translatable("options.iris.wynncraftAmbienceSelectedMissing", packId).withStyle(ChatFormatting.YELLOW);
		}
	}

	private Optional<AmbienceProfile> selectedProfile() {
		return loadedPack.flatMap(loaded -> Optional.ofNullable(loaded.profilesById().get(selectedProfileId)));
	}

	private void renameSelectedProfile() {
		if (!selectedProfileId.isBlank()) {
			this.minecraft.setScreen(new AmbienceProfileRenameScreen(this, packId, selectedProfileId));
		}
	}

	private void duplicateSelectedProfile() {
		try {
			AmbienceProfile duplicated = manager.duplicateProfile(packId, selectedProfileId, selectedProfileId + "_copy");
			onPresetChanged(duplicated.id, Component.translatable("options.iris.wynncraftAmbienceProfileDuplicated", duplicated.id).withStyle(ChatFormatting.YELLOW));
		} catch (IOException e) {
			Iris.logger.warn("Failed to duplicate ambience preset", e);
			status = Component.literal(e.getMessage() == null ? "Duplicate failed" : e.getMessage()).withStyle(ChatFormatting.RED);
			updateButtons();
		}
	}

	private void changeSelectedShader() {
		if (!selectedProfileId.isBlank()) {
			this.minecraft.setScreen(new AmbiencePresetCreateScreen(this, packId, selectedProfileId));
		}
	}

	private void setSelectedDefault() {
		try {
			manager.setDefaultProfile(packId, selectedProfileId);
			onPresetChanged(selectedProfileId, Component.translatable("options.iris.wynncraftAmbienceProfileDefaultSet", selectedProfileId).withStyle(ChatFormatting.YELLOW));
		} catch (IOException e) {
			Iris.logger.warn("Failed to set ambience default preset", e);
			status = Component.literal(e.getMessage() == null ? "Set default failed" : e.getMessage()).withStyle(ChatFormatting.RED);
			updateButtons();
		}
	}

	private void deleteSelectedProfile() {
		String profileId = selectedProfileId;
		if (profileId.isBlank()) {
			return;
		}
		this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				try {
					manager.deleteProfile(packId, profileId);
					selectedProfileId = "";
					reloadProfiles();
					status = Component.translatable("options.iris.wynncraftAmbienceProfileDeleted", profileId).withStyle(ChatFormatting.YELLOW);
				} catch (IOException e) {
					Iris.logger.warn("Failed to delete ambience preset", e);
					status = Component.literal(e.getMessage() == null ? "Delete failed" : e.getMessage()).withStyle(ChatFormatting.RED);
				}
			}
			this.minecraft.setScreen(this);
			updateButtons();
		}, Component.translatable("options.iris.wynncraftAmbienceProfileDeleteTitle"),
			Component.translatable("options.iris.wynncraftAmbienceProfileDeleteMessage", profileId),
			Component.translatable("options.iris.delete"),
			CommonComponents.GUI_CANCEL));
	}

	private void updateButtons() {
		boolean hasSelection = selectedProfile().isPresent();
		boolean resolvable = hasSelection && resolveProfile(selectedProfileId).isPresent();
		if (settingsButton != null) {
			settingsButton.active = resolvable;
		}
		if (renameButton != null) {
			renameButton.active = hasSelection;
		}
		if (duplicateButton != null) {
			duplicateButton.active = hasSelection;
		}
		if (shaderButton != null) {
			shaderButton.active = hasSelection;
		}
		if (defaultButton != null) {
			defaultButton.active = hasSelection && loadedPack.isPresent() && !selectedProfileId.equals(loadedPack.get().pack().defaultProfile);
		}
		if (deleteButton != null) {
			deleteButton.active = hasSelection;
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
