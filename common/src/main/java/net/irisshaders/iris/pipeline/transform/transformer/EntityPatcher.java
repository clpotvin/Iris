package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.parser.ParseShape;
import io.github.douira.glsl_transformer.util.Type;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.VanillaParameters;

public class EntityPatcher {
	// ====================================================================================
	// WYNNCRAFT GLINT CROSS-TEXTURE SWEEP FIX
	// ====================================================================================
	//
	// PROBLEM:
	// Wynncraft weapons render as ~50 cube elements using 2+ textures in separate draw
	// calls. Example:
	//   - Texture #0 (handle/string): rainbow_gradient 32x32
	//   - Texture #1 (limbs): rainbow_gradient_anim 32x320
	//
	// The original sweep effect used per-sprite normalized UV (eUV via fract()), which
	// creates DISCONTINUOUS coordinates between sprites. Each sprite has its own [0,1]
	// UV space, so pixels at texture boundaries are swept at wrong times.
	//
	// ARTIFACT: 1-3 pixels at texture boundaries swept out of order with spatial neighbors
	//   - Seraphim Bow: 1 pixel at handle/limb boundary
	//   - Blunderbuss: 3 pixels
	//   - Wands: 1 pixel
	//
	// WHY VANILLA WORKS:
	// In vanilla Wynncraft with ~1024px atlas and SCALE=64, raw texel coordinates provide
	// enough phase variation per sprite for visible sweep (32/1024 * 64 ≈ 2.0 range).
	// In Iris with ~4096px atlas, fract()-based eUV recovery creates discontinuities.
	//
	// SOLUTION:
	// Use mc_midTexCoord (sprite center on atlas) + raw UV to compute continuous sweep
	// coordinates. Raw atlas UV is already continuous across sprites - we just need to
	// scale it appropriately.
	//
	// ALGORITHM:
	// 1. For atlas textures: Use rawUV * 64 * (atlasSize/1024) for continuous sweep
	// 2. For dedicated textures: Use eUV (single texture, no cross-boundary issue)
	// 3. Fallback if mc_midTexCoord unavailable: Use legacy eUV approach
	// Detection: mc_midTexCoord position (primary) + texture size (secondary)
	//
	// APPLYING TO OTHER EFFECTS:
	// Effects 11 (reflection), 12 (plasma), and 14 (chrome) may have similar issues.
	// To fix them:
	// 1. Replace iW_eUV with irisW_continuousSweepUV(iW_uv, iW_midTex, iW_texSize, iW_eUV)
	// 2. Adjust any scale factors to match original visual intent
	// 3. Test on multi-texture weapons
	//
	// VARYING: iris_wynncraft_midtex (vec2) carries mc_midTexCoord to fragment shader
	// ====================================================================================

	// Wynncraft glint signal: vertex Color with G≈1.0, B≈0.0, R in (0,1) encodes glint ID 1-31.
	// Lower bound on R (> 0.002) ensures the decoded ID is at least 1, avoiding false positives
	// from legitimate vertex colors where R=0 would decode to ID 0 (and still neutralize the color).
	private static final String IRISW_SIGNAL_DETECT =
		"bool iris_wynn_isSignal = (iris_Color.g > 0.99 && iris_Color.b < 0.01 && iris_Color.r > 0.002 && iris_Color.r < 0.99);";

