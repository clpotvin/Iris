package net.irisshaders.iris.pathways;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.mixinterface.CustomPass;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.util.OptionalInt;

import static net.irisshaders.iris.pipeline.CompositeRenderer.COMPOSITE_PIPELINE;

/**
 * Post-process depth fog for biomes with custom close fog (e.g. Wynncraft Mist Woods).
 * <p>
 * Reads the depth buffer, reconstructs linear distance, and applies linear fog
 * using the biome's EnvironmentAttributes fog start/end values. Only active when
 * the player is in a biome with close fog (mushroom_fields on Wynncraft).
 * <p>
 * Follows the same swap-texture + framebuffer + copy-back pattern as
 * {@link WynncraftSkyboxRenderer}.
 */
public class WynncraftBiomeFogRenderer {
	private static final CustomPass EMPTY_PASS = () -> {};

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

	private static final String FRAGMENT_SOURCE = """
		#version 330 core

		uniform sampler2D DepthTex;
		uniform sampler2D ColorTex;
		uniform mat4 InvProjMat;
		uniform float FogStart;
		uniform float FogEnd;
		uniform vec3 FogColor;
		uniform float Opacity;

		in vec2 uv;
		out vec4 fragColor;

		void main() {
		    float depth = texture(DepthTex, uv).r;
		    vec4 existing = texture(ColorTex, uv);

		    // Sky pixels (depth >= 1.0) get full fog color — in vanilla, the sky
		    // is completely hidden by the biome fog at this distance.
		    if (depth > 0.999999) {
		        fragColor = vec4(mix(existing.rgb, FogColor, Opacity), existing.a);
		        return;
		    }

		    // Reconstruct linear view-space distance from depth
		    vec4 viewPos = InvProjMat * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
		    float linearDist = (abs(viewPos.w) > 1e-6) ? -viewPos.z / viewPos.w : 0.0;
		    linearDist = max(linearDist, 0.0);

		    // Linear fog: same math as vanilla custom_fog.glsl total_fog_value
		    float fogFactor = clamp((linearDist - FogStart) / (FogEnd - FogStart), 0.0, 1.0);

		    // Blend with opacity for smooth biome transitions
		    fogFactor *= Opacity;

		    fragColor = vec4(mix(existing.rgb, FogColor, fogFactor), existing.a);
		}
		""";

	private int width;
	private int height;
	private Program program;
	private GlFramebuffer framebuffer;
	private int swapTexture;

	// Mutable state set before each render
	private int depthTexId;
	private int colorTexId;
	private float fogStart;
	private float fogEnd;
	private float opacity;

	public WynncraftBiomeFogRenderer(int width, int height) {
		rebuild(width, height);
	}

	public void rebuild(int width, int height) {
		if (program != null) {
			program.destroy();
			program = null;
			framebuffer.destroy();
			framebuffer = null;
			GlStateManager._deleteTexture(swapTexture);
			swapTexture = 0;
		}

		this.width = width;
		this.height = height;

		ProgramBuilder builder = ProgramBuilder.begin("wynncraftBiomeFog",
			VERTEX_SOURCE, null, FRAGMENT_SOURCE, ImmutableSet.of());

		builder.uniformMatrix(UniformUpdateFrequency.ONCE, "projection",
			() -> new Matrix4f(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, -1, -1, 0, 1));

		builder.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "InvProjMat", () -> {
			Matrix4fc p = CapturedRenderingState.INSTANCE.getGbufferProjection();
			return p != null ? new Matrix4f(p).invert() : new Matrix4f();
		});

		builder.uniform1f(UniformUpdateFrequency.PER_FRAME, "FogStart", () -> fogStart);
		builder.uniform1f(UniformUpdateFrequency.PER_FRAME, "FogEnd", () -> fogEnd);
		builder.uniform3f(UniformUpdateFrequency.PER_FRAME, "FogColor", () -> {
			Vector3d c = CapturedRenderingState.INSTANCE.getFogColor();
			return new org.joml.Vector3f((float) c.x, (float) c.y, (float) c.z);
		});
		builder.uniform1f(UniformUpdateFrequency.PER_FRAME, "Opacity", () -> opacity);

		builder.addDynamicSampler(() -> depthTexId, GlSampler.NEAREST, "DepthTex");
		builder.addDynamicSampler(() -> colorTexId, GlSampler.NEAREST, "ColorTex");

		swapTexture = GlStateManager._genTexture();
		IrisRenderSystem.texImage2D(swapTexture, GL30C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA8,
			width, height, 0, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE, null);

		this.framebuffer = new GlFramebuffer();
		framebuffer.addColorAttachment(0, swapTexture);
		this.program = builder.build();
	}

	public void render(int depthTexId, GlTexture colorTex, float fogStart, float fogEnd, float opacity) {
		if (opacity <= 0.001f) return;

		this.depthTexId = depthTexId;
		this.colorTexId = colorTex.iris$getGlId();
		this.fogStart = fogStart;
		this.fogEnd = fogEnd;
		this.opacity = opacity;

		GpuBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).getBuffer(6);
		VertexFormat.IndexType type = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> "Wynncraft Biome Fog",
			Minecraft.getInstance().getMainRenderTarget().getColorTextureView(),
			OptionalInt.empty())) {

			pass.setPipeline(COMPOSITE_PIPELINE);
			pass.iris$setCustomPass(EMPTY_PASS);

			program.use();
			framebuffer.bind();

			pass.setIndexBuffer(indices, type);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad());

			pass.drawIndexed(0, 0, 6, 1);
		}
		Program.unbind();

		framebuffer.bindAsReadBuffer();
		IrisRenderSystem.copyTexSubImage2D(colorTex.glId(), GL11C.GL_TEXTURE_2D,
			0, 0, 0, 0, 0, width, height);
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
		if (swapTexture != 0) {
			GlStateManager._deleteTexture(swapTexture);
			swapTexture = 0;
		}
	}
}
