package net.irisshaders.iris.pathways;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.mixinterface.CustomPass;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

import static net.irisshaders.iris.pipeline.CompositeRenderer.COMPOSITE_PIPELINE;

/**
 * Clears Voxy's auxiliary translucent-only colortex targets at pixels where
 * vanilla opaque geometry (terrain or entities) is closer than Voxy's LOD
 * translucent (water) surface.
 *
 * <p>Background: Voxy (MCRcortex/voxy) renders LOD terrain during Sodium's
 * CUTOUT terrain pass, before entities. With {@code excludeLodsFromVanillaDepth}
 * set (Photon/Solas), Voxy keeps its depth in {@code vxDepthTex*} and writes
 * LOD translucent color into pack-declared colortex slots
 * ({@code translucentDrawBuffers} in voxy.json). At pixels that were "sky" at
 * Voxy time but later get covered by entities, the LOD water color persists in
 * those colortexes. Pack composites typically only check a crude near-depth
 * threshold (e.g. Solas {@code z0 > 0.56}), so the water ends up blended over
 * the entity.
 *
 * <p>Fix: after all vanilla opaque rendering completes (entities have written
 * their depth) and before the deferred composite reads the voxy aux buffers,
 * run a fullscreen pass that clears the "aux" translucent targets
 * ({@code translucentDrawBuffers - opaqueDrawBuffers}, excluding colortex 0)
 * at pixels where the vanilla opaque depth is closer in view space than the
 * reprojected Voxy translucent depth.
 *
 * <p>The pass is gated on {@code vxDepthTexTrans < vxDepthTexOpaque} so it
 * only fires at pixels where Voxy actually drew LOD water (Voxy's translucent
 * framebuffer starts as a blit of its opaque framebuffer's depth and is only
 * overwritten where water is drawn — so a strictly-closer trans depth is the
 * signal for "water present").
 *
 * <p>All Voxy access goes through reflection — the mod is a soft dependency.
 */
public class VoxyEntityDepthClearPass {
	private static final String VOXY_PATCH_HOLDER = "me.cortex.voxy.client.iris.IGetVoxyPatchData";
	private static final String VOXY_PIPELINE_HOLDER = "me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData";

	/**
	 * Custom pass that explicitly disables blending before our draw.
	 *
	 * <p>Iris's MixinGlCommandEncoder shortcut path for custom passes does not
	 * force blend state from the active RenderPipeline, so the global blend
	 * state last set by Sodium/Voxy (typically {@code GL_ONE,
	 * GL_ONE_MINUS_SRC_ALPHA} for Voxy translucents) leaks into our pass. With
	 * that blend, writing {@code (0,0,0,0)} evaluates to {@code dest*1 = dest},
	 * so our "clear" becomes a no-op and the bleed persists. Explicitly
	 * disabling blend here makes our writes replace the destination.
	 */
	private static final CustomPass NO_BLEND_PASS = new CustomPass() {
		@Override
		public void setupState() {
			GlStateManager._disableBlend();
		}
	};

	private static final String VERTEX_SOURCE = """
		#version 330 core
		in vec3 iris_Position;
		in vec2 iris_UV0;
		uniform mat4 projection;
		out vec2 uv;
		void main() {
		    gl_Position = projection * vec4(iris_Position, 1.0);
		    uv = iris_UV0;
		}
		""";

	/**
	 * The aux colortex indices (translucent-only, excluding the main scene color)
	 * that this pass clears. Built from the pack's voxy.json at construction.
	 */
	private final int[] auxTargets;
	/** Cached reflected accessor for the Voxy translucent depth texture id. */
	private final java.util.function.IntSupplier vxDepthTransIdSupplier;
	/** Cached reflected accessor for the Voxy opaque depth texture id. */
	private final java.util.function.IntSupplier vxDepthOpaqueIdSupplier;

	private final RenderTargets renderTargets;
	private final ImmutableSet<Integer> flippedBeforeDeferred;

	private Program program;
	private GlFramebuffer framebuffer;

	/**
	 * Build the pass. Returns {@code null} from the factory (see
	 * {@link #tryCreate}) if Voxy is not loaded or the active pack has no
	 * aux-only translucent targets.
	 */
	private VoxyEntityDepthClearPass(int[] auxTargets,
	                                 java.util.function.IntSupplier vxDepthTransIdSupplier,
	                                 java.util.function.IntSupplier vxDepthOpaqueIdSupplier,
	                                 RenderTargets renderTargets,
	                                 ImmutableSet<Integer> flippedBeforeDeferred) {
		this.auxTargets = auxTargets;
		this.vxDepthTransIdSupplier = vxDepthTransIdSupplier;
		this.vxDepthOpaqueIdSupplier = vxDepthOpaqueIdSupplier;
		this.renderTargets = renderTargets;
		this.flippedBeforeDeferred = flippedBeforeDeferred;
		buildProgramAndFramebuffer();
	}