	// GLSL helpers for Wynncraft glint effects — one function per element, since
	// parseAndInjectNodes requires exactly one external declaration per string.
	private static final String[] IRISW_HELPERS = {
		"vec3 irisW_rgb(int r, int g, int b) { return vec3(float(r)/255.0, float(g)/255.0, float(b)/255.0); }",
		"""
		vec3 irisW_hsvToRgb(vec3 c) {
		    vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
		    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
		    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
		}""",
		"float irisW_random(float seed) { return fract(57128.836 * sin(dot(vec2(seed), vec2(12.77251, 72.37871)))); }",
		"float irisW_random(vec2 seed) { return fract(57128.836 * sin(dot(seed, vec2(12.77251, 72.37871)))); }",
		"float irisW_noise(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }",
		"""
		float irisW_smoothNoise(vec2 p) {
		    vec2 i = floor(p); vec2 f = fract(p);
		    float a = irisW_noise(i); float b = irisW_noise(i + vec2(1.0, 0.0));
		    float c = irisW_noise(i + vec2(0.0, 1.0)); float d = irisW_noise(i + vec2(1.0, 1.0));
		    vec2 u = f * f * (3.0 - 2.0 * f);
		    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
		}""",
		"""
		vec2 irisW_rotate(vec2 coord, float angle) {
		    float s = sin(angle); float c = cos(angle);
		    return mat2(c, -s, s, c) * coord;
		}""",
		"""
		float irisW_smoothen(float distA, float distB, float amt) {
		    float blend = clamp(0.5 + 0.5 * (distB - distA) / amt, 0.0, 0.5);
		    return mix(distB, distA, blend) - amt * blend * (1.0 - blend);
		}""",
		"vec3 irisW_blend(vec4 a, vec4 b, float amt) { return mix(a, b, amt).rgb; }",
		"""
		vec4 irisW_grayscale(vec4 color) {
		    float brightness = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
		    return vec4(vec3(brightness), color.a);
		}""",
		"""
		vec3 irisW_tint(vec3 tex, vec3 tintColor, float contrast) {
		    float brightness = pow(dot(tex, vec3(0.2126, 0.7152, 0.0722)), 0.7);
		    vec3 color = tintColor * brightness;
		    return color;
		}""",
		"""
		vec3 irisW_aberration(vec2 uv, float factor, float iW_t) {
		    vec4 color = texture(Sampler0, uv);
		    color.x = texture(Sampler0, vec2(uv.x + sin(iW_t * 500.0) * factor, uv.y)).x;
		    color.y = texture(Sampler0, vec2(uv.x + cos(iW_t * 500.0) * factor, uv.y)).y;
		    color.z = texture(Sampler0, uv).z;
		    return irisW_blend(texture(Sampler0, uv), color, color.a);
		}""",
		"""
		vec4 irisW_shiny(vec3 iW_color, float iW_intensity, float iW_brightness, vec2 iW_sUV, float iW_time, vec4 iW_tex) {
		    // Screen-space sparkle grid: each 8x8 pixel cell has a randomly positioned,
		    // independently flashing highlight. This produces multiple small sparkle points
		    // scattered across the surface rather than one sweeping blob.
		    vec2 iW_cell = floor(gl_FragCoord.xy / 8.0);
		    vec2 iW_f    = fract(gl_FragCoord.xy / 8.0);
		    float iW_r1  = irisW_random(iW_cell);
		    float iW_r2  = irisW_random(iW_cell + 31.71);
		    float iW_r3  = irisW_random(iW_cell + 57.13);
		    // Spot centre randomised within inner 60% of cell to avoid seam artifacts
		    vec2  iW_ctr = vec2(0.2 + iW_r2 * 0.6, 0.2 + iW_r3 * 0.6);
		    // Per-cell flash: random period (0.5-2s) and random phase offset
		    float iW_flash = sin(iW_time * (13.0 + iW_r1 * 37.0) + iW_r1 * 6.28318) * 0.5 + 0.5;
		    iW_flash = smoothstep(0.72, 0.98, iW_flash);
		    // Circular spot (~25% of cell width)
		    float iW_spot = 1.0 - smoothstep(0.12, 0.30, length(iW_f - iW_ctr));
		    // Prefer brighter (metallic) texture areas
		    float iW_luma = dot(iW_tex.rgb, vec3(0.2126, 0.7152, 0.0722));
		    float iW_mask = iW_spot * iW_flash * smoothstep(0.2, 0.7, iW_luma) * iW_intensity;
		    return vec4(iW_tex.rgb + iW_color * iW_mask * iW_brightness, iW_tex.a);
		}""",
		"""
		vec4 irisW_tintEffect(vec3 tintColor, vec4 texColor) {
		    vec4 gs = irisW_grayscale(texColor);
		    gs.rgb = irisW_tint(gs.rgb, tintColor, 0.5);
		    return gs;
		}""",
		// Cross-texture sweep fix helper: computes sweep UV that's continuous across
		// sprite boundaries (see documentation at class level for details).
		// Uses texture size as primary atlas detection (robust against sprite position).
		"""
		vec2 irisW_continuousSweepUV(vec2 rawUV, vec2 midTex, vec2 texSize, vec2 eUV) {
		    // Computes a sweep UV that's continuous across sprite boundaries.
		    // PARAMETERS:
		    //   rawUV:   atlas UV of current pixel (iris_wynncraft_texcoord)
		    //   midTex:  atlas UV of sprite center (mc_midTexCoord / iris_wynncraft_midtex)
		    //   texSize: atlas texture dimensions
		    //   eUV:     per-sprite normalized UV (fallback for dedicated textures)
		    // RETURNS:
		    //   Sweep-ready UV coordinates, continuous across all sprites
		    //
		    // Check if mc_midTexCoord is provided (exactly 0,0 means not provided)
		    // Real sprite centers are NEVER exactly (0,0) - minimum offset is 0.5/atlasSize
		    bool hasMidTex = !(midTex.x == 0.0 && midTex.y == 0.0);
		    if (!hasMidTex) {
		        // Fallback: use per-sprite eUV (may have boundary artifacts)
		        return eUV;
		    }

		    // Detect atlas vs dedicated texture using TEXTURE SIZE as primary criterion.
		    // Minecraft atlas textures are always >= 2048px (typically 4096px).
		    // Dedicated textures (armor, weapon skins) are small (16px-1024px).
		    // This is robust regardless of where the sprite is positioned on the atlas.
		    bool isAtlas = max(texSize.x, texSize.y) > 2000.0;

		    if (isAtlas) {
		        // Atlas: scale raw UV to match vanilla SCALE=64 behavior on 1024px
		        float atlasScale = max(texSize.x, texSize.y) / 1024.0;
		        return rawUV * 64.0 * atlasScale;
		    } else {
		        // Dedicated texture: single texture, use eUV (no boundary issue)
		        return eUV;
		    }
		}""",
	};

