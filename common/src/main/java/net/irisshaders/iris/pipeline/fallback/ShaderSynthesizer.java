package net.irisshaders.iris.pipeline.fallback;

import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;

public class ShaderSynthesizer {
	public static String vsh(boolean hasChunkOffset, ShaderAttributeInputs inputs, FogMode fogMode,
							 boolean entityLighting, boolean isLeash) {
		StringBuilder shader = new StringBuilder();
		StringBuilder main = new StringBuilder();

		shader.append("#version 150 core\n");

		// Vertex Position
		shader.append("""
			layout(std140) uniform Projection {
			    mat4 ProjMat;
			};
			""");
		shader.append("""
			layout(std140) uniform DynamicTransforms {
			    mat4 ModelViewMat;
			    vec4 ColorModulator;
			    vec3 ModelOffset;
			    mat4 TextureMat;
			};
			""");
		shader.append("""
			layout(std140) uniform Globals {
			    vec2 ScreenSize;
			    float GlintAlpha;
			    float GameTime;
			    int MenuBlurRadius;
			};
			""");
		shader.append("in vec3 Position;\n");

		String position;

		if (hasChunkOffset) {
			position = "Position + ModelOffset";
		} else {
			position = "Position";
		}

		if (inputs.isNewLines()) {
			shader.append("in float LineWidth;\n");
			shader.append("const float VIEW_SHRINK = 1.0 - (1.0 / 256.0);\n" +
				"const mat4 VIEW_SCALE = mat4(\n" +
				"    VIEW_SHRINK, 0.0, 0.0, 0.0,\n" +
				"    0.0, VIEW_SHRINK, 0.0, 0.0,\n" +
				"    0.0, 0.0, VIEW_SHRINK, 0.0,\n" +
				"    0.0, 0.0, 0.0, 1.0\n" +
				");\n");

			main.append("vec4 linePosStart = ProjMat * VIEW_SCALE * ModelViewMat * vec4(" + position + ", 1.0);\n" +
				"    vec4 linePosEnd = ProjMat * VIEW_SCALE * ModelViewMat * vec4(" + position + " + Normal, 1.0);\n" +
				"\n" +
				"    vec3 ndc1 = linePosStart.xyz / linePosStart.w;\n" +
				"    vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;\n" +
				"\n" +
				"    vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * ScreenSize);\n" +
				"    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * LineWidth / ScreenSize;\n" +
				"\n" +
				"    if (lineOffset.x < 0.0) {\n" +
				"        lineOffset *= -1.0;\n" +
				"    }\n" +
				"\n" +
				"    if (gl_VertexID % 2 == 0) {\n" +
				"        gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);\n" +
				"    } else {\n" +
				"        gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);\n" +
				"    }\n");
		} else {
			main.append("    gl_Position = ProjMat * ModelViewMat * vec4(");
			main.append(position);
			main.append(", 1.0);\n");
		}

		// Vertex Color
		if (isLeash) {
			shader.append("flat ");
		}
		shader.append("out vec4 iris_vertexColor;\n");

		// Vertex Normal
		if (inputs.hasNormal() && inputs.hasColor()) {
			shader.append("in vec4 Color;\n");

			// TODO: Entity lighting without color? Only a theoretical possibility since all
			//       entity shaders use vertex color.
			if (entityLighting) {
				shader.append("""
					layout(std140) uniform Lighting {
					    vec3 Light0_Direction;
					    vec3 Light1_Direction;
					};""");

				// Copied from Mojang code.
				shader.append("vec4 minecraft_mix_light(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color) {\n" +
		//			"    lightDir0 = normalize(lightDir0);\n" +
		//			"    lightDir1 = normalize(lightDir1);\n" +
					"    float light0 = max(0.0, dot(lightDir0, normal));\n" +
					"    float light1 = max(0.0, dot(lightDir1, normal));\n" +
					"    float lightAccum = min(1.0, (light0 + light1) * 0.6 + 0.4);\n" +
					"    return vec4(color.rgb * lightAccum, color.a);\n" +
					"}\n");

				shader.append("in vec3 Normal;\n");

				// minecraft_mix_light just passes through the original alpha value, so it's safe here.
				main.append("    iris_vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color * ColorModulator);\n");
			} else if (inputs.isNewLines()) {
				shader.append("in vec3 Normal;\n");
				main.append("    iris_vertexColor = Color * ColorModulator;\n");
			} else {
				main.append("    iris_vertexColor = Color * ColorModulator;\n");
			}
		} else if (inputs.hasColor()) {
			shader.append("in vec4 Color;\n");

			main.append("    iris_vertexColor = Color * ColorModulator;\n");
		} else {
			main.append("    iris_vertexColor = ColorModulator;\n");
		}

		// Overlay Color
		if (inputs.hasOverlay()) {
			shader.append("uniform sampler2D Sampler1;\n");
			shader.append("in ivec2 UV1;\n");
			shader.append("out vec4 overlayColor;\n");

			main.append("    overlayColor = texelFetch(Sampler1, UV1, 0);\n");
		}

		// Vertex Texture
		if (inputs.hasTex()) {
			shader.append("in vec2 UV0;\n");
			shader.append("out vec2 texCoord;\n");

			main.append("    texCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;\n");
		}

		// Fog
		if (fogMode == FogMode.PER_VERTEX) {
			shader.append("out float vertexDistance;\n");

			main.append("    vertexDistance = length((ModelViewMat * vec4(");
			main.append(position);
			main.append(", 1.0)).xyz);\n");
		}

		// Vertex Light
		if (inputs.hasLight()) {
			shader.append("in ivec2 UV2;\n");
			shader.append("out vec2 lightCoord;\n");

			main.append("    lightCoord = clamp(UV2 / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));\n");
		}


		// Wynncraft transition: inject signal detection + fullscreen remap for text fallback shaders
		if (inputs.isText() && inputs.hasTex() && net.irisshaders.iris.gui.option.IrisVideoSettings.wynncraftDebugLogging) {
			System.out.println("[WynnIris] Synthesizing fallback text VS with transition support");
		}
		if (inputs.isText() && inputs.hasTex()) {
			shader.append("uniform sampler2D Sampler0;\n"); // VS needs sampler for signal detection
			shader.append("in vec2 mc_midTexCoord;\n");
			shader.append("flat out int irisW_transType;\n");
			shader.append("out vec4 irisW_transColor;\n");
			shader.append("flat out float irisW_transShadow;\n");
			main.append("""
			    vec4 irisW_texSample = textureLod(Sampler0, mc_midTexCoord.xy, 0.0) * 255.0;
			    irisW_transType = 0;
			    irisW_transColor = vec4(0.0);
			    irisW_transShadow = 0.0;
			    if (irisW_texSample.a > 252.5 && irisW_texSample.a < 253.5) {
			        irisW_transType = clamp(int(irisW_texSample.b + 0.5), 0, 19);
			        float irisW_shadowMax = max(Color.r, max(Color.g, Color.b));
			        irisW_transShadow = (irisW_shadowMax / 4.0 < 0.23) ? 1.0 : 0.0;
			        irisW_transColor = Color;
			        if (irisW_transType > 0) {
			            const vec2 irisW_c2[4] = vec2[4](vec2(0.0,0.0), vec2(0.0,1.0), vec2(1.0,1.0), vec2(1.0,0.0));
			            vec2 irisW_screen = irisW_c2[gl_VertexID % 4];
			            gl_Position = vec4((irisW_screen * 2.0 - 1.0) * vec2(1.0, -1.0), -1.0, 1.0);
			        }
			    }
			""");
		}

		// void main
		shader.append("void main() {\n");
		shader.append(main);
		shader.append("}\n");

		return shader.toString();
	}

