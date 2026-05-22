package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.util.Type;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.VanillaParameters;

import static net.irisshaders.iris.pipeline.transform.transformer.CommonTransformer.addIfNotExists;

public class VanillaCoreTransformer {
	// ========================================================================
	// Wynncraft Transition Screen Effects — GLSL helpers and effect functions
	// Injected into text fragment shaders for fullscreen transition rendering.
	// ========================================================================

	// Utility functions shared by transition effects (noise, hash, FBM, etc.)
	private static final String[] IRISW_TRANSITION_UTILS = {
		"const float IRISW_PI = 3.14159265359;",
		"const float IRISW_TAU = IRISW_PI * 2.0;",
		"""
		int irisW_hash(int x) {
		    x += (x << 10);
		    x ^= (x >> 6);
		    x += (x << 3);
		    x ^= (x >> 11);
		    x += (x << 15);
		    return x;
		}""",
		"""
		float irisW_noise2(vec2 p) {
		    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
		}""",
		"""
		float irisW_noiseT(vec2 uv, float t1, float t2) {
		    return fract(sin(uv.x * t1 + uv.y * t2) * 56789.0);
		}""",
		"""
		float irisW_smoothNoise(vec2 p) {
		    vec2 i = floor(p);
		    vec2 f = fract(p);
		    float a = irisW_noise2(i);
		    float b = irisW_noise2(i + vec2(1.0, 0.0));
		    float c = irisW_noise2(i + vec2(0.0, 1.0));
		    float d = irisW_noise2(i + vec2(1.0, 1.0));
		    vec2 u = f * f * (3.0 - 2.0 * f);
		    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
		}""",
		"""
		float irisW_fbm(vec2 p) {
		    float v = 0.0;
		    float a = 0.5;
		    float freq = 1.0;
		    for (int i = 0; i < 5; i++) {
		        v += a * irisW_smoothNoise(p * freq);
		        freq *= 2.0;
		        a *= 0.5;
		    }
		    return v;
		}""",
	};

