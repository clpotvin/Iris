package net.irisshaders.iris.pathways;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.mixinterface.CustomPass;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.util.OptionalInt;

import static net.irisshaders.iris.pipeline.CompositeRenderer.COMPOSITE_PIPELINE;

/**
 * Renders Wynncraft transition screen effects as a fullscreen overlay.
 * Used for both debug testing (F7/F8 keys) and could be used for
 * direct transition rendering independent of text shaders.
 */
public class WynncraftTransitionRenderer {
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

		uniform sampler2D ColorTex;
		uniform float GameTime;
		uniform vec2 ScreenSize;
		uniform int TransType;
		uniform float TransProgress;
		uniform vec3 TransColor;

		in vec2 uv;
		out vec4 fragColor;

		const float PI = 3.14159265359;
		const float TAU = PI * 2.0;

		int irisW_hash(int x) { x += (x << 10); x ^= (x >> 6); x += (x << 3); x ^= (x >> 11); x += (x << 15); return x; }
		float irisW_noise2(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
		float irisW_noiseT(vec2 uv, float t1, float t2) { return fract(sin(uv.x * t1 + uv.y * t2) * 56789.0); }
		float irisW_smoothNoise(vec2 p) { vec2 i = floor(p); vec2 f = fract(p); float a = irisW_noise2(i); float b = irisW_noise2(i + vec2(1.0, 0.0)); float c = irisW_noise2(i + vec2(0.0, 1.0)); float d = irisW_noise2(i + vec2(1.0, 1.0)); vec2 u = f * f * (3.0 - 2.0 * f); return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y; }
		float irisW_fbm(vec2 p) { float v = 0.0; float a = 0.5; float freq = 1.0; for (int i = 0; i < 5; i++) { v += a * irisW_smoothNoise(p * freq); freq *= 2.0; a *= 0.5; } return v; }

		void main() {
		    if (TransType <= 0) { discard; return; }

		    vec2 ss = ScreenSize;
		    vec2 cuv = gl_FragCoord.xy / ss - 0.5;
		    float ar = ss.y / ss.x;
		    vec2 UV = cuv / vec2(ar, 1.0);
		    float prog = cos(TransProgress * PI / 2.0);
		    float gt = GameTime;
		    vec3 rgb = TransColor;
		    float rawA = TransProgress;
		    vec4 result = vec4(0.0);

		    switch (TransType) {
		        case 1: { result = vec4(rgb, (length((gl_FragCoord.xy / ss - 0.5) / vec2(ar, 1.0)) + 0.1 - prog * 1.5) * (1.0 - prog) * 100.0); break; }
		        case 2: { result = vec4(rgb, clamp(length(cuv * vec2(1.0, 2.0 / max(1.0 - rawA, 0.001))) - 1.0, 0.0, 1.0)); break; }
		        case 3: { result = vec4(0.0); float a3 = (atan(cuv.y, cuv.x) / PI / 2.0 + 0.5) * 30.0; float t3 = gt * 2000.0 + float(irisW_hash(int(a3)) % 100) * 64.2343; float s3 = (abs(fract(a3) - 0.5) * 20.0 / 30.0 - 0.2) * length(cuv) + 0.07 + (1.0 - rawA) * 0.05 + abs(fract(t3) - 0.5) * 0.25; if (s3 < 0.0) result = vec4(rgb, clamp(-s3 * 200.0, 0.0, 1.0)); break; }
		        case 4: { vec2 g4 = vec2(ivec2(gl_FragCoord.xy / 64.0) * 64); vec2 ig4 = gl_FragCoord.xy - g4 - 32.0; float sz4 = (g4.y / ss.y - rawA * 2.0 + 1.0) * 64.0; result = (abs(ig4.x) + abs(ig4.y) > sz4) ? vec4(rgb, 1.0) : vec4(0.0); break; }
		        case 5: { ivec2 g5 = ivec2(gl_FragCoord.xy / 64.0) * 64; result = abs(irisW_hash(g5.x ^ irisW_hash(g5.y)) % 256) < int(rawA * (length(vec2(g5) / ss - 0.5) * 2.0 + 1.0) * 256.0) ? vec4(rgb, 1.0) : vec4(0.0); break; }
		        case 6: { result = vec4(0.0); float r6 = length(UV); if (r6 >= 0.07 && r6 < 0.1 && rawA >= 0.99) { float a6 = fract(-atan(UV.y, UV.x) / TAU - gt * 1000.0); result = vec4(rgb, a6); } break; }
		        case 7: { vec2 c7 = cuv * (ss.x / ss.y); float d7 = length(c7) / length(vec2(1.0, ss.y / ss.x)); result = vec4(rgb, smoothstep(0.0, 1.0, d7 * 0.4)) * (1.0 - prog); break; }
		        case 8: { float s8 = 2.0 - abs((cuv.y - 0.5) / max(1.0 - prog, 0.001) - 1.0); result = vec4(rgb, step(0.0, s8)); break; }
		        case 9: { float s9 = 2.0 - abs((cuv.y + 0.5) / max(1.0 - prog, 0.001) - 1.0); result = vec4(rgb, step(0.0, s9)); break; }
		        case 10: { float s10 = 2.0 - abs((cuv.x - 0.5) / max(1.0 - prog, 0.001) - 1.0); result = vec4(rgb, step(0.0, s10)); break; }
		        case 11: { float s11 = 2.0 - abs((cuv.x + 0.5) / max(1.0 - prog, 0.001) - 1.0); result = vec4(rgb, step(0.0, s11)); break; }
		        case 12: { result = vec4(rgb, 1.0 - prog); break; }
		        case 13: { result = vec4(0.0); float top = 2.0 - abs((cuv.y - 0.5) / max(1.0 - prog, 0.001)); float bot = 2.0 - abs((cuv.y + 0.5) / max(1.0 - prog, 0.001)); if (UV.y > 0.4) result = vec4(rgb, step(0.0, top)); if (UV.y < -0.4) result = vec4(rgb, step(0.0, bot)); break; }
		        case 14: { float cp14 = atan(UV.y, UV.x) + prog * 2.0; result = vec4(rgb, step(sign(prog - mod(cp14, PI / 4.0)), 0.5)); break; }
		        case 15: { float os15 = PI / 2.0; float a15 = atan(UV.y, UV.x) + os15; float n15 = (a15 + PI) / TAU; n15 = n15 - floor(n15); result = vec4(rgb, step(n15, 1.0 - prog)); break; }
		        case 16: { float t16 = gt * 2000.0; result = vec4(rgb, irisW_noiseT(UV, t16 * 0.654321, t16 * (t16 * 0.654321 * 0.123456)) * (1.0 - prog)); break; }
		        case 17: { vec2 c17 = cuv * (ss.x / ss.y); float d17 = length(c17) / length(vec2(1.0, ss.y / ss.x)); float v17 = smoothstep(0.0, 1.0, d17 * 0.5) * irisW_fbm(c17 * 1.5 + vec2(gt * 4000.0 * 0.1, 0.0)); result = vec4(rgb, v17) * (1.0 - prog); break; }
		        case 18: { vec2 c18 = cuv * (ss.x / ss.y); float d18 = length(c18) / length(vec2(1.0, ss.y / ss.x)); float p18 = (ss.x / ss.y) + 0.1 * sin(gt * 3000.0); result = vec4(rgb, smoothstep(0.1, 1.0, d18 * 0.25 * p18)) * (1.0 - prog); break; }
		        case 19: { vec2 uv19 = (gl_FragCoord.xy / ss) * 2.0 - 1.0; uv19.x *= ss.x / ss.y; float p19 = rawA; float t19 = p19 * 8.5; float r19 = length(uv19); float th19 = atan(uv19.y, uv19.x); th19 += mix(0.0, 4.0, p19) * sin(r19 * 8.0 - t19 * 3.0) * (1.0 - p19); float mr19 = length(vec2(ss.x / ss.y, 1.0)); float a19 = smoothstep((1.0 - p19) * mr19 - 0.25, (1.0 - p19) * mr19, r19); float sc19 = sin(th19 * 6.0 + t19) * 0.5 + 0.5; vec3 pc19 = mix(vec3(0.741, 0.282, 0.910), vec3(0.545, 0.098, 0.749), sc19); result = mix(vec4(pc19, a19), vec4(0.667, 0.153, 0.812, 1.0), smoothstep(0.8, 1.0, p19)); break; }
		    }

		    // Read existing color, blend transition on top
		    vec4 existing = texture(ColorTex, uv);
		    fragColor = mix(existing, vec4(result.rgb, 1.0), result.a);
		}
		""";

	private Program program;
	private int swapTexture;
	private net.irisshaders.iris.gl.framebuffer.GlFramebuffer framebuffer;
	private int width, height;

	// Mutable state set before render()
	private int colorTexId;
	private float gameTime;
	private int transType;
	private float transProgress;
	private float transColorR, transColorG, transColorB;

	public WynncraftTransitionRenderer(int width, int height) {
		rebuild(width, height);
	}

	public boolean needsRebuild(int width, int height) {
		return this.width != width || this.height != height;
	}

	public void rebuild(int width, int height) {
		if (program != null) destroy();
		this.width = width;
		this.height = height;

		ProgramBuilder builder = ProgramBuilder.begin("wynncraftTransition",
			VERTEX_SOURCE, null, FRAGMENT_SOURCE, ImmutableSet.of());

		builder.uniformMatrix(UniformUpdateFrequency.ONCE, "projection",
			() -> new org.joml.Matrix4f(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, -1, -1, 0, 1));

		builder.uniform1f(UniformUpdateFrequency.PER_FRAME, "GameTime", () -> gameTime);
		builder.uniform2f(UniformUpdateFrequency.PER_FRAME, "ScreenSize",
			() -> new org.joml.Vector2f(width, height));
		builder.uniform1i(UniformUpdateFrequency.PER_FRAME, "TransType", () -> transType);
		builder.uniform1f(UniformUpdateFrequency.PER_FRAME, "TransProgress", () -> transProgress);
		builder.uniform3f(UniformUpdateFrequency.PER_FRAME, "TransColor",
			() -> new org.joml.Vector3f(transColorR, transColorG, transColorB));

		builder.addDynamicSampler(() -> colorTexId, GlSampler.NEAREST, "ColorTex");

		swapTexture = GlStateManager._genTexture();
		IrisRenderSystem.texImage2D(swapTexture, GL30C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA8,
			width, height, 0, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE, null);

		this.framebuffer = new net.irisshaders.iris.gl.framebuffer.GlFramebuffer();
		framebuffer.addColorAttachment(0, swapTexture);
		this.program = builder.build();
	}

	public void render(com.mojang.blaze3d.opengl.GlTexture colorTex, float gameTime, int type, float progress) {
		render(colorTex, gameTime, type, progress, 0x000000);
	}

	public void render(com.mojang.blaze3d.opengl.GlTexture colorTex, float gameTime, int type, float progress, int rgbColor) {
		if (type <= 0) return;

		this.colorTexId = colorTex.iris$getGlId();
		this.gameTime = gameTime;
		this.transType = type;
		this.transProgress = progress;
		this.transColorR = ((rgbColor >> 16) & 0xFF) / 255.0f;
		this.transColorG = ((rgbColor >> 8) & 0xFF) / 255.0f;
		this.transColorB = (rgbColor & 0xFF) / 255.0f;

		GpuBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).getBuffer(6);
		VertexFormat.IndexType indexType = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> "Wynncraft Transition",
			Minecraft.getInstance().getMainRenderTarget().getColorTextureView(),
			OptionalInt.empty())) {

			pass.setPipeline(COMPOSITE_PIPELINE);
			pass.iris$setCustomPass(EMPTY_PASS);

			program.use();
			framebuffer.bind();

			pass.setIndexBuffer(indices, indexType);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad());
			pass.drawIndexed(0, 0, 6, 1);
		}
		Program.unbind();

		framebuffer.bindAsReadBuffer();
		IrisRenderSystem.copyTexSubImage2D(colorTex.glId(), GL11C.GL_TEXTURE_2D,
			0, 0, 0, 0, 0, width, height);
	}

	public void destroy() {
		if (program != null) { program.destroy(); program = null; }
		if (framebuffer != null) { framebuffer.destroy(); framebuffer = null; }
		if (swapTexture != 0) { GlStateManager._deleteTexture(swapTexture); swapTexture = 0; }
	}
}
