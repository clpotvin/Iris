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
		float r = cir.getReturnValue().x;
		float g = cir.getReturnValue().y;
		float b = cir.getReturnValue().z;

		// Override fog color when Wynncraft skybox is active.
		// Blended with vanilla fog using skybox fade opacity for smooth transitions.
		float[] skyFog = net.irisshaders.iris.pipeline.IrisRenderingPipeline.skyboxFogColor;
		float blend = net.irisshaders.iris.pipeline.IrisRenderingPipeline.skyboxFogBlendFactor;
		if (skyFog != null && blend > 0.001f) {
			r = r * (1 - blend) + skyFog[0] * blend;
			g = g * (1 - blend) + skyFog[1] * blend;
			b = b * (1 - blend) + skyFog[2] * blend;
		}

		CapturedRenderingState.INSTANCE.setFogColor(r, g, b);

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
			if (inMushroomFields) {
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
