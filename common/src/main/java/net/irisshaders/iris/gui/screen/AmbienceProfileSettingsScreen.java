package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.ambience.AmbienceRuntime;
import net.irisshaders.iris.gui.GuiUtil;
import net.irisshaders.iris.gui.NavigationController;
import net.irisshaders.iris.gui.element.ShaderPackOptionList;
import net.irisshaders.iris.gui.element.screen.IrisButton;
import net.irisshaders.iris.gui.element.widget.AbstractElementWidget;
import net.irisshaders.iris.gui.element.widget.CommentedElementWidget;
import net.irisshaders.iris.shaderpack.option.values.MutableOptionValues;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

public class AmbienceProfileSettingsScreen extends Screen implements ShaderPackOptionScreen {
	private static final int COMMENT_PANEL_WIDTH = 314;
	private static final Component CONFIGURE_TITLE = Component.translatable("pack.iris.ambience.profile.configure.title").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	private final Screen parent;
	private final String packId;
	private final String profileId;
	private final AmbiencePackManager manager = AmbiencePackManager.getInstance();
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private Iris.LoadedShaderPackOptions loadedShaderPack;
	private ShaderPackOptionList optionList;
	private NavigationController navigation;
	private Component notificationDialog = Component.empty();
	private int notificationDialogTimer;
	private AbstractElementWidget<?> hoveredElement = null;
	private Optional<Component> hoveredElementCommentTitle = Optional.empty();
	private List<FormattedCharSequence> hoveredElementCommentBody = new ArrayList<>();
	private int hoveredElementCommentTimer = 0;
	private boolean dropChanges;
	private float backgroundInit = 0.0f;

