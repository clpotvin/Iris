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
 * Intercepts text display entity rendering for Wynncraft features:
 * 1. Brightness adjustments for dark skyboxes and generally dark areas
 * 2. Transition screen effect detection (characters U+E000-U+E012 with
 *    font minecraft:screen/transition → suppress entity, trigger fullscreen effect)
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public class MixinTextDisplayRenderer {

	@Unique
	private static final Identifier TRANSITION_FONT = Identifier.fromNamespaceAndPath("minecraft", "screen/transition");
	@Unique
	private static final int TEXT_LIGHT_FOR_BRIGHT_COLORS = 12;
	@Unique
	private static final int TEXT_LIGHT_FOR_DARK_COLORS = 15;
	@Unique
	private static final float TEXT_DARK_LUMINANCE = 0.18f;
	@Unique
	private static final float TEXT_BRIGHT_LUMINANCE = 0.82f;

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
				net.irisshaders.iris.gui.option.WynncraftDebugLog.info("trans-pua",
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

			net.irisshaders.iris.gui.option.WynncraftDebugLog.info("trans-match-" + detectedType.get(),
				"[WynnIris Trans] MATCHED: type={} opacity={} color=0x{}",
				detectedType.get(), opacity, Integer.toHexString(detectedColor.get()));

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
	private int iris$boostTextLight(int packedLight, TextDisplayEntityRenderState state) {
		int block = (packedLight >> 4) & 0xF;
		int sky = (packedLight >> 20) & 0xF;

		if (IrisVideoSettings.wynncraftTextBrightnessFloor) {
			int floor = iris$clampLight(IrisVideoSettings.wynncraftTextBrightnessFloorLevel);
			block = Math.max(block, floor);
			sky = Math.max(sky, floor);
		}

		float boostStrength = iris$textDarkSkyboxBoostStrength();
		if (boostStrength > 0.0f) {
			float luminance = iris$estimateTextLuminance(state);
			float darkTextAmount = 1.0f - iris$smoothstep(TEXT_DARK_LUMINANCE, TEXT_BRIGHT_LUMINANCE, luminance);
			int targetLight = Math.round(TEXT_LIGHT_FOR_BRIGHT_COLORS +
				(TEXT_LIGHT_FOR_DARK_COLORS - TEXT_LIGHT_FOR_BRIGHT_COLORS) * darkTextAmount);

			block = iris$lerpLight(block, targetLight, boostStrength);
			sky = iris$lerpLight(sky, targetLight, boostStrength);
		}

		if (block == ((packedLight >> 4) & 0xF) && sky == ((packedLight >> 20) & 0xF)) {
			return packedLight;
		}
		return (packedLight & ~0x00F000F0) | (block << 4) | (sky << 20);
	}

	@Unique
	private static float iris$textDarkSkyboxBoostStrength() {
		if (IrisRenderingPipeline.skyboxFogColor == null) {
			return 0.0f;
		}
		float entityBrightness = IrisVideoSettings.wynncraftEntityBrightness / 100.0f;
		float sceneDarkening = IrisVideoSettings.wynncraftSceneDarkening / 100.0f;
		return iris$clamp01(entityBrightness * sceneDarkening * IrisRenderingPipeline.skyboxFadeOpacity);
	}

	@Unique
	private static float iris$estimateTextLuminance(TextDisplayEntityRenderState state) {
		if (state == null || state.textRenderState == null || state.textRenderState.text() == null) {
			return 1.0f;
		}

		float[] weightedLuminance = new float[] { 0.0f };
		int[] totalWeight = new int[] { 0 };
		state.textRenderState.text().visit((Style style, String content) -> {
			int weight = content.codePointCount(0, content.length());
			if (weight <= 0) {
				return Optional.empty();
			}

			TextColor textColor = style.getColor();
			float luminance = textColor != null ? iris$relativeLuminance(textColor.getValue()) : 1.0f;
			weightedLuminance[0] += luminance * weight;
			totalWeight[0] += weight;
			return Optional.empty();
		}, Style.EMPTY);

		if (totalWeight[0] == 0) {
			return 1.0f;
		}
		return iris$clamp01(weightedLuminance[0] / totalWeight[0]);
	}

	@Unique
	private static float iris$relativeLuminance(int rgb) {
		float r = iris$srgbToLinear(((rgb >> 16) & 0xFF) / 255.0f);
		float g = iris$srgbToLinear(((rgb >> 8) & 0xFF) / 255.0f);
		float b = iris$srgbToLinear((rgb & 0xFF) / 255.0f);
		return 0.2126f * r + 0.7152f * g + 0.0722f * b;
	}

	@Unique
	private static float iris$srgbToLinear(float channel) {
		if (channel <= 0.04045f) {
			return channel / 12.92f;
		}
		return (float) Math.pow((channel + 0.055f) / 1.055f, 2.4f);
	}

	@Unique
	private static int iris$lerpLight(int current, int target, float amount) {
		int light = Math.round(current + (target - current) * iris$clamp01(amount));
		return iris$clampLight(light);
	}

	@Unique
	private static int iris$clampLight(int light) {
		if (light < 0) return 0;
		if (light > 15) return 15;
		return light;
	}

	@Unique
	private static float iris$smoothstep(float edge0, float edge1, float value) {
		float t = iris$clamp01((value - edge0) / (edge1 - edge0));
		return t * t * (3.0f - 2.0f * t);
	}

	@Unique
	private static float iris$clamp01(float value) {
		if (value < 0.0f) return 0.0f;
		if (value > 1.0f) return 1.0f;
		return value;
	}
}
