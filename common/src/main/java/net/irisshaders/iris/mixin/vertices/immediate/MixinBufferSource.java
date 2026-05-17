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

	// Mark the item VFX builder while ItemRenderer is rendering a tint-signaled layer.
	@Inject(method = "getBuffer",
		at = @At("RETURN"))
	private void iris$trackTranslucentBuilder(RenderType renderType, CallbackInfoReturnable<VertexConsumer> cir) {
		if (ImmediateState.captureItemEntityBatches
			&& ((Object) this) == ImmediateState.captureSource
			&& ImmediateState.isWynncraftVfxCandidatePipeline(renderType.pipeline())
			&& ImmediateState.shouldForceCurrentItemLayerWynnSignal(renderType.pipeline())) {
			VertexConsumer consumer = cir.getReturnValue();
			if (consumer instanceof BufferBuilder builder) {
				ImmediateState.buildersWithWynnSignal.add(builder);
			}
		}
	}

	@Inject(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
		at = @At("HEAD"))
	private void iris$beginFlushBuffer(RenderType renderType, BufferBuilder bufferBuilder, CallbackInfo ci) {
		if (ImmediateState.captureItemEntityBatches
			&& ((Object) this) == ImmediateState.captureSource
			&& ImmediateState.isWynncraftVfxCandidatePipeline(renderType.pipeline())) {
			ImmediateState.flushingBuilder = bufferBuilder;
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
		if (ImmediateState.flushingBuilder == bufferBuilder) {
			ImmediateState.flushingBuilder = null;
		}
		if (iris$notRenderingLevel()) {
			ImmediateState.renderWithExtendedVertexFormat = true;
		}
	}

	@Inject(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
		at = @At("RETURN"))
	private void iris$endFlushBuffer(RenderType renderType, BufferBuilder bufferBuilder, CallbackInfo ci) {
		if (ImmediateState.flushingBuilder == bufferBuilder) {
			ImmediateState.flushingBuilder = null;
		}
	}

	// Wynncraft translucent entity deferral: intercept RenderType.draw(MeshData) calls
	// for candidate entity batches that contain the Wynncraft translucency signal.
	// Signal-containing batches are queued and drawn later (after beginTranslucents) so they
	// render with the sky already composited, fixing the black halo issue.
	// Non-signal batches draw immediately, avoiding the translucency bleed on normal display entities.
	@WrapOperation(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/rendertype/RenderType;draw(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
	private void iris$conditionallyDeferDraw(RenderType renderType, MeshData meshData, Operation<Void> original) {
		if (ImmediateState.captureItemEntityBatches
			&& ((Object) this) == ImmediateState.captureSource
			&& ImmediateState.isWynncraftVfxCandidatePipeline(renderType.pipeline())
			&& ImmediateState.buildersWithWynnSignal.contains(ImmediateState.flushingBuilder)) {
			// Defer: enqueue for drawing after beginTranslucents
			ImmediateState.deferredDraws.add(new ImmediateState.DeferredDraw(renderType, meshData));
			// Reset signal flag only after processing this tracked batch
			ImmediateState.buildersWithWynnSignal.remove(ImmediateState.flushingBuilder);
		} else {
			// Draw immediately as normal
			original.call(renderType, meshData);
			// Only reset signal flag for tracked candidate entity batches
			// from the tracked source. Other render types or other BufferSources must
			// NOT clear the latch — the signal may have been detected but the tracked
			// batch hasn't flushed yet.
			if (ImmediateState.isWynncraftVfxCandidatePipeline(renderType.pipeline())
				&& ((Object) this) == ImmediateState.captureSource) {
				ImmediateState.buildersWithWynnSignal.remove(ImmediateState.flushingBuilder);
			}
		}
	}

	@Unique
	private boolean iris$notRenderingLevel() {
		return !ImmediateState.isRenderingLevel;
	}
}
