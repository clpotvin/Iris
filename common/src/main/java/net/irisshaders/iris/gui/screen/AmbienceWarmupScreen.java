package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.ambience.AmbienceRuntime;
import net.irisshaders.iris.gui.element.screen.IrisButton;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class AmbienceWarmupScreen extends Screen {
	private static final Component SUBTITLE = Component.translatable("options.iris.wynncraftAmbienceWarmupSubtitle").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	private final Screen parent;
	private final boolean autoStarted;
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private Button closeButton;
	private boolean autoClosed;
	private float backgroundInit = 0.0f;

	public final SmoothedFloat buttonTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);

	public AmbienceWarmupScreen(Screen parent, boolean autoStarted) {
		super(Component.translatable("options.iris.wynncraftAmbienceWarmupTitle"));
		this.parent = parent;
		this.autoStarted = autoStarted;
	}

	@Override
	protected void init() {
		super.init();
		this.clearWidgets();
		this.closeButton = this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_CANCEL, button -> closeOrCancel(), buttonTransition)
			.bounds(this.width / 2 - 75, this.height - 31, 150, 20)
			.build());
		updateButton();
	}

	@Override
	public void tick() {
		super.tick();
		AmbienceRuntime.WarmupProgress progress = AmbienceRuntime.getWarmupProgress();
		updateButton(progress);
		autoCloseIfFinished(progress);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		notifier.onNewFrame();
		backgroundInit = 1.0f;

		super.render(guiGraphics, mouseX, mouseY, delta);

		AmbienceRuntime.WarmupProgress progress = AmbienceRuntime.getWarmupProgress();
		drawCenteredTruncated(guiGraphics, this.title, 18, 0xFFFFFFFF);
		drawCenteredTruncated(guiGraphics, progress.packDisplayName().isBlank() ? SUBTITLE : Component.literal(progress.packDisplayName()).withStyle(ChatFormatting.YELLOW), 34, 0xFFFFFFFF);

		int panelWidth = Math.min(420, this.width - 40);
		int panelLeft = this.width / 2 - panelWidth / 2;
		int panelTop = Math.max(58, this.height / 2 - 68);
		int panelBottom = panelTop + 112;
		guiGraphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelBottom, 0xA0000000);
		guiGraphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 1, 0xFF777777);
		guiGraphics.fill(panelLeft, panelBottom - 1, panelLeft + panelWidth, panelBottom, 0xFF777777);
		guiGraphics.fill(panelLeft, panelTop, panelLeft + 1, panelBottom, 0xFF777777);
		guiGraphics.fill(panelLeft + panelWidth - 1, panelTop, panelLeft + panelWidth, panelBottom, 0xFF777777);

		Component status = statusText(progress);
		drawCenteredTruncated(guiGraphics, status, panelTop + 14, 0xFFFFFFFF);

		int barLeft = panelLeft + 24;
		int barTop = panelTop + 38;
		int barWidth = panelWidth - 48;
		int barHeight = 12;
		guiGraphics.fill(barLeft, barTop, barLeft + barWidth, barTop + barHeight, 0xFF202020);
		guiGraphics.fill(barLeft - 1, barTop - 1, barLeft + barWidth + 1, barTop, 0xFF777777);
		guiGraphics.fill(barLeft - 1, barTop + barHeight, barLeft + barWidth + 1, barTop + barHeight + 1, 0xFF777777);
		guiGraphics.fill(barLeft - 1, barTop - 1, barLeft, barTop + barHeight + 1, 0xFF777777);
		guiGraphics.fill(barLeft + barWidth, barTop - 1, barLeft + barWidth + 1, barTop + barHeight + 1, 0xFF777777);
		int filledWidth = progress.totalProfiles() <= 0 ? 0 : Math.min(barWidth, Math.max(0, progress.processedProfiles() * barWidth / progress.totalProfiles()));
		int fillColor = progress.failedProfiles() > 0 ? 0xFFE6B84A : 0xFF66DD88;
		guiGraphics.fill(barLeft, barTop, barLeft + filledWidth, barTop + barHeight, fillColor);

		Component counts = Component.translatable("options.iris.wynncraftAmbienceWarmupCounts",
			progress.processedProfiles(), progress.totalProfiles(), progress.warmedProfiles(), progress.cachedProfiles(), progress.failedProfiles());
		drawCenteredTruncated(guiGraphics, counts, panelTop + 58, 0xFFCCCCCC);

		Component elapsed = Component.translatable("options.iris.wynncraftAmbienceWarmupElapsed",
			formatMillis(progress.elapsedMillis()), formatMillis(progress.lastProfileMillis()));
		drawCenteredTruncated(guiGraphics, elapsed, panelTop + 74, 0xFFAAAAAA);

		if (autoStarted && progress.running()) {
			drawCenteredTruncated(guiGraphics, Component.translatable("options.iris.wynncraftAmbienceWarmupAuto"), panelTop + 92, 0xFFAAAAAA);
		} else if (progress.complete() && progress.restoreMillis() > 0) {
			drawCenteredTruncated(guiGraphics, Component.translatable("options.iris.wynncraftAmbienceWarmupRestoreTime", formatMillis(progress.restoreMillis())), panelTop + 92, 0xFFAAAAAA);
		} else {
			drawCenteredTruncated(guiGraphics, SUBTITLE, panelTop + 92, 0xFFAAAAAA);
		}

		AmbienceRuntime.processWarmupWithoutLevelRender();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return !AmbienceRuntime.getWarmupProgress().running();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (AmbienceRuntime.getWarmupProgress().running()) {
			return;
		}
		this.minecraft.setScreen(parent);
	}

	private void closeOrCancel() {
		AmbienceRuntime.WarmupProgress progress = AmbienceRuntime.getWarmupProgress();
		if (progress.running()) {
			AmbienceRuntime.cancelWarmup();
		}
		this.minecraft.setScreen(parent);
	}

	private void updateButton() {
		updateButton(AmbienceRuntime.getWarmupProgress());
	}

	private void updateButton(AmbienceRuntime.WarmupProgress progress) {
		if (closeButton == null) {
			return;
		}
		closeButton.setMessage(progress.running() ? CommonComponents.GUI_CANCEL : CommonComponents.GUI_DONE);
	}

	private void autoCloseIfFinished(AmbienceRuntime.WarmupProgress progress) {
		if (autoClosed || progress.running() || !progress.complete() || progress.cancelled() || progress.failedProfiles() > 0) {
			return;
		}

		autoClosed = true;
		this.minecraft.setScreen(parent);
	}

	private Component statusText(AmbienceRuntime.WarmupProgress progress) {
		if (!progress.active()) {
			return Component.translatable("options.iris.wynncraftAmbienceWarmupIdle").withStyle(ChatFormatting.GRAY);
		}
		if (progress.running() && progress.restoring()) {
			return Component.translatable("options.iris.wynncraftAmbienceWarmupRestoring");
		}
		if (progress.running()) {
			return Component.translatable("options.iris.wynncraftAmbienceWarmupPreparing",
				progress.processedProfiles() + 1, progress.totalProfiles(), progress.nextProfileId());
		}
		if (progress.cancelled()) {
			return Component.translatable("options.iris.wynncraftAmbienceWarmupCancelled").withStyle(ChatFormatting.YELLOW);
		}
		if (progress.failedProfiles() > 0) {
			return Component.translatable("options.iris.wynncraftAmbienceWarmupCompleteWithFailures", progress.failedProfiles()).withStyle(ChatFormatting.YELLOW);
		}
		return Component.translatable("options.iris.wynncraftAmbienceWarmupComplete").withStyle(ChatFormatting.GREEN);
	}

	private String formatMillis(long millis) {
		if (millis < 1000) {
			return millis + " ms";
		}
		return String.format("%.1f s", millis / 1000.0);
	}

	private void drawCenteredTruncated(GuiGraphics guiGraphics, Component component, int y, int color) {
		Component rendered = component;
		if (this.font.width(component) > this.width - 20) {
			rendered = Component.literal(this.font.plainSubstrByWidth(component.getString(), this.width - 32) + "...").setStyle(component.getStyle());
		}
		guiGraphics.drawCenteredString(this.font, rendered, (int) (this.width * 0.5), y, color);
	}
}