	// The main glint apply function, inlined into the fragment shader.
	private static final String IRISW_APPLY_GLINT_FUNC = """
		vec4 irisW_applyGlint(int iW_id, vec2 iW_uv, vec2 iW_eUV, vec2 iW_sUV, vec2 iW_midTex, vec2 iW_rUV, vec2 iW_texSize, bool iW_isAtlas, float iW_time, vec4 iW_tex, vec4 iW_in) {
		    vec4 iW_out = iW_in;
		    bool iW_applyLighting = true;
		    switch (iW_id) {
		        case 1:  { iW_out = irisW_shiny(irisW_rgb(255, 200, 100), 0.4, 1.0, iW_sUV, iW_time, iW_tex); break; }
		        case 2:  { iW_out.a *= 0.5; iW_applyLighting = false; break; }
		        case 3:  {
		            iW_out = irisW_grayscale(iW_tex);
		            float iW_spatial3 = 0.05 * (iW_rUV.x + iW_rUV.y);
		            iW_out.rgb *= irisW_hsvToRgb(vec3(iW_spatial3 - iW_time, 0.7, 1.0));
		            break;
		        }
		        case 4:  {
		            // Glitch effect — scale distortion to sprite size, keep chromatic aberration for green tint
		            float iW_pixelSize4 = 1.0 / max(iW_texSize.x, iW_texSize.y);
		            float iW_sz = irisW_random(iW_time); float iW_sp = 10.0;
		            float iW_tf = float(irisW_random(floor(iW_time * iW_sp)) < 0.5);
		            // Scale offset: vanilla 0.015 was for ~1.0 UV range, scale to atlas pixel size
		            float iW_offX = (irisW_random(floor(iW_uv.y * iW_sz) + iW_time) - 0.5) * iW_pixelSize4 * 2.0 * iW_tf;
		            float iW_offY = (irisW_random(floor(iW_uv.x * iW_sz) + iW_time + 31.0) - 0.5) * iW_pixelSize4 * 2.0 * iW_tf;
		            vec2 iW_gu = iW_uv + vec2(iW_offX, iW_offY);
		            vec4 iW_gc = texture(Sampler0, iW_gu);
		            // R/B channel offsets sample far away — this kills R and B, leaving green tint
		            iW_gc.r = mix(iW_gc.r, texture(Sampler0, iW_gu + vec2(0.995, 0.0)).r, iW_tf);
		            iW_gc.b = mix(iW_gc.b, texture(Sampler0, iW_gu - vec2(0.995, 0.0)).b, iW_tf);
		            iW_out.rgb = irisW_blend(iW_tex, iW_gc, iW_gc.a); break;
		        }
		        case 5:  {
		            vec2 iW_ru = iW_eUV * 2.0;
		            mat3 iW_m = mat3(-2, -1, 2, 3, -2, 1, 1, 2, 2);
		            vec3 iW_a = vec3(iW_ru, iW_time * 0.5) * iW_m;
		            vec3 iW_b = iW_a * iW_m * 0.4; vec3 iW_c = iW_b * iW_m * 0.3;
		            iW_out.rgb = iW_tex.rgb + vec3(pow(min(min(length(0.5 - fract(iW_a)), length(0.5 - fract(iW_b))), length(0.5 - fract(iW_c))), 7.0) * 12.0);
		            break;
		        }
		        case 6:  { iW_out.rgb = irisW_aberration(iW_uv, 0.0025, iW_time); break; }
		        case 7:  { iW_out = irisW_grayscale(iW_tex); break; }
		        case 8:  { iW_out = vec4(vec3(1.0) - iW_tex.rgb, iW_tex.a); break; }
		        // ============================================================
		        // CASE 9: SHADOW SWEEP — DO NOT MODIFY THESE PARAMETERS!
		        // These values were painstakingly tuned over 25+ iterations.
		        // The waveform shape, speed, frequency, direction, and fade
		        // values are ALL intentional and interdependent. Changing any
		        // single value will break the carefully balanced look.
		        // DO NOT use gl_FragCoord / screen-space approaches here.
		        // DO NOT change the trapezoid waveform shape.
		        // DO NOT adjust speed/frequency/fade without explicit approval.
		        // ============================================================
		        case 9:  {
		            // Shadow sweep with cross-texture continuity fix.
		            // Uses irisW_continuousSweepUV for atlas textures (raw atlas UV * scale),
		            // which is continuous across all sprites/draw calls — no boundary artifacts.
		            // Falls back to eUV for dedicated textures (armor) where it works fine.
		            float iW_freq9 = IRIS_WYNNCRAFT_GLINT_FREQ;
		            vec2 iW_sweepCoord9 = irisW_continuousSweepUV(iW_uv, iW_midTex, iW_texSize, iW_eUV);
		            // Atlas (weapons): straight sweep, freq=0.125, speed=5, short fade.
		            // Dedicated (armor): angled sweep, freq=0.5*base, speed=1, longer fade.
		            vec2 iW_dir9 = iW_isAtlas ? vec2(0.3, 0.0) : vec2(0.3, -0.07);
		            float iW_speed9 = iW_isAtlas ? 5.0 : 1.0;
		            float iW_effFreq9 = iW_isAtlas ? 0.125 : iW_freq9 * 0.5;
		            float iW_x9 = dot(iW_dir9, iW_sweepCoord9) - iW_time * iW_speed9;
		            // Trapezoid waveform: razor-sharp leading edge, short flat top,
		            // smooth trailing fade, long gap. Phase inverted (1-fract) so
		            // the sharp edge leads in the sweep direction.
		            float iW_phase9 = 1.0 - fract(iW_x9 * iW_effFreq9);
		            float iW_fadeEnd9 = iW_isAtlas ? 0.20 : 0.30;
		            float iW_fadeStart9 = iW_isAtlas ? 0.035 : 0.08;
		            float iW_wave9 = smoothstep(0.0, 0.001, iW_phase9) * (1.0 - smoothstep(iW_fadeStart9, iW_fadeEnd9, iW_phase9));
		            float iW_shine9 = 1.0 - iW_wave9 * 0.9;
		            iW_out.rgb = iW_tex.rgb * iW_shine9; break;
		        }
		        case 10: {
		            // Aurora effect — uses model-space radial UV for atlas (continuous across
		            // body parts and multi-texture items), eUV*64 for dedicated textures.
		            vec2 iW_aUV = iW_rUV;
		            float iW_r = length(iW_aUV);
		            vec2 iW_pa = sin(iW_aUV * iW_r);
		            iW_pa = irisW_rotate(iW_pa, -cos(iW_r * 5.0 + iW_time * 10.0));
		            float iW_di = length(exp(-iW_pa * iW_pa));
		            iW_di = irisW_smoothen(length(iW_pa), iW_di, 0.9);
		            vec4 iW_auroraColor = sin(iW_di * vec4(4.0, 3.0, 2.0, 1.0)) * 0.5 + 0.5;
		            iW_out.rgb = irisW_blend(iW_tex, iW_auroraColor, 0.5);
		            break;
		        }
		        case 11: {
		            // Reflection effect with cross-texture continuity fix
		            vec2 iW_contUV11 = irisW_continuousSweepUV(iW_uv, iW_midTex, iW_texSize, iW_eUV);
		            vec2 iW_ru = iW_contUV11 / 8.0;
		            float iW_bd = sin((iW_ru.x + iW_ru.y + iW_time) * 10.0) * 0.5 + 0.5;
		            iW_out.rgb = iW_tex.rgb + vec3(smoothstep(0.7, 1.0, iW_bd) * 0.4);
		            break;
		        }
		        case 12: {
		            // Plasma effect with cross-texture continuity fix
		            vec2 iW_contUV12 = irisW_continuousSweepUV(iW_uv, iW_midTex, iW_texSize, iW_eUV);
		            vec2 iW_pu = iW_contUV12 * 4.0; float iW_pt = iW_time * 2.0;
		            float iW_po = 0.1 + cos(iW_pu.y + sin(0.15 - iW_pt)) + iW_pt;
		            float iW_pd = 0.9 + sin(iW_pu.x + cos(0.65 + iW_pt)) - iW_pt;
		            float iW_pp = 8.0 * cos(length(iW_pu) + iW_pd) * sin(iW_po - iW_pd);
		            iW_out.rgb = irisW_blend(iW_tex, -sin(iW_pp + vec4(0.5, 0.7, 0.8, 1.0)), 0.1);
		            break;
		        }
		        case 13: {
		            // Distort effect — perpendicular sine waves for X and Y
		            float iW_pixelSize13 = 1.0 / max(iW_texSize.x, iW_texSize.y);
		            // Amplitude modulated by slow beat — occasionally drops to near-zero
		            float iW_beat13 = 0.3 + 0.7 * abs(sin(iW_time * 0.7));
		            float iW_doX = sin(iW_eUV.y * 40.0 + iW_time * 8.0) * 0.5;
		            float iW_doY = sin(iW_eUV.x * 40.0 + iW_time * 10.0) * 0.5;
		            vec2 iW_do = vec2(iW_doX, iW_doY) * iW_pixelSize13 * iW_beat13;
		            vec4 iW_dc = texture(Sampler0, iW_uv + iW_do);
		            iW_out.rgb = irisW_blend(iW_tex, iW_dc, iW_dc.a); break;
		        }
		        case 14: {
		            // Chrome effect with cross-texture continuity fix
		            vec2 iW_contUV14 = irisW_continuousSweepUV(iW_uv, iW_midTex, iW_texSize, iW_eUV);
		            vec2 iW_cu = iW_contUV14 * 0.3; float iW_ct = iW_time * 4.0;
		            vec4 iW_cc = vec4(0.5 + 0.5 * sin(10.0 * iW_cu.x + iW_ct),
		                              0.5 + 0.5 * sin(10.0 * iW_cu.y + iW_ct + 1.0),
		                              0.5 + 0.5 * sin(10.0 * (iW_cu.x + iW_cu.y) + iW_ct + 2.0), 1.0);
		            iW_out.rgb = irisW_blend(iW_tex, pow(iW_cc, vec4(2.0)), 0.3);
		            break;
		        }
		        case 15: { iW_out = irisW_tintEffect(irisW_rgb(80,  130, 230), iW_tex); break; }
		        case 16: { iW_out = irisW_tintEffect(irisW_rgb(30,  230, 130), iW_tex); break; }
		        case 17: { iW_out = irisW_tintEffect(irisW_rgb(235, 70,  70 ), iW_tex); break; }
		        case 18: { iW_out = irisW_tintEffect(irisW_rgb(100, 190, 190), iW_tex); break; }
		        case 19: { iW_out = irisW_tintEffect(irisW_rgb(250, 120, 20 ), iW_tex); break; }
		        case 20: { iW_out = irisW_tintEffect(irisW_rgb(50,  50,  50 ), iW_tex); break; }
		        case 21: { iW_out = irisW_tintEffect(irisW_rgb(250, 230, 230), iW_tex); break; }
		        case 22: { iW_out = irisW_tintEffect(irisW_rgb(255, 150, 200), iW_tex); break; }
		        case 23: { iW_out = irisW_tintEffect(irisW_rgb(200, 60,  230), iW_tex); break; }
		        case 24: { iW_out = irisW_tintEffect(irisW_rgb(240, 240, 80 ), iW_tex); break; }
		        case 25: { iW_out = irisW_shiny(irisW_rgb(255, 255, 255), 0.4, 1.0, iW_sUV, iW_time, iW_tex); break; }
		        case 26: { iW_out = irisW_shiny(irisW_rgb(85,  255, 85 ), 0.4, 1.0, iW_sUV, iW_time, iW_tex); break; }
		        case 27: { iW_out = irisW_shiny(irisW_rgb(255, 255, 85 ), 0.4, 1.0, iW_sUV, iW_time, iW_tex); break; }
		        case 28: { iW_out = irisW_shiny(irisW_rgb(255, 85,  255), 0.4, 1.0, iW_sUV, iW_time, iW_tex); break; }
		        case 29: { iW_out = irisW_shiny(irisW_rgb(85,  255, 255), 0.4, 1.0, iW_sUV, iW_time, iW_tex); break; }
		        case 30: { iW_out = irisW_shiny(irisW_rgb(255, 85,  85 ), 0.4, 1.0, iW_sUV, iW_time, iW_tex); break; }
		        case 31: { iW_out = irisW_shiny(irisW_rgb(170, 0,   170), 0.4, 1.0, iW_sUV, iW_time, iW_tex); break; }
		    }
		    if (iW_applyLighting) {
		        float iW_texLuma = max(dot(iW_tex.rgb, vec3(0.2126, 0.7152, 0.0722)), 0.001);
		        float iW_inLuma = dot(iW_in.rgb, vec3(0.2126, 0.7152, 0.0722));
		        iW_out.rgb *= min(iW_inLuma / iW_texLuma * 1.1, 1.0);
		    }
		    return iW_out;
		}
		""";

