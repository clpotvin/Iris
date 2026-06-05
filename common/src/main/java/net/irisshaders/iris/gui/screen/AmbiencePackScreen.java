package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.ambience.AmbienceDependency;
import net.irisshaders.iris.ambience.AmbienceDependencyCandidate;
import net.irisshaders.iris.ambience.AmbienceDependencyResolver;
import net.irisshaders.iris.ambience.AmbienceDependencyStatus;
import net.irisshaders.iris.ambience.AmbiencePack;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.ambience.AmbienceRuntime;
import net.irisshaders.iris.gui.FileDialogUtil;
import net.irisshaders.iris.gui.GuiUtil;
import net.irisshaders.iris.gui.element.AmbiencePackSelectionList;
import net.irisshaders.iris.gui.element.screen.IrisButton;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class AmbiencePackScreen extends Screen {
	private static final Component SELECT_TITLE = Component.translatable("pack.iris.ambience.select.title").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
	private static final int ICON_BUTTON_SIZE = 20;

	private final Screen parent;
	private final AmbiencePackManager manager = AmbiencePackManager.getInstance();
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private List<AmbiencePackManager.LoadedAmbiencePack> packs = new ArrayList<>();
	private int selectedIndex;
	private Component status = Component.empty();
	private AmbiencePackSelectionList ambiencePackList;
	private Button installButton;
	private Button warmButton;
	private Button autoWarmButton;
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

		int rowGap = 4;
		int topTextButtonCount = 4;
		int topTextButtonWidth = Math.max(74, Math.min(100, (this.width - 40 - rowGap * (topTextButtonCount - 1)) / topTextButtonCount));
		int topRowTotalWidth = topTextButtonWidth * topTextButtonCount + rowGap * (topTextButtonCount - 1);
		int topCursor = this.width / 2 - topRowTotalWidth / 2;
		int topRowY = this.height - 51;
		int cornerButtonY = 8;
		int cornerButtonX = this.width - 8 - ICON_BUTTON_SIZE * 3 - rowGap * 2;

		int bottomButtonCount = 4;
		int bottomButtonWidth = Math.max(74, Math.min(100, (this.width - 40 - rowGap * (bottomButtonCount - 1)) / bottomButtonCount));
		int bottomCursor = this.width / 2 - (bottomButtonWidth * bottomButtonCount + rowGap * (bottomButtonCount - 1)) / 2;
		int bottomRowY = this.height - 27;

		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.openAmbiencePackFolderShort"), button -> openAmbiencePackFolder(), buttonTransition)
			.bounds(topCursor, topRowY, topTextButtonWidth, 20)
			.build());
		topCursor += topTextButtonWidth + rowGap;
		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceCreatePack"), button -> createNewPack(), buttonTransition)
			.bounds(topCursor, topRowY, topTextButtonWidth, 20)
			.build());
		topCursor += topTextButtonWidth + rowGap;
		settingsButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceProfileSettings"), button -> openProfileSettings(), buttonTransition)
			.bounds(topCursor, topRowY, topTextButtonWidth, 20)
			.build());
		topCursor += topTextButtonWidth + rowGap;
		regionsButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceRegionEditor"), button -> openRegionEditor(), buttonTransition)
			.bounds(topCursor, topRowY, topTextButtonWidth, 20)
			.build());
		this.addRenderableWidget(iconButton(Component.translatable("options.iris.refresh"), GuiUtil.Icon.REFRESH, GuiUtil.Icon.REFRESH, cornerButtonX, cornerButtonY, button -> reloadPacks()));
		this.addRenderableWidget(iconButton(Component.translatable("options.iris.import"), GuiUtil.Icon.IMPORT, GuiUtil.Icon.IMPORT_COLORED, cornerButtonX + ICON_BUTTON_SIZE + rowGap, cornerButtonY, button -> importPack()));
		this.addRenderableWidget(iconButton(Component.translatable("options.iris.export"), GuiUtil.Icon.EXPORT, GuiUtil.Icon.EXPORT_COLORED, cornerButtonX + (ICON_BUTTON_SIZE + rowGap) * 2, cornerButtonY, button -> exportPack()));

		installButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceInstallMissingShort"), button -> installMissing(), buttonTransition)
			.bounds(bottomCursor, bottomRowY, bottomButtonWidth, 20)
			.build());
		bottomCursor += bottomButtonWidth + rowGap;
		warmButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbiencePrepareCache"), button -> warmCache(), buttonTransition)
			.bounds(bottomCursor, bottomRowY, bottomButtonWidth, 20)
			.build());
		bottomCursor += bottomButtonWidth + rowGap;
		autoWarmButton = this.addRenderableWidget(IrisButton.iris$builder(autoWarmLabel(), button -> toggleAutoWarm(), buttonTransition)
			.bounds(bottomCursor, bottomRowY, bottomButtonWidth, 20)
			.build());
		bottomCursor += bottomButtonWidth + rowGap;
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_DONE, button -> this.minecraft.setScreen(parent), buttonTransition)
			.bounds(bottomCursor, bottomRowY, bottomButtonWidth, 20)
			.build());

		updateButtons();
	}

	private IconButton iconButton(Component label, GuiUtil.Icon icon, GuiUtil.Icon hoveredIcon, int x, int y, Button.OnPress onPress) {
		IconButton button = new IconButton(x, y, ICON_BUTTON_SIZE, ICON_BUTTON_SIZE, label, icon, hoveredIcon, onPress, buttonTransition);
		button.setTooltip(Tooltip.create(label));
		return button;
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

	@Override
	public void onFilesDrop(List<Path> paths) {
		if (paths == null || paths.isEmpty()) {
			return;
		}
		if (paths.size() > 1) {
			status = Component.translatable("options.iris.wynncraftAmbienceImportOne").withStyle(ChatFormatting.RED);
			return;
		}
		importPack(paths.getFirst());
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
		maybeOpenAutoWarmScreen();
	}

	public void setAmbienceEnabled(boolean enabled) {
		IrisVideoSettings.wynncraftAmbienceEnabled = enabled;
		if (!enabled) {
			AmbienceRuntime.cancelWarmup();
		}
		status = Component.translatable(enabled ? "options.iris.wynncraftAmbienceEnabledStatus" : "options.iris.wynncraftAmbienceDisabledStatus");
		try {
			Iris.getIrisConfig().save();
		} catch (IOException e) {
			Iris.logger.warn("Failed to save ambience pack setting", e);
		}
		updateButtons();
		if (enabled) {
			maybeOpenAutoWarmScreen();
		}
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
			warmButton.active = dependencyStatus != null && !dependencyStatus.hasMissing() && !AmbienceRuntime.isWarmupRunning();
		}
		if (autoWarmButton != null) {
			autoWarmButton.setMessage(autoWarmLabel());
			autoWarmButton.active = true;
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
		this.minecraft.setScreen(new AmbiencePackMetadataScreen(this));
	}

	public void onPackCreated(AmbiencePackManager.LoadedAmbiencePack loaded, boolean openAddPreset) {
		IrisVideoSettings.wynncraftSelectedAmbiencePack = loaded.pack().id;
		refreshPacks();
		saveSelectedPack();
		if (ambiencePackList != null) {
			ambiencePackList.refresh(packs);
			ambiencePackList.selectPackId(loaded.pack().id);
		}
		status = Component.translatable("options.iris.wynncraftAmbiencePackCreated", loaded.pack().displayName());
		updateButtons();
		if (openAddPreset) {
			this.minecraft.setScreen(new AmbiencePresetCreateScreen(new AmbienceProfileSelectionScreen(this, loaded.pack().id), loaded.pack().id));
		} else {
			this.minecraft.setScreen(this);
		}
	}

	private void importPack() {
		if (Minecraft.getInstance().getWindow().isFullscreen()) {
			status = Component.translatable("options.iris.mustDisableFullscreen").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			return;
		}
		FileDialogUtil.fileSelectDialog(FileDialogUtil.DialogType.OPEN, "Import Ambience Pack", manager.getDirectory(), "Ambience Pack (.wynnambience.zip)", "*.wynnambience.zip")
			.whenComplete((path, error) -> {
				if (error != null) {
					Iris.logger.warn("Failed to select ambience pack for import", error);
					return;
				}
				path.ifPresent(selectedPath -> Minecraft.getInstance().execute(() -> importPack(selectedPath)));
			});
	}

	private void importPack(Path path) {
		try {
			AmbiencePackManager.LoadedAmbiencePack loaded = manager.importPack(path);
			IrisVideoSettings.wynncraftSelectedAmbiencePack = loaded.pack().id;
			Iris.getIrisConfig().save();
			reloadPacks();
			if (ambiencePackList != null) {
				ambiencePackList.selectPackId(loaded.pack().id);
			}
			status = Component.translatable("options.iris.wynncraftAmbienceImported", loaded.pack().displayName()).withStyle(ChatFormatting.YELLOW);
		} catch (IOException e) {
			Iris.logger.warn("Failed to import ambience pack {}", path, e);
			status = Component.literal(e.getMessage() == null ? "Import failed" : e.getMessage()).withStyle(ChatFormatting.RED);
			updateButtons();
		}
	}

	private void exportPack() {
		if (packs.isEmpty()) {
			return;
		}
		if (Minecraft.getInstance().getWindow().isFullscreen()) {
			status = Component.translatable("options.iris.mustDisableFullscreen").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			return;
		}
		String fileName = selected().pack().id + ".wynnambience.zip";
		Path defaultPath = manager.getDirectory().resolve(fileName);
		FileDialogUtil.fileSelectDialog(FileDialogUtil.DialogType.SAVE, "Export Ambience Pack", defaultPath, "Ambience Pack (.wynnambience.zip)", "*.wynnambience.zip")
			.whenComplete((path, error) -> {
				if (error != null) {
					Iris.logger.warn("Failed to select ambience pack export path", error);
					return;
				}
				path.ifPresent(selectedPath -> Minecraft.getInstance().execute(() -> confirmAndExportPack(selectedPath)));
			});
	}

	private void confirmAndExportPack(Path destination) {
		try {
			List<AmbienceDependency> localOnly = manager.localOnlyDependencies(selected().pack().id);
			if (!localOnly.isEmpty()) {
				offerDependencyLinkBeforeExport(destination, localOnly.getFirst());
				return;
			}
			exportPack(destination);
		} catch (IOException e) {
			Iris.logger.warn("Failed to inspect ambience pack dependencies before export", e);
			status = Component.literal(e.getMessage() == null ? "Export failed" : e.getMessage()).withStyle(ChatFormatting.RED);
		}
	}

	private void offerDependencyLinkBeforeExport(Path destination, AmbienceDependency dependency) {
		String localName = firstLocalName(dependency);
		this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
			this.minecraft.setScreen(this);
			if (confirmed) {
				linkDependencyBeforeExport(destination, dependency, localName);
			} else {
				exportPack(destination);
			}
		}, Component.translatable("options.iris.wynncraftAmbienceExportLinkTitle"),
			Component.translatable("options.iris.wynncraftAmbienceExportLinkMessage", localName),
			Component.translatable("options.iris.wynncraftAmbienceExportLink"),
			Component.translatable("options.iris.export")));
	}

	private void linkDependencyBeforeExport(Path destination, AmbienceDependency dependency, String localName) {
		status = Component.translatable("options.iris.wynncraftAmbienceDependencyResolving", localName).withStyle(ChatFormatting.GRAY);
		CompletableFuture.runAsync(() -> {
			AmbienceDependencyResolver resolver = new AmbienceDependencyResolver();
			try {
				Path shaderPackPath = Iris.getShaderpacksDirectory().resolve(localName);
				Optional<AmbienceDependencyCandidate> exact = Files.isRegularFile(shaderPackPath)
					? resolver.findExactDependency(shaderPackPath, localName)
					: Optional.empty();
				if (exact.isPresent()) {
					AmbienceDependency linked = resolver.dependencyFromCandidate(exact.get());
					Minecraft.getInstance().execute(() -> saveLinkedDependencyAndContinueExport(destination, dependency.id, linked));
					return;
				}
				List<AmbienceDependencyCandidate> candidates = resolver.searchDependencies(localName, localName);
				Minecraft.getInstance().execute(() -> {
					if (candidates.isEmpty()) {
						status = Component.translatable("options.iris.wynncraftAmbienceDependencyNoMatches", localName).withStyle(ChatFormatting.YELLOW);
						exportPack(destination);
						return;
					}
					this.minecraft.setScreen(new AmbienceDependencySelectionScreen(this, localName, candidates, chosen -> {
						this.minecraft.setScreen(this);
						if ("modrinth".equalsIgnoreCase(chosen.type)) {
							saveLinkedDependencyAndContinueExport(destination, dependency.id, chosen);
						} else {
							exportPack(destination);
						}
					}));
				});
			} catch (Exception e) {
				Iris.logger.warn("Failed to link ambience dependency before export", e);
				Minecraft.getInstance().execute(() -> {
					status = Component.literal(e.getMessage() == null ? "Dependency search failed" : e.getMessage()).withStyle(ChatFormatting.RED);
					exportPack(destination);
				});
			}
		});
	}

	private void saveLinkedDependencyAndContinueExport(Path destination, String oldDependencyId, AmbienceDependency linked) {
		try {
			this.minecraft.setScreen(this);
			manager.replaceDependency(selected().pack().id, oldDependencyId, linked);
			status = Component.translatable("options.iris.wynncraftAmbienceDependencyLinked", firstLocalName(linked)).withStyle(ChatFormatting.YELLOW);
			manager.reload();
			refreshPacks();
			if (ambiencePackList != null) {
				ambiencePackList.refresh(packs);
				ambiencePackList.selectPackId(IrisVideoSettings.wynncraftSelectedAmbiencePack);
			}
			confirmAndExportPack(destination);
		} catch (IOException e) {
			Iris.logger.warn("Failed to save linked ambience dependency", e);
			status = Component.literal(e.getMessage() == null ? "Dependency link failed" : e.getMessage()).withStyle(ChatFormatting.RED);
			exportPack(destination);
		}
	}

	private String firstLocalName(AmbienceDependency dependency) {
		if (dependency != null && dependency.localNames != null) {
			for (String localName : dependency.localNames) {
				if (localName != null && !localName.isBlank()) {
					return localName;
				}
			}
		}
		return dependency == null || dependency.id == null || dependency.id.isBlank() ? "shader pack" : dependency.id;
	}

	private void exportPack(Path destination) {
		try {
			manager.exportPack(selected().pack().id, destination);
			status = Component.translatable("options.iris.wynncraftAmbienceExported", destination.getFileName().toString()).withStyle(ChatFormatting.YELLOW);
		} catch (IOException e) {
			Iris.logger.warn("Failed to export ambience pack", e);
			status = Component.literal(e.getMessage() == null ? "Export failed" : e.getMessage()).withStyle(ChatFormatting.RED);
		}
		updateButtons();
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
					maybeOpenAutoWarmScreen();
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
		AmbienceRuntime.WarmupStartResult result = AmbienceRuntime.startWarmSelectedPackProfiles("manual");
		if (result.started()) {
			this.minecraft.setScreen(new AmbienceWarmupScreen(this, false));
			return;
		}
		status = result.alreadyWarm()
			? Component.translatable("options.iris.wynncraftAmbienceWarmupAlreadyPrepared", result.totalProfiles())
			: Component.translatable("options.iris.wynncraftAmbienceNoProfiles").withStyle(ChatFormatting.YELLOW);
		updateButtons();
	}

	private void toggleAutoWarm() {
		IrisVideoSettings.wynncraftAmbienceAutoWarmCache = !IrisVideoSettings.wynncraftAmbienceAutoWarmCache;
		status = Component.translatable(IrisVideoSettings.wynncraftAmbienceAutoWarmCache
			? "options.iris.wynncraftAmbienceAutoWarmEnabledStatus"
			: "options.iris.wynncraftAmbienceAutoWarmDisabledStatus");
		try {
			Iris.getIrisConfig().save();
		} catch (IOException e) {
			Iris.logger.warn("Failed to save ambience auto warm setting", e);
		}
		updateButtons();
		if (IrisVideoSettings.wynncraftAmbienceAutoWarmCache) {
			maybeOpenAutoWarmScreen();
		}
	}

	private Component autoWarmLabel() {
		return Component.translatable(IrisVideoSettings.wynncraftAmbienceAutoWarmCache
			? "options.iris.wynncraftAmbienceAutoWarmOn"
			: "options.iris.wynncraftAmbienceAutoWarmOff");
	}

	private void maybeOpenAutoWarmScreen() {
		if (packs.isEmpty() || !IrisVideoSettings.wynncraftAmbienceEnabled || !IrisVideoSettings.wynncraftAmbienceAutoWarmCache) {
			return;
		}
		AmbienceDependencyStatus dependencyStatus = manager.dependencyStatus(selected().pack());
		if (dependencyStatus.hasMissing()) {
			return;
		}
		AmbienceRuntime.WarmupStartResult result = AmbienceRuntime.startWarmSelectedPackProfiles("auto");
		if (result.started()) {
			this.minecraft.setScreen(new AmbienceWarmupScreen(this, true));
		} else if (result.alreadyWarm()) {
			status = Component.translatable("options.iris.wynncraftAmbienceWarmupAlreadyPrepared", result.totalProfiles());
		}
	}

	private static class IconButton extends IrisButton {
		private final GuiUtil.Icon icon;
		private final GuiUtil.Icon hoveredIcon;

		private IconButton(int x, int y, int width, int height, Component label, GuiUtil.Icon icon, GuiUtil.Icon hoveredIcon, OnPress onPress, FloatSupplier alpha) {
			super(x, y, width, height, label, onPress, DEFAULT_NARRATION, alpha);
			this.icon = icon;
			this.hoveredIcon = hoveredIcon;
		}

		@Override
		protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
			GuiUtil.drawButton(guiGraphics, this.getX(), this.getY(), this.getWidth(), this.getHeight(), this.isHoveredOrFocused(), !this.isActive());
			GuiUtil.Icon renderedIcon = this.isHoveredOrFocused() && this.isActive() ? hoveredIcon : icon;
			int iconX = this.getX() + (this.getWidth() - renderedIcon.getWidth()) / 2;
			int iconY = this.getY() + (this.getHeight() - renderedIcon.getHeight()) / 2;
			renderedIcon.draw(guiGraphics, iconX, iconY);
		}
	}
}
