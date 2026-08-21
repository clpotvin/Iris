package net.irisshaders.iris.pathways;

import java.lang.reflect.Method;
import java.util.function.IntSupplier;

/**
 * Read-only access to Voxy's LOD depth textures for sky classification.
 *
 * <p>Under Iris, Voxy renders LOD terrain into its own framebuffers
 * ({@code thePipeline.fb} opaque, {@code thePipeline.fbTranslucent} opaque +
 * LOD water) and — as observed on every pack and Voxy build tested — does not
 * write the vanilla depth buffer. Any
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

	/**
	 * @param pipeline the active world pipeline; expected (but not required) to
	 *                 implement Voxy's {@code IGetIrisVoxyPipelineData} mixin
	 *                 interface, which is not on the compile classpath — hence
	 *                 {@code Object}.
	 * @return null if Voxy is absent or its pipeline-data accessor cannot be
	 *         resolved. Note the data itself is not touched here: a non-null
	 *         result whose data is unavailable at render time yields 0 from
	 *         {@link #currentDepthTexId()} instead.
	 */
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
			// Voxy-absent is handled above (ClassNotFoundException / isInstance);
			// reaching here means Voxy IS present but its API changed. Silent
			// degradation would resurface as "skybox paints over LOD terrain"
			// with nothing in the log, so mirror the sibling pass's warn.
			net.irisshaders.iris.Iris.logger.warn("VoxyLodDepth: setup failed, skybox LOD depth integration disabled", t);
			return null;
		}
	}

	/**
	 * Current GL id of Voxy's LOD depth texture, or 0 when Voxy's pipeline
	 * data, framebuffer, or depth texture is not currently present (Voxy idle
	 * or torn down — no per-frame "did Voxy draw" check exists, matching
	 * {@code DHCompat.getDepthTex()} semantics). Suppliers re-resolve Voxy's
	 * pipeline data per call, so Voxy-side pipeline rebuilds are picked up;
	 * ambience/profile swaps are covered because each new IrisRenderingPipeline
	 * constructs its own VoxyLodDepth.
	 */
	public int currentDepthTexId() {
		int id = translucentDepth.getAsInt();
		return id != 0 ? id : opaqueDepth.getAsInt();
	}
}
