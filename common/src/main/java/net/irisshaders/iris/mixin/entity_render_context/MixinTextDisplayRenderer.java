package net.irisshaders.iris.mixin.entity_render_context;

import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Intercepts text display entity rendering for two Wynncraft features:
 * 1. Brightness boost when a custom skybox is active
 * 2. Transition screen effect detection (characters U+E000-U+E012 with
 *    font minecraft:screen/transition → suppress entity, trigger fullscreen effect)
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public class MixinTextDisplayRenderer {

	@Unique
	private static final Identifier TRANSITION_FONT = Identifier.fromNamespaceAndPath("minecraft", "screen/transition");

	/**
	 * Detect Wynncraft transition signal in text display entities.
	 * If a transition character (U+E000-U+E012) with the transition font is found,
	 * store the detection in ImmediateState and cancel the entity's rendering.
	 */
	@Inject(
		method = "submitInner(Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void iris$detectTransition(TextDisplayEntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, int packedLight, float interpolationProgress, CallbackInfo ci) {
		if (state.textRenderState == null) return;

		Display.TextDisplay.TextRenderState trs = state.textRenderState;
		Component text = trs.text();
		if (text == null) return;

		// Quick check: does the plain text contain any PUA character?
		String plain = text.getString();
		boolean hasPUA = false;
		for (int i = 0; i < plain.length(); i++) {
			char c = plain.charAt(i);
			if (c >= 0xE000 && c <= 0xE012) {
				hasPUA = true;
				break;
			}
		}

		// Debug: log text display entities with PUA characters in the transition font
		if (hasPUA && IrisVideoSettings.wynncraftDebugLogging) {
			StringBuilder charInfo = new StringBuilder();
			text.visit((Style style, String content) -> {
				FontDescription font = style.getFont();
				if (!(font instanceof FontDescription.Resource r) || !r.id().equals(TRANSITION_FONT)) {
					return Optional.empty();
				}
				for (int i = 0; i < content.length(); i++) {
					char c = content.charAt(i);
					if (c >= 0xE000 && c <= 0xE1FF) {
						charInfo.append(String.format("U+%04X ", (int) c));
					}
				}
				return Optional.empty();
			}, Style.EMPTY);
			if (!charInfo.isEmpty()) {
				int opacity = trs.textOpacity().get(interpolationProgress) & 0xFF;
				net.irisshaders.iris.Iris.logger.info(
					"[WynnIris Trans] PUA text entity: chars=[{}] opacity={} plainLen={}",
					charInfo.toString().trim(), opacity, plain.length());
			}
		}

		if (!hasPUA) return;

		// Full walk: check font AND extract character, color
		AtomicInteger detectedType = new AtomicInteger(0);
		AtomicInteger detectedColor = new AtomicInteger(0x000000);

		text.visit((Style style, String content) -> {
			// Check font
			FontDescription font = style.getFont();
			if (!(font instanceof FontDescription.Resource r) || !r.id().equals(TRANSITION_FONT)) {
				return Optional.empty(); // wrong font, skip
			}
			// Check characters
			for (int i = 0; i < content.length(); i++) {
				char c = content.charAt(i);
				if (c >= 0xE000 && c <= 0xE012) {
					int type = c - 0xE000 + 1; // U+E000 = type 1, U+E012 = type 19
					detectedType.set(type);
					// Extract color from style
					TextColor textColor = style.getColor();
					if (textColor != null) {
						detectedColor.set(textColor.getValue()); // 0xRRGGBB
					}
					return Optional.of(Boolean.TRUE); // stop walking
				}
			}
			return Optional.empty();
		}, Style.EMPTY);

		if (detectedType.get() > 0) {
			// Extract opacity
			int opacity = trs.textOpacity().get(interpolationProgress) & 0xFF;

			if (IrisVideoSettings.wynncraftDebugLogging) {
				net.irisshaders.iris.Iris.logger.info(
					"[WynnIris Trans] MATCHED: type={} opacity={} color=0x{}",
					detectedType.get(), opacity, Integer.toHexString(detectedColor.get()));
			}

			// Only suppress + redirect when Iris pipeline is active (it renders the transition).
			// When shaders are off, let the vanilla RP text shaders handle it natively.
			if (net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline) {
				ImmediateState.noteTransitionDetection(detectedType.get(), opacity, detectedColor.get());
				ci.cancel();
			}
		}
	}

	@ModifyVariable(
		method = "submitInner(Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
		at = @At("HEAD"),
		ordinal = 0,
		argsOnly = true
	)
	private int iris$boostTextLight(int packedLight) {
		if (IrisRenderingPipeline.skyboxFogColor != null) {
			return 0xF000F0; // LightTexture.FULL_BRIGHT
		}
		return packedLight;
	}

}
