package net.irisshaders.iris.vertices;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

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
	// True only while replaying deferred Wynncraft VFX batches. Shader override code
	// can use this to select a dedicated forward fallback shader for specific packs.
	public static boolean drawingDeferredWynncraftVfx;

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
		captureSource = null;
		trackedTranslucentBuilder = null;
		trackedBuilderHasWynnSignal = false;
		drawingDeferredWynncraftVfx = false;
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
	private static final long SKYBOX_ENTITY_FORCE_RENDER_MS = 8000L;
	public static volatile int cpuDetectedSkyboxId = 0;
	public static volatile float cpuDetectedSkyboxBestDeltaY = Float.MAX_VALUE;
	private static volatile int skyboxEntityForceRenderId = 0;
	private static volatile long skyboxEntityForceRenderTimeMs = 0L;

	public static void noteSkyboxDetection(int id) {
		noteSkyboxDetection(id, Float.MAX_VALUE);
	}

	public static void noteSkyboxDetection(int id, float deltaY, int entityId) {
		noteSkyboxDetection(id, deltaY);
		if (id >= 1 && id <= 7 && entityId > 0 && deltaY >= -700f && deltaY <= -550f) {
			skyboxEntityForceRenderId = entityId;
			skyboxEntityForceRenderTimeMs = System.currentTimeMillis();
		}
	}

	public static boolean shouldForceSkyboxEntityRender(Entity entity) {
		if (!(entity instanceof Display.ItemDisplay) || skyboxEntityForceRenderId <= 0) {
			return false;
		}
		if (entity.getId() != skyboxEntityForceRenderId) {
			return false;
		}
		long ageMs = System.currentTimeMillis() - skyboxEntityForceRenderTimeMs;
		return ageMs >= 0 && ageMs <= SKYBOX_ENTITY_FORCE_RENDER_MS;
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
