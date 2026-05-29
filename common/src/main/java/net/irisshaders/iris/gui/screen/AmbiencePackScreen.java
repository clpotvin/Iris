package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.ambience.AmbienceDependencyResolver;
import net.irisshaders.iris.ambience.AmbienceDependencyStatus;
import net.irisshaders.iris.ambience.AmbiencePack;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.ambience.AmbienceRuntime;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AmbiencePackScreen extends Screen {
	private final Screen parent;
	private final AmbiencePackManager manager = AmbiencePackManager.getInstance();
	private List<AmbiencePackManager.LoadedAmbiencePack> packs = new ArrayList<>();
	private int selectedIndex;
	private Component status = Component.empty();
	private Button installButton;
	private Button warmButton;

	public AmbiencePackScreen(Screen parent) {
		super(Component.translatable("options.iris.wynncraftAmbiencePacks"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		manager.reloadIfNeeded();
		refreshPacks();
		int center = this.width / 2;
		int y = this.height - 110;

		this.addRenderableWidget(Button.builder(Component.literal("<"), button -> select(selectedIndex - 1))
			.bounds(center - 154, y, 48, 20)
			.build());
		this.addRenderableWidget(Button.builder(Component.literal(">"), button -> select(selectedIndex + 1))
			.bounds(center - 102, y, 48, 20)
			.build());
		this.addRenderableWidget(Button.builder(Component.translatable("options.iris.refresh"), button -> {
			manager.reload();
			refreshPacks();
			status = Component.translatable("options.iris.wynncraftAmbienceReloaded");
		}).bounds(center - 50, y, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("options.iris.openAmbiencePackFolder"), button ->
			CompletableFuture.runAsync(() -> Util.getPlatform().openUri(manager.getDirectory().toUri())))
			.bounds(center + 54, y, 152, 20)
			.build());

		installButton = this.addRenderableWidget(Button.builder(Component.translatable("options.iris.wynncraftAmbienceInstallMissing"), button -> installMissing())
			.bounds(center - 154, y + 26, 204, 20)
			.build());
		warmButton = this.addRenderableWidget(Button.builder(Component.translatable("options.iris.wynncraftAmbienceWarmCache"), button -> warmCache())
			.bounds(center + 54, y + 26, 152, 20)
			.build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.minecraft.setScreen(parent))
			.bounds(center + 54, y + 52, 152, 20)
			.build());
		updateButtons();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		super.render(guiGraphics, mouseX, mouseY, delta);
		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);

		if (packs.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.translatable("options.iris.wynncraftAmbienceNoPacks"), this.width / 2, 52, 0xFFFFAAAA);
			guiGraphics.drawCenteredString(this.font, manager.getDirectory().toString(), this.width / 2, 66, 0xFFAAAAAA);
			return;
		}

		AmbiencePack pack = selected().pack();
		int x = this.width / 2 - 154;
		int y = 48;
		guiGraphics.drawString(this.font, Component.literal(pack.displayName()).withStyle(ChatFormatting.BOLD), x, y, 0xFFFFFFFF);
		guiGraphics.drawString(this.font, Component.literal(pack.id + (pack.version == null || pack.version.isBlank() ? "" : " " + pack.version)), x, y + 14, 0xFFAAAAAA);
		guiGraphics.drawString(this.font, Component.translatable("options.iris.wynncraftAmbienceSelected", selectedIndex + 1, packs.size()), x, y + 28, 0xFFCCCCCC);
		guiGraphics.drawString(this.font, Component.literal(selected().path().getFileName().toString()), x, y + 42, 0xFF888888);

		AmbienceDependencyStatus dependencyStatus = manager.dependencyStatus(pack);
		if (dependencyStatus.hasMissing()) {
			guiGraphics.drawString(this.font, Component.translatable("options.iris.wynncraftAmbienceMissing", dependencyStatus.missing().size()).withStyle(ChatFormatting.RED), x, y + 64, 0xFFFF7777);
			int offset = 78;
			for (int i = 0; i < Math.min(5, dependencyStatus.missing().size()); i++) {
				guiGraphics.drawString(this.font, Component.literal("- " + dependencyStatus.missing().get(i).id), x, y + offset, 0xFFFFAAAA);
				offset += 12;
			}
		} else {
			guiGraphics.drawString(this.font, Component.translatable("options.iris.wynncraftAmbienceReady").withStyle(ChatFormatting.GREEN), x, y + 64, 0xFFAAFFAA);
		}

		if (status != null && !status.getString().isBlank()) {
			guiGraphics.drawCenteredString(this.font, status, this.width / 2, this.height - 136, 0xFFFFFFFF);
		}
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
		if (selectedFound) {
			updateButtons();
			return;
		}
		if (selectedId == null || selectedId.isBlank()) {
			saveSelectedPack();
		} else {
			status = Component.translatable("options.iris.wynncraftAmbienceSelectedMissing", selectedId).withStyle(ChatFormatting.YELLOW);
		}
		updateButtons();
	}

	private void select(int index) {
		if (packs.isEmpty()) {
			return;
		}
		selectedIndex = Math.floorMod(index, packs.size());
		saveSelectedPack();
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
		if (installButton == null) {
			return;
		}
		installButton.active = dependencyStatus != null && dependencyStatus.hasInstallableMissing();
		if (warmButton != null) {
			warmButton.active = dependencyStatus != null && !dependencyStatus.hasMissing();
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