	private static final AutoHintedMatcher<ExternalDeclaration> uniformVec4EntityColor = new AutoHintedMatcher<>(
		"uniform vec4 entityColor;", ParseShape.EXTERNAL_DECLARATION);

	private static final AutoHintedMatcher<ExternalDeclaration> uniformIntEntityId = new AutoHintedMatcher<>(
		"uniform int entityId;", ParseShape.EXTERNAL_DECLARATION);

	private static final AutoHintedMatcher<ExternalDeclaration> uniformIntBlockEntityId = new AutoHintedMatcher<>(
		"uniform int blockEntityId;", ParseShape.EXTERNAL_DECLARATION);

	private static final AutoHintedMatcher<ExternalDeclaration> uniformIntCurrentRenderedItemId = new AutoHintedMatcher<>(
		"uniform int currentRenderedItemId;", ParseShape.EXTERNAL_DECLARATION);

	// Add entity color -> overlay color attribute support.
	public static void patchOverlayColor(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		VanillaParameters parameters) {
		// delete original declaration
		root.processMatches(t, uniformVec4EntityColor, ASTNode::detachAndDelete);

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			// add our own declarations
			// TODO: We're exposing entityColor to this stage even if it isn't declared in
			// this stage. But this is needed for the pass-through behavior.
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform sampler2D iris_overlay;",
				"out vec4 entityColor;",
				"out vec4 iris_vertexColor;",
				"flat out int iris_wynncraft_glint;",
				"out vec2 iris_wynncraft_texcoord;",
				"out vec2 iris_wynncraft_midtex;",
				parameters.inputs.isIE() ? "uniform ivec2 iris_OverlayUV;" : "in ivec2 iris_UV1;");

