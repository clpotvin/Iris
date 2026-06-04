package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.ambience.AmbiencePackMetadata;
import net.irisshaders.iris.gui.element.screen.IrisButton;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class AmbiencePackMetadataScreen extends Screen {
	private static final Component SUBTITLE = Component.translatable("options.iris.wynncraftAmbiencePackCreateSubtitle").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	private final AmbiencePackScreen parent;
	private final AmbiencePackManager manager = AmbiencePackManager.getInstance();
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private EditBox nameBox;
	private EditBox versionBox;
	private EditBox authorsBox;
	private Button createButton;
	private Component status = Component.empty();
	private float backgroundInit = 0.0f;

	public final SmoothedFloat buttonTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);

	public AmbiencePackMetadataScreen(AmbiencePackScreen parent) {
		super(Component.translatable("options.iris.wynncraftAmbiencePackCreateTitle"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		this.clearWidgets();

		int fieldWidth = Math.min(308, this.width - 50);
		int fieldLeft = this.width / 2 - fieldWidth / 2;
		this.nameBox = new EditBox(this.font, fieldLeft, 58, fieldWidth, 20, Component.translatable("options.iris.wynncraftAmbiencePackName"));
		this.nameBox.setMaxLength(64);
		this.nameBox.setValue(Component.translatable("options.iris.wynncraftAmbiencePackDefaultName").getString());
		this.addRenderableWidget(this.nameBox);

		this.versionBox = new EditBox(this.font, fieldLeft, 96, fieldWidth, 20, Component.translatable("options.iris.wynncraftAmbiencePackVersion"));
		this.versionBox.setMaxLength(32);
		this.versionBox.setValue("1.0.0");
		this.addRenderableWidget(this.versionBox);

		this.authorsBox = new EditBox(this.font, fieldLeft, 134, fieldWidth, 20, Component.translatable("options.iris.wynncraftAmbiencePackAuthors"));
		this.authorsBox.setMaxLength(128);
		this.addRenderableWidget(this.authorsBox);

		int bottomCenter = this.width / 2 - 50;
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_CANCEL, button -> this.minecraft.setScreen(parent), buttonTransition)
			.bounds(bottomCenter - 52, this.height - 31, 100, 20)
			.build());
		this.createButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.create"), button -> createPack(), buttonTransition)
			.bounds(bottomCenter + 52, this.height - 31, 100, 20)
			.build());
		this.setInitialFocus(this.nameBox);
		updateCreateButton();
	}

	@Override
	public void tick() {
		super.tick();
		updateCreateButton();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		notifier.onNewFrame();
		backgroundInit = 1.0f;
		super.render(guiGraphics, mouseX, mouseY, delta);

		drawCenteredTruncated(guiGraphics, this.title, 8, 0xFFFFFFFF);
		drawCenteredTruncated(guiGraphics, status == null || status.getString().isBlank() ? SUBTITLE : status, 21, 0xFFFFFFFF);
		guiGraphics.drawString(this.font, Component.translatable("options.iris.wynncraftAmbiencePackName"), this.nameBox.getX(), 46, 0xFFCCCCCC);
		guiGraphics.drawString(this.font, Component.translatable("options.iris.wynncraftAmbiencePackVersion"), this.versionBox.getX(), 84, 0xFFCCCCCC);
		guiGraphics.drawString(this.font, Component.translatable("options.iris.wynncraftAmbiencePackAuthors"), this.authorsBox.getX(), 122, 0xFFCCCCCC);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			this.minecraft.setScreen(parent);
			return true;
		}
		return super.keyPressed(event);
	}

	private void createPack() {
		String name = nameBox.getValue().trim();
		if (name.isBlank()) {
			updateCreateButton();
			return;
		}
		try {
			List<String> authors = Arrays.stream(authorsBox.getValue().split(","))
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.toList();
			AmbiencePackMetadata metadata = new AmbiencePackMetadata(name, versionBox.getValue(), authors);
			AmbiencePackManager.LoadedAmbiencePack loaded = manager.createPack(metadata);
			IrisVideoSettings.wynncraftSelectedAmbiencePack = loaded.pack().id;
			Iris.getIrisConfig().save();
			parent.onPackCreated(loaded, true);
		} catch (IOException e) {
			Iris.logger.warn("Failed to create ambience pack", e);
			status = Component.literal(e.getMessage() == null ? "Create failed" : e.getMessage()).withStyle(ChatFormatting.RED);
			updateCreateButton();
		}
	}

	private void updateCreateButton() {
		if (createButton != null) {
			createButton.active = nameBox != null && !nameBox.getValue().trim().isBlank();
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