	public final SmoothedFloat listTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);
	public final SmoothedFloat buttonTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);

	public AmbienceProfileSettingsScreen(Screen parent, String packId, String profileId) {
		super(Component.translatable("options.iris.wynncraftAmbienceProfileSettings.title"));
		this.parent = parent;
		this.packId = packId;
		this.profileId = profileId;
		Iris.clearShaderPackOptionQueue();
	}

	@Override
	protected void init() {
		super.init();
		loadShaderPackOptions();
		this.hoveredElement = null;
		this.hoveredElementCommentTimer = 0;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		notifier.onNewFrame();
		backgroundInit = 1.0f;

		super.render(guiGraphics, mouseX, mouseY, delta);

		drawCenteredTruncated(guiGraphics, this.title, 8, 0xFFFFFFFF);
		if (notificationDialog != null && !notificationDialog.getString().isBlank() && notificationDialogTimer > 0) {
			drawCenteredTruncated(guiGraphics, notificationDialog, 21, 0xFFFFFFFF);
		} else {
			drawCenteredTruncated(guiGraphics, CONFIGURE_TITLE, 21, 0xFFFFFFFF);
		}

		if (this.isDisplayingComment()) {
			int panelHeight = Math.max(50, 18 + (this.hoveredElementCommentBody.size() * 10));
			int x = (int) (0.5 * this.width) - 157;
			int y = this.height - (panelHeight + 4);
			GuiUtil.drawPanel(guiGraphics, x, y, COMMENT_PANEL_WIDTH, panelHeight);
			guiGraphics.drawString(font, this.hoveredElementCommentTitle.orElse(Component.empty()), x + 4, y + 4, 0xFFFFFFFF);
			for (int i = 0; i < this.hoveredElementCommentBody.size(); i++) {
				guiGraphics.drawString(font, this.hoveredElementCommentBody.get(i), x + 4, (y + 16) + (i * 10), 0xFFFFFFFF);
			}
		}

		ShaderPackOptionScreen.renderTopLayerQueue();
	}

	@Override
	public void tick() {
		super.tick();
		if (this.notificationDialogTimer > 0) {
			this.notificationDialogTimer--;
		}
		if (this.hoveredElement != null) {
			this.hoveredElementCommentTimer++;
		} else {
			this.hoveredElementCommentTimer = 0;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			if (this.navigation != null && this.navigation.hasHistory()) {
				this.navigation.back();
				return true;
			}
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		if (!dropChanges) {
			applyChanges();
		} else {
			Iris.clearShaderPackOptionQueue();
		}
		closeLoadedShaderPack();
		this.minecraft.setScreen(parent);
	}

	@Override
	public float iris$getListTransition() {
		return listTransition.getAsFloat();
	}

	@Override
	public Component iris$getOptionMenuTitle() {
		return Component.literal(profileId + " - " + resolvedShaderPackName().orElse(""));
	}

	@Override
	public boolean isDisplayingComment() {
		return this.hoveredElementCommentTimer > 10 &&
			this.hoveredElementCommentTitle.isPresent() &&
			!this.hoveredElementCommentBody.isEmpty();
	}

	@Override
	public void setElementHoveredStatus(AbstractElementWidget<?> widget, boolean hovered) {
		if (hovered && widget != this.hoveredElement) {
			this.hoveredElement = widget;

			if (widget instanceof CommentedElementWidget<?> commented) {
				this.hoveredElementCommentTitle = commented.getCommentTitle();

				Optional<Component> commentBody = commented.getCommentBody();
				if (commentBody.isEmpty()) {
					this.hoveredElementCommentBody.clear();
				} else {
					String rawCommentBody = commentBody.get().getString();
					if (rawCommentBody.endsWith(".")) {
						rawCommentBody = rawCommentBody.substring(0, rawCommentBody.length() - 1);
					}
					List<MutableComponent> splitByPeriods = java.util.Arrays.stream(rawCommentBody.split("\\. [ ]*")).map(Component::literal).toList();
					this.hoveredElementCommentBody = new ArrayList<>();
					for (MutableComponent text : splitByPeriods) {
						this.hoveredElementCommentBody.addAll(this.font.split(text, COMMENT_PANEL_WIDTH - 8));
					}
				}
			} else {
				this.hoveredElementCommentTitle = Optional.empty();
				this.hoveredElementCommentBody.clear();
			}

			this.hoveredElementCommentTimer = 0;
		} else if (!hovered && widget == this.hoveredElement) {
			this.hoveredElement = null;
			this.hoveredElementCommentTitle = Optional.empty();
			this.hoveredElementCommentBody.clear();
			this.hoveredElementCommentTimer = 0;
		}
	}

	@Override
	public void displayNotification(Component component) {
		this.notificationDialog = component;
		this.notificationDialogTimer = 100;
	}

	@Override
	public void applyChanges() {
		if (loadedShaderPack == null) {
			return;
		}

		try {
			Map<String, String> options = collectCurrentOptions();
			if (options.equals(currentSavedOptions())) {
				Iris.clearShaderPackOptionQueue();
				return;
			}
			manager.saveProfileOptions(packId, profileId, options);
			Iris.clearShaderPackOptionQueue();
			AmbienceRuntime.invalidateActiveProfile();
			displayNotification(Component.translatable("options.iris.wynncraftAmbienceProfileSettingsSaved", profileId).withStyle(ChatFormatting.YELLOW));
			loadShaderPackOptions();
		} catch (IOException e) {
			Iris.logger.warn("Failed to save ambience profile settings", e);
			displayNotification(Component.literal(e.getMessage() == null ? "Save failed" : e.getMessage()).withStyle(ChatFormatting.RED));
		}
	}

	@Override
	public void resetShaderPackOptions() {
		try {
			manager.saveProfileOptions(packId, profileId, Map.of());
			Iris.clearShaderPackOptionQueue();
			AmbienceRuntime.invalidateActiveProfile();
			displayNotification(Component.translatable("options.iris.wynncraftAmbienceProfileSettingsReset", profileId).withStyle(ChatFormatting.YELLOW));
			loadShaderPackOptions();
		} catch (IOException e) {
			Iris.logger.warn("Failed to reset ambience profile settings", e);
			displayNotification(Component.literal(e.getMessage() == null ? "Reset failed" : e.getMessage()).withStyle(ChatFormatting.RED));
		}
	}

	@Override
	public void importPackOptions(Path settingFile) {
		try (InputStream in = java.nio.file.Files.newInputStream(settingFile)) {
			Properties properties = new Properties();
			properties.load(in);

			Iris.clearShaderPackOptionQueue();
			properties.stringPropertyNames().forEach(key -> Iris.getShaderPackOptionQueue().put(key, properties.getProperty(key)));

			displayNotification(Component.translatable("options.iris.shaderPackOptions.importedSettings", settingFile.getFileName().toString()).withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW));
			if (this.navigation != null) {
				this.navigation.refresh();
			}
		} catch (Exception e) {
			Iris.logger.error("Error importing ambience shader settings file \"" + settingFile + "\"", e);
			displayNotification(Component.translatable("options.iris.shaderPackOptions.failedImport", settingFile.getFileName().toString()).withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));
		}
	}

	@Override
	public void exportPackOptions(Path settingFile) {
		try (OutputStream out = java.nio.file.Files.newOutputStream(settingFile)) {
			Properties properties = new Properties();
			collectCurrentOptions().forEach(properties::setProperty);
			properties.store(out, null);
		} catch (IOException e) {
			Iris.logger.error("Error exporting ambience shader settings file \"" + settingFile + "\"", e);
		}
	}

	@Override
	public Path getDefaultShaderPackOptionsPath() {
		return manager.getDirectory().resolve(sanitizeFileName(packId + "-" + profileId) + ".txt");
	}

	private void loadShaderPackOptions() {
		Optional<AmbiencePackManager.ResolvedProfile> resolved = manager.resolveProfile(packId, profileId);
		if (resolved.isEmpty()) {
			closeLoadedShaderPack();
			optionList = null;
			navigation = null;
			displayNotification(Component.translatable("options.iris.wynncraftAmbienceProfileMissingShader").withStyle(ChatFormatting.RED));
			layoutWidgets();
			return;
		}

		try {
			closeLoadedShaderPack();
			Map<String, String> options = resolved.get().profile().options == null ? Map.of() : resolved.get().profile().options;
			loadedShaderPack = Iris.loadShaderPackForOptionEditing(resolved.get().resolvedShaderPack(), options);
			navigation = new NavigationController(loadedShaderPack.pack().getMenuContainer());
			optionList = new ShaderPackOptionList(this, navigation, loadedShaderPack.pack(), this.minecraft, this.width, this.height, 32, this.height - 58, 0, this.width);
			navigation.setActiveOptionList(optionList);
			optionList.rebuild();
			layoutWidgets();
		} catch (IOException | RuntimeException e) {
			Iris.logger.warn("Failed to load ambience profile settings", e);
			closeLoadedShaderPack();
			optionList = null;
			navigation = null;
			displayNotification(Component.literal(e.getMessage() == null ? "Load failed" : e.getMessage()).withStyle(ChatFormatting.RED));
			layoutWidgets();
		}
	}

	private void layoutWidgets() {
		this.clearWidgets();
		if (this.optionList != null) {
			this.addRenderableWidget(optionList);
		}

		int bottomCenter = this.width / 2 - 50;
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_CANCEL, button -> this.dropChangesAndClose(), buttonTransition)
			.bounds(bottomCenter - 104, this.height - 27, 100, 20)
			.build());
		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.apply"), button -> this.applyChanges(), buttonTransition)
			.bounds(bottomCenter, this.height - 27, 100, 20)
			.build());
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_DONE, button -> this.onClose(), buttonTransition)
			.bounds(bottomCenter + 104, this.height - 27, 100, 20)
			.build());
	}

	private Map<String, String> collectCurrentOptions() {
		if (loadedShaderPack == null) {
			return Map.of();
		}
		MutableOptionValues values = loadedShaderPack.pack().getShaderPackOptions().getOptionValues().mutableCopy();
		values.addAll(Iris.getShaderPackOptionQueue());

		Map<String, String> options = new LinkedHashMap<>();
		values.getBooleanValues().entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> options.put(entry.getKey(), Boolean.toString(entry.getValue())));
		values.getStringValues().entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> options.put(entry.getKey(), entry.getValue()));
		return options;
	}

	private Map<String, String> currentSavedOptions() {
		return manager.resolveProfile(packId, profileId)
			.map(resolved -> resolved.profile().options)
			.map(options -> {
				Map<String, String> copied = new LinkedHashMap<>();
				options.entrySet().stream()
					.filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null)
					.sorted(Map.Entry.comparingByKey())
					.forEach(entry -> copied.put(entry.getKey(), entry.getValue()));
				return copied;
			})
			.orElse(Map.of());
	}

	private Optional<String> resolvedShaderPackName() {
		return manager.resolveProfile(packId, profileId).map(AmbiencePackManager.ResolvedProfile::resolvedShaderPack);
	}

	private void dropChangesAndClose() {
		dropChanges = true;
		onClose();
	}

	private void closeLoadedShaderPack() {
		if (loadedShaderPack != null) {
			try {
				loadedShaderPack.close();
			} catch (IOException e) {
				Iris.logger.warn("Failed to close ambience profile shader pack options", e);
			}
			loadedShaderPack = null;
		}
	}

	private void drawCenteredTruncated(GuiGraphics guiGraphics, Component component, int y, int color) {
		Component rendered = component;
		if (this.font.width(component) > this.width - 20) {
			rendered = Component.literal(this.font.plainSubstrByWidth(component.getString(), this.width - 32) + "...").setStyle(component.getStyle());
		}
		guiGraphics.drawCenteredString(this.font, rendered, (int) (this.width * 0.5), y, color);
	}

	private static String sanitizeFileName(String value) {
		return value.replaceAll("[^A-Za-z0-9._-]", "_");
	}
}