			// Create our own main function to wrap the existing main function, so that we
			// can pass through the overlay color at the end to the geometry or fragment
			// stage.
			// Detect Wynncraft glint signal: vertex Color with G≈1.0, B≈0.0, R∈(0,1) encodes
			// glint ID 1-31. Neutralize to white so the shader pack does not see the raw signal.
			boolean hasMidTexCoord = root.identifierIndex.has("mc_midTexCoord");
			tree.prependMainFunctionBody(t,
				"vec4 overlayColor = texelFetch(iris_overlay, " + (parameters.inputs.isIE() ? "iris_OverlayUV" : "iris_UV1") + ", 0);",
				"entityColor = vec4(overlayColor.rgb, 1.0 - overlayColor.a);",
				IRISW_SIGNAL_DETECT,
				"iris_wynncraft_glint = iris_wynn_isSignal ? int(round(iris_Color.r * 255.0)) : 0;",
				"iris_wynncraft_texcoord = iris_UV0;",
				hasMidTexCoord ? "iris_wynncraft_midtex = mc_midTexCoord.xy;" : "iris_wynncraft_midtex = vec2(0.0);",
				"iris_vertexColor = iris_wynn_isSignal ? vec4(1.0) : iris_Color;",
				// Workaround for a shader pack bug:
				// https://github.com/IrisShaders/Iris/issues/1549
				// Some shader packs incorrectly ignore the alpha value, and assume that rgb
				// will be zero if there is no hit flash, we try to emulate that here
				"entityColor.rgb *= float(entityColor.a != 0.0);");
		} else if (parameters.type.glShaderType == ShaderType.TESSELATION_CONTROL) {
			// replace read references to grab the color from the first vertex.
			root.replaceReferenceExpressions(t, "entityColor", "entityColor[gl_InvocationID]");

			// TODO: this is passthrough behavior
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"patch out vec4 entityColorTCS;",
				"in vec4 entityColor[];",
				"out vec4 iris_vertexColorTCS[];",
				"in vec4 iris_vertexColor[];",
				"flat in int iris_wynncraft_glint[];",
				"flat out int iris_wynncraft_glintTCS[];",
				"in vec2 iris_wynncraft_texcoord[];",
				"out vec2 iris_wynncraft_texcoordTCS[];",
				"in vec2 iris_wynncraft_midtex[];",
				"out vec2 iris_wynncraft_midtexTCS[];");
			tree.prependMainFunctionBody(t,
				"entityColorTCS = entityColor[gl_InvocationID];",
				"iris_vertexColorTCS[gl_InvocationID] = iris_vertexColor[gl_InvocationID];",
				"iris_wynncraft_glintTCS[gl_InvocationID] = iris_wynncraft_glint[gl_InvocationID];",
				"iris_wynncraft_texcoordTCS[gl_InvocationID] = iris_wynncraft_texcoord[gl_InvocationID];",
				"iris_wynncraft_midtexTCS[gl_InvocationID] = iris_wynncraft_midtex[gl_InvocationID];");
		} else if (parameters.type.glShaderType == ShaderType.TESSELATION_EVAL) {
			// replace read references to grab the color from the first vertex.
			root.replaceReferenceExpressions(t, "entityColor", "entityColorTCS");

			// TODO: this is passthrough behavior
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"out vec4 entityColorTES;",
				"patch in vec4 entityColorTCS;",
				"out vec4 iris_vertexColorTES;",
				"in vec4 iris_vertexColorTCS[];",
				"flat in int iris_wynncraft_glintTCS[];",
				"flat out int iris_wynncraft_glintTES;",
				"in vec2 iris_wynncraft_texcoordTCS[];",
				"out vec2 iris_wynncraft_texcoordTES;",
				"in vec2 iris_wynncraft_midtexTCS[];",
				"out vec2 iris_wynncraft_midtexTES;");
			tree.prependMainFunctionBody(t,
				"entityColorTES = entityColorTCS;",
				"iris_vertexColorTES = iris_vertexColorTCS[0];",
				"iris_wynncraft_glintTES = iris_wynncraft_glintTCS[0];",
				"iris_wynncraft_texcoordTES = iris_wynncraft_texcoordTCS[0];",
				"iris_wynncraft_midtexTES = iris_wynncraft_midtexTCS[0];");
		} else if (parameters.type.glShaderType == ShaderType.GEOMETRY) {
			// replace read references to grab the color from the first vertex.
			root.replaceReferenceExpressions(t, "entityColor", "entityColor[0]");

			// TODO: this is passthrough behavior
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"out vec4 entityColorGS;",
				"in vec4 entityColor[];",
				"out vec4 iris_vertexColorGS;",
				"in vec4 iris_vertexColor[];",
				"flat in int iris_wynncraft_glint[];",
				"flat out int iris_wynncraft_glintGS;",
				"in vec2 iris_wynncraft_texcoord[];",
				"out vec2 iris_wynncraft_texcoordGS;",
				"in vec2 iris_wynncraft_midtex[];",
				"out vec2 iris_wynncraft_midtexGS;");
			tree.prependMainFunctionBody(t,
				"entityColorGS = entityColor[0];",
				"iris_vertexColorGS = iris_vertexColor[0];",
				"iris_wynncraft_glintGS = iris_wynncraft_glint[0];",
				"iris_wynncraft_texcoordGS = iris_wynncraft_texcoord[0];",
				"iris_wynncraft_midtexGS = iris_wynncraft_midtex[0];");

			if (parameters.hasTesselation) {
				root.rename("iris_vertexColor", "iris_vertexColorTES");
				root.rename("entityColor", "entityColorTES");
				root.rename("iris_wynncraft_glint", "iris_wynncraft_glintTES");
				root.rename("iris_wynncraft_texcoord", "iris_wynncraft_texcoordTES");
				root.rename("iris_wynncraft_midtex", "iris_wynncraft_midtexTES");
			}
		} else if (parameters.type.glShaderType == ShaderType.FRAGMENT) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"in vec4 entityColor;", "in vec4 iris_vertexColor;",
				"flat in int iris_wynncraft_glint;",
				"in vec2 iris_wynncraft_texcoord;",
				"in vec2 iris_wynncraft_midtex;");

			tree.prependMainFunctionBody(t, "float iris_vertexColorAlpha = iris_vertexColor.a;");

			// Inject Sampler0 if not already declared (needed by glint effects to sample entity texture).
			// Entity textures are always on texture unit 0; Sampler0 is the conventional name.
			if (!root.identifierIndex.has("Sampler0")) {
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform sampler2D Sampler0;");
			}

			// Inject Wynncraft glint GLSL helpers and apply function.
			// Use BEFORE_FUNCTIONS so they land after all uniform/varying declarations.
			// (BEFORE_DECLARATIONS pushes functions before uniforms, breaking GLSL compilers
			// that require declarations before use in function bodies.)
			// Inject apply function FIRST so that when helpers are inserted at BEFORE_FUNCTIONS
			// they end up before the apply function (each addAll goes to first-FunctionDef index).
			// Hardcoded frequency: 2.0 (was configurable via wyncraftGlintFreq slider, now removed)
			String glintFunc = IRISW_APPLY_GLINT_FUNC.replace("IRIS_WYNNCRAFT_GLINT_FREQ", "2.00");
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS, glintFunc);
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS, IRISW_HELPERS);

			// Apply glint effects after the shader pack's main() runs, modifying iris_FragData0.
			// EFFECT_UV is adapted from Wynncraft's entity formula: (uv - 1.0) * (texW / texH, 1.0)
			tree.appendMainFunctionBody(t, """
				if (iris_wynncraft_glint != 0) {
				    vec2 irisW_texSize = vec2(textureSize(Sampler0, 0));
				    bool irisW_isAtlas = max(irisW_texSize.x, irisW_texSize.y) > 2000.0;
				    vec2 irisW_uv = iris_wynncraft_texcoord;
				    float irisW_time = iris_globalInfo.GameTime * 300.0;
				    vec4 irisW_tex = texture(Sampler0, irisW_uv);
				    vec2 irisW_eUV;
				    if (irisW_isAtlas) {
				        // True atlas texture (Minecraft item/block atlas is always >= 2048px):
				        // UV is a tiny sub-region, so recover per-sprite [0,1] UV via fract.
				        vec2 iW_spriteUV = fract(irisW_uv * irisW_texSize / 16.0);
				        irisW_eUV = (iW_spriteUV - 1.0) * vec2(irisW_texSize.x / irisW_texSize.y, 1.0) / 5.0;
				    } else {
				        // Dedicated texture (armor): UV spans [0,1] so UV-derived formulas give spatial variation.
				        irisW_eUV = (irisW_uv - 1.0) * vec2(irisW_texSize.x / irisW_texSize.y, 1.0);
				    }
				    // Screen-space sparkle UV (shared across atlas/dedicated)
				    vec2 iW_dU = max(abs(dFdx(irisW_uv)), abs(dFdy(irisW_uv)));
				    vec2 iW_uvRate = max(iW_dU, vec2(1e-6));
				    vec2 irisW_sUV = fract(irisW_uv / (iW_uvRate * 50.0)) * 4.0;
				    // Radial UV: centered continuousSweepUV for radial effects
				    vec2 irisW_sweepFull = irisW_continuousSweepUV(irisW_uv, iris_wynncraft_midtex, irisW_texSize, irisW_eUV);
				    vec2 irisW_sweepMid = irisW_continuousSweepUV(iris_wynncraft_midtex, iris_wynncraft_midtex, irisW_texSize, vec2(0.5));
				    vec2 irisW_rUV = (irisW_sweepFull - irisW_sweepMid) * 0.25 + vec2(8.0);
				    int irisW_effectId = iris_wynncraft_glint & 31;
				    iris_FragData0 = irisW_applyGlint(irisW_effectId, irisW_uv, irisW_eUV, irisW_sUV, iris_wynncraft_midtex, irisW_rUV, irisW_texSize, irisW_isAtlas, irisW_time, irisW_tex, iris_FragData0);
				}
				""");

			// Different output name to avoid a name collision in the geometry or tessellation stage.
			if (parameters.hasGeometry) {
				root.rename("entityColor", "entityColorGS");
				root.rename("iris_vertexColor", "iris_vertexColorGS");
				root.rename("iris_wynncraft_glint", "iris_wynncraft_glintGS");
				root.rename("iris_wynncraft_texcoord", "iris_wynncraft_texcoordGS");
				root.rename("iris_wynncraft_midtex", "iris_wynncraft_midtexGS");
			} else if (parameters.hasTesselation) {
				root.rename("entityColor", "entityColorTES");
				root.rename("iris_vertexColor", "iris_vertexColorTES");
				root.rename("iris_wynncraft_glint", "iris_wynncraft_glintTES");
				root.rename("iris_wynncraft_texcoord", "iris_wynncraft_texcoordTES");
				root.rename("iris_wynncraft_midtex", "iris_wynncraft_midtexTES");
			}
		}
	}

	public static void patchEntityId(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		VanillaParameters parameters) {
		// delete original declaration
		root.processMatches(t, uniformIntEntityId, ASTNode::detachAndDelete);
		root.processMatches(t, uniformIntBlockEntityId, ASTNode::detachAndDelete);
		root.processMatches(t, uniformIntCurrentRenderedItemId, ASTNode::detachAndDelete);

		if (parameters.type.glShaderType == ShaderType.GEOMETRY) {
			root.replaceReferenceExpressions(t, "entityId",
				"iris_entityInfo[0].x");

			root.replaceReferenceExpressions(t, "blockEntityId",
				"iris_entityInfo[0].y");

			root.replaceReferenceExpressions(t, "currentRenderedItemId",
				"iris_entityInfo[0].z");
		} else {
			root.replaceReferenceExpressions(t, "entityId",
				"iris_entityInfo.x");

			root.replaceReferenceExpressions(t, "blockEntityId",
				"iris_entityInfo.y");

			root.replaceReferenceExpressions(t, "currentRenderedItemId",
				"iris_entityInfo.z");
		}

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			// add our own declarations
			// TODO: We're exposing entityColor to this stage even if it isn't declared in
			// this stage. But this is needed for the pass-through behavior.
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat out ivec3 iris_entityInfo;",
				"in ivec3 iris_Entity;");

			// Create our own main function to wrap the existing main function, so that we
			// can pass through the overlay color at the end to the geometry or fragment
			// stage.
			tree.prependMainFunctionBody(t,
				"iris_entityInfo = iris_Entity;");
		} else if (parameters.type.glShaderType == ShaderType.TESSELATION_CONTROL) {
			// TODO: this is passthrough behavior
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat out ivec3 iris_entityInfoTCS[];",
				"flat in ivec3 iris_entityInfo[];");
			root.replaceReferenceExpressions(t, "iris_entityInfo", "iris_EntityInfo[gl_InvocationID]");

			tree.prependMainFunctionBody(t,
				"iris_entityInfoTCS[gl_InvocationID] = iris_entityInfo[gl_InvocationID];");
		} else if (parameters.type.glShaderType == ShaderType.TESSELATION_EVAL) {
			// TODO: this is passthrough behavior
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat out ivec3 iris_entityInfoTES;",
				"flat in ivec3 iris_entityInfoTCS[];");
			tree.prependMainFunctionBody(t,
				"iris_entityInfoTES = iris_entityInfoTCS[0];");

			root.replaceReferenceExpressions(t, "iris_entityInfo", "iris_EntityInfoTCS[0]");

		} else if (parameters.type.glShaderType == ShaderType.GEOMETRY) {
			// TODO: this is passthrough behavior
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat out ivec3 iris_entityInfoGS;",
				"flat in ivec3 iris_entityInfo" + (parameters.hasTesselation ? "TES" : "") + "[];");
			tree.prependMainFunctionBody(t,
				"iris_entityInfoGS = iris_entityInfo" + (parameters.hasTesselation ? "TES" : "") + "[0];");
		} else if (parameters.type.glShaderType == ShaderType.FRAGMENT) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat in ivec3 iris_entityInfo;");

			// Different output name to avoid a name collision in the geometry shader.
			if (parameters.hasGeometry) {
				root.rename("iris_entityInfo", "iris_EntityInfoGS");
			} else if (parameters.hasTesselation) {
				root.rename("iris_entityInfo", "iris_entityInfoTES");
			}
		}
	}
}
