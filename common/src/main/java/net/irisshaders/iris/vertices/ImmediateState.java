package net.irisshaders.iris.vertices;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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

	// When true, translucent entity draw calls are checked for the signal.
	public static boolean captureItemEntityBatches;
	// Photon disables its entities_translucent pass for this MC version. Only that fallback
	// mode uses the item-tint bridge below.
	public static boolean capturePhotonTranslucentVfxPipelines;
	// The BufferSource being tracked (to avoid affecting other buffer sources).
	public static Object captureSource;
	// Builders whose current batch contains the Wynncraft translucency signal. Reset per flush.
	public static final Set<BufferBuilder> buildersWithWynnSignal = Collections.newSetFromMap(new IdentityHashMap<>());
	// BufferBuilder currently being flushed by BufferSource.endBatch.
	public static BufferBuilder flushingBuilder;
	// True only while replaying deferred Wynncraft VFX batches. Shader override code
	// can use this to select a dedicated forward fallback shader for specific packs.
	public static boolean drawingDeferredWynncraftVfx;
	// Set only around ItemRenderer.renderItem when its tint array contains the Wynncraft
	// translucency signal. This covers item display VFX paths where the final builder
	// hook does not observe the color bytes before Photon fallback routing is needed.
	public static RenderPipeline forcedWynncraftItemLayerPipeline;
	public static boolean forcedWynncraftItemLayerSignal;
	public static int forcedWynncraftItemLayerSignalDepth;

	public static boolean isWynncraftTranslucencySignal(int r, int g, int b) {
		return g == 254 && b == 0 && r >= 1 && r <= 254;
	}

	public static boolean isWynncraftTranslucencySignalArgb(int color) {
		int r = (color >>> 16) & 0xFF;
		int g = (color >>> 8) & 0xFF;
		int b = color & 0xFF;
		return isWynncraftTranslucencySignal(r, g, b);
	}

	public static void beginForcedWynncraftItemLayerSignal(RenderPipeline pipeline) {
		forcedWynncraftItemLayerPipeline = pipeline;
		forcedWynncraftItemLayerSignal = true;
		forcedWynncraftItemLayerSignalDepth++;
	}

	public static void endForcedWynncraftItemLayerSignal() {
		if (forcedWynncraftItemLayerSignalDepth > 0) {
			forcedWynncraftItemLayerSignalDepth--;
		}
		if (forcedWynncraftItemLayerSignalDepth == 0) {
			forcedWynncraftItemLayerPipeline = null;
			forcedWynncraftItemLayerSignal = false;
		}
	}

	public static boolean shouldForceCurrentItemLayerWynnSignal(RenderPipeline pipeline) {
		return forcedWynncraftItemLayerSignalDepth > 0
			&& forcedWynncraftItemLayerSignal
			&& forcedWynncraftItemLayerPipeline == pipeline;
	}

	public static boolean isWynncraftVfxCandidatePipeline(RenderPipeline pipeline) {
		return pipeline == RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL;
	}

	// Queue of deferred mesh draws (signal-containing batches held until beginTranslucents).
	public record DeferredDraw(RenderType renderType, MeshData meshData) {}
	public static final List<DeferredDraw> deferredDraws = new ArrayList<>();

	// Flush all deferred draws (called at beginTranslucents).
	public static void flushDeferredDraws() {
		boolean previous = drawingDeferredWynncraftVfx;
		drawingDeferredWynncraftVfx = true;
		try {
			for (DeferredDraw draw : deferredDraws) {
				draw.renderType.draw(draw.meshData);
			}
		} finally {
			drawingDeferredWynncraftVfx = previous;
			deferredDraws.clear();
		}
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
		capturePhotonTranslucentVfxPipelines = false;
		captureSource = null;
		buildersWithWynnSignal.clear();
		flushingBuilder = null;
		drawingDeferredWynncraftVfx = false;
		forcedWynncraftItemLayerPipeline = null;
		forcedWynncraftItemLayerSignal = false;
		forcedWynncraftItemLayerSignalDepth = 0;
	}

	// ====================================================================================
	// WYNNCRAFT SKYBOX CPU-SIDE DETECTION
	// ====================================================================================
	// Detects skybox variant ID from item display entity textures on the CPU.
	// Works on ALL platforms including Mac (no GL 4.2 required).
	// Set from ItemStackStateLayerMixin when a skybox texture signal is found.
	// Read from IrisRenderingPipeline.finalizeLevelRendering().

	// The skybox ID detected this frame (1-7), or 0 if none detected.
	// Reset to 0 at frame start. When multiple skybox entities are detected,
	// the one with delta_y closest to -601.6 wins (the "correct" beacon height).
	private static final float SKYBOX_TARGET_DELTA_Y = -601.6f;
	public static volatile int cpuDetectedSkyboxId = 0;
	public static volatile float cpuDetectedSkyboxBestDeltaY = Float.MAX_VALUE;

	public static void noteSkyboxDetection(int id) {
		noteSkyboxDetection(id, Float.MAX_VALUE);
	}

	// Fallback: any skybox entity, used when no entity is in the preferred delta_y range.
	// Prevents detection from dropping to 0 when entities are rendered but outside range.
	public static volatile int cpuDetectedSkyboxFallbackId = 0;

	public static void noteSkyboxDetection(int id, float deltaY) {
		if (id >= 1 && id <= 7) {
			// Always track as fallback (any skybox entity is better than none)
			cpuDetectedSkyboxFallbackId = id;

			// Preferred: entities in the expected primary skybox delta_y range
			if (deltaY >= -700f && deltaY <= -550f) {
				float deviation = Math.abs(deltaY - SKYBOX_TARGET_DELTA_Y);
				float currentDeviation = Math.abs(cpuDetectedSkyboxBestDeltaY - SKYBOX_TARGET_DELTA_Y);
				if (cpuDetectedSkyboxId == 0 || deviation < currentDeviation) {
					cpuDetectedSkyboxId = id;
					cpuDetectedSkyboxBestDeltaY = deltaY;
				}
			}
		}
	}

	/** Returns the preferred (delta_y range) detection, or 0 if none in range. */
	public static int consumeSkyboxPreferred() {
		int id = cpuDetectedSkyboxId;
		cpuDetectedSkyboxId = 0;
		cpuDetectedSkyboxBestDeltaY = Float.MAX_VALUE;
		return id;
	}

	/** Returns any skybox detection (fallback), or 0 if no skybox entities rendered. */
	public static int consumeSkyboxFallback() {
		int id = cpuDetectedSkyboxFallbackId;
		cpuDetectedSkyboxFallbackId = 0;
		return id;
	}

	// ====================================================================================
	// WYNNCRAFT TRANSITION CPU-SIDE DETECTION
	// ====================================================================================
	// Detects transition screen effects from text display entities on the CPU.
	// The transition signal is a Unicode character U+E000-U+E012 rendered with
	// font "minecraft:screen/transition". When detected, the text entity is
	// suppressed and a fullscreen post-process transition is rendered instead.

	/** Atomic transition detection payload — avoids split reads across separate fields. */
	public record TransitionDetection(int type, int opacity, int color) {
		public static final TransitionDetection NONE = new TransitionDetection(0, 0, 0);
	}

	private static volatile TransitionDetection cpuDetectedTransition = TransitionDetection.NONE;

	public static void noteTransitionDetection(int type, int opacity, int color) {
		if (type >= 1 && type <= 19) {
			cpuDetectedTransition = new TransitionDetection(type, opacity, color);
		}
	}

	/** Returns and resets the detected transition as a single atomic snapshot. */
	public static TransitionDetection consumeTransitionDetection() {
		TransitionDetection d = cpuDetectedTransition;
		cpuDetectedTransition = TransitionDetection.NONE;
		return d;
	}
}