	// The main transition dispatch function, injected into text fragment shaders.
	// Called as early-out at start of main() to bypass pack-authored discard/logic.
	private static String buildTransitionDispatch(String fragOutput) {
		return ("""
		if (irisW_transType > 0) {
		    vec2 irisW_ss = iris_globalInfo.ScreenSize;
		    vec2 irisW_cuv = gl_FragCoord.xy / irisW_ss - 0.5;
		    float irisW_ar = irisW_ss.y / irisW_ss.x;
		    vec2 irisW_UV = irisW_cuv / vec2(irisW_ar, 1.0);
		    float irisW_prog = cos(irisW_transColor.a * IRISW_PI / 2.0);
		    float irisW_gt = iris_globalInfo.GameTime;
		    vec3 irisW_rgb = irisW_transColor.rgb;
		    float irisW_rawA = irisW_transColor.a;
		    vec4 irisW_result = vec4(0.0);

		    if (irisW_transShadow > 0.5) {
		        IRISW_FRAG_OUT = vec4(0.0);
		        return;
		    }

		    switch (irisW_transType) {
		        case 1: { // Iris wipe
		            irisW_result = vec4(irisW_rgb,
		                (length((gl_FragCoord.xy / irisW_ss - 0.5) / vec2(irisW_ar, 1.0))
		                 + 0.1 - irisW_prog * 1.5) * (1.0 - irisW_prog) * 100.0);
		            break;
		        }
		        case 2: { // Blink
		            irisW_result = vec4(irisW_rgb,
		                clamp(length(irisW_cuv * vec2(1.0, 2.0 / max(1.0 - irisW_rawA, 0.001))) - 1.0, 0.0, 1.0));
		            break;
		        }
		        case 3: { // Speed lines
		            irisW_result = vec4(0.0);
		            float irisW_angle3 = (atan(irisW_cuv.y, irisW_cuv.x) / IRISW_PI / 2.0 + 0.5) * 30.0;
		            float irisW_time3 = irisW_gt * 2000.0 + float(irisW_hash(int(irisW_angle3)) % 100) * 64.2343;
		            float irisW_s3 = (abs(fract(irisW_angle3) - 0.5) * 20.0 / 30.0 - 0.2) * length(irisW_cuv)
		                + 0.07 + (1.0 - irisW_rawA) * 0.05 + abs(fract(irisW_time3) - 0.5) * 0.25;
		            if (irisW_s3 < 0.0) {
		                irisW_result = vec4(irisW_rgb, clamp(-irisW_s3 * 200.0, 0.0, 1.0));
		            }
		            break;
		        }
		        case 4: { // Diamond
		            vec2 irisW_grid4 = vec2(ivec2(gl_FragCoord.xy / 64.0) * 64);
		            vec2 irisW_ig4 = gl_FragCoord.xy - irisW_grid4 - 32.0;
		            float irisW_sz4 = irisW_grid4.y / irisW_ss.y;
		            irisW_sz4 = (irisW_sz4 - irisW_rawA * 2.0 + 1.0) * 64.0;
		            irisW_result = (abs(irisW_ig4.x) + abs(irisW_ig4.y) > irisW_sz4) ? vec4(irisW_rgb, 1.0) : vec4(0.0);
		            break;
		        }
		        case 5: { // Noise
		            ivec2 irisW_grid5 = ivec2(gl_FragCoord.xy / 64.0) * 64;
		            irisW_result = abs(irisW_hash(irisW_grid5.x ^ irisW_hash(irisW_grid5.y)) % 256)
		                < int(irisW_rawA * (length(vec2(irisW_grid5) / irisW_ss - 0.5) * 2.0 + 1.0) * 256.0)
		                ? vec4(irisW_rgb, 1.0) : vec4(0.0);
		            break;
		        }
		        case 6: { // Load spinner
		            irisW_result = vec4(0.0);
		            float irisW_r6 = length(irisW_UV);
		            if (irisW_r6 >= 0.07 && irisW_r6 < 0.1 && irisW_rawA >= 0.99) {
		                float irisW_a6 = fract(-atan(irisW_UV.y, irisW_UV.x) / IRISW_TAU - irisW_gt * 1000.0);
		                irisW_result = vec4(irisW_rgb, irisW_a6);
		            }
		            break;
		        }
		        case 7: { // Vignette
		            vec2 irisW_c7 = irisW_cuv * (irisW_ss.x / irisW_ss.y);
		            float irisW_d7 = length(irisW_c7);
		            irisW_d7 /= length(vec2(1.0, irisW_ss.y / irisW_ss.x));
		            float irisW_v7 = smoothstep(0.0, 1.0, irisW_d7 * 0.4);
		            irisW_result = vec4(irisW_rgb, irisW_v7) * (1.0 - irisW_prog);
		            break;
		        }
		        case 8: { // Close from top
		            float irisW_s8 = 2.0 - abs((irisW_cuv.y - 0.5) / max(1.0 - irisW_prog, 0.001) - 1.0);
		            irisW_result = vec4(irisW_rgb, step(0.0, irisW_s8));
		            break;
		        }
		        case 9: { // Close from bottom
		            float irisW_s9 = 2.0 - abs((irisW_cuv.y + 0.5) / max(1.0 - irisW_prog, 0.001) - 1.0);
		            irisW_result = vec4(irisW_rgb, step(0.0, irisW_s9));
		            break;
		        }
		        case 10: { // Close from left
		            float irisW_s10 = 2.0 - abs((irisW_cuv.x - 0.5) / max(1.0 - irisW_prog, 0.001) - 1.0);
		            irisW_result = vec4(irisW_rgb, step(0.0, irisW_s10));
		            break;
		        }
		        case 11: { // Close from right
		            float irisW_s11 = 2.0 - abs((irisW_cuv.x + 0.5) / max(1.0 - irisW_prog, 0.001) - 1.0);
		            irisW_result = vec4(irisW_rgb, step(0.0, irisW_s11));
		            break;
		        }
		        case 12: { // Fade
		            irisW_result = vec4(irisW_rgb, 1.0 - irisW_prog);
		            break;
		        }
		        case 13: { // Letterbox
		            irisW_result = vec4(0.0);
		            float irisW_top = 2.0 - abs((irisW_cuv.y - 0.5) / max(1.0 - irisW_prog, 0.001));
		            float irisW_bot = 2.0 - abs((irisW_cuv.y + 0.5) / max(1.0 - irisW_prog, 0.001));
		            if (irisW_UV.y > 1.0 - 0.6)
		                irisW_result = vec4(irisW_rgb, step(0.0, irisW_top));
		            if (irisW_UV.y < -0.4)
		                irisW_result = vec4(irisW_rgb, step(0.0, irisW_bot));
		            break;
		        }
		        case 14: { // Wheel
		            float irisW_cp14 = atan(irisW_UV.y, irisW_UV.x) + irisW_prog * 2.0;
		            float irisW_sg14 = sign(irisW_prog - mod(irisW_cp14, IRISW_PI / 4.0));
		            irisW_result = vec4(irisW_rgb, step(irisW_sg14, 0.5));
		            break;
		        }
		        case 15: { // Angular
		            float irisW_os15 = 90.0 * IRISW_PI / 180.0;
		            float irisW_a15 = atan(irisW_UV.y, irisW_UV.x) + irisW_os15;
		            float irisW_n15 = (irisW_a15 + IRISW_PI) / IRISW_TAU;
		            irisW_n15 = irisW_n15 - floor(irisW_n15);
		            irisW_result = vec4(irisW_rgb, step(irisW_n15, 1.0 - irisW_prog));
		            break;
		        }
		        case 16: { // Static
		            float irisW_t16 = irisW_gt * 2000.0;
		            float irisW_t1_16 = irisW_t16 * 0.654321;
		            float irisW_t2_16 = irisW_t16 * (irisW_t1_16 * 0.123456);
		            float irisW_st16 = irisW_noiseT(irisW_UV, irisW_t1_16, irisW_t2_16);
		            irisW_result = vec4(irisW_rgb, irisW_st16 * (1.0 - irisW_prog));
		            break;
		        }
		        case 17: { // Vignette Fog
		            vec2 irisW_c17 = irisW_cuv * (irisW_ss.x / irisW_ss.y);
		            float irisW_d17 = length(irisW_c17);
		            float irisW_t17 = irisW_gt * 4000.0;
		            irisW_d17 /= length(vec2(1.0, irisW_ss.y / irisW_ss.x));
		            float irisW_v17 = smoothstep(0.0, 1.0, irisW_d17 * 0.5);
		            float irisW_f17 = irisW_fbm(irisW_c17 * 1.5 + vec2(irisW_t17 * 0.1, 0.0));
		            irisW_v17 *= irisW_f17;
		            irisW_result = vec4(irisW_rgb, irisW_v17) * (1.0 - irisW_prog);
		            break;
		        }
		        case 18: { // Focus
		            vec2 irisW_c18 = irisW_cuv * (irisW_ss.x / irisW_ss.y);
		            float irisW_d18 = length(irisW_c18);
		            float irisW_t18 = irisW_gt * 3000.0;
		            float irisW_p18 = (irisW_ss.x / irisW_ss.y) + 0.1 * sin(irisW_t18);
		            irisW_d18 /= length(vec2(1.0, irisW_ss.y / irisW_ss.x));
		            float irisW_v18 = smoothstep(0.1, 1.0, irisW_d18 * 0.25 * irisW_p18);
		            irisW_result = vec4(irisW_rgb, irisW_v18) * (1.0 - irisW_prog);
		            break;
		        }
		        case 19: { // Portal
		            vec2 irisW_uv19 = (gl_FragCoord.xy / irisW_ss) * 2.0 - 1.0;
		            irisW_uv19.x *= irisW_ss.x / irisW_ss.y;
		            float irisW_p19 = irisW_rawA;
		            float irisW_t19 = irisW_p19 * 8.5;
		            float irisW_r19 = length(irisW_uv19);
		            float irisW_th19 = atan(irisW_uv19.y, irisW_uv19.x);
		            float irisW_sw19 = mix(0.0, 4.0, irisW_p19);
		            irisW_th19 += irisW_sw19 * sin(irisW_r19 * 8.0 - irisW_t19 * 3.0) * (1.0 - irisW_p19);
		            float irisW_mr19 = length(vec2(irisW_ss.x / irisW_ss.y, 1.0));
		            float irisW_tr19 = (1.0 - irisW_p19) * irisW_mr19;
		            float irisW_a19 = smoothstep(irisW_tr19 - 0.25, irisW_tr19, irisW_r19);
		            float irisW_sc19 = sin(irisW_th19 * 6.0 + irisW_t19) * 0.5 + 0.5;
		            vec3 irisW_pc19 = mix(
		                vec3(189.0/255.0, 72.0/255.0, 232.0/255.0),
		                vec3(139.0/255.0, 25.0/255.0, 191.0/255.0),
		                irisW_sc19);
		            vec4 irisW_col19 = vec4(irisW_pc19, irisW_a19);
		            float irisW_fd19 = smoothstep(0.8, 1.0, irisW_p19);
		            irisW_col19 = mix(irisW_col19, vec4(170.0/255.0, 39.0/255.0, 207.0/255.0, 1.0), irisW_fd19);
		            irisW_result = irisW_col19;
		            break;
		        }
		    }
		    IRISW_FRAG_OUT = irisW_result;
		    return;
		}
		""").replace("IRISW_FRAG_OUT", fragOutput);
	}

