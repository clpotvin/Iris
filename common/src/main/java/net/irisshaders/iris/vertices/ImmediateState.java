package net.irisshaders.iris.vertices;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.ArrayList;
import java.util.List;

/**
 * Some annoying global state needed for rendering.
 */
public class ImmediateState {
	public static final ThreadLocal<Boolean> skipExtension = ThreadLocal.withInitial(() -> false);
	public static boolean isRenderingLevel = false;
	public static boolean usingTessellation = false;
	public static boolean renderWithExtendedVertexFormat = true;
	public static boolean bypass;
	public static boolean temporarilyIgnorePass;
	public static boolean safeToMultiply;
	public static boolean isRenderingBEs;

	// ====================================================================================
	// WYNNCRAFT TRANSLUCENT ENTITY DEFERRAL (per-mesh, signal-gated)
	// ====================================================================================
	// Defers only display entity meshes that contain the Wynncraft translucency signal
	// (vertex color G=254, B=0) past beginTranslucents(), so they render with the sky
	// already composited. Non-signal meshes flush immediately as normal.

	// When true, ITEM_ENTITY_TRANSLUCENT_CULL draw calls are checked for the signal.
	public static boolean captureItemEntityBatches;
	// The BufferSource being tracked (to avoid affecting other buffer sources).
	public static Object captureSource;
	// The BufferBuilder currently assigned to ITEM_ENTITY_TRANSLUCENT_CULL.
	// Set in MixinBufferSource.getBuffer, read in MixinBufferBuilder.fillExtendedData.
	public static BufferBuilder trackedTranslucentBuilder;
	// Latched true when any vertex in the current batch has the Wynncraft translucency signal.
	// Reset after each endBatch draw decision.
	public static boolean trackedBuilderHasWynnSignal;

	// Queue of deferred mesh draws (signal-containing batches held until beginTranslucents).
	public record DeferredDraw(RenderType renderType, MeshData meshData) {}
	public static final List<DeferredDraw> deferredDraws = new ArrayList<>();

	// Flush all deferred draws (called at beginTranslucents).
	public static void flushDeferredDraws() {
		for (DeferredDraw draw : deferredDraws) {
			draw.renderType.draw(draw.meshData);
		}
		deferredDraws.clear();
	}

	// Close and discard any leftover deferred draws (cleanup/error path).
	public static void clearDeferredDraws() {
		for (DeferredDraw draw : deferredDraws) {
			draw.meshData.close();
		}
		deferredDraws.clear();
	}

	// Reset all capture state.
	public static void resetCapture() {
		captureItemEntityBatches = false;
		captureSource = null;
		trackedTranslucentBuilder = null;
		trackedBuilderHasWynnSignal = false;
	}

	// ====================================================================================
	// WYNNCRAFT SKYBOX CPU-SIDE DETECTION
	// ====================================================================================
	// Detects skybox variant ID from item display entity textures on the CPU.
	// Works on ALL platforms including Mac (no GL 4.2 required).
	// Set from ItemStackStateLayerMixin when a skybox texture signal is found.
	// Read from IrisRenderingPipeline.finalizeLevelRendering().

	// The skybox ID detected this frame (1-7), or 0 if none detected.
	// Reset to 0 at frame start. The FIRST detection wins (lowest ID priority).
	public static volatile int cpuDetectedSkyboxId = 0;

	public static void noteSkyboxDetection(int id) {
		if (id >= 1 && id <= 7) {
			int current = cpuDetectedSkyboxId;
			if (current == 0 || id < current) {
				cpuDetectedSkyboxId = id; // lowest ID wins (primary skybox effect)
			}
		}
	}

	public static int consumeSkyboxDetection() {
		int id = cpuDetectedSkyboxId;
		cpuDetectedSkyboxId = 0;
		return id;
	}
}
