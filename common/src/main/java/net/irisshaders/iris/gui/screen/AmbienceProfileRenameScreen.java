package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.gui.element.screen.IrisButton;
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

public class AmbienceProfileRenameScreen extends Screen {
	private final AmbienceProfileSelectionScreen parent;
	private final String packId;
	private final String profileId;
	private final AmbiencePackManager manager = AmbiencePackManager.getInstance();
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private EditBox nameBox;
	private Button saveButton;
	private Component status = Component.empty();
	private float backgroundInit = 0.0f;

	public final SmoothedFloat buttonTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);

	public AmbienceProfileRenameScreen(AmbienceProfileSelectionScreen parent, String packId, String profileId) {
		super(Component.translatable("options.iris.wynncraftAmbienceProfileRenameTitle"));
		this.parent = parent;
		this.packId = packId;
		this.profileId = profileId;
	}

	@Override
	protected void init() {
		super.init();
		this.clearWidgets();
		int fieldWidth = Math.min(308, this.width - 50);
		int fieldLeft = this.width / 2 - fieldWidth / 2;
		this.nameBox = new EditBox(this.font, fieldLeft, 70, fieldWidth, 20, Component.translatable("options.iris.wynncraftAmbienceProfileName"));
		this.nameBox.setMaxLength(64);
		this.nameBox.setValue(profileId);
		this.addRenderableWidget(nameBox);
		this.setInitialFocus(nameBox);

		int bottomCenter = this.width / 2 - 50;
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_CANCEL, button -> this.minecraft.setScreen(parent), buttonTransition)
			.bounds(bottomCenter - 52, this.height - 31, 100, 20)
			.build());
		this.saveButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.apply"), button -> rename(), buttonTransition)
			.bounds(bottomCenter + 52, this.height - 31, 100, 20)
			.build());
		updateButton();
	}

	@Override
	public void tick() {
		super.tick();
		updateButton();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		notifier.onNewFrame();
		backgroundInit = 1.0f;
		super.render(guiGraphics, mouseX, mouseY, delta);
		drawCenteredTruncated(guiGraphics, this.title, 8, 0xFFFFFFFF);
		drawCenteredTruncated(guiGraphics, status == null || status.getString().isBlank() ? Component.literal(profileId).withStyle(ChatFormatting.GRAY) : status, 21, 0xFFFFFFFF);
		guiGraphics.drawString(this.font, Component.translatable("options.iris.wynncraftAmbienceProfileName"), this.nameBox.getX(), 58, 0xFFCCCCCC);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			this.minecraft.setScreen(parent);
			return true;
		}
		return super.keyPressed(event);
	}

	private void rename() {
		try {
			String id = manager.renameProfile(packId, profileId, nameBox.getValue()).id;
			parent.onPresetChanged(id, Component.translatable("options.iris.wynncraftAmbienceProfileRenamed", id).withStyle(ChatFormatting.YELLOW));
			this.minecraft.setScreen(parent);
		} catch (IOException e) {
			Iris.logger.warn("Failed to rename ambience preset", e);
			status = Component.literal(e.getMessage() == null ? "Rename failed" : e.getMessage()).withStyle(ChatFormatting.RED);
			updateButton();
		}
	}

	private void updateButton() {
		if (saveButton != null) {
			saveButton.active = nameBox != null && !nameBox.getValue().trim().isBlank();
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
