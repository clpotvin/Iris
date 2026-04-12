package net.irisshaders.iris.mixin.entity_render_context;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.mixinterface.ItemContextState;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SolidBucketItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public class ItemStackStateLayerMixin {
	@Unique
	private ItemStackRenderState parentState;

	@Shadow
	net.minecraft.client.renderer.texture.TextureAtlasSprite particleIcon;

	@Shadow
	private java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void iris$catchParent(ItemStackRenderState itemStackRenderState, CallbackInfo ci) {
		this.parentState = itemStackRenderState;
	}

	/**
	 * Unified skybox + emissive detection via packedLight modification.
	 * Runs as @ModifyVariable on the first int param (packedLight) of submit().
	 * Single-pass scan of quads: detects skybox signal (G=251,A=254) AND emissive signal (A=254,G!=251).
	 * Skybox detection always runs regardless of emissivity setting.
	 * PoseStack is captured from the method args for delta_y extraction.
	 */
	@ModifyVariable(
		method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
		at = @At("HEAD"),
		ordinal = 0,
		argsOnly = true
	)
	private int iris$detectSignalsAndModifyLight(int packedLight, PoseStack poseStack) {
		if (quads == null || quads.isEmpty()) return packedLight;

		boolean foundEmissive = false;
		int skyboxId = 0;
		float skyboxDeltaY = 0;
		String skyboxSpriteName = null;
		int skyboxPixelA = 0, skyboxPixelR = 0, skyboxPixelG = 0, skyboxPixelB = 0;

		try {
			for (var quad : quads) {
				var sprite = quad.sprite();
				if (sprite == null) continue;
				var contents = sprite.contents();
				if (contents == null) continue;
				var image = ((net.irisshaders.iris.mixin.texture.SpriteContentsAccessor) contents).getOriginalImage();
				if (image == null) continue;
				int w = contents.width();
				int h = contents.height();
				if (w < 1 || h < 1) continue;

				// NativeImage.getPixel returns ARGB format
				int pixel = image.getPixel(w / 2, h / 2);
				int a = (pixel >> 24) & 0xFF;
				int g = (pixel >> 8) & 0xFF;
				int b = (pixel >> 0) & 0xFF;

				if (g == 251 && a == 254 && b >= 1 && b <= 7) {
					// Skybox signal — first valid wins
					if (skyboxId == 0) {
						skyboxId = b;
						try { skyboxDeltaY = poseStack.last().pose().m31(); } catch (Exception ignored) {}
						skyboxSpriteName = contents.name().toString();
						skyboxPixelA = a;
						skyboxPixelR = (pixel >> 16) & 0xFF;
						skyboxPixelG = g;
						skyboxPixelB = b;
					}
				} else if (!foundEmissive && g != 251) {
					// Emissive signal: A=254 anywhere in the texture.
					// Scan 9-point grid (center, 4 corners, 4 edge midpoints) because
					// RP uses different pixel positions for texture properties.
					int[][] samplePositions = {
						{w/2, h/2}, {0, 0}, {w-1, 0}, {0, h-1}, {w-1, h-1},
						{w/2, 0}, {w/2, h-1}, {0, h/2}, {w-1, h/2}
					};
					for (int[] pos : samplePositions) {
						int sx = Math.max(0, Math.min(pos[0], w-1));
						int sy = Math.max(0, Math.min(pos[1], h-1));
						int sp = image.getPixel(sx, sy);
						int sa = (sp >> 24) & 0xFF;
						int sg = (sp >> 8) & 0xFF;
						if (sa == 254 && sg != 251) { foundEmissive = true; break; }
					}
				}
			}
		} catch (Exception e) {
			net.irisshaders.iris.gui.option.WynncraftDebugLog.info("signal-error",
				"[WynnIris] Signal detection error: {}", e.toString());
		}

		// Apply skybox detection (independent of emissivity)
		if (skyboxId > 0) {
			net.irisshaders.iris.vertices.ImmediateState.noteSkyboxDetection(skyboxId, skyboxDeltaY);
			if (net.irisshaders.iris.gui.option.WynncraftDebugLog.shouldLog("skybox-detect-" + skyboxId)) {
				float dx = 0, dy = 0, dz = 0;
				double wx = 0, wy = 0, wz = 0;
				try {
					org.joml.Matrix4f mat = poseStack.last().pose();
					dx = mat.m30(); dy = mat.m31(); dz = mat.m32();
					var cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
					var camPos = cam.position();
					wx = camPos.x() + dx; wy = camPos.y() + dy; wz = camPos.z() + dz;
				} catch (Exception ignored) {}
				net.irisshaders.iris.gui.option.WynncraftDebugLog.info("skybox-detect-" + skyboxId,
					"[WynnIris Skybox] CPU detected skybox ID={} world=({},{},{}) delta=({},{},{}) sprite={} (argb={},{},{},{})",
					skyboxId, String.format("%.1f", wx), String.format("%.1f", wy), String.format("%.1f", wz),
					String.format("%.1f", dx), String.format("%.1f", dy), String.format("%.1f", dz),
					skyboxSpriteName, skyboxPixelA, skyboxPixelR, skyboxPixelG, skyboxPixelB);
			}
		}

		// Apply emissive boost
		if (foundEmissive) {
			int emissivity = IrisVideoSettings.wynncraftEntityEmissivity;
			if (emissivity <= 0) return packedLight;
			if (emissivity >= 100) return 0xF000F0; // LightTexture.FULL_BRIGHT

			// Channel-wise lerp toward fullbright
			int block = (packedLight >> 4) & 0xF;
			int sky = (packedLight >> 20) & 0xF;
			float t = emissivity / 100.0f;
			int blockOut = Math.round(block + (15 - block) * t);
			int skyOut = Math.round(sky + (15 - sky) * t);
			return (blockOut << 4) | (skyOut << 20);
		}

		return packedLight;
	}

	@Inject(method = "submit", at = @At("HEAD"))
	private void onRender(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int i, int j, int k, CallbackInfo ci, @Share("lastBState") LocalIntRef ref) {
		ref.set(CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity());
		iris$setupId(((ItemContextState) parentState).getDisplayItem(), ((ItemContextState) parentState).getDisplayItemModel());
	}

	@Inject(method = "submit", at = @At("TAIL"))
	private void onRenderEnd(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int i, int j, int k, CallbackInfo ci, @Share("lastBState") LocalIntRef ref) {
		CapturedRenderingState.INSTANCE.setCurrentBlockEntity(ref.get());
		CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
	}

	@Unique
	private void iris$setupId(Item item, Identifier modelId) {
		if (WorldRenderingSettings.INSTANCE.getItemIds() == null) return;

		if (item instanceof BlockItem blockItem && !(item instanceof SolidBucketItem)) {
			if (WorldRenderingSettings.INSTANCE.getBlockStateIds() == null) return;

			CapturedRenderingState.INSTANCE.setCurrentBlockEntity(1);

			CapturedRenderingState.INSTANCE.setCurrentRenderedItem(WorldRenderingSettings.INSTANCE.getBlockStateIds().getOrDefault(blockItem.getBlock().defaultBlockState(), 0));
		} else {
			Identifier location = modelId != null ? modelId : BuiltInRegistries.ITEM.getKey(item);

			CapturedRenderingState.INSTANCE.setCurrentRenderedItem(WorldRenderingSettings.INSTANCE.getItemIds().applyAsInt(new NamespacedId(location.getNamespace(), location.getPath())));
		}
	}
}
