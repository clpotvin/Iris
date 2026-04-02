package net.irisshaders.iris.mixin.vertices.immediate;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Quick optimization to disable the extended vertex format outside of level rendering if we're using a BufferSource.
 * This is a heuristic that should hopefully work almost always because of how people use BufferSource.
 */
@Mixin(MultiBufferSource.BufferSource.class)
public class MixinBufferSource {
	@WrapOperation(method = "getBuffer",
		at = @At(value = "NEW",
			target = "(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;Lcom/mojang/blaze3d/vertex/VertexFormat;)Lcom/mojang/blaze3d/vertex/BufferBuilder;"))
	private BufferBuilder iris$redirectBegin(ByteBufferBuilder byteBufferBuilder, VertexFormat.Mode mode, VertexFormat vertexFormat, Operation<BufferBuilder> original) {
		ImmediateState.skipExtension.set(iris$notRenderingLevel());
		BufferBuilder builder = original.call(byteBufferBuilder, mode, vertexFormat);
		ImmediateState.skipExtension.set(false);

		return builder;
	}

	// Track ITEM_ENTITY_TRANSLUCENT_CULL buffer builders for Wynncraft signal detection.
	// When getBuffer returns a builder for this render type, store a reference so
	// MixinBufferBuilder.fillExtendedData can check vertex colors only for this builder.
	@Inject(method = "getBuffer",
		at = @At("RETURN"))
	private void iris$trackTranslucentBuilder(RenderType renderType, CallbackInfoReturnable<VertexConsumer> cir) {
		if (ImmediateState.captureItemEntityBatches
			&& ((Object) this) == ImmediateState.captureSource
			&& renderType.pipeline() == RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL) {
			VertexConsumer consumer = cir.getReturnValue();
			if (consumer instanceof BufferBuilder builder) {
				ImmediateState.trackedTranslucentBuilder = builder;
			}
		}
	}

	@Inject(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/rendertype/RenderType;draw(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
	private void iris$beforeFlushBuffer(RenderType renderType, BufferBuilder bufferBuilder, CallbackInfo ci) {
		if (iris$notRenderingLevel()) {
			ImmediateState.renderWithExtendedVertexFormat = false;
		}
	}

	@Inject(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/rendertype/RenderType;draw(Lcom/mojang/blaze3d/vertex/MeshData;)V",
			shift = At.Shift.AFTER))
	private void iris$afterFlushBuffer(RenderType renderType, BufferBuilder bufferBuilder, CallbackInfo ci) {
		if (iris$notRenderingLevel()) {
			ImmediateState.renderWithExtendedVertexFormat = true;
		}
	}

	// Wynncraft translucent entity deferral: intercept RenderType.draw(MeshData) calls
	// for ITEM_ENTITY_TRANSLUCENT_CULL batches that contain the Wynncraft translucency signal.
	// Signal-containing batches are queued and drawn later (after beginTranslucents) so they
	// render with the sky already composited, fixing the black halo issue.
	// Non-signal batches draw immediately, avoiding the translucency bleed on normal display entities.
	@WrapOperation(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/rendertype/RenderType;draw(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
	private void iris$conditionallyDeferDraw(RenderType renderType, MeshData meshData, Operation<Void> original) {
		if (ImmediateState.captureItemEntityBatches
			&& ((Object) this) == ImmediateState.captureSource
			&& renderType.pipeline() == RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL
			&& ImmediateState.trackedBuilderHasWynnSignal) {
			// Defer: enqueue for drawing after beginTranslucents
			ImmediateState.deferredDraws.add(new ImmediateState.DeferredDraw(renderType, meshData));
			// Reset signal flag only after processing a tracked batch
			ImmediateState.trackedBuilderHasWynnSignal = false;
		} else {
			// Draw immediately as normal
			original.call(renderType, meshData);
			// Only reset signal flag for tracked ITEM_ENTITY_TRANSLUCENT_CULL batches
			// from the tracked source. Other render types or other BufferSources must
			// NOT clear the latch — the signal may have been detected but the tracked
			// batch hasn't flushed yet.
			if (renderType.pipeline() == RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL
				&& ((Object) this) == ImmediateState.captureSource) {
				ImmediateState.trackedBuilderHasWynnSignal = false;
			}
		}
	}

	@Unique
	private boolean iris$notRenderingLevel() {
		return !ImmediateState.isRenderingLevel;
	}
}