	public static String fsh(ShaderAttributeInputs inputs, FogMode fogMode, AlphaTest alphaTest, boolean intensityTex, boolean isLeash) {
		StringBuilder shader = new StringBuilder();
		StringBuilder main = new StringBuilder();

		shader.append("#version 150 core\n");

		shader.append("out vec4 fragColor;\n");
		shader.append("uniform float AlphaTestValue;\n");
		if (isLeash) {
			shader.append("flat ");
		}
		shader.append("in vec4 iris_vertexColor;\n");
		shader.append("""
			layout(std140) uniform Projection {
			    mat4 ProjMat;
			};
			""");
		main.append("float iris_vertexColorAlpha = iris_vertexColor.a;");

		if (inputs.hasTex()) {
			shader.append("uniform sampler2D Sampler0;\n");
			shader.append("in vec2 texCoord;\n");

			main.append("    vec4 color = texture(Sampler0, texCoord)");

			if (intensityTex) {
				main.append(".rrrr");
			}

			if (alphaTest == AlphaTests.VERTEX_ALPHA) {
				main.append(" * vec4(iris_vertexColor.rgb, 1);\n");
			} else {
				main.append(" * iris_vertexColor;\n");
			}
		} else {
			if (alphaTest == AlphaTests.VERTEX_ALPHA) {
				main.append("vec4 color = vec4(iris_vertexColor.rgb, 1);\n");
			} else {
				main.append("vec4 color = iris_vertexColor;\n");
			}
		}

		if (inputs.hasOverlay()) {
			shader.append("in vec4 overlayColor;\n");

			main.append("    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);\n");
		}

		if (inputs.hasLight()) {
			shader.append("uniform sampler2D Sampler2;\n");
			shader.append("in vec2 lightCoord;\n");

			main.append("    color *= texture(Sampler2, lightCoord);\n");
		}

		if (fogMode == FogMode.PER_VERTEX || fogMode == FogMode.PER_FRAGMENT) {
			shader.append("""
				layout(std140) uniform Fog {
				    vec4 FogColor;
				    float FogEnvironmentalStart;
				    float FogEnvironmentalEnd;
				    float FogRenderDistanceStart;
				    float FogRenderDistanceEnd;
				    float FogSkyEnd;
				    float FogCloudsEnd;
				};""");

			if (fogMode == FogMode.PER_VERTEX) {
				// Use vertex distances, close enough
				shader.append("in float vertexDistance;\n");
				main.append("float fragmentDistance = vertexDistance;\n");
			} else /*if (fogMode == FogMode.PER_FRAGMENT)*/ {
				// Use fragment distances since beam vertices are very far apart
				main.append("float fragmentDistance = -ProjMat[3].z / ((gl_FragCoord.z) * -2.0 + 1.0 - ProjMat[2].z);\n");
			}

			// These are custom Iris uniforms implemented in FallbackShader.
			shader.append("uniform float FogDensity = 1.0;\n");
			shader.append("uniform int FogIsExp2 = 1;\n");

			main.append("    float fogFactor;\n");
			main.append("    if (FogIsExp2 == 1) {\n");
			main.append("        float x = fragmentDistance * FogDensity;\n");
			main.append("        fogFactor = exp(-x * x);\n");
			main.append("    } else {\n");
			main.append("        fogFactor = (FogRenderDistanceEnd - fragmentDistance) / (FogRenderDistanceEnd - FogRenderDistanceStart);\n");
			main.append("    }\n");

			main.append("    fogFactor = clamp(fogFactor, 0.0, 1.0);\n");

			main.append("    color.rgb = mix(FogColor.rgb, color.rgb, fogFactor * FogColor.a);\n");
		}

		main.append("    fragColor = color;\n");

		// Wynncraft transition: inject fullscreen effects for text fallback shaders
		if (inputs.isText() && inputs.hasTex() && net.irisshaders.iris.gui.option.IrisVideoSettings.wynncraftDebugLogging) {
			System.out.println("[WynnIris] Synthesizing fallback text FS with transition support");
		}
		if (inputs.isText() && inputs.hasTex()) {
			shader.append("""
				layout(std140) uniform Globals {
				    ivec3 CameraBlockPos;
				    vec3 CameraOffset;
				    vec2 ScreenSize;
				    float GlintAlpha;
				    float GameTime;
				    int MenuBlurRadius;
				};
				""");
			shader.append("flat in int irisW_transType;\n");
			shader.append("in vec4 irisW_transColor;\n");
			shader.append("flat in float irisW_transShadow;\n");
			shader.append("""
				const float IRISW_PI = 3.14159265359;
				const float IRISW_TAU = IRISW_PI * 2.0;
				int irisW_hash(int x) { x += (x << 10); x ^= (x >> 6); x += (x << 3); x ^= (x >> 11); x += (x << 15); return x; }
				float irisW_noise2(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
				float irisW_noiseT(vec2 uv, float t1, float t2) { return fract(sin(uv.x * t1 + uv.y * t2) * 56789.0); }
				float irisW_smoothNoise(vec2 p) { vec2 i = floor(p); vec2 f = fract(p); float a = irisW_noise2(i); float b = irisW_noise2(i + vec2(1.0, 0.0)); float c = irisW_noise2(i + vec2(0.0, 1.0)); float d = irisW_noise2(i + vec2(1.0, 1.0)); vec2 u = f * f * (3.0 - 2.0 * f); return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y; }
				float irisW_fbm(vec2 p) { float v = 0.0; float a = 0.5; float freq = 1.0; for (int i = 0; i < 5; i++) { v += a * irisW_smoothNoise(p * freq); freq *= 2.0; a *= 0.5; } return v; }
				""");
		}

		// void main
		shader.append("void main() {\n");

		// Wynncraft transition: early-out dispatch BEFORE pack logic
		if (inputs.isText() && inputs.hasTex()) {
			shader.append(buildFallbackTransitionDispatch());
		}

		shader.append(main);
		shader.append(alphaTest.toExpression("fragColor.a", "AlphaTestValue", "    "));
		shader.append("}\n");

		return shader.toString();
	}