	/**
	 * Factory. Returns null if the pass is not applicable (Voxy absent, no
	 * voxy.json, no aux targets, or a reflection/setup error).
	 */
	public static VoxyEntityDepthClearPass tryCreate(Object pipeline,
	                                                 RenderTargets renderTargets,
	                                                 ImmutableSet<Integer> flippedBeforeDeferred) {
		try {
			ClassLoader cl = pipeline.getClass().getClassLoader();
			Class<?> patchHolderCls;
			Class<?> pipeHolderCls;
			try {
				patchHolderCls = Class.forName(VOXY_PATCH_HOLDER, false, cl);
				pipeHolderCls = Class.forName(VOXY_PIPELINE_HOLDER, false, cl);
			} catch (ClassNotFoundException e) {
				return null; // Voxy not present
			}
			if (!patchHolderCls.isInstance(pipeline) || !pipeHolderCls.isInstance(pipeline)) {
				return null;
			}
			Object patchData = patchHolderCls.getMethod("voxy$getPatchData").invoke(pipeline);
			if (patchData == null) {
				return null; // Pack has no voxy.json
			}
			int[] opaque = (int[]) patchData.getClass().getMethod("getOpqaueTargets").invoke(patchData);
			int[] trans = (int[]) patchData.getClass().getMethod("getTranslucentTargets").invoke(patchData);
			if (opaque == null || trans == null) return null;
			int[] aux = auxOnly(trans, opaque);
			if (aux.length == 0) return null;

			// Resolve voxy depth texture id suppliers once.
			// Path: pipeline.voxy$getPipelineData().thePipeline.{fb,fbTranslucent}.getDepthTex().id
			Method getPipeData = pipeHolderCls.getMethod("voxy$getPipelineData");
			java.util.function.IntSupplier transDepthSupplier =
				makeDepthSupplier(pipeline, getPipeData, "fbTranslucent");
			java.util.function.IntSupplier opaqueDepthSupplier =
				makeDepthSupplier(pipeline, getPipeData, "fb");

			return new VoxyEntityDepthClearPass(aux, transDepthSupplier, opaqueDepthSupplier,
				renderTargets, flippedBeforeDeferred);
		} catch (Throwable t) {
			Iris.logger.warn("VoxyEntityDepthClearPass: setup failed, entity/LOD-water occlusion fix disabled", t);
			return null;
		}
	}

	/** Build a lazy supplier for a Voxy depth texture id via reflection. */
	private static java.util.function.IntSupplier makeDepthSupplier(Object pipeline,
	                                                                Method getPipeData,
	                                                                String fbFieldName) {
		return () -> {
			try {
				Object pipeData = getPipeData.invoke(pipeline);
				if (pipeData == null) return 0;
				Object thePipeline = pipeData.getClass().getField("thePipeline").get(pipeData);
				if (thePipeline == null) return 0;
				Object fb = thePipeline.getClass().getField(fbFieldName).get(thePipeline);
				if (fb == null) return 0;
				Object depthTex = fb.getClass().getMethod("getDepthTex").invoke(fb);
				if (depthTex == null) return 0;
				return depthTex.getClass().getField("id").getInt(depthTex);
			} catch (Throwable t) {
				return 0;
			}
		};
	}

	/**
	 * aux = (translucent \ opaque) excluding colortex 0 (main scene color,
	 * which entity rendering already overwrites at entity pixels).
	 */
	private static int[] auxOnly(int[] translucent, int[] opaque) {
		Set<Integer> opaqueSet = Arrays.stream(opaque).boxed().collect(Collectors.toCollection(HashSet::new));
		return Arrays.stream(translucent)
			.filter(i -> i != 0 && !opaqueSet.contains(i))
			.distinct()
			.toArray();
	}