	// Vertex shader GLSL for transition signal detection and fullscreen remap.
	private static final String IRISW_TRANSITION_VERTEX_MAIN = """
		// Wynncraft transition detection: sample glyph atlas at sprite center
		vec4 irisW_texSample = textureLod(Sampler0, mc_midTexCoord.xy, 0.0) * 255.0;
		irisW_transType = 0;
		irisW_transColor = vec4(0.0);
		irisW_transShadow = 0.0;

		if (irisW_texSample.a > 252.5 && irisW_texSample.a < 253.5) {
		    irisW_transType = clamp(int(irisW_texSample.b + 0.5), 0, 19);

		    // Shadow detection: MC text shadow = vertex color / 4.
		    // RP heuristic in 0-1 space: shadow when all channels / 4 < 0.23 (i.e. < 0.92).
		    // Shadow of white (0.25) → 0.0625 < 0.23 ✓. Normal white (1.0) → 0.25 > 0.23 ✗.
		    // For black overlay (common), both passes have (0,0,0) → both "shadow" →
		    // both suppressed. This matches RP behavior; Wynncraft handles via overlay design.
		    float irisW_shadowMax = max(iris_Color.r, max(iris_Color.g, iris_Color.b));
		    irisW_transShadow = (irisW_shadowMax / 4.0 < 0.23) ? 1.0 : 0.0;

		    // Store overlay color (RGB) and progress (alpha)
		    irisW_transColor = iris_Color;

		    if (irisW_transType > 0) {
		        // Reposition to fullscreen quad
		        const vec2 irisW_corners[4] = vec2[4](vec2(0.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0), vec2(1.0, 0.0));
		        vec2 irisW_screen = irisW_corners[gl_VertexID % 4];
		        gl_Position = vec4((irisW_screen * 2.0 - 1.0) * vec2(1.0, -1.0), -1.0, 1.0);
		    }
		}
		""";

	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		VanillaParameters parameters) {

		if (parameters.inputs.hasOverlay()) {
			if (!parameters.inputs.isText()) {
				EntityPatcher.patchOverlayColor(t, tree, root, parameters);
			}
			EntityPatcher.patchEntityId(t, tree, root, parameters);
		} else if (parameters.inputs.hasColor() && !parameters.inputs.isText() && parameters.inputs.hasNormal()) {
			EntityPatcher.patchTranslucencyOnly(t, tree, root, parameters);
		}

		// Wynncraft transition screen effects: inject into text vertex shaders.
		// Detects transition signal (texture alpha=253) and remaps quad to fullscreen.
		// Gated: text only, no geometry/tessellation shaders (varyings would be zeroed).
		if (parameters.inputs.isText() && net.irisshaders.iris.gui.option.IrisVideoSettings.wynncraftDebugLogging) {
			net.irisshaders.iris.gui.option.WynncraftDebugLog.info("compile", "[WynnIris] Text shader detected: type=" + parameters.type
				+ " hasGeo=" + parameters.hasGeometry + " hasTes=" + parameters.hasTesselation);
		}
		if (parameters.inputs.isText() && !parameters.hasGeometry && !parameters.hasTesselation
			&& parameters.type == PatchShaderType.VERTEX) {
			if (net.irisshaders.iris.gui.option.IrisVideoSettings.wynncraftDebugLogging) {
				net.irisshaders.iris.gui.option.WynncraftDebugLog.info("compile", "[WynnIris] Injecting transition VS code");
				net.irisshaders.iris.gui.option.WynncraftDebugLog.info("compile", "[WynnIris]   has Sampler0=" + root.identifierIndex.has("Sampler0"));
				net.irisshaders.iris.gui.option.WynncraftDebugLog.info("compile", "[WynnIris]   has mc_midTexCoord=" + root.identifierIndex.has("mc_midTexCoord"));
			}
			// Inject Sampler0 for vertex texture sampling if not already declared
			if (!root.identifierIndex.has("Sampler0")) {
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"uniform sampler2D Sampler0;");
			}
			// Ensure mc_midTexCoord is declared (stable per-quad UV for signal detection)
			addIfNotExists(root, t, tree, "mc_midTexCoord", Type.F32VEC2, StorageType.IN);
			// Declare varyings for vertex → fragment transport
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat out int irisW_transType;",
				"out vec4 irisW_transColor;",
				"flat out float irisW_transShadow;");
			// Append detection + fullscreen remap to end of main()
			tree.appendMainFunctionBody(t, IRISW_TRANSITION_VERTEX_MAIN);
		}

		// Wynncraft text display entity brightness boost + transition fragment handling.
		if (parameters.inputs.isText() && parameters.type == PatchShaderType.FRAGMENT) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform float iris_wynncraftEntityBoost;");
			// Determine the fragment output variable name (core vs compat profile).
			String textOutput;
			if (root.identifierIndex.has("outColor0")) {
				textOutput = "outColor0";
			} else if (root.identifierIndex.has("gl_FragData") || root.identifierIndex.has("gl_FragColor")) {
				textOutput = "iris_FragData0";
			} else {
				textOutput = null;
			}
			if (textOutput != null) {
				// Text gets ~3x the entity boost (cubed) for readability at night.
				// Only effective for shader packs with custom text programs (non-fallback).
				// Fallback text shaders are handled by MixinTextDisplayRenderer (packedLight override).
				tree.appendMainFunctionBody(t,
					textOutput + ".rgb *= iris_wynncraftEntityBoost * iris_wynncraftEntityBoost * iris_wynncraftEntityBoost;");
			}

			// Wynncraft transition fragment injection (gated by same conditions as vertex)
			if (net.irisshaders.iris.gui.option.IrisVideoSettings.wynncraftDebugLogging) {
				net.irisshaders.iris.gui.option.WynncraftDebugLog.info("compile", "[WynnIris] Text FS: textOutput=" + textOutput + " hasGeo=" + parameters.hasGeometry + " hasTes=" + parameters.hasTesselation);
			}
			if (!parameters.hasGeometry && !parameters.hasTesselation && textOutput != null) {
				if (net.irisshaders.iris.gui.option.IrisVideoSettings.wynncraftDebugLogging) {
					net.irisshaders.iris.gui.option.WynncraftDebugLog.info("compile", "[WynnIris] Injecting transition FS code with output=" + textOutput);
				}
				// Declare varying inputs from vertex shader
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"flat in int irisW_transType;",
					"in vec4 irisW_transColor;",
					"flat in float irisW_transShadow;");
				// Inject utility functions
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					IRISW_TRANSITION_UTILS);
				// Prepend early-out transition dispatch at START of main()
				// This runs before any pack-authored discard/logic.
				tree.prependMainFunctionBody(t, buildTransitionDispatch(textOutput));
			}
		}

		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"const float mc_chunkFade = -1.0;",
			"""
				layout(std140) uniform iris_Fog {
				    vec4 FogColor;
				    float FogEnvironmentalStart;
				    float FogEnvironmentalEnd;
				    float FogRenderDistanceStart;
				    float FogRenderDistanceEnd;
				    float FogSkyEnd;
				    float FogCloudsEnd;
				} iris_fogP;
				""",
			"struct iris_FogParameters {" +
				"vec4 color;" +
				"float density;" +
				"float start;" +
				"float end;" +
				"float scale;" +
				"};",
			"iris_FogParameters irisInt_Fog = iris_FogParameters(iris_fogP.FogColor, 0.0, iris_fogP.FogEnvironmentalStart, iris_fogP.FogEnvironmentalEnd, 1.0 / (iris_fogP.FogEnvironmentalEnd - iris_fogP.FogEnvironmentalStart));");

		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS, """
			layout(std140) uniform iris_DynamicTransforms {
			    mat4 ModelViewMat;
			    vec4 ColorModulator;
			    vec3 ModelOffset;
			    mat4 TextureMat;
			} iris_transforms;
			""",
			"""
				layout(std140) uniform iris_Projection {
				    mat4 iris_ProjMat;
				};
				""",
			"""
				layout(std140) uniform iris_Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
				} iris_globalInfo;
				""");

		CommonTransformer.transform(t, tree, root, parameters, true);
		root.rename("alphaTestRef", "iris_currentAlphaTest");
		root.replaceReferenceExpressions(t, "modelViewMatrix", "iris_transforms.ModelViewMat");
		root.replaceReferenceExpressions(t, "gl_ModelViewMatrix", "iris_transforms.ModelViewMat");
		root.rename("modelViewMatrixInverse", "iris_ModelViewMatInverse");
		root.rename("gl_ModelViewMatrixInverse", "iris_ModelViewMatInverse");
		root.replaceReferenceExpressions(t, "projectionMatrix", "iris_ProjMat");
		root.replaceReferenceExpressions(t, "gl_ProjectionMatrix", "iris_ProjMat");
		root.rename("projectionMatrixInverse", "iris_ProjMatInverse");
		root.rename("gl_ProjectionMatrixInverse", "iris_ProjMatInverse");
		root.replaceReferenceExpressions(t, "textureMatrix", "iris_transforms.TextureMat");

		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix0, "iris_transforms.TextureMat");
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix1,
			"mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0))");
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix2,
			"mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0))");
		root.rename("normalMatrix", "iris_NormalMat");
		root.rename("gl_NormalMatrix", "iris_NormalMat");
		addIfNotExists(root, t, tree, "iris_NormalMat", Type.F32MAT3X3, StorageType.UNIFORM);
		root.replaceReferenceExpressions(t, "chunkOffset", "iris_transforms.ModelOffset");

		CommonTransformer.upgradeStorageQualifiers(t, tree, root, parameters);

		if (parameters.type == PatchShaderType.VERTEX) {
			// Redirect position/UV to mutable intermediates when EntityPatcher has injected
			// the player emote function (irisw_pos/irisw_uv0 hold decoded emote positions).
			// Use replaceReferenceExpressions (not rename) for the emote path to avoid
			// renaming declarations — `in vec3 vaPosition;` must not become `in vec3 irisw_pos;`
			// which would conflict with EntityPatcher's `vec3 irisw_pos;` mutable global.
			boolean isEntityOverlay = parameters.inputs.hasOverlay() && !parameters.inputs.isText()
				&& root.identifierIndex.has("irisw_pos");
			if (isEntityOverlay) {
				root.replaceReferenceExpressions(t, "gl_Vertex", "vec4(irisw_pos, 1.0)");
				root.replaceReferenceExpressions(t, "vaPosition", "irisw_pos");
			} else {
				root.replaceReferenceExpressions(t, "gl_Vertex", "vec4(iris_Position, 1.0)");
				root.rename("vaPosition", "iris_Position");
			}
			if (parameters.inputs.hasColor()) {
				// Neutralize Wynncraft signal colors to white.
				// Signals: glint (G=255), translucency (G=254), mount armor overlay
				// (alpha=252/250, legacy green G=252/250, or near-white R=252/250),
				// effects (G=240, G=60, G=58, G=59), movements (G=235).
				// Integer-domain detection: int(round(iris_Color.g * 255.0)) == exact value.
				// Glint/effect/movement branches use full alpha. Translucency (G=254) uses reduced
				// alpha (level encoded in R byte) so the pack's downstream fragment logic —
				// anything that multiplies by vaColor.a / gl_Color.a — propagates the translucency
				// weight, mirroring Wynncraft RP's applyTranslucent(). End-of-main fragment-side
				// fallback in EntityPatcher.appendTranslucencyAlpha stays to cover packs that
				// overwrite alpha mid-shader (double-attenuation is the accepted trade-off).
				String effectMovementNeutral =
					"int(round(iris_Color.g * 255.0)) == 240 || int(round(iris_Color.g * 255.0)) == 235"
					+ " || int(round(iris_Color.g * 255.0)) == 60 || int(round(iris_Color.g * 255.0)) == 58"
					+ " || int(round(iris_Color.g * 255.0)) == 59";
				String armorOverlayNeutral = "(int(round(iris_Color.a * 255.0)) == 252 || int(round(iris_Color.a * 255.0)) == 250"
					+ " || (int(round(iris_Color.a * 255.0)) >= 161 && int(round(iris_Color.a * 255.0)) <= 192))"
					+ " || (iris_Color.r < 0.01 && iris_Color.b < 0.01"
					+ " && (int(round(iris_Color.g * 255.0)) == 252 || int(round(iris_Color.g * 255.0)) == 250))"
					+ " || ((int(round(iris_Color.r * 255.0)) == 252 || int(round(iris_Color.r * 255.0)) == 250)"
					+ " && int(round(iris_Color.g * 255.0)) == 255 && int(round(iris_Color.b * 255.0)) == 255)";
				// Alpha factor mirrors Wynncraft RP include/translucency.glsl's applyTranslucent():
				//   color.a = mix(color.a, 0.0, level/100.0) = color.a * (1.0 - level/100.0)
				// Integer-domain decode matches EntityPatcher's iris_wynncraft_translucency.
				// Floor at 0.10 (instead of 0.0) so level=100 VFX remains barely visible —
				// fully-invisible at level=100 looked too aggressive in shader-pack testing.
				String translucencyAlphaFactor = "max(0.10, 1.0 - float(int(round(iris_Color.r * 255.0))) / 100.0)";
				String translucencyBranch = "vec4(1.0, 1.0, 1.0, " + translucencyAlphaFactor + ")";
				String signalNeutral = "(iris_Color.g > 0.998 && iris_Color.b < 0.01 && iris_Color.r > 0.002 && iris_Color.r < 0.13 ? vec4(1.0)"
					+ " : iris_Color.g > 0.994 && iris_Color.g < 0.998 && iris_Color.b < 0.01 && iris_Color.r > 0.002 && iris_Color.r < 0.998 ? " + translucencyBranch
					+ " : ((" + effectMovementNeutral + ") || (" + armorOverlayNeutral + ")) ? vec4(1.0)"
					+ " : iris_Color)";
				String translucencyOnlyNeutral = "(iris_Color.g > 0.994 && iris_Color.g < 0.998 && iris_Color.b < 0.01 && iris_Color.r > 0.002 && iris_Color.r < 0.998 ? " + translucencyBranch
					+ " : ((" + effectMovementNeutral + ") || (" + armorOverlayNeutral + ")) ? vec4(1.0)"
					+ " : iris_Color)";
				if (parameters.inputs.hasOverlay() && !parameters.inputs.isText()) {
					// Entity: neutralize glint, translucency, effects, and movements
					root.replaceReferenceExpressions(t, "vaColor", signalNeutral + " * iris_transforms.ColorModulator");
					root.replaceReferenceExpressions(t, "gl_Color", signalNeutral + " * iris_transforms.ColorModulator");
				} else if (!parameters.inputs.isText() && parameters.inputs.hasNormal()) {
					// Non-overlay with Normal (entities/display entities): neutralize translucency + effects/movements
					root.replaceReferenceExpressions(t, "vaColor", translucencyOnlyNeutral + " * iris_transforms.ColorModulator");
					root.replaceReferenceExpressions(t, "gl_Color", translucencyOnlyNeutral + " * iris_transforms.ColorModulator");
				} else {
					// Text, particles, weather, etc.: no signal neutralization
					root.replaceReferenceExpressions(t, "vaColor", "iris_Color * iris_transforms.ColorModulator");
					root.replaceReferenceExpressions(t, "gl_Color", "iris_Color * iris_transforms.ColorModulator");
				}
			} else {
				root.replaceReferenceExpressions(t, "vaColor", "iris_transforms.ColorModulator");
				root.replaceReferenceExpressions(t, "gl_Color", "iris_transforms.ColorModulator");
			}
			root.rename("vaNormal", "iris_Normal");
			root.rename("gl_Normal", "iris_Normal");
			if (isEntityOverlay) {
				root.replaceReferenceExpressions(t, "vaUV0", "irisw_uv0");
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord0", "vec4(irisw_uv0, 0.0, 1.0)");
			} else {
				root.rename("vaUV0", "iris_UV0");
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord0", "vec4(iris_UV0, 0.0, 1.0)");
			}
			if (parameters.inputs.hasLight()) {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1", "vec4(iris_UV2, 0.0, 1.0)");
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord2", "vec4(iris_UV2, 0.0, 1.0)");
				root.rename("vaUV2", "iris_UV2");
			} else {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1", "vec4(240.0, 240.0, 0.0, 1.0)");
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord2", "vec4(240.0, 240.0, 0.0, 1.0)");
				root.rename("vaUV2", "iris_UV2");
			}
			root.rename("vaUV1", "iris_UV1");

			addIfNotExists(root, t, tree, "iris_Color", Type.F32VEC4, StorageType.IN);
			addIfNotExists(root, t, tree, "iris_Position", Type.F32VEC3, StorageType.IN);
			addIfNotExists(root, t, tree, "iris_Normal", Type.F32VEC3, StorageType.IN);
			addIfNotExists(root, t, tree, "iris_UV0", Type.F32VEC2, StorageType.IN);
			addIfNotExists(root, t, tree, "iris_UV1", Type.F32VEC2, StorageType.IN);
			addIfNotExists(root, t, tree, "iris_UV2", Type.F32VEC2, StorageType.IN);
		}
	}
}