	// Builds the transition dispatch GLSL for fallback text fragment shaders.
	// Uses 'fragColor' as output (the fallback shader's output variable name).
	private static String buildFallbackTransitionDispatch() {
		return """
		if (irisW_transType > 0) {
		    vec2 irisW_ss = ScreenSize;
		    vec2 irisW_cuv = gl_FragCoord.xy / irisW_ss - 0.5;
		    float irisW_ar = irisW_ss.y / irisW_ss.x;
		    vec2 irisW_UV = irisW_cuv / vec2(irisW_ar, 1.0);
		    float irisW_prog = cos(irisW_transColor.a * IRISW_PI / 2.0);
		    float irisW_gt = GameTime;
		    vec3 irisW_rgb = irisW_transColor.rgb;
		    float irisW_rawA = irisW_transColor.a;
		    vec4 irisW_result = vec4(0.0);
		    if (irisW_transShadow > 0.5) { fragColor = vec4(0.0); return; }
		    switch (irisW_transType) {
		        case 1: { irisW_result = vec4(irisW_rgb, (length((gl_FragCoord.xy / irisW_ss - 0.5) / vec2(irisW_ar, 1.0)) + 0.1 - irisW_prog * 1.5) * (1.0 - irisW_prog) * 100.0); break; }
		        case 2: { irisW_result = vec4(irisW_rgb, clamp(length(irisW_cuv * vec2(1.0, 2.0 / max(1.0 - irisW_rawA, 0.001))) - 1.0, 0.0, 1.0)); break; }
		        case 3: { irisW_result = vec4(0.0); float a3 = (atan(irisW_cuv.y, irisW_cuv.x) / IRISW_PI / 2.0 + 0.5) * 30.0; float t3 = irisW_gt * 2000.0 + float(irisW_hash(int(a3)) % 100) * 64.2343; float s3 = (abs(fract(a3) - 0.5) * 20.0 / 30.0 - 0.2) * length(irisW_cuv) + 0.07 + (1.0 - irisW_rawA) * 0.05 + abs(fract(t3) - 0.5) * 0.25; if (s3 < 0.0) irisW_result = vec4(irisW_rgb, clamp(-s3 * 200.0, 0.0, 1.0)); break; }
		        case 4: { vec2 g4 = vec2(ivec2(gl_FragCoord.xy / 64.0) * 64); vec2 ig4 = gl_FragCoord.xy - g4 - 32.0; float sz4 = (g4.y / irisW_ss.y - irisW_rawA * 2.0 + 1.0) * 64.0; irisW_result = (abs(ig4.x) + abs(ig4.y) > sz4) ? vec4(irisW_rgb, 1.0) : vec4(0.0); break; }
		        case 5: { ivec2 g5 = ivec2(gl_FragCoord.xy / 64.0) * 64; irisW_result = abs(irisW_hash(g5.x ^ irisW_hash(g5.y)) % 256) < int(irisW_rawA * (length(vec2(g5) / irisW_ss - 0.5) * 2.0 + 1.0) * 256.0) ? vec4(irisW_rgb, 1.0) : vec4(0.0); break; }
		        case 6: { irisW_result = vec4(0.0); float r6 = length(irisW_UV); if (r6 >= 0.07 && r6 < 0.1 && irisW_rawA >= 0.99) { float a6 = fract(-atan(irisW_UV.y, irisW_UV.x) / IRISW_TAU - irisW_gt * 1000.0); irisW_result = vec4(irisW_rgb, a6); } break; }
		        case 7: { vec2 c7 = irisW_cuv * (irisW_ss.x / irisW_ss.y); float d7 = length(c7) / length(vec2(1.0, irisW_ss.y / irisW_ss.x)); irisW_result = vec4(irisW_rgb, smoothstep(0.0, 1.0, d7 * 0.4)) * (1.0 - irisW_prog); break; }
		        case 8: { float s8 = 2.0 - abs((irisW_cuv.y - 0.5) / max(1.0 - irisW_prog, 0.001) - 1.0); irisW_result = vec4(irisW_rgb, step(0.0, s8)); break; }
		        case 9: { float s9 = 2.0 - abs((irisW_cuv.y + 0.5) / max(1.0 - irisW_prog, 0.001) - 1.0); irisW_result = vec4(irisW_rgb, step(0.0, s9)); break; }
		        case 10: { float s10 = 2.0 - abs((irisW_cuv.x - 0.5) / max(1.0 - irisW_prog, 0.001) - 1.0); irisW_result = vec4(irisW_rgb, step(0.0, s10)); break; }
		        case 11: { float s11 = 2.0 - abs((irisW_cuv.x + 0.5) / max(1.0 - irisW_prog, 0.001) - 1.0); irisW_result = vec4(irisW_rgb, step(0.0, s11)); break; }
		        case 12: { irisW_result = vec4(irisW_rgb, 1.0 - irisW_prog); break; }
		        case 13: { irisW_result = vec4(0.0); float top = 2.0 - abs((irisW_cuv.y - 0.5) / max(1.0 - irisW_prog, 0.001)); float bot = 2.0 - abs((irisW_cuv.y + 0.5) / max(1.0 - irisW_prog, 0.001)); if (irisW_UV.y > 0.4) irisW_result = vec4(irisW_rgb, step(0.0, top)); if (irisW_UV.y < -0.4) irisW_result = vec4(irisW_rgb, step(0.0, bot)); break; }
		        case 14: { float cp14 = atan(irisW_UV.y, irisW_UV.x) + irisW_prog * 2.0; irisW_result = vec4(irisW_rgb, step(sign(irisW_prog - mod(cp14, IRISW_PI / 4.0)), 0.5)); break; }
		        case 15: { float os15 = IRISW_PI / 2.0; float a15 = atan(irisW_UV.y, irisW_UV.x) + os15; float n15 = (a15 + IRISW_PI) / IRISW_TAU; n15 = n15 - floor(n15); irisW_result = vec4(irisW_rgb, step(n15, 1.0 - irisW_prog)); break; }
		        case 16: { float t16 = irisW_gt * 2000.0; irisW_result = vec4(irisW_rgb, irisW_noiseT(irisW_UV, t16 * 0.654321, t16 * (t16 * 0.654321 * 0.123456)) * (1.0 - irisW_prog)); break; }
		        case 17: { vec2 c17 = irisW_cuv * (irisW_ss.x / irisW_ss.y); float d17 = length(c17) / length(vec2(1.0, irisW_ss.y / irisW_ss.x)); float v17 = smoothstep(0.0, 1.0, d17 * 0.5) * irisW_fbm(c17 * 1.5 + vec2(irisW_gt * 4000.0 * 0.1, 0.0)); irisW_result = vec4(irisW_rgb, v17) * (1.0 - irisW_prog); break; }
		        case 18: { vec2 c18 = irisW_cuv * (irisW_ss.x / irisW_ss.y); float d18 = length(c18) / length(vec2(1.0, irisW_ss.y / irisW_ss.x)); float p18 = (irisW_ss.x / irisW_ss.y) + 0.1 * sin(irisW_gt * 3000.0); irisW_result = vec4(irisW_rgb, smoothstep(0.1, 1.0, d18 * 0.25 * p18)) * (1.0 - irisW_prog); break; }
		        case 19: { vec2 uv19 = (gl_FragCoord.xy / irisW_ss) * 2.0 - 1.0; uv19.x *= irisW_ss.x / irisW_ss.y; float p19 = irisW_rawA; float t19 = p19 * 8.5; float r19 = length(uv19); float th19 = atan(uv19.y, uv19.x); th19 += mix(0.0, 4.0, p19) * sin(r19 * 8.0 - t19 * 3.0) * (1.0 - p19); float mr19 = length(vec2(irisW_ss.x / irisW_ss.y, 1.0)); float a19 = smoothstep((1.0 - p19) * mr19 - 0.25, (1.0 - p19) * mr19, r19); float sc19 = sin(th19 * 6.0 + t19) * 0.5 + 0.5; vec3 pc19 = mix(vec3(0.741, 0.282, 0.910), vec3(0.545, 0.098, 0.749), sc19); irisW_result = mix(vec4(pc19, a19), vec4(0.667, 0.153, 0.812, 1.0), smoothstep(0.8, 1.0, p19)); break; }
		    }
		    fragColor = irisW_result;
		    return;
		}
		""";
	}
}
