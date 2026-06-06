package net.irisshaders.iris.mixin.entity_render_context;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.irisshaders.iris.layer.WynncraftVfxRenderStateShard;
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

	@WrapOperation(method = {"getFoilBuffer", "getSpecialFoilBuffer"},
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private static VertexConsumer iris$useIsolatedWynncraftVfxBuffer(MultiBufferSource bufferSource, RenderType renderType,
																	 Operation<VertexConsumer> original) {
		if (ImmediateState.captureItemEntityBatches
			&& ImmediateState.capturePhotonTranslucentVfxPipelines
			&& renderType != null
			&& ImmediateState.isWynncraftVfxCandidatePipeline(renderType.pipeline())
			&& ImmediateState.shouldForceCurrentItemLayerWynnSignal(renderType.pipeline())) {
			renderType = OuterWrappedRenderType.wrapExactlyOnce("iris:wynncraft_vfx", renderType, WynncraftVfxRenderStateShard.INSTANCE);
		}

		return original.call(bufferSource, renderType);
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
