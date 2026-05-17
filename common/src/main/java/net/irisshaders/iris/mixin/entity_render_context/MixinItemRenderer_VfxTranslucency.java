package net.irisshaders.iris.mixin.entity_render_context;

import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ItemRenderer.class)
public class MixinItemRenderer_VfxTranslucency {
	@Inject(method = "renderItem", at = @At("HEAD"))
	private static void iris$beginWynncraftVfxTintLayer(ItemDisplayContext displayContext, PoseStack poseStack,
													   MultiBufferSource bufferSource, int packedLight, int packedOverlay,
													   int[] tintLayers, List<BakedQuad> quads, RenderType renderType,
													   ItemStackRenderState.FoilType foilType, CallbackInfo ci) {
		if (!ImmediateState.captureItemEntityBatches
			|| !ImmediateState.capturePhotonTranslucentVfxPipelines
			|| renderType == null
			|| !ImmediateState.isWynncraftVfxCandidatePipeline(renderType.pipeline())) {
			return;
		}

		if (!iris$hasTranslucencyTint(tintLayers)) {
			return;
		}

		ImmediateState.beginForcedWynncraftItemLayerSignal(renderType.pipeline());
	}

	@Inject(method = "renderItem", at = @At("RETURN"))
	private static void iris$endWynncraftVfxTintLayer(ItemDisplayContext displayContext, PoseStack poseStack,
													 MultiBufferSource bufferSource, int packedLight, int packedOverlay,
													 int[] tintLayers, List<BakedQuad> quads, RenderType renderType,
													 ItemStackRenderState.FoilType foilType, CallbackInfo ci) {
		if (ImmediateState.shouldForceCurrentItemLayerWynnSignal(renderType == null ? null : renderType.pipeline())) {
			ImmediateState.endForcedWynncraftItemLayerSignal();
		}
	}

	@Unique
	private static boolean iris$hasTranslucencyTint(int[] tintLayers) {
		if (tintLayers == null) {
			return false;
		}
		for (int tint : tintLayers) {
			if (ImmediateState.isWynncraftTranslucencySignalArgb(tint)) {
				return true;
			}
		}
		return false;
	}
}