	private void buildProgramAndFramebuffer() {
		String fragmentSource = buildFragmentSource(auxTargets.length);

		ProgramBuilder builder = ProgramBuilder.begin("iris_voxy_entity_depth_clear",
			VERTEX_SOURCE, null, fragmentSource, ImmutableSet.of());

		builder.uniformMatrix(UniformUpdateFrequency.ONCE, "projection",
			() -> new Matrix4f(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, -1, -1, 0, 1));
		builder.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "InvVanillaProj", () -> {
			Matrix4fc p = CapturedRenderingState.INSTANCE.getGbufferProjection();
			return p != null ? new Matrix4f(p).invert() : new Matrix4f();
		});
		builder.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "InvVoxyProj", () -> {
			Matrix4f m = getVoxyProjInv();
			return m != null ? m : new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection()).invert();
		});

		// Vanilla opaque-only depth: depthtex1 (no-translucents copy). This
		// includes entity depth because it is snapshotted at beginTranslucents
		// right before this pass runs.
		builder.addDynamicSampler(() -> renderTargets.getDepthTextureNoTranslucents().iris$getGlId(),
			GlSampler.NEAREST, "VanillaDepth");
		builder.addDynamicSampler(vxDepthTransIdSupplier::getAsInt, GlSampler.NEAREST, "VoxyTransDepth");
		builder.addDynamicSampler(vxDepthOpaqueIdSupplier::getAsInt, GlSampler.NEAREST, "VoxyOpaqueDepth");

		this.program = builder.build();

		// Attach the aux colortexes (current-flip side) as color buffers in the
		// same order Voxy wrote them. We write the same cleared output to all.
		this.framebuffer = new GlFramebuffer();
		for (int slot = 0; slot < auxTargets.length; slot++) {
			int idx = auxTargets[slot];
			net.irisshaders.iris.targets.RenderTarget tgt = renderTargets.getOrCreate(idx);
			int texId = flippedBeforeDeferred.contains(idx) ? tgt.getAltTexture() : tgt.getMainTexture();
			framebuffer.addColorAttachment(slot, texId);
		}
		int[] drawBuffers = new int[auxTargets.length];
		for (int i = 0; i < drawBuffers.length; i++) drawBuffers[i] = i;
		framebuffer.drawBuffers(drawBuffers);
	}

	private static String buildFragmentSource(int numOutputs) {
		StringBuilder sb = new StringBuilder();
		sb.append("#version 330 core\n");
		sb.append("in vec2 uv;\n");
		sb.append("uniform sampler2D VanillaDepth;\n");
		sb.append("uniform sampler2D VoxyTransDepth;\n");
		sb.append("uniform sampler2D VoxyOpaqueDepth;\n");
		sb.append("uniform mat4 InvVanillaProj;\n");
		sb.append("uniform mat4 InvVoxyProj;\n");
		for (int i = 0; i < numOutputs; i++) {
			sb.append("layout(location = ").append(i).append(") out vec4 outColor").append(i).append(";\n");
		}
		sb.append("""
			float linearViewZ(float ndcDepth, mat4 invProj) {
			    // ndcDepth in [0,1] → clip space z in [-1,1]
			    vec4 clip = vec4(0.0, 0.0, ndcDepth * 2.0 - 1.0, 1.0);
			    vec4 view = invProj * clip;
			    return view.z / view.w; // negative forward
			}
			void main() {
			    // Voxy water is the only thing that matters for this pass.
			    // Voxy starts its translucent framebuffer as a BLIT of the opaque
			    // framebuffer's depth, then overwrites where LOD water is drawn.
			    // So water-present at this pixel iff trans depth < opaque depth.
			    float voxyTransZ = texture(VoxyTransDepth, uv).r;
			    float voxyOpaqueZ = texture(VoxyOpaqueDepth, uv).r;
			    if (voxyTransZ >= voxyOpaqueZ - 0.0001) discard;

			    // Pure sky: voxy LOD water legitimately shows; nothing to clear.
			    float vanillaZ = texture(VanillaDepth, uv).r;
			    if (vanillaZ >= 1.0) discard;

			    float vzLin = linearViewZ(voxyTransZ, InvVoxyProj);
			    float mzLin = linearViewZ(vanillaZ, InvVanillaProj);

			    // View-space Z is negative forward: closer = less negative.
			    // Only clear when vanilla is strictly closer than voxy water.
			    if (mzLin <= vzLin + 0.05) discard;
			""");
		for (int i = 0; i < numOutputs; i++) {
			sb.append("    outColor").append(i).append(" = vec4(0.0);\n");
		}
		sb.append("}\n");
		return sb.toString();
	}

	/** Optional: returns Voxy's projection-inverse matrix, or null if unavailable. */
	private static Matrix4f getVoxyProjInv() {
		try {
			Class<?> cls = Class.forName("me.cortex.voxy.client.iris.VoxyUniforms", false,
				VoxyEntityDepthClearPass.class.getClassLoader());
			Method m = cls.getMethod("getProjection");
			Object ref = m.invoke(null); // AtomicReference<Matrix4f> per voxy
			if (ref == null) return null;
			Object val;
			try {
				val = ref.getClass().getMethod("get").invoke(ref);
			} catch (NoSuchMethodException nsme) {
				val = ref;
			}
			if (val instanceof Matrix4fc p) return new Matrix4f(p).invert();
		} catch (Throwable ignored) {
		}
		return null;
	}

	/**
	 * Invoke once per frame, at {@code beginTranslucents()} time, before the
	 * deferred composite runs. No-ops if Voxy's translucent depth texture is
	 * not yet allocated this frame.
	 */
	public void render() {
		int transId = vxDepthTransIdSupplier.getAsInt();
		int opaqueId = vxDepthOpaqueIdSupplier.getAsInt();
		if (transId == 0 || opaqueId == 0) return; // voxy pipeline not ready this frame

		GpuBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).getBuffer(6);
		VertexFormat.IndexType type = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> "Iris: Voxy entity/LOD-water occlusion clear",
			Minecraft.getInstance().getMainRenderTarget().getColorTextureView(),
			OptionalInt.empty())) {
			pass.setPipeline(COMPOSITE_PIPELINE);
			pass.iris$setCustomPass(NO_BLEND_PASS);

			program.use();
			framebuffer.bind();

			pass.setIndexBuffer(indices, type);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad());
			pass.drawIndexed(0, 0, 6, 1);
		}
		Program.unbind();
	}

	public void destroy() {
		if (program != null) {
			program.destroy();
			program = null;
		}
		if (framebuffer != null) {
			framebuffer.destroy();
			framebuffer = null;
		}
	}
}
