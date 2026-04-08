package net.irisshaders.iris.mixin.entity_render_context;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.mixinterface.ItemContextState;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.MultiBufferSource;
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

	@Inject(method = "submit", at = @At("HEAD"))
	private void onRender(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int i, int j, int k, CallbackInfo ci, @Share("lastBState") LocalIntRef ref) {
		ref.set(CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity());
		iris$setupId(((ItemContextState) parentState).getDisplayItem(), ((ItemContextState) parentState).getDisplayItemModel());

		// Wynncraft skybox CPU detection: check this layer's particle icon texture
		// for the skybox signal (G=251, A=254, B=variant ID). Works on ALL platforms.
		iris$checkSkyboxSignal(poseStack);
	}

	@Unique
	private void iris$checkSkyboxSignal(PoseStack poseStack) {
		// Check quad sprites for the Wynncraft skybox texture signal (G=251, A=254, B=variant ID).
		// particleIcon is often minecraft:item/empty for custom models — the actual texture is on quads.
		if (quads == null || quads.isEmpty()) return;
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
					net.irisshaders.iris.vertices.ImmediateState.noteSkyboxDetection(b);
					if (net.irisshaders.iris.gui.option.IrisVideoSettings.wynncraftDebugLogging) {
						int r = (pixel >> 16) & 0xFF;
						// Extract entity distance from camera via PoseStack model matrix translation
						float dist = -1f;
						try {
							org.joml.Matrix4f mat = poseStack.last().pose();
							float tx = mat.m30(), ty = mat.m31(), tz = mat.m32();
							dist = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
						} catch (Exception ignored) {}
						net.irisshaders.iris.Iris.logger.info(
							"[WynnIris Skybox] CPU detected skybox ID={} dist={} from quad sprite {} (pixel argb={},{},{},{})",
							b, dist, contents.name(), a, r, g, b);
					}
					return;
				}
			}
		} catch (Exception e) {
			if (net.irisshaders.iris.gui.option.IrisVideoSettings.wynncraftDebugLogging) {
				net.irisshaders.iris.Iris.logger.warn("[WynnIris Skybox] CPU detection error: {}", e.toString());
			}
		}
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

			//System.out.println(WorldRenderingSettings.INSTANCE.getBlockStateIds().getInt(blockItem.getBlock().defaultBlockState()));
			CapturedRenderingState.INSTANCE.setCurrentRenderedItem(WorldRenderingSettings.INSTANCE.getBlockStateIds().getOrDefault(blockItem.getBlock().defaultBlockState(), 0));
		} else {
			Identifier location = modelId != null ? modelId : BuiltInRegistries.ITEM.getKey(item);

			CapturedRenderingState.INSTANCE.setCurrentRenderedItem(WorldRenderingSettings.INSTANCE.getItemIds().applyAsInt(new NamespacedId(location.getNamespace(), location.getPath())));
		}
	}
}
