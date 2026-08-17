package net.irisshaders.iris.pathways;

import java.lang.reflect.Method;
import java.util.function.IntSupplier;

/**
 * Read-only access to Voxy's LOD depth textures for sky classification.
 *
 * <p>Under Iris, Voxy renders LOD terrain into its own framebuffers
 * ({@code thePipeline.fb} opaque, {@code thePipeline.fbTranslucent} opaque +
 * LOD water) and does not write the vanilla depth buffer — on every pack. Any
 * screen-space pass that classifies "sky" purely from the vanilla depth buffer
 * (e.g. the Wynncraft skybox paint) therefore treats LOD-only pixels as sky
 * and draws over the composited LOD terrain. This mirrors the DH depth
 * integration: sample Voxy's depth as a second opinion.
 *
 * <p>The translucent framebuffer's depth is preferred because Voxy seeds it
 * from the opaque depth and then draws LOD water into it, making it the
 * "depth including translucents" analog of {@code DHCompat.getDepthTex()}.
 *
 * <p>Unlike {@link VoxyEntityDepthClearPass}, this does NOT require the pack
 * to declare aux translucent targets — only that Voxy's Iris pipeline data is
 * reachable. All access is via reflection; Voxy is a soft dependency.
 */
public final class VoxyLodDepth {
	private static final String VOXY_PIPELINE_HOLDER = "me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData";

	private final IntSupplier translucentDepth;
	private final IntSupplier opaqueDepth;

	private VoxyLodDepth(IntSupplier translucentDepth, IntSupplier opaqueDepth) {
		this.translucentDepth = translucentDepth;
		this.opaqueDepth = opaqueDepth;
	}

	/** Returns null if Voxy is absent or its pipeline data is unreachable. */
	public static VoxyLodDepth tryCreate(Object pipeline) {
		try {
			ClassLoader cl = pipeline.getClass().getClassLoader();
			Class<?> pipeHolderCls;
			try {
				pipeHolderCls = Class.forName(VOXY_PIPELINE_HOLDER, false, cl);
			} catch (ClassNotFoundException e) {
				return null; // Voxy not present
			}
			if (!pipeHolderCls.isInstance(pipeline)) {
				return null;
			}
			Method getPipeData = pipeHolderCls.getMethod("voxy$getPipelineData");
			return new VoxyLodDepth(
				VoxyEntityDepthClearPass.makeDepthSupplier(pipeline, getPipeData, "fbTranslucent"),
				VoxyEntityDepthClearPass.makeDepthSupplier(pipeline, getPipeData, "fb"));
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Current GL id of Voxy's LOD depth texture, or 0 when Voxy has not
	 * rendered this frame (suppliers re-resolve per call, so ambience pipeline
	 * swaps and Voxy pipeline rebuilds are picked up automatically).
	 */
	public int currentDepthTexId() {
		int id = translucentDepth.getAsInt();
		return id != 0 ? id : opaqueDepth.getAsInt();
	}
}
