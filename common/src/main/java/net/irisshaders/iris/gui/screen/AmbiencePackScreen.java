package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.ambience.AmbienceDependencyResolver;
import net.irisshaders.iris.ambience.AmbienceDependencyStatus;
import net.irisshaders.iris.ambience.AmbiencePack;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.ambience.AmbienceRuntime;
import net.irisshaders.iris.gui.element.AmbiencePackSelectionList;
import net.irisshaders.iris.gui.element.screen.IrisButton;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AmbiencePackScreen extends Screen {
	private static final Component SELECT_TITLE = Component.translatable("pack.iris.ambience.select.title").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	private final Screen parent;
	private final AmbiencePackManager manager = AmbiencePackManager.getInstance();
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private List<AmbiencePackManager.LoadedAmbiencePack> packs = new ArrayList<>();
	private int selectedIndex;
	private Component status = Component.empty();
	private AmbiencePackSelectionList ambiencePackList;
	private Button installButton;
	private Button warmButton;
	private Button settingsButton;
	private Button regionsButton;
	private float backgroundInit = 0.0f;

	public final SmoothedFloat listTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);
	public final SmoothedFloat buttonTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);

	public AmbiencePackScreen(Screen parent) {
		super(Component.translatable("options.iris.wynncraftAmbiencePackSelection.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		manager.reloadIfNeeded();
		refreshPacks();

		this.removeWidget(this.ambiencePackList);
		this.ambiencePackList = new AmbiencePackSelectionList(this, this.minecraft, this.width, this.height, 32, this.height - 58 - 36, 0, this.width);

		this.clearWidgets();
		this.addRenderableWidget(ambiencePackList);

		int bottomCenter = this.width / 2 - 50;
		int topRowWidth = 100;
		int topRowGap = 4;
		int topLeft = this.width / 2 - ((topRowWidth * 5) + (topRowGap * 4)) / 2;

		installButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceInstallMissingShort"), button -> installMissing(), buttonTransition)
			.bounds(bottomCenter - 104, this.height - 27, 100, 20)
			.build());
		warmButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceWarmCache"), button -> warmCache(), buttonTransition)
			.bounds(bottomCenter, this.height - 27, 100, 20)
			.build());
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_DONE, button -> this.minecraft.setScreen(parent), buttonTransition)
			.bounds(bottomCenter + 104, this.height - 27, 100, 20)
			.build());

		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.openAmbiencePackFolderShort"), button -> openAmbiencePackFolder(), buttonTransition)
			.bounds(topLeft, this.height - 51, 100, 20)
			.build());
		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.refresh"), button -> reloadPacks(), buttonTransition)
			.bounds(topLeft + 104, this.height - 51, 100, 20)
			.build());
		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceCreatePack"), button -> createNewPack(), buttonTransition)
			.bounds(topLeft + 208, this.height - 51, 100, 20)
			.build());
		settingsButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceProfileSettings"), button -> openProfileSettings(), buttonTransition)
			.bounds(topLeft + 312, this.height - 51, 100, 20)
			.build());
		regionsButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceRegionEditor"), button -> openRegionEditor(), buttonTransition)
			.bounds(topLeft + 416, this.height - 51, 100, 20)
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

	private void drawCenteredTruncated(GuiGraphics guiGraphics, Component component, int y, int color) {
		Component rendered = component;
		if (this.font.width(component) > this.width - 20) {
			rendered = Component.literal(this.font.plainSubstrByWidth(component.getString(), this.width - 32) + "...").setStyle(component.getStyle());
		}
		guiGraphics.drawCenteredString(this.font, rendered, (int) (this.width * 0.5), y, color);
	}

	public List<AmbiencePackManager.LoadedAmbiencePack> getPacks() {
		return packs;
	}

	public Path getAmbiencePackDirectory() {
		return manager.getDirectory();
	}

	public void selectPack(int index) {
		if (packs.isEmpty()) {
			return;
		}
		selectedIndex = Math.max(0, Math.min(index, packs.size() - 1));
		saveSelectedPack();
		AmbiencePack pack = selected().pack();
		status = Component.translatable("options.iris.wynncraftAmbienceSelectedPack", pack.displayName());
		updateButtons();
	}

	public void setAmbienceEnabled(boolean enabled) {
		IrisVideoSettings.wynncraftAmbienceEnabled = enabled;
		status = Component.translatable(enabled ? "options.iris.wynncraftAmbienceEnabledStatus" : "options.iris.wynncraftAmbienceDisabledStatus");
		try {
			Iris.getIrisConfig().save();
		} catch (IOException e) {
			Iris.logger.warn("Failed to save ambience pack setting", e);
		}
		updateButtons();
	}

	private void reloadPacks() {
		manager.reload();
		refreshPacks();
		if (ambiencePackList != null) {
			ambiencePackList.refresh(packs);
		}
		status = Component.translatable("options.iris.wynncraftAmbienceReloaded");
	}

	private void refreshPacks() {
		packs = new ArrayList<>(manager.getPacks());
		selectedIndex = 0;
		String selectedId = IrisVideoSettings.wynncraftSelectedAmbiencePack;
		boolean selectedFound = false;
		for (int i = 0; i < packs.size(); i++) {
			if (packs.get(i).pack().id.equals(selectedId)) {
				selectedIndex = i;
				selectedFound = true;
				break;
			}
		}
		if (packs.isEmpty()) {
			updateButtons();
			return;
		}
		if (!selectedFound) {
			if (selectedId == null || selectedId.isBlank()) {
				saveSelectedPack();
			} else {
				status = Component.translatable("options.iris.wynncraftAmbienceSelectedMissing", selectedId).withStyle(ChatFormatting.YELLOW);
			}
		}
		updateButtons();
	}

	private AmbiencePackManager.LoadedAmbiencePack selected() {
		return packs.get(Math.max(0, Math.min(selectedIndex, packs.size() - 1)));
	}

	private void saveSelectedPack() {
		IrisVideoSettings.wynncraftSelectedAmbiencePack = selected().pack().id;
		try {
			Iris.getIrisConfig().save();
		} catch (IOException e) {
			Iris.logger.warn("Failed to save selected ambience pack", e);
		}
	}

	private void updateButtons() {
		AmbienceDependencyStatus dependencyStatus = packs.isEmpty() ? null : manager.dependencyStatus(selected().pack());
		if (installButton != null) {
			installButton.active = dependencyStatus != null && dependencyStatus.hasInstallableMissing();
		}
		if (warmButton != null) {
			warmButton.active = dependencyStatus != null && !dependencyStatus.hasMissing();
		}
		if (settingsButton != null) {
			settingsButton.active = dependencyStatus != null;
		}
		if (regionsButton != null) {
			regionsButton.active = dependencyStatus != null;
		}
	}

	private void openProfileSettings() {
		if (packs.isEmpty()) {
			return;
		}
		this.minecraft.setScreen(new AmbienceProfileSelectionScreen(this, selected().pack().id));
	}

	private void openRegionEditor() {
		if (packs.isEmpty()) {
			return;
		}
		this.minecraft.setScreen(new AmbienceRegionEditorScreen(this, selected().pack().id));
	}

	private void openAmbiencePackFolder() {
		CompletableFuture.runAsync(() -> Util.getPlatform().openUri(manager.getDirectory().toUri()));
	}

	private void createNewPack() {
		try {
			AmbiencePackManager.LoadedAmbiencePack loaded = manager.createEmptyPack("New Ambience Pack");
			IrisVideoSettings.wynncraftSelectedAmbiencePack = loaded.pack().id;
			refreshPacks();
			saveSelectedPack();
			if (ambiencePackList != null) {
				ambiencePackList.refresh(packs);
				ambiencePackList.selectPackId(loaded.pack().id);
			}
			status = Component.translatable("options.iris.wynncraftAmbiencePackCreated", loaded.pack().displayName());
			updateButtons();
		} catch (IOException e) {
			Iris.logger.warn("Failed to create ambience pack", e);
			status = Component.literal(e.getMessage() == null ? "Create failed" : e.getMessage()).withStyle(ChatFormatting.RED);
		}
	}

	private void installMissing() {
		if (packs.isEmpty()) {
			return;
		}
		installButton.active = false;
		status = Component.translatable("options.iris.wynncraftAmbienceInstalling");
		AmbiencePack pack = selected().pack();
		CompletableFuture.runAsync(() -> {
			try {
				new AmbienceDependencyResolver().installMissing(pack);
				Minecraft.getInstance().execute(() -> {
					status = Component.translatable("options.iris.wynncraftAmbienceInstalled");
					manager.reload();
					refreshPacks();
					if (ambiencePackList != null) {
						ambiencePackList.refresh(packs);
					}
				});
			} catch (Exception e) {
				Iris.logger.warn("Failed to install ambience dependencies", e);
				Minecraft.getInstance().execute(() -> {
					status = Component.literal(e.getMessage() == null ? "Install failed" : e.getMessage()).withStyle(ChatFormatting.RED);
					updateButtons();
				});
			}
		});
	}

	private void warmCache() {
		if (packs.isEmpty()) {
			return;
		}
		if (warmButton != null) {
			warmButton.active = false;
		}
		status = Component.translatable("options.iris.wynncraftAmbienceWarming");
		AmbienceRuntime.WarmupResult result = AmbienceRuntime.warmSelectedPackProfiles();
		status = result.failed() == 0
			? Component.translatable("options.iris.wynncraftAmbienceWarmed", result.warmed())
			: Component.translatable("options.iris.wynncraftAmbienceWarmedWithFailures", result.warmed(), result.failed()).withStyle(ChatFormatting.YELLOW);
		updateButtons();
	}
}
