package net.irisshaders.iris.mixin;

import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public class MixinFogRenderer {
	@Unique
	private static String iris$lastBiome = "";
	@Unique
	private static float iris$lastFogStart = Float.NaN;
	@Unique
	private static float iris$lastFogEnd = Float.NaN;

	// Temporal smoothing state for the chroma-preserved biome target color.
	// Smoothing only the biome branch (not vanilla fog) so weather/darkness
	// transitions stay responsive while sun-direction color churn is damped.
	@Unique
	private static long iris$lastFrameNanos = 0L;
	@Unique
	private static float iris$smoothedBiomeR = 0f;
	@Unique
	private static float iris$smoothedBiomeG = 0f;
	@Unique
	private static float iris$smoothedBiomeB = 0f;
	@Unique
	private static ResourceKey<net.minecraft.world.level.Level> iris$lastDimension = null;
	@Inject(method = "setupFog", at = @At("HEAD"))
	private void iris$setupLegacyWaterFog(Camera camera, int i, DeltaTracker deltaTracker, float f, ClientLevel clientLevel, CallbackInfoReturnable<Vector4f> cir) {
		if (camera.getFluidInCamera() == FogType.WATER) {
			Entity entity = camera.entity();

			float density = 0.05F;

			if (entity instanceof LocalPlayer localPlayer) {
				density -= localPlayer.getWaterVision() * localPlayer.getWaterVision() * 0.03F;
				Holder<Biome> biome = localPlayer.level().getBiome(localPlayer.blockPosition());

				// TODO: not supported (1.21.11+)
				//if (biome.is(BiomeTags.HAS_CLOSER_WATER_FOG)) {
				//	density += 0.005F;
				//}
			}

			CapturedRenderingState.INSTANCE.setFogDensity(density);
		} else {
			CapturedRenderingState.INSTANCE.setFogDensity(-1.0F);
		}
	}

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void render(Camera camera, int i, DeltaTracker deltaTracker, float f, ClientLevel clientLevel, CallbackInfoReturnable<Vector4f> cir) {
		float rawR = cir.getReturnValue().x;
		float rawG = cir.getReturnValue().y;
		float rawB = cir.getReturnValue().z;

		// Sun tint reduction target: a chroma-preserved version of the raw biome
		// fog color, scaled to match the vanilla fog's luminance. This avoids the
		// math-inversion approach (which couldn't reverse vanilla's post-tint
		// sky-blend / weather / darkness ops) while still pulling the fog hue back
		// toward the biome's natural color. Keeping vanilla's luminance means the
		// overall scene brightness responds correctly to rain/darkness/night.
		// Only active when the camera is in air; water/lava/powder snow use a
		// totally different color source that we should not touch.
		float untintedR = rawR, untintedG = rawG, untintedB = rawB;
		// Fast path: when the feature is fully disabled, skip the attribute probe
		// and EMA work entirely. Invalidate smoothing so re-enabling seeds fresh.
		// Threshold matches the shader's `strength > 0.001` check so Java and
		// GPU agree on when the pass is active.
		float sunTintStrength = Math.max(0f, Math.min(1f, IrisVideoSettings.wynncraftMistWoodsFogSunTintAmount / 100f));
		boolean sunTintActive = IrisVideoSettings.wynncraftMistWoodsFogSunTintReduction
			&& sunTintStrength > 0.001f;
		if (!sunTintActive) {
			iris$lastFrameNanos = 0L;
		}
		try {
			if (sunTintActive && camera.getFluidInCamera() == FogType.NONE) {
				float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
				int rawBiomeARGB = camera.attributeProbe().getValue(EnvironmentAttributes.FOG_COLOR, tickDelta);
				float pureR = ARGB.redFloat(rawBiomeARGB);
				float pureG = ARGB.greenFloat(rawBiomeARGB);
				float pureB = ARGB.blueFloat(rawBiomeARGB);

				float vanLuma = rawR * 0.2126f + rawG * 0.7152f + rawB * 0.0722f;
				float pureLuma = pureR * 0.2126f + pureG * 0.7152f + pureB * 0.0722f;
				float targetR = rawR, targetG = rawG, targetB = rawB;
				if (pureLuma > 1e-4f) {
					float scale = vanLuma / pureLuma;
					targetR = Math.max(0f, Math.min(1f, pureR * scale));
					targetG = Math.max(0f, Math.min(1f, pureG * scale));
					targetB = Math.max(0f, Math.min(1f, pureB * scale));
				}

				// Detect world/dimension change and first-frame so the EMA seeds directly
				// to the current target rather than drifting from stale or zero state.
				ResourceKey<net.minecraft.world.level.Level> currentDim =
					(clientLevel != null) ? clientLevel.dimension() : null;
				boolean resetSmoothing = iris$lastFrameNanos == 0L
					|| currentDim == null
					|| !currentDim.equals(iris$lastDimension);
				iris$lastDimension = currentDim;

				long nowNs = System.nanoTime();
				if (resetSmoothing) {
					iris$smoothedBiomeR = targetR;
					iris$smoothedBiomeG = targetG;
					iris$smoothedBiomeB = targetB;
					iris$lastFrameNanos = nowNs;
				} else {
					// Frame-rate-independent EMA: alpha = 1 - exp(-dt / tau).
					// tau ≈ 0.4s gives moderate smoothing without feeling sluggish.
					float dt = Math.max(0f, Math.min(0.25f, (nowNs - iris$lastFrameNanos) / 1_000_000_000f));
					iris$lastFrameNanos = nowNs;
					float alpha = 1f - (float) Math.exp(-dt / 0.4f);
					iris$smoothedBiomeR += (targetR - iris$smoothedBiomeR) * alpha;
					iris$smoothedBiomeG += (targetG - iris$smoothedBiomeG) * alpha;
					iris$smoothedBiomeB += (targetB - iris$smoothedBiomeB) * alpha;
				}

				untintedR = iris$smoothedBiomeR;
				untintedG = iris$smoothedBiomeG;
				untintedB = iris$smoothedBiomeB;
			} else {
				// Non-air fluid: invalidate smoothing so re-entering air seeds fresh
				// rather than continuing from stale biome state (hue pop on exit).
				iris$lastFrameNanos = 0L;
			}
		} catch (Exception e) {
			// Attribute access failed — fall back to raw vanilla fog (slider becomes no-op).
			// Invalidate smoothing state so the next successful frame seeds fresh.
			untintedR = rawR;
			untintedG = rawG;
			untintedB = rawB;
			iris$lastFrameNanos = 0L;
			iris$lastDimension = null;
		}

		// Override fog color when Wynncraft skybox is active.
		// Blended with vanilla fog using skybox fade opacity for smooth transitions.
		// Apply the same blend to the untinted color so both stay aligned.
		float r = rawR, g = rawG, b = rawB;
		float[] skyFog = net.irisshaders.iris.pipeline.IrisRenderingPipeline.skyboxFogColor;
		// Clamp to [0, 1] so any easing overshoot in the skybox state machine
		// can't produce out-of-range color spikes during transitions.
		float blend = Math.max(0f, Math.min(1f, net.irisshaders.iris.pipeline.IrisRenderingPipeline.skyboxFogBlendFactor));
		if (skyFog != null && blend > 0.001f) {
			r = rawR * (1 - blend) + skyFog[0] * blend;
			g = rawG * (1 - blend) + skyFog[1] * blend;
			b = rawB * (1 - blend) + skyFog[2] * blend;
			untintedR = untintedR * (1 - blend) + skyFog[0] * blend;
			untintedG = untintedG * (1 - blend) + skyFog[1] * blend;
			untintedB = untintedB * (1 - blend) + skyFog[2] * blend;
		}

		CapturedRenderingState.INSTANCE.setFogColor(r, g, b);
		CapturedRenderingState.INSTANCE.setUntintedFogColor(untintedR, untintedG, untintedB);

		// Wynncraft biome fog: detect mushroom_fields and capture close fog parameters.
		// Only mushroom_fields is handled — other biomes must not have their fog overridden
		// to avoid breaking Distant Horizons / Voxy far rendering.
		{
			Entity cameraEntity = camera.entity();
			boolean inMushroomFields = false;
			if (cameraEntity != null && clientLevel != null) {
				Holder<Biome> biome = clientLevel.getBiome(cameraEntity.blockPosition());
				inMushroomFields = biome.unwrapKey()
					.map(key -> key.identifier().toString().equals("minecraft:mushroom_fields"))
					.orElse(false);
			}
			if (inMushroomFields && IrisVideoSettings.wynncraftMistWoodsFog) {
				float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
				net.irisshaders.iris.pipeline.IrisRenderingPipeline.biomeFogStart =
					camera.attributeProbe().getValue(EnvironmentAttributes.FOG_START_DISTANCE, tickDelta);
				net.irisshaders.iris.pipeline.IrisRenderingPipeline.biomeFogEnd =
					camera.attributeProbe().getValue(EnvironmentAttributes.FOG_END_DISTANCE, tickDelta);
				net.irisshaders.iris.pipeline.IrisRenderingPipeline.biomeFogActive = true;
			} else {
				net.irisshaders.iris.pipeline.IrisRenderingPipeline.biomeFogActive = false;
			}
		}

		// Debug logging for fog diagnostics — only logs on biome or fog distance change
		if (net.irisshaders.iris.BuildConfig.WYNNIRIS_EXPERIMENTAL && IrisVideoSettings.wynncraftDebugLogging) {
			try {
				float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();

				Entity cameraEntity = camera.entity();
				String biomeName = "unknown";
				if (cameraEntity != null && clientLevel != null) {
					Holder<Biome> biome = clientLevel.getBiome(cameraEntity.blockPosition());
					biomeName = biome.unwrapKey().map(key -> key.identifier().toString()).orElse("unregistered");
				}

				float envFogStart = camera.attributeProbe().getValue(EnvironmentAttributes.FOG_START_DISTANCE, tickDelta);
				float envFogEnd = camera.attributeProbe().getValue(EnvironmentAttributes.FOG_END_DISTANCE, tickDelta);

				boolean biomeChanged = !biomeName.equals(iris$lastBiome);
				boolean fogChanged = Math.abs(envFogStart - iris$lastFogStart) > 0.5f || Math.abs(envFogEnd - iris$lastFogEnd) > 0.5f;

				if (biomeChanged || fogChanged) {
					iris$lastBiome = biomeName;
					iris$lastFogStart = envFogStart;
					iris$lastFogEnd = envFogEnd;

					float envSkyFogEnd = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, tickDelta);
					float envCloudFogEnd = camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE, tickDelta);

					FogParameters fogParams = ((FogStorage) Minecraft.getInstance().gameRenderer).sodium$getFogParameters();
					String fogStorageInfo = (fogParams == FogParameters.NONE)
						? "NONE"
						: String.format("envStart=%.1f envEnd=%.1f renderStart=%.1f renderEnd=%.1f color=(%.2f,%.2f,%.2f,%.2f)",
							fogParams.environmentalStart(), fogParams.environmentalEnd(),
							fogParams.renderStart(), fogParams.renderEnd(),
							fogParams.red(), fogParams.green(), fogParams.blue(), fogParams.alpha());

					float capturedDensity = CapturedRenderingState.INSTANCE.getFogDensity();

					Iris.logger.info("[WynnIris Fog Debug] biome={} envAttr: start={} end={} skyEnd={} cloudEnd={} | fogStorage: {} | density={} fogColor=({},{},{})",
						biomeName, envFogStart, envFogEnd, envSkyFogEnd, envCloudFogEnd,
						fogStorageInfo, capturedDensity, r, g, b);
				}
			} catch (Exception e) {
				Iris.logger.warn("[WynnIris Fog Debug] Failed to log fog info", e);
			}
		}
	}
}
