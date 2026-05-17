package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.data.ChildNodeList;
import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.ArrayAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.selection.SelectionStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DiscardStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.parser.ParseShape;
import io.github.douira.glsl_transformer.util.Type;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.VanillaParameters;

import java.util.Collection;


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

	// Wynncraft glint signal: vertex Color with G=255/255 (1.0), B=0, R encodes glint ID 1-32.
	// Threshold G>0.998 distinguishes from translucency signal (G=254/255≈0.996).
	// Lower bound on R (> 0.002) ensures the decoded ID is at least 1.
	// Upper bound on R (< 0.13) caps at ID ~33 — valid glint IDs are 1-32.
	// This rejects VFX display entities whose vertex colors happen to have high G / low B
	// but R values well above the glint range.
	private static final String IRISW_SIGNAL_DETECT =
		"bool iris_wynn_isSignal = (iris_Color.g > 0.998 && iris_Color.b < 0.01 && iris_Color.r > 0.002 && iris_Color.r < 0.13);";

	// Wynncraft translucency signal: vertex Color with G=254/255 (≈0.996), B=0, R in (0,1) encodes
	// translucency level. R*255 gives the translucency value (0-100+), applied as alpha reduction
	// in the fragment shader: alpha = mix(alpha, 0.0, translucent/100.0).
	// G range (0.994, 0.998) uniquely matches G=254/255 without overlapping glint (G=255/255).
	private static final String IRISW_TRANSLUCENCY_DETECT =
		"bool iris_wynn_isTranslucent = (iris_Color.g > 0.994 && iris_Color.g < 0.998 && iris_Color.b < 0.01 && iris_Color.r > 0.002 && iris_Color.r < 0.998);";

	// ====================================================================================
	// WYNNCRAFT SKYBOX RENDERING (GLSL injection mirroring vanilla RP)
	// ====================================================================================
	// Skybox signal is TEXTURE-based (not vertex-color): textureColor.g ≈ 251/255 and
	// textureColor.a ≈ 254/255. Blue channel encodes effect variant ID (1-7).
	// Instead of discarding skybox entities, we replace their fragment color with
	// procedural skybox effects — exactly how the vanilla Wynncraft resource pack works.
	// CPU-side detection (ItemStackStateLayerMixin) still runs for fog/sky color state.

	// Signal decode helper — shared by forward apply, deferred apply, and deferred fallback.
	// Returns skybox ID (1-7) or 0 if no match.
	private static final String IRISW_SKYBOX_SIGNAL_HELPER = """
		int irisW_skyboxSignal(sampler2D tex, vec2 uv) {
		    vec4 sc = texture(tex, uv);
		    int sg = int(round(sc.g * 255.0));
		    int sa = int(round(sc.a * 255.0));
		    if (sg == 251 && sa == 254) {
		        int sid = int(round(sc.b * 255.0));
		        if (sid >= 1 && sid <= 7) return sid;
		    }
		    return 0;
		}""";

	private static final String IRISW_EMISSIVE_SIGNAL_HELPER = """
		bool irisW_isEmissiveSignal(vec4 color) {
		    int ia = int(round(color.a * 255.0));
		    int ig = int(round(color.g * 255.0));
		    return ia == 254 && ig != 251;
		}""";

	// Forward path: detect skybox signal and apply procedural effect to FRAG_OUTPUT.
	// Sets irisW_skyboxApplied flag to prevent subsequent glint/translucency/boost from running.
	// FRAG_OUTPUT is replaced with the actual variable name at injection time.
	private static final String IRISW_SKYBOX_APPLY_FORWARD = """
		{
		    int irisW_skyId = irisW_skyboxSignal(Sampler0, iris_wynncraft_texcoord);
		    if (irisW_skyId > 0) {
		        if (irisW_skyId == iris_wynncraftPrimarySkyboxId) {
		            discard;
		        }
		        float irisW_skyTime = fract(iris_globalInfo.GameTime) * 12000.0;
		        vec3 irisW_skyDir = normalize(iris_wynncraft_position);
		        vec4 irisW_skyColor = irisW_applySkybox(irisW_skyId, irisW_skyTime, irisW_skyDir);
		        FRAG_OUTPUT = irisW_skyColor;
		        irisW_skyboxApplied = true;
		    }
		}""";

	// Forward path (premultiplied output): same as above but premultiplies RGB by alpha.
	private static final String IRISW_SKYBOX_APPLY_FORWARD_PREMUL = """
		{
		    int irisW_skyId = irisW_skyboxSignal(Sampler0, iris_wynncraft_texcoord);
		    if (irisW_skyId > 0) {
		        if (irisW_skyId == iris_wynncraftPrimarySkyboxId) {
		            discard;
		        }
		        float irisW_skyTime = fract(iris_globalInfo.GameTime) * 12000.0;
		        vec3 irisW_skyDir = normalize(iris_wynncraft_position);
		        vec4 irisW_skyColor = irisW_applySkybox(irisW_skyId, irisW_skyTime, irisW_skyDir);
		        FRAG_OUTPUT = vec4(irisW_skyColor.rgb * irisW_skyColor.a, irisW_skyColor.a);
		        irisW_skyboxApplied = true;
		    }
		}""";

	// Deferred path: detect skybox signal from the ALBEDO VARIABLE (already sampled by the pack)
	// instead of resampling via Sampler0. Packs like Photon use `gtexture` (not Sampler0) for
	// entity textures — an injected Sampler0 may not be bound to the correct texture unit.
	// At the overlay anchor, albedo = texture(packSampler, uv) * tint. For skybox entities the
	// vertex color is neutralized to white, so albedo ≈ raw texture with signal intact.
	// ALBEDO_VAR is replaced with the actual variable name at injection time.
	private static final String IRISW_SKYBOX_APPLY_DEFERRED = """
		{
		    int irisW_sg = int(round(ALBEDO_VAR.g * 255.0));
		    int irisW_sa = int(round(ALBEDO_VAR.a * 255.0));
		    int irisW_skyId = 0;
		    if (irisW_sg == 251 && irisW_sa == 254) {
		        irisW_skyId = int(round(ALBEDO_VAR.b * 255.0));
		        if (irisW_skyId < 1 || irisW_skyId > 7) irisW_skyId = 0;
		    }
		    if (irisW_skyId > 0) {
		        if (irisW_skyId == iris_wynncraftPrimarySkyboxId) {
		            discard;
		        }
		        float irisW_skyTime = fract(iris_globalInfo.GameTime) * 12000.0;
		        vec3 irisW_skyDir = normalize(iris_wynncraft_position);
		        vec4 irisW_skyColor = irisW_applySkybox(irisW_skyId, irisW_skyTime, irisW_skyDir);
		        ALBEDO_VAR = irisW_skyColor;
		        irisW_skyboxApplied = true;
		    }
		}""";

	// Deferred fallback: discard skybox entities when no anchors found (prepended near top of main).
	// Also uses albedo-based detection instead of Sampler0.
	private static final String IRISW_SKYBOX_FALLBACK_DISCARD = """
		{
		    int irisW_skyFB = irisW_skyboxSignal(Sampler0, iris_wynncraft_texcoord);
		    if (irisW_skyFB > 0) discard;
		}""";

	// Skybox GLSL helpers — noise functions and procedural generators needed by the
	// skybox effects but not by glint effects. Injected via BEFORE_FUNCTIONS.
	private static final String[] IRISW_SKYBOX_HELPERS = {
		// fbm(vec2) — 5-octave fractional Brownian motion (ported from RP util.glsl)
		"""
		float irisW_fbm(vec2 p) {
		    float v = 0.0, a = 0.5, freq = 1.0;
		    for (int i = 0; i < 5; i++) {
		        v += a * irisW_smoothNoise(p * freq);
		        freq *= 2.0; a *= 0.5;
		    }
		    return v;
		}""",
		// fbm(vec3) — combines three 2D fbm samples (ported from RP util.glsl)
		"""
		float irisW_fbm(vec3 p) {
		    return irisW_fbm(p.xy) + irisW_fbm(p.yz) + irisW_fbm(p.zx);
		}""",
		// rotateAxis — Rodrigues rotation formula (ported from RP util.glsl)
		"""
		vec3 irisW_rotateAxis(vec3 v, vec3 axis, float angle) {
		    return mix(dot(v, axis) * axis, v, cos(angle)) + cross(axis, v) * sin(angle);
		}""",
		// crystalNoise — 8-iteration crystalline noise (ported from RP util.glsl)
		"""
		float irisW_crystalNoise(vec3 position, float time) {
		    const float IRISW_PI = 3.14159265359;
		    const float IRISW_TAU = IRISW_PI * 2.0;
		    int iterations = 8;
		    float start = 2.20, expand = 1.20, edgeThickness = 0.25;
		    vec3 axis1 = vec3(0.8506, 0.5257, 0.0);
		    vec3 axis2 = vec3(0.0, 0.5257, 0.8506);
		    float angle1 = IRISW_PI / 7.0, angle2 = IRISW_PI / 27.0;
		    float expand1 = 1.0, expand2 = 1.25;
		    float centralise = 0.5, dampen = 1.8;
		    float n = 0.0, scale = start;
		    vec3 travel1 = position;
		    vec3 travel2 = abs(fract(position) - 0.5) * 0.15;
		    for (int i = 0; i < iterations; i++) {
		        travel1 = irisW_rotateAxis(travel1, axis1, angle1);
		        travel1 *= expand1;
		        vec3 pt = cos(travel1 * scale + travel2 + time);
		        n += sin(IRISW_TAU * dot(pt, vec3(0.3))) * 0.5 + 0.5;
		        scale *= expand;
		        travel2 += cos(smoothstep(0.0, edgeThickness, pt));
		        travel2 = irisW_rotateAxis(travel2, axis2, angle2);
		        travel2 *= expand2;
		    }
		    n = n / float(iterations);
		    n = n * 2.0 - 1.0;
		    n = pow(abs(n), centralise) * sign(n);
		    n = n * 0.5 + 0.5;
		    n = pow(n, dampen);
		    return n;
		}""",
		// lightningBolt — procedural bolt with branches (ported from RP skybox.glsl)
		"""
		float irisW_lightningBolt(vec2 uv, vec2 start, vec2 end, float seed, float width) {
		    vec2 dir = end - start;
		    float len = length(dir);
		    vec2 norm = dir / len;
		    vec2 perp = vec2(-norm.y, norm.x);
		    vec2 toPoint = uv - start;
		    float t = clamp(dot(toPoint, norm) / len, 0.0, 1.0);
		    float across = dot(toPoint, perp);
		    float disp = 0.0;
		    disp += 0.06 * sin(t * 8.0 + seed * 3.7) * smoothstep(0.0, 0.3, t) * smoothstep(1.0, 0.7, t);
		    disp += 0.03 * sin(t * 17.0 + seed * 7.1);
		    disp += 0.015 * sin(t * 31.0 + seed * 11.3);
		    disp += 0.008 * sin(t * 61.0 + seed * 19.7);
		    float dist = abs(across - disp);
		    float core = smoothstep(width * 0.5, 0.0, dist);
		    float glow1 = smoothstep(width * 3.0, 0.0, dist) * 0.6;
		    float glow2 = smoothstep(width * 8.0, 0.0, dist) * 0.2;
		    float bolt = (core + glow1 + glow2) * step(0.0, t) * step(t, 1.0);
		    float branch = 0.0;
		    float bt1 = 0.4 + 0.2 * fract(seed * 1.618);
		    vec2 branchPt1 = start + dir * bt1 + perp * disp;
		    vec2 branchEnd1 = branchPt1 + vec2(0.08, -0.12) + vec2(fract(seed * 2.71) * 0.1 - 0.05, 0.0);
		    { vec2 bd = branchEnd1 - branchPt1; float bl = length(bd); vec2 bn = bd / bl; vec2 bp = vec2(-bn.y, bn.x);
		      vec2 tp = uv - branchPt1; float bt = clamp(dot(tp, bn) / bl, 0.0, 1.0); float ba = dot(tp, bp);
		      float bd2 = 0.04 * sin(bt * 12.0 + seed * 5.3); float bd3 = abs(ba - bd2);
		      branch += smoothstep(width * 0.3, 0.0, bd3) * step(0.0, bt) * step(bt, 1.0);
		      branch += smoothstep(width * 2.0, 0.0, bd3) * 0.4 * step(0.0, bt) * step(bt, 1.0); }
		    float bt2 = 0.65 + 0.15 * fract(seed * 2.414);
		    vec2 branchPt2 = start + dir * bt2 + perp * disp;
		    vec2 branchEnd2 = branchPt2 + vec2(-0.10, -0.09) + vec2(fract(seed * 1.41) * 0.08 - 0.04, 0.0);
		    { vec2 bd = branchEnd2 - branchPt2; float bl = length(bd); vec2 bn = bd / bl; vec2 bp = vec2(-bn.y, bn.x);
		      vec2 tp = uv - branchPt2; float bt = clamp(dot(tp, bn) / bl, 0.0, 1.0); float ba = dot(tp, bp);
		      float bd2 = 0.03 * sin(bt * 15.0 + seed * 8.1); float bd3 = abs(ba - bd2);
		      branch += smoothstep(width * 0.25, 0.0, bd3) * step(0.0, bt) * step(bt, 1.0);
		      branch += smoothstep(width * 2.0, 0.0, bd3) * 0.35 * step(0.0, bt) * step(bt, 1.0); }
		    return clamp(bolt + branch * 0.7, 0.0, 1.0);
		}""",
		// lightningFlash — flash timing envelope (ported from RP skybox.glsl)
		"""
		float irisW_lightningFlash(float time, float seed) {
		    float period = 35.0 + 25.0 * irisW_random(seed);
		    float phase = fract((time + seed * 37.3) / period);
		    float numFlashes = floor(irisW_random(seed + floor((time + seed * 37.3) / period) * 7.91) * 3.0) + 1.0;
		    float spacing = 0.012 + 0.018 * irisW_random(seed + 44.1);
		    float duration = 0.025 + 0.015 * irisW_random(seed + 88.3);
		    float result = 0.0;
		    for (int f = 0; f < 3; f++) {
		        if (float(f) >= numFlashes) break;
		        float offset = float(f) * spacing;
		        float brightness = pow(0.55, float(f));
		        result += brightness * smoothstep(0.0, 0.003, phase - offset) * smoothstep(duration + offset, duration + offset - 0.008, phase);
		    }
		    return clamp(result, 0.0, 1.0);
		}""",
		// distantCloudFlash — distant cloud illumination (ported from RP skybox.glsl)
		"""
		float irisW_distantCloudFlash(float time, float seed) {
		    float period = 40.0 + 80.0 * irisW_random(seed + 100.0);
		    float phase = fract((time + seed * 53.7) / period);
		    float duration = 0.08 + 0.06 * irisW_random(seed + 200.0);
		    float envelope = smoothstep(0.0, duration * 0.4, phase) * smoothstep(duration, duration * 0.6, phase);
		    return envelope * 0.18;
		}""",
	};

	// Main skybox apply function — dispatches to 7 procedural effects based on ID.
	// Ported from Wynncraft RP skybox.glsl + config/skybox.glsl.
	// Each effect returns vec4(color.rgb, alpha). Most return alpha=1.0;
	// Memory Fog (ID 2) uses partial alpha for transparency.
	private static final String IRISW_APPLY_SKYBOX_FUNC = """
		vec4 irisW_applySkybox(int id, float time, vec3 direction) {
		    const float IRISW_PI = 3.14159265359;
		    vec3 color = vec3(0.0);
		    float alpha = 1.0;

		    // Sub-function: red cloudy sky base (used by cases 3-5, 7)
		    // Inlined as a block to avoid needing a separate function declaration.
		    // After this block, color holds the red cloudy result.
		    // Cases that use it will call this macro-like pattern.

		    switch (id) {
		        case 1: {
		            // Memory Mist (RP skyboxMemoryMist)
		            vec3 mistColor = vec3(0.89, 0.91, 0.95);
		            float shiftS = 0.025 * time;
		            float mystifyA = irisW_fbm(direction + vec3(0.0, sin(shiftS), 0.0));
		            vec2 reMiss = vec2(0.8 * irisW_fbm(direction.xy + vec2(mystifyA, 0.1)),
		                               irisW_fbm(direction.yz - mystifyA));
		            float mystifyB = irisW_fbm(0.25 * (direction + vec3(0.0, atan(reMiss.x, reMiss.y) / IRISW_PI, 0.0)));
		            color = mix(vec3(-0.15), mistColor + vec3(0.25), mystifyB);
		            break;
		        }
		        case 2: {
		            // Memory Fog (RP skyboxMemoryFog) — uses alpha for transparency
		            vec3 fogColor = vec3(0.55, 0.5, 0.6);
		            float shiftS = -0.05 * time;
		            vec3 pos = direction + 0.25 * vec3(sin(shiftS), shiftS, cos(shiftS));
		            float noises = irisW_fbm(pos + vec3(0.2, 0.3, 0.2)) * 0.3;
		            float redir = direction.y + noises;
		            float q = (1.0 - redir * redir * 1.4) * 0.9;
		            float fogAlpha = 1.0 - smoothstep(0.0, 0.6, redir);
		            color = mix(vec3(-0.2), fogColor + vec3(0.2), q);
		            alpha = 0.85 * fogAlpha;
		            break;
		        }
		        case 3: {
		            // Stormy (RP skyboxStormy) — red cloudy base with dark horizon
		            vec3 valuationUpper = vec3(0.106, 0.358, 0.036);
		            vec3 colorHorizon = vec3(0.05, 0.05, 0.05);
		            float heightHorizon = 0.15, widthHorizon = 0.30, mixHorizon = 0.10;
		            // Inline redCloudy
		            float rcSpeed = 0.01, rcIntensity = 2.5, rcScale = 1.2;
		            vec3 rc1 = vec3(0.6, 0.0, 0.0), rc2 = vec3(1.0, 0.2, 0.0);
		            vec3 rc3 = vec3(0.0, 0.2, 0.0), rc4 = vec3(1.0, 0.6, 0.6);
		            vec3 rc5 = vec3(0.3, 0.3, 0.3), rc6 = vec3(1.2, 1.2, 1.2);
		            float rcShift = time * rcSpeed;
		            vec3 rcPos1 = direction * rcIntensity + vec3(0.0, rcShift, rcShift);
		            float rcNoise1 = irisW_fbm(rcScale * rcPos1);
		            vec2 rcPos2 = vec2(irisW_fbm(rcPos1.xy + rcNoise1), irisW_fbm(rcPos1.yz - rcNoise1));
		            float rcNoise2 = irisW_fbm(rcScale * (rcPos1 + vec3(rcPos2, 0.0)));
		            vec3 rcColor = mix(rc1, rc2, rcNoise2);
		            rcColor += mix(rc3, rc4, rcPos2.x);
		            rcColor -= mix(rc5, rc6, rcPos2.y);
		            rcColor = clamp(rcColor, 0.0, 1.0);
		            // Apply stormy horizon
		            vec3 colorUpperSky = vec3(dot(rcColor, valuationUpper));
		            float influenceUpper = smoothstep(heightHorizon - widthHorizon, heightHorizon, direction.y);
		            vec3 colorSky = mix(vec3(0.0), colorUpperSky, influenceUpper);
		            float influenceSky = mix(mixHorizon, 1.0, smoothstep(0.0, widthHorizon, abs(direction.y - heightHorizon)));
		            color = mix(colorHorizon, colorSky, influenceSky);
		            break;
		        }
		        case 4: {
		            // War Surface (RP skyboxWarSurface) — red cloudy fading to black at horizon
		            float heightHorizon = 0.5;
		            // Inline redCloudy
		            float rcSpeed = 0.01, rcIntensity = 2.5, rcScale = 1.2;
		            vec3 rc1 = vec3(0.6, 0.0, 0.0), rc2 = vec3(1.0, 0.2, 0.0);
		            vec3 rc3 = vec3(0.0, 0.2, 0.0), rc4 = vec3(1.0, 0.6, 0.6);
		            vec3 rc5 = vec3(0.3, 0.3, 0.3), rc6 = vec3(1.2, 1.2, 1.2);
		            float rcShift = time * rcSpeed;
		            vec3 rcPos1 = direction * rcIntensity + vec3(0.0, rcShift, rcShift);
		            float rcNoise1 = irisW_fbm(rcScale * rcPos1);
		            vec2 rcPos2 = vec2(irisW_fbm(rcPos1.xy + rcNoise1), irisW_fbm(rcPos1.yz - rcNoise1));
		            float rcNoise2 = irisW_fbm(rcScale * (rcPos1 + vec3(rcPos2, 0.0)));
		            vec3 rcColor = mix(rc1, rc2, rcNoise2);
		            rcColor += mix(rc3, rc4, rcPos2.x);
		            rcColor -= mix(rc5, rc6, rcPos2.y);
		            rcColor = clamp(rcColor, 0.0, 1.0);
		            // Apply war surface horizon
		            float influenceSky = smoothstep(0.0, heightHorizon, direction.y);
		            color = mix(vec3(0.0), rcColor, influenceSky);
		            break;
		        }
		        case 5: {
		            // War Heights (RP skyboxWarHeights) — variant of stormy with brightness shift
		            vec3 valuationUpper = vec3(0.106, 0.358, 0.036);
		            vec3 colorHorizon = vec3(0.05, 0.05, 0.05);
		            float heightHorizon = 0.15, widthHorizon = 0.30, mixHorizon = 0.10;
		            // Inline redCloudy
		            float rcSpeed = 0.01, rcIntensity = 2.5, rcScale = 1.2;
		            vec3 rc1 = vec3(0.6, 0.0, 0.0), rc2 = vec3(1.0, 0.2, 0.0);
		            vec3 rc3 = vec3(0.0, 0.2, 0.0), rc4 = vec3(1.0, 0.6, 0.6);
		            vec3 rc5 = vec3(0.3, 0.3, 0.3), rc6 = vec3(1.2, 1.2, 1.2);
		            float rcShift = time * rcSpeed;
		            vec3 rcPos1 = direction * rcIntensity + vec3(0.0, rcShift, rcShift);
		            float rcNoise1 = irisW_fbm(rcScale * rcPos1);
		            vec2 rcPos2 = vec2(irisW_fbm(rcPos1.xy + rcNoise1), irisW_fbm(rcPos1.yz - rcNoise1));
		            float rcNoise2 = irisW_fbm(rcScale * (rcPos1 + vec3(rcPos2, 0.0)));
		            vec3 rcColor = mix(rc1, rc2, rcNoise2);
		            rcColor += mix(rc3, rc4, rcPos2.x);
		            rcColor -= mix(rc5, rc6, rcPos2.y);
		            rcColor = clamp(rcColor, 0.0, 1.0);
		            // Apply war heights horizon (differs from stormy: uses colorLowerSky)
		            vec3 colorLowerSky = rcColor;
		            vec3 colorUpperSky = vec3(dot(colorLowerSky, valuationUpper));
		            float influenceUpper = smoothstep(heightHorizon - widthHorizon, heightHorizon, direction.y);
		            vec3 colorSky = mix(colorLowerSky, colorUpperSky, influenceUpper);
		            float influenceSky = mix(mixHorizon, 1.0, smoothstep(0.0, widthHorizon, abs(direction.y - heightHorizon)));
		            color = mix(colorHorizon, colorSky, influenceSky);
		            break;
		        }
		        case 6: {
		            // Light (RP skyboxLight) — crystal noise patterns
		            vec3 color1 = vec3(0.85, 0.85, 1.0);
		            vec3 color2 = vec3(0.75, 0.4, 0.0);
		            float cn = irisW_crystalNoise(direction * 3.0, time * 0.01);
		            vec3 baseColor = mix(color1, color2, cn);
		            color = mix(vec3(1.0, 0.9, 0.8), baseColor, smoothstep(0.1, 0.5, direction.y));
		            break;
		        }
		        case 7: {
		            // Red Lightning (RP skyboxRedLightning) — red cloudy + lightning bolts + screen flash
		            vec3 valuationUpper = vec3(0.106, 0.358, 0.036);
		            vec3 colorUpper = vec3(1.0, 1.0, 1.0);
		            vec3 colorHorizon = vec3(0.05, 0.05, 0.05);
		            float heightHorizon = 0.15, widthHorizon = 0.30, mixHorizon = 0.10;
		            // Inline redCloudy
		            float rcSpeed = 0.01, rcIntensity = 2.5, rcScale = 1.2;
		            vec3 rc1 = vec3(0.6, 0.0, 0.0), rc2 = vec3(1.0, 0.2, 0.0);
		            vec3 rc3 = vec3(0.0, 0.2, 0.0), rc4 = vec3(1.0, 0.6, 0.6);
		            vec3 rc5 = vec3(0.3, 0.3, 0.3), rc6 = vec3(1.2, 1.2, 1.2);
		            float rcShift = time * rcSpeed;
		            vec3 rcPos1 = direction * rcIntensity + vec3(0.0, rcShift, rcShift);
		            float rcNoise1 = irisW_fbm(rcScale * rcPos1);
		            vec2 rcPos2 = vec2(irisW_fbm(rcPos1.xy + rcNoise1), irisW_fbm(rcPos1.yz - rcNoise1));
		            float rcNoise2 = irisW_fbm(rcScale * (rcPos1 + vec3(rcPos2, 0.0)));
		            vec3 rcColor = mix(rc1, rc2, rcNoise2);
		            rcColor += mix(rc3, rc4, rcPos2.x);
		            rcColor -= mix(rc5, rc6, rcPos2.y);
		            rcColor = clamp(rcColor, 0.0, 1.0);
		            // Stormy sky base
		            vec3 colorUpperSky = vec3(dot(rcColor, valuationUpper) * colorUpper);
		            float influenceUpper = smoothstep(heightHorizon - widthHorizon, heightHorizon, direction.y);
		            vec3 colorSky = mix(vec3(0.0), colorUpperSky, influenceUpper);
		            float influenceSky = mix(mixHorizon, 1.0, smoothstep(0.0, widthHorizon, abs(direction.y - heightHorizon)));
		            color = mix(colorHorizon, colorSky, influenceSky);
		            // Distant cloud flashes
		            float cloudGlow = 0.0;
		            for (int ci = 0; ci < 3; ci++) {
		                float cseed = float(ci) * 17.13 + 3.7;
		                float glow = irisW_distantCloudFlash(time, cseed);
		                float cycle = floor((time + cseed * 53.7) / (20.0 + 40.0 * irisW_random(cseed + 100.0)));
		                float az = fract(cseed * 0.137 + cycle * 0.318) * 6.28318;
		                vec3 flashDir = vec3(cos(az), 0.0, sin(az));
		                float azimuthalFocus = dot(normalize(direction.xz), flashDir.xz);
		                float azimuthalMask = smoothstep(0.55, 0.90, azimuthalFocus);
		                float flashEl = 0.25 + fract(cseed * 0.331 + cycle * 0.271) * 0.30;
		                float elevationMask = smoothstep(0.18, 0.0, abs(direction.y - flashEl));
		                cloudGlow += glow * azimuthalMask * elevationMask;
		            }
		            color += vec3(1.0, 0.10, 0.05) * clamp(cloudGlow, 0.0, 0.35);
		            // Lightning bolts
		            float totalLightning = 0.0, totalScreenFlash = 0.0;
		            for (int li = 0; li < 4; li++) {
		                float lseed = float(li) * 31.41592 + 7.3;
		                float strikeIntensity = irisW_lightningFlash(time, lseed);
		                if (strikeIntensity > 0.0) {
		                    float lcycle = floor((time + lseed * 37.3) / (35.0 + 25.0 * irisW_random(lseed)));
		                    float laz = fract(lseed * 0.137 + lcycle * 0.419) * 6.28318;
		                    float lel = 0.3 + fract(lseed * 0.271 + lcycle * 0.347) * 0.35;
		                    vec3 boltCenter = normalize(vec3(cos(laz) * sqrt(1.0 - lel * lel), lel, sin(laz) * sqrt(1.0 - lel * lel)));
		                    vec3 up = vec3(0.0, 1.0, 0.0);
		                    vec3 tangentX = normalize(cross(up, boltCenter));
		                    vec3 tangentY = normalize(cross(boltCenter, tangentX));
		                    vec3 d = normalize(direction);
		                    vec2 localUV = vec2(dot(d, tangentX), dot(d, tangentY));
		                    vec2 origin = vec2(fract(lseed * 0.413 + lcycle * 0.531) * 0.3 - 0.15, 0.25);
		                    vec2 target = origin + vec2(fract(lseed * 0.619 + lcycle * 0.217) * 0.16 - 0.08, -0.5);
		                    float proximity = smoothstep(0.5, 0.85, dot(d, boltCenter));
		                    float bolt = irisW_lightningBolt(localUV, origin, target, lseed + lcycle, 0.003);
		                    totalLightning += bolt * strikeIntensity * proximity;
		                    totalScreenFlash += strikeIntensity * 0.25 * proximity;
		                }
		            }
		            totalLightning = clamp(totalLightning, 0.0, 1.0);
		            totalScreenFlash = clamp(totalScreenFlash, 0.0, 1.0);
		            vec3 boltColor = mix(vec3(1.0, 0.05, 0.02), vec3(1.0, 0.85, 0.80), totalLightning);
		            vec3 flashColor = vec3(0.9, 0.08, 0.04) * totalScreenFlash;
		            color = clamp(color + flashColor + boltColor * totalLightning, 0.0, 1.0);
		            break;
		        }
		    }
		    return vec4(color, alpha);
		}""";

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
		    color = mix(color, vec3(1.0), smoothstep(0.7, 1.0, brightness) * contrast);
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
		vec4 irisW_shiny(vec3 iW_color, float iW_intensity, float iW_brightness, vec2 iW_sweepUV, bool iW_isAtlas, float iW_time, vec4 iW_tex) {
		    // Clean directional sweep with irregular burst timing.
		    // Sweep speed/width is constant; a visibility gate creates bursts and pauses.
		    vec2 iW_dir = iW_isAtlas ? vec2(0.3, 0.0) : vec2(0.3, -0.07);
		    float iW_speed = iW_isAtlas ? 6.0 : 1.5;
		    float iW_freq = iW_isAtlas ? 0.25 : 0.5;
		    float iW_x = dot(iW_dir, iW_sweepUV) - iW_time * iW_speed;
		    float iW_phase = 1.0 - fract(iW_x * iW_freq);
		    float iW_wave = smoothstep(0.0, 0.05, iW_phase) * (1.0 - smoothstep(0.1, 0.4, iW_phase));
		    // Per-cycle visibility gate: decide once per sweep whether it's visible.
		    // Uses the cycle index (floor of the sweep counter) so the decision is
		    // constant for the entire pass — no mid-sweep cutoffs.
		    float iW_cycle = floor(iW_x * iW_freq);
		    float iW_gate = sin(iW_cycle * 1.7) + sin(iW_cycle * 0.73) + sin(iW_cycle * 0.31);
		    iW_wave *= step(-0.3, iW_gate);
		    return vec4(iW_tex.rgb + iW_color * iW_wave * iW_intensity * iW_brightness, iW_tex.a);
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
		    bool iW_isTint = (iW_id >= 15 && iW_id <= 24);
		    bool iW_knownEffect = (iW_id >= 1 && iW_id <= 32);
		    // Shiny uses continuousSweepUV (same as shadow sweep) for clean directional band
		    vec2 iW_shinySweep = irisW_continuousSweepUV(iW_uv, iW_midTex, iW_texSize, iW_eUV);
		    switch (iW_id) {
		        case 1:  { iW_out = irisW_shiny(irisW_rgb(255, 200, 100), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }
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
		        case 25: { iW_out = irisW_shiny(irisW_rgb(255, 255, 255), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }
		        case 26: { iW_out = irisW_shiny(irisW_rgb(85,  255, 85 ), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }
		        case 27: { iW_out = irisW_shiny(irisW_rgb(255, 255, 85 ), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }
		        case 28: { iW_out = irisW_shiny(irisW_rgb(255, 85,  255), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }
		        case 29: { iW_out = irisW_shiny(irisW_rgb(85,  255, 255), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }
		        case 30: { iW_out = irisW_shiny(irisW_rgb(255, 85,  85 ), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }
		        case 31: { iW_out = irisW_shiny(irisW_rgb(170, 0,   170), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }
		        case 32: { iW_applyLighting = false; break; } // Clear fog — entity renders without fog blend
		    }
		    if (iW_applyLighting && iW_knownEffect) {
		        float iW_texLuma = max(dot(iW_tex.rgb, vec3(0.2126, 0.7152, 0.0722)), 0.001);
		        float iW_inLuma  = max(dot(iW_in.rgb,  vec3(0.2126, 0.7152, 0.0722)), 0.0);
		        if (iW_isTint) {
		            float iW_tintRatio = iW_inLuma / iW_texLuma * iris_tintBrightness;
		            iW_out.rgb *= clamp(iW_tintRatio, 0.0, 4.0);
		        } else {
		            float iW_ratio = iW_inLuma / iW_texLuma * iris_glintBrightness;
		            iW_out.rgb *= min(iW_ratio, 1.0);
		        }
		    }
		    return iW_out;
		}
		""";

	// Glint fragment code used by the FORWARD path. FRAG_OUTPUT is replaced with the
	// actual fragment output variable name (e.g., iris_FragData0).
	private static final String IRISW_GLINT_FRAGMENT_CODE = """
		if (iris_wynncraft_glint != 0) {
		    vec2 irisW_texSize = vec2(textureSize(Sampler0, 0));
		    bool irisW_isAtlas = max(irisW_texSize.x, irisW_texSize.y) > 2000.0;
		    vec2 irisW_uv = iris_wynncraft_texcoord;
		    float irisW_time = iris_globalInfo.GameTime * 300.0;
		    vec4 irisW_tex = texture(Sampler0, irisW_uv);
		    vec2 irisW_eUV;
		    if (irisW_isAtlas) {
		        vec2 iW_spriteUV = fract(irisW_uv * irisW_texSize / 16.0);
		        irisW_eUV = (iW_spriteUV - 1.0) * vec2(irisW_texSize.x / irisW_texSize.y, 1.0) / 5.0;
		    } else {
		        irisW_eUV = (irisW_uv - 1.0) * vec2(irisW_texSize.x / irisW_texSize.y, 1.0);
		    }
		    vec2 iW_dU = max(abs(dFdx(irisW_uv)), abs(dFdy(irisW_uv)));
		    vec2 iW_uvRate = max(iW_dU, vec2(1e-6));
		    vec2 irisW_sUV = fract(irisW_uv / (iW_uvRate * 50.0)) * 4.0;
		    vec2 irisW_sweepFull = irisW_continuousSweepUV(irisW_uv, iris_wynncraft_midtex, irisW_texSize, irisW_eUV);
		    vec2 irisW_sweepMid = irisW_continuousSweepUV(iris_wynncraft_midtex, iris_wynncraft_midtex, irisW_texSize, vec2(0.5));
		    vec2 irisW_rUV = (irisW_sweepFull - irisW_sweepMid) * 0.25 + vec2(8.0);
		    int irisW_effectId = iris_wynncraft_glint & 31;
		    FRAG_OUTPUT = irisW_applyGlint(irisW_effectId, irisW_uv, irisW_eUV, irisW_sUV, iris_wynncraft_midtex, irisW_rUV, irisW_texSize, irisW_isAtlas, irisW_time, irisW_tex, FRAG_OUTPUT);
		}
		""";

	private static final String IRISW_ITEM_TINT_FRAGMENT_CODE = """
		if (!irisW_skyboxApplied && !irisW_skipItemTint && iris_wynncraft_glint == 0 && iris_wynncraft_translucency == 0 && currentRenderedItemId > 0) {
		    vec3 irisW_tintColor = clamp(iris_vertexColor.rgb * iris_transforms.ColorModulator.rgb, vec3(0.0), vec3(1.0));
		    vec3 irisW_tintDelta = abs(irisW_tintColor - vec3(1.0));
		    float irisW_tintStrength = clamp(max(max(irisW_tintDelta.r, irisW_tintDelta.g), irisW_tintDelta.b) * 4.0, 0.0, 1.0);
		    if (irisW_tintStrength > 0.001) {
		        float irisW_tintLuma = dot(irisW_tintColor, vec3(0.2126, 0.7152, 0.0722));
		        float irisW_outLuma = dot(max(FRAG_OUTPUT.rgb, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
		        if (irisW_outLuma > 0.001) {
		            vec3 irisW_tintHue = irisW_tintLuma > 0.001 ? irisW_tintColor / irisW_tintLuma : vec3(0.0);
		            vec3 irisW_preservedTint = clamp(irisW_tintHue * irisW_outLuma, vec3(0.0), vec3(1.0));
		            FRAG_OUTPUT.rgb = mix(FRAG_OUTPUT.rgb, irisW_preservedTint, irisW_tintStrength);
		        }
		    }
		}
		""";

	private static final String IRISW_BSL_TINT_VL_SUPPRESS_CODE = """
		if (!irisW_skyboxApplied) {
		    bool irisW_shaderTint = iris_wynncraft_glint >= 15 && iris_wynncraft_glint <= 24;
		    vec3 irisW_tintColor = clamp(iris_vertexColor.rgb * iris_transforms.ColorModulator.rgb, vec3(0.0), vec3(1.0));
		    vec3 irisW_tintDelta = abs(irisW_tintColor - vec3(1.0));
		    float irisW_tintStrength = clamp(max(max(irisW_tintDelta.r, irisW_tintDelta.g), irisW_tintDelta.b) * 4.0, 0.0, 1.0);
		    bool irisW_itemTint = !irisW_skipItemTint && iris_wynncraft_glint == 0 && iris_wynncraft_translucency == 0 && currentRenderedItemId > 0 && irisW_tintStrength > 0.001;
		    if (irisW_shaderTint || irisW_itemTint) {
		        iris_FragData1.rgb = vec3(0.0);
		    }
		}
		""";

	// Glint fragment code for the DEFERRED path. ALBEDO_VAR is replaced with the
	// pack's albedo variable name (e.g., base_color). Modifies .rgb only, wrapping
	// in vec4 for the applyGlint call since it expects vec4 in/out.
	private static final String IRISW_DEFERRED_GLINT_CODE = """
		if (iris_wynncraft_glint != 0) {
		    vec2 irisW_texSize = vec2(textureSize(Sampler0, 0));
		    bool irisW_isAtlas = max(irisW_texSize.x, irisW_texSize.y) > 2000.0;
		    vec2 irisW_uv = iris_wynncraft_texcoord;
		    float irisW_time = iris_globalInfo.GameTime * 300.0;
		    vec4 irisW_tex = texture(Sampler0, irisW_uv);
		    vec2 irisW_eUV;
		    if (irisW_isAtlas) {
		        vec2 iW_spriteUV = fract(irisW_uv * irisW_texSize / 16.0);
		        irisW_eUV = (iW_spriteUV - 1.0) * vec2(irisW_texSize.x / irisW_texSize.y, 1.0) / 5.0;
		    } else {
		        irisW_eUV = (irisW_uv - 1.0) * vec2(irisW_texSize.x / irisW_texSize.y, 1.0);
		    }
		    vec2 iW_dU = max(abs(dFdx(irisW_uv)), abs(dFdy(irisW_uv)));
		    vec2 iW_uvRate = max(iW_dU, vec2(1e-6));
		    vec2 irisW_sUV = fract(irisW_uv / (iW_uvRate * 50.0)) * 4.0;
		    vec2 irisW_sweepFull = irisW_continuousSweepUV(irisW_uv, iris_wynncraft_midtex, irisW_texSize, irisW_eUV);
		    vec2 irisW_sweepMid = irisW_continuousSweepUV(iris_wynncraft_midtex, iris_wynncraft_midtex, irisW_texSize, vec2(0.5));
		    vec2 irisW_rUV = (irisW_sweepFull - irisW_sweepMid) * 0.25 + vec2(8.0);
		    int irisW_effectId = iris_wynncraft_glint & 31;
		    vec4 irisW_albedoIn = vec4(ALBEDO_VAR.rgb, 1.0);
		    vec4 irisW_albedoOut = irisW_applyGlint(irisW_effectId, irisW_uv, irisW_eUV, irisW_sUV, iris_wynncraft_midtex, irisW_rUV, irisW_texSize, irisW_isAtlas, irisW_time, irisW_tex, irisW_albedoIn);
		    ALBEDO_VAR.rgb = irisW_albedoOut.rgb;
		}
		""";

	// Translucency code for the DEFERRED path. Currently a no-op placeholder —
	// deferred packs don't alpha-blend at the GBuffer stage, and dithered discard
	// doesn't produce acceptable visual results. True deferred translucency requires
	// routing VFX entities through the pack's forward translucent pass
	// (e.g., gbuffers_entities_translucent in Photon).
	// TODO: Investigate routing translucent entities to the forward translucent path.
	private static final String IRISW_DEFERRED_TRANSLUCENCY_CODE = """
		if (iris_wynncraft_translucency > 0) {
		    // Deferred translucency: clamp alpha to the target rather than multiply, so this
		    // composes cleanly with vertex-side propagation (no double-attenuation).
		    // Formula mirrors Wynncraft RP include/translucency.glsl:
		    //   color.a = mix(color.a, 0.0, level / 100.0) = color.a * (1.0 - level / 100.0)
		    // Floor at 0.10 so level=100 VFX remains barely visible.
		    ALBEDO_VAR.a = min(ALBEDO_VAR.a, max(0.10, 1.0 - float(iris_wynncraft_translucency) / 100.0));
		}
		""";

	// Shadeless code for the FORWARD path. Wynncraft texture alpha = 251/255 signals
	// "shadeless" — the pixel should be flat-lit with no directional entity shading.
	// We divide out iris_vertexColor.rgb to remove the baked-in vertex color shading.
	// FRAG_OUTPUT is replaced with the actual fragment output variable at injection time.
	private static final String IRISW_SHADELESS_FORWARD = """
		if (!irisW_skyboxApplied) {
		    vec4 irisW_shadelessTex = texture(Sampler0, iris_wynncraft_texcoord);
		    if (abs(irisW_shadelessTex.a * 255.0 - 251.0) < 0.5) {
		        FRAG_OUTPUT.rgb /= max(iris_vertexColor.rgb, vec3(0.05));
		    }
		}
		""";

	// Shadeless code for the DEFERRED path. Uses the albedo variable (already sampled
	// by the pack) instead of resampling via Sampler0 — avoids sampler binding issues
	// on packs that use gtexture instead of Sampler0.
	private static final String IRISW_SHADELESS_DEFERRED = """
		if (!irisW_skyboxApplied) {
		    if (abs(ALBEDO_VAR.a * 255.0 - 251.0) < 0.5) {
		        ALBEDO_VAR.rgb /= max(iris_vertexColor.rgb, vec3(0.05));
		    }
		}
		""";

	// ====================================================================================
	// WYNNCRAFT PLAYER EMOTE SUPPORT
	// ====================================================================================
	// Wynncraft's resource pack encodes player emote limb metadata into entity vertex
	// Y-positions. When Iris replaces vanilla entity shaders with shader pack programs,
	// the RP's applyPlayer() never runs — encoded positions aren't decoded, UVs aren't
	// remapped, nearFade doesn't execute. This injects an equivalent function.
	// ====================================================================================

	// GLSL data for player emote system: struct, limb UV table, radix constants.
	// Copied verbatim from Wynncraft RP player.glsl.
	private static final String[] IRISW_PLAYER_DATA = {
		"""
		struct irisw_LimbUv {
		    vec2 faceSizes[6];
		    vec2 faceOrigins[6];
		    vec2 overlayOffset;
		};""",
		"const int IRISW_Y_POSITION_RADIX = 512;",
		"const int IRISW_STEVE_ALEX_RADIX = 2;",
		"const int IRISW_LIMB_FADE_RADIX = 3;",
		"const int IRISW_LIMB_INDEX_RADIX = 6;",
		"const float IRISW_SKIN_TEX_SIZE = 64.0;",
		"const float IRISW_SKIN_TEX_SIZE_INV = 1.0 / 64.0;",
		"const float IRISW_SOFT_FADE_START_SQ = 0.5;",
		"const float IRISW_SOFT_FADE_END_SQ = 1.0;",
		"const float IRISW_HARD_FADE_SQ = 6.0;",
		"""
		const irisw_LimbUv IRISW_LIMB_UVS[8] = irisw_LimbUv[](
		    irisw_LimbUv( // Head
		        vec2[](
		            vec2(8.0, 8.0), vec2(8.0, 8.0), vec2(8.0, 8.0),
		            vec2(8.0, 8.0), vec2(8.0, 8.0), vec2(8.0, 8.0)
		        ),
		        vec2[](
		            vec2(16.0, 0.0), vec2(24.0, 8.0), vec2(8.0, 8.0),
		            vec2(16.0, 8.0), vec2(24.0, 8.0), vec2(32.0, 8.0)
		        ),
		        vec2(32.0, 0.0)
		    ),
		    irisw_LimbUv( // Body
		        vec2[](
		            vec2(8.0, 4.0), vec2(8.0, 4.0), vec2(4.0, 12.0),
		            vec2(8.0, 12.0), vec2(4.0, 12.0), vec2(8.0, 12.0)
		        ),
		        vec2[](
		            vec2(28.0, 16.0), vec2(36.0, 20.0), vec2(20.0, 20.0),
		            vec2(28.0, 20.0), vec2(32.0, 20.0), vec2(40.0, 20.0)
		        ),
		        vec2(0.0, 16.0)
		    ),
		    irisw_LimbUv( // Left Arm (Steve)
		        vec2[](
		            vec2(4.0, 4.0), vec2(4.0, 4.0), vec2(4.0, 12.0),
		            vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0)
		        ),
		        vec2[](
		            vec2(40.0, 48.0), vec2(44.0, 52.0), vec2(36.0, 52.0),
		            vec2(40.0, 52.0), vec2(44.0, 52.0), vec2(48.0, 52.0)
		        ),
		        vec2(16.0, 0.0)
		    ),
		    irisw_LimbUv( // Right Arm (Steve)
		        vec2[](
		            vec2(4.0, 4.0), vec2(4.0, 4.0), vec2(4.0, 12.0),
		            vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0)
		        ),
		        vec2[](
		            vec2(48.0, 16.0), vec2(52.0, 20.0), vec2(44.0, 20.0),
		            vec2(48.0, 20.0), vec2(52.0, 20.0), vec2(56.0, 20.0)
		        ),
		        vec2(0.0, 16.0)
		    ),
		    irisw_LimbUv( // Left Leg
		        vec2[](
		            vec2(4.0, 4.0), vec2(4.0, 4.0), vec2(4.0, 12.0),
		            vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0)
		        ),
		        vec2[](
		            vec2(24.0, 48.0), vec2(28.0, 52.0), vec2(20.0, 52.0),
		            vec2(24.0, 52.0), vec2(28.0, 52.0), vec2(32.0, 52.0)
		        ),
		        vec2(-16.0, 0.0)
		    ),
		    irisw_LimbUv( // Right Leg
		        vec2[](
		            vec2(4.0, 4.0), vec2(4.0, 4.0), vec2(4.0, 12.0),
		            vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0)
		        ),
		        vec2[](
		            vec2(8.0, 16.0), vec2(12.0, 20.0), vec2(4.0, 20.0),
		            vec2(8.0, 20.0), vec2(12.0, 20.0), vec2(16.0, 20.0)
		        ),
		        vec2(0.0, 16.0)
		    ),
		    irisw_LimbUv( // Left Arm (Alex)
		        vec2[](
		            vec2(3.0, 4.0), vec2(3.0, 4.0), vec2(4.0, 12.0),
		            vec2(3.0, 12.0), vec2(4.0, 12.0), vec2(3.0, 12.0)
		        ),
		        vec2[](
		            vec2(39.0, 48.0), vec2(42.0, 52.0), vec2(36.0, 52.0),
		            vec2(39.0, 52.0), vec2(43.0, 52.0), vec2(46.0, 52.0)
		        ),
		        vec2(16.0, 0.0)
		    ),
		    irisw_LimbUv( // Right Arm (Alex)
		        vec2[](
		            vec2(3.0, 4.0), vec2(3.0, 4.0), vec2(4.0, 12.0),
		            vec2(3.0, 12.0), vec2(4.0, 12.0), vec2(3.0, 12.0)
		        ),
		        vec2[](
		            vec2(47.0, 16.0), vec2(50.0, 20.0), vec2(44.0, 20.0),
		            vec2(47.0, 20.0), vec2(51.0, 20.0), vec2(54.0, 20.0)
		        ),
		        vec2(0.0, 16.0)
		    )
		);""",
		"const int IRISW_STEVE_OFFSETS[6] = int[](0, 0, 0, 0, 0, 0);",
		"const int IRISW_ALEX_OFFSETS[6] = int[](0, 0, 4, 4, 0, 0);",
		"const int IRISW_FACE_RIGHT = 2;",
		"const int IRISW_FACE_FRONT = 3;",
		"const int IRISW_FACE_LEFT = 4;",
		"const int IRISW_HEAD = 0;",
		"const int IRISW_BODY = 1;",
	};

	// Player emote function — decodes metadata from Y-position, remaps UV, computes nearFade.
	// Ported from Wynncraft RP player.glsl applyPlayer().
	private static final String IRISW_APPLY_PLAYER_FUNC = """
		void irisw_applyPlayer(inout vec3 pos, inout vec2 uv, out float nearFade) {
		    nearFade = 1.0;
		    if (pos.y < 2.0 * float(IRISW_Y_POSITION_RADIX)) return;
		    if (iris_transforms.ModelViewMat == mat4(1.0)) return;

		    int metadata = int(pos.y) - 2 * IRISW_Y_POSITION_RADIX;

		    int steveAlex = (metadata / IRISW_Y_POSITION_RADIX) % IRISW_STEVE_ALEX_RADIX;
		    int limbFade = (metadata / IRISW_Y_POSITION_RADIX / IRISW_STEVE_ALEX_RADIX) % IRISW_LIMB_FADE_RADIX;
		    int limbIndex = (metadata / IRISW_Y_POSITION_RADIX / IRISW_STEVE_ALEX_RADIX / IRISW_LIMB_FADE_RADIX) % IRISW_LIMB_INDEX_RADIX;

		    pos.y = mod(pos.y, float(IRISW_Y_POSITION_RADIX)) - (float(IRISW_Y_POSITION_RADIX) / 2.0 - 1.0);

		    int face = (gl_VertexID % 24) / 4;
		    int overlay = (gl_VertexID / 24) % 2;

		    int limbUvOffset = steveAlex == 0 ? IRISW_STEVE_OFFSETS[limbIndex] : IRISW_ALEX_OFFSETS[limbIndex];
		    irisw_LimbUv limbUv = IRISW_LIMB_UVS[limbIndex + limbUvOffset];
		    irisw_LimbUv headUv = IRISW_LIMB_UVS[0];

		    uv -= float(overlay) * headUv.overlayOffset * IRISW_SKIN_TEX_SIZE_INV;

		    float faceDivideX = (headUv.faceOrigins[IRISW_FACE_RIGHT].x + headUv.faceOrigins[IRISW_FACE_LEFT].x) / 2.0;
		    int divide = int(uv.x >= faceDivideX * IRISW_SKIN_TEX_SIZE_INV);

		    face += divide * int(face == IRISW_FACE_RIGHT) * (IRISW_FACE_LEFT - IRISW_FACE_RIGHT);
		    face -= (1 - divide) * int(face == IRISW_FACE_LEFT) * (IRISW_FACE_LEFT - IRISW_FACE_RIGHT);

		    vec2 sizeRatio = limbUv.faceSizes[face] / headUv.faceSizes[face];
		    vec2 originOffset = limbUv.faceOrigins[face] - (headUv.faceOrigins[face] * sizeRatio);

		    uv *= sizeRatio;
		    uv += originOffset * IRISW_SKIN_TEX_SIZE_INV;

		    uv += float(overlay) * limbUv.overlayOffset * IRISW_SKIN_TEX_SIZE_INV;

		    vec4 blockPos = iris_transforms.ModelViewMat * vec4(pos, 1.0);
		    float blockDistSq = dot(blockPos.xyz, blockPos.xyz);

		    float softFade = smoothstep(IRISW_SOFT_FADE_START_SQ, IRISW_SOFT_FADE_END_SQ, blockDistSq);
		    float hardFade = step(IRISW_HARD_FADE_SQ, blockDistSq);

		    nearFade = mix(1.0, mix(softFade, hardFade, limbFade == 2), limbFade != 0);

		    int headBody = int(limbIndex == IRISW_HEAD || limbIndex == IRISW_BODY);
		    nearFade = mix(1.0, nearFade, headBody);
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
				"flat out int iris_wynncraft_translucency;",
				"out vec2 iris_wynncraft_texcoord;",
				"out vec2 iris_wynncraft_midtex;",
				"out vec3 iris_wynncraft_position;",
				"vec3 irisw_pos;",
				"vec2 irisw_uv0;",
				"out float iris_wynncraft_nearfade;",
				parameters.inputs.isIE() ? "uniform ivec2 iris_OverlayUV;" : "in ivec2 iris_UV1;");

			// Create our own main function to wrap the existing main function, so that we
			// can pass through the overlay color at the end to the geometry or fragment
			// stage.
			// Detect Wynncraft glint signal (G=255, B=0) and translucency signal (G=254, B=0).
			// Both encode a value in the R channel. Neutralize color to white for shader packs.
			boolean hasMidTexCoord = root.identifierIndex.has("mc_midTexCoord");
			tree.prependMainFunctionBody(t,
				"vec4 overlayColor = texelFetch(iris_overlay, " + (parameters.inputs.isIE() ? "iris_OverlayUV" : "iris_UV1") + ", 0);",
				"entityColor = vec4(overlayColor.rgb, 1.0 - overlayColor.a);",
				IRISW_SIGNAL_DETECT,
				IRISW_TRANSLUCENCY_DETECT,
				"iris_wynncraft_glint = iris_wynn_isSignal ? int(round(iris_Color.r * 255.0)) : 0;",
				"iris_wynncraft_translucency = iris_wynn_isTranslucent ? int(round(iris_Color.r * 255.0)) : 0;",
				"irisw_pos = iris_Position;",
				"iris_wynncraft_position = iris_Position;",
				"irisw_uv0 = iris_UV0;",
				"float irisw_nf = 1.0;",
				"irisw_applyPlayer(irisw_pos, irisw_uv0, irisw_nf);",
				"iris_wynncraft_nearfade = irisw_nf;",
				"iris_wynncraft_texcoord = irisw_uv0;",
				hasMidTexCoord ? "iris_wynncraft_midtex = mc_midTexCoord.xy;" : "iris_wynncraft_midtex = vec2(0.0);",
				"iris_vertexColor = (iris_wynn_isSignal || iris_wynn_isTranslucent) ? vec4(1.0) : iris_Color;",
				// Workaround for a shader pack bug:
				// https://github.com/IrisShaders/Iris/issues/1549
				// Some shader packs incorrectly ignore the alpha value, and assume that rgb
				// will be zero if there is no hit flash, we try to emulate that here
				"entityColor.rgb *= float(entityColor.a != 0.0);");

			// Inject player emote function and data into vertex shader.
			// Data goes to BEFORE_DECLARATIONS (struct, constants, arrays).
			// Function goes to BEFORE_FUNCTIONS (needs data declared above it).
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS, IRISW_APPLY_PLAYER_FUNC);
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS, IRISW_PLAYER_DATA);
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
				"flat in int iris_wynncraft_translucency[];",
				"flat out int iris_wynncraft_translucencyTCS[];",
				"in vec2 iris_wynncraft_texcoord[];",
				"out vec2 iris_wynncraft_texcoordTCS[];",
				"in vec2 iris_wynncraft_midtex[];",
				"out vec2 iris_wynncraft_midtexTCS[];",
				"in vec3 iris_wynncraft_position[];",
				"out vec3 iris_wynncraft_positionTCS[];",
				"in float iris_wynncraft_nearfade[];",
				"out float iris_wynncraft_nearfadeTCS[];");
			tree.prependMainFunctionBody(t,
				"entityColorTCS = entityColor[gl_InvocationID];",
				"iris_vertexColorTCS[gl_InvocationID] = iris_vertexColor[gl_InvocationID];",
				"iris_wynncraft_glintTCS[gl_InvocationID] = iris_wynncraft_glint[gl_InvocationID];",
				"iris_wynncraft_translucencyTCS[gl_InvocationID] = iris_wynncraft_translucency[gl_InvocationID];",
				"iris_wynncraft_texcoordTCS[gl_InvocationID] = iris_wynncraft_texcoord[gl_InvocationID];",
				"iris_wynncraft_midtexTCS[gl_InvocationID] = iris_wynncraft_midtex[gl_InvocationID];",
				"iris_wynncraft_positionTCS[gl_InvocationID] = iris_wynncraft_position[gl_InvocationID];",
				"iris_wynncraft_nearfadeTCS[gl_InvocationID] = iris_wynncraft_nearfade[gl_InvocationID];");
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
				"flat in int iris_wynncraft_translucencyTCS[];",
				"flat out int iris_wynncraft_translucencyTES;",
				"in vec2 iris_wynncraft_texcoordTCS[];",
				"out vec2 iris_wynncraft_texcoordTES;",
				"in vec2 iris_wynncraft_midtexTCS[];",
				"out vec2 iris_wynncraft_midtexTES;",
				"in vec3 iris_wynncraft_positionTCS[];",
				"out vec3 iris_wynncraft_positionTES;",
				"in float iris_wynncraft_nearfadeTCS[];",
				"out float iris_wynncraft_nearfadeTES;");
			tree.prependMainFunctionBody(t,
				"entityColorTES = entityColorTCS;",
				"iris_vertexColorTES = iris_vertexColorTCS[0];",
				"iris_wynncraft_glintTES = iris_wynncraft_glintTCS[0];",
				"iris_wynncraft_translucencyTES = iris_wynncraft_translucencyTCS[0];",
				"iris_wynncraft_texcoordTES = iris_wynncraft_texcoordTCS[0];",
				"iris_wynncraft_midtexTES = iris_wynncraft_midtexTCS[0];",
				"iris_wynncraft_positionTES = iris_wynncraft_positionTCS[0];",
				"iris_wynncraft_nearfadeTES = iris_wynncraft_nearfadeTCS[0];");
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
				"flat in int iris_wynncraft_translucency[];",
				"flat out int iris_wynncraft_translucencyGS;",
				"in vec2 iris_wynncraft_texcoord[];",
				"out vec2 iris_wynncraft_texcoordGS;",
				"in vec2 iris_wynncraft_midtex[];",
				"out vec2 iris_wynncraft_midtexGS;",
				"in vec3 iris_wynncraft_position[];",
				"out vec3 iris_wynncraft_positionGS;",
				"in float iris_wynncraft_nearfade[];",
				"out float iris_wynncraft_nearfadeGS;");
			tree.prependMainFunctionBody(t,
				"entityColorGS = entityColor[0];",
				"iris_vertexColorGS = iris_vertexColor[0];",
				"iris_wynncraft_glintGS = iris_wynncraft_glint[0];",
				"iris_wynncraft_translucencyGS = iris_wynncraft_translucency[0];",
				"iris_wynncraft_texcoordGS = iris_wynncraft_texcoord[0];",
				"iris_wynncraft_midtexGS = iris_wynncraft_midtex[0];",
				"iris_wynncraft_positionGS = iris_wynncraft_position[0];",
				"iris_wynncraft_nearfadeGS = iris_wynncraft_nearfade[0];");

			if (parameters.hasTesselation) {
				root.rename("iris_vertexColor", "iris_vertexColorTES");
				root.rename("entityColor", "entityColorTES");
				root.rename("iris_wynncraft_glint", "iris_wynncraft_glintTES");
				root.rename("iris_wynncraft_translucency", "iris_wynncraft_translucencyTES");
				root.rename("iris_wynncraft_texcoord", "iris_wynncraft_texcoordTES");
				root.rename("iris_wynncraft_midtex", "iris_wynncraft_midtexTES");
				root.rename("iris_wynncraft_position", "iris_wynncraft_positionTES");
				root.rename("iris_wynncraft_nearfade", "iris_wynncraft_nearfadeTES");
			}
		} else if (parameters.type.glShaderType == ShaderType.FRAGMENT) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"in vec4 entityColor;", "in vec4 iris_vertexColor;",
				"flat in int iris_wynncraft_glint;",
				"flat in int iris_wynncraft_translucency;",
				"in vec2 iris_wynncraft_texcoord;",
				"in vec2 iris_wynncraft_midtex;",
				"in vec3 iris_wynncraft_position;",
				"in float iris_wynncraft_nearfade;");

			tree.prependMainFunctionBody(t,
				"float iris_vertexColorAlpha = iris_vertexColor.a;",
				"if (iris_wynncraft_nearfade <= 0.01) discard;",
				"int irisW_entityInfoFlags = iris_entityInfo.y / 16384;",
				"bool irisW_skipItemTint = irisW_entityInfoFlags == 1 || irisW_entityInfoFlags == 3;",
				"bool irisW_skipEntityLightTweaks = irisW_entityInfoFlags >= 2;",
				"bool irisW_skyboxApplied = false;");

			if (!root.identifierIndex.has("Sampler0")) {
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform sampler2D Sampler0;");
			}
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform float iris_glintBrightness;");
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform float iris_tintBrightness;");
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform float iris_wynncraftEntityEmissivity;");
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform float iris_wynncraftEntityBoost;");
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform int iris_wynncraftPrimarySkyboxId;");

			// Inject Wynncraft GLSL functions into fragment shader.
			// Use BEFORE_FUNCTIONS so they land after all uniform/varying declarations.
			// Inject in reverse order since BEFORE_FUNCTIONS prepends:
			//   HELPERS → SKYBOX_HELPERS → APPLY_SKYBOX → SIGNAL_HELPER → EMISSIVE_HELPER → APPLY_GLINT
			// Hardcoded frequency: 2.0 (was configurable via wyncraftGlintFreq slider, now removed)
			String glintFunc = IRISW_APPLY_GLINT_FUNC.replace("IRIS_WYNNCRAFT_GLINT_FREQ", "2.00");
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS, glintFunc);
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS, IRISW_EMISSIVE_SIGNAL_HELPER);
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS, IRISW_SKYBOX_SIGNAL_HELPER);
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS, IRISW_APPLY_SKYBOX_FUNC);
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS, IRISW_SKYBOX_HELPERS);
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS, IRISW_HELPERS);

			// Apply skybox, glint, and translucency effects.
			// Forward packs: append to end of main() targeting the fragment output.
			// Deferred packs: inject mid-main targeting the albedo variable before GBuffer packing.
			FragOutput fragOutput = resolveFragOutput(root);
			boolean applyEntityLightTweaks = !parameters.isHandProgram();

			if (fragOutput != null) {
				// FORWARD PATH: append effects after main().
				// Skybox apply runs first — replaces fragment color for skybox entities.
				// Guard flag prevents subsequent effects from mutating skybox output.
				String fo = fragOutput.name();
				String skyboxApplyCode = (fragOutput.premultiplied()
					? IRISW_SKYBOX_APPLY_FORWARD_PREMUL : IRISW_SKYBOX_APPLY_FORWARD)
					.replace("FRAG_OUTPUT", fo);
				tree.appendMainFunctionBody(t, skyboxApplyCode);
				// Shadeless: remove vertex color shading for textures with alpha = 251/255.
				tree.appendMainFunctionBody(t, IRISW_SHADELESS_FORWARD.replace("FRAG_OUTPUT", fo));
				// Glint and translucency skip naturally for skybox entities (signal is in
				// texture, not vertex color, so iris_wynncraft_glint/translucency == 0).
				tree.appendMainFunctionBody(t, IRISW_GLINT_FRAGMENT_CODE.replace("FRAG_OUTPUT", fo));
				appendTranslucencyAlpha(t, tree, fo, fragOutput.premultiplied());
				tree.appendMainFunctionBody(t, IRISW_ITEM_TINT_FRAGMENT_CODE.replace("FRAG_OUTPUT", fo));
				if (root.identifierIndex.has("vlAlbedo") && hasGlFragDataIndex(root, 1)) {
					tree.appendMainFunctionBody(t, IRISW_BSL_TINT_VL_SUPPRESS_CODE);
				}
				// Nearfade and optional entity lighting tweaks guarded — these would otherwise modify skybox output.
				tree.appendMainFunctionBody(t, "if (!irisW_skyboxApplied) " + fo + " *= iris_wynncraft_nearfade;");
				if (applyEntityLightTweaks) {
					tree.appendMainFunctionBody(t, """
						if (!irisW_skyboxApplied && !irisW_skipEntityLightTweaks) {
						    vec4 irisW_emissiveSample = texture(Sampler0, iris_wynncraft_texcoord);
						    bool irisW_emissiveEntity = irisW_isEmissiveSignal(irisW_emissiveSample);
						    bool irisW_shaderTint = iris_wynncraft_glint >= 15 && iris_wynncraft_glint <= 24;
						    if (irisW_emissiveEntity && !irisW_shaderTint) {
						        FRAG_OUTPUT.rgb = mix(FRAG_OUTPUT.rgb, max(FRAG_OUTPUT.rgb, irisW_emissiveSample.rgb), iris_wynncraftEntityEmissivity);
						    } else {
						        float irisW_boostLuma = dot(FRAG_OUTPUT.rgb, vec3(0.2126, 0.7152, 0.0722));
						        float irisW_boostScale = mix(iris_wynncraftEntityBoost, 1.0, smoothstep(0.3, 0.8, irisW_boostLuma));
						        float irisW_boostMax = max(max(FRAG_OUTPUT.r, FRAG_OUTPUT.g), FRAG_OUTPUT.b);
						        if (irisW_boostMax * irisW_boostScale > 1.0) {
						            irisW_boostScale = 1.0 / max(irisW_boostMax, 1e-5);
						        }
						        FRAG_OUTPUT.rgb *= irisW_boostScale;
						    }
						}
						""".replace("FRAG_OUTPUT", fo));
				}
			} else {
				// DEFERRED PATH: mid-main injection for packed GBuffer packs (e.g., Photon).
				// irisW_skyboxApplied already declared at top of main via prependMainFunctionBody.

				// Find anchors for mid-main injection
				CompoundStatement mainBody = null;
				try { mainBody = tree.getOneMainDefinitionBody(); } catch (Exception ignored) {}

				if (mainBody != null) {
					OverlayAnchor overlayAnchor = findOverlayAnchorInMain(root, tree);
					AlphaDiscardAnchor discardAnchor = findAlphaDiscardAnchorInMain(root, tree,
						overlayAnchor != null ? overlayAnchor.albedoVar() : null);

					// 1. Translucency at alpha-discard anchor (BEFORE discard)
					int translucencyStmtsInserted = 0;
					if (discardAnchor != null) {
						String av = discardAnchor.albedoVar();
						Collection<? extends Statement> stmts = t.parseStatements(root,
							"if (!irisW_skyboxApplied) {" +
							IRISW_DEFERRED_TRANSLUCENCY_CODE.replace("ALBEDO_VAR", av) + "}");
						translucencyStmtsInserted = stmts.size();
						mainBody.getStatements().addAll(discardAnchor.topLevelInsertBeforeIndex(), stmts);
					}

					// 2. Skybox + Glint + NearFade + optional entity lighting tweaks at anchor point
					String glintAlbedoVar = null;
					int glintIdx = -1;

					if (overlayAnchor != null) {
						glintAlbedoVar = overlayAnchor.albedoVar();
						glintIdx = overlayAnchor.topLevelInsertAfterIndex();
						if (discardAnchor != null && discardAnchor.topLevelInsertBeforeIndex() <= glintIdx) {
							glintIdx += translucencyStmtsInserted;
						}
					} else if (discardAnchor != null) {
						glintAlbedoVar = discardAnchor.albedoVar();
						glintIdx = discardAnchor.topLevelInsertBeforeIndex() + translucencyStmtsInserted + 1;
					}

					if (glintAlbedoVar != null && glintIdx >= 0) {
						// Insert skybox apply FIRST at the anchor, then shadeless, then glint/nearfade/light tweaks
						String skyboxDeferred = IRISW_SKYBOX_APPLY_DEFERRED.replace("ALBEDO_VAR", glintAlbedoVar);
						Collection<? extends Statement> skyboxStmts = t.parseStatements(root, skyboxDeferred);
						int skyboxStmtsCount = skyboxStmts.size();
						mainBody.getStatements().addAll(glintIdx, skyboxStmts);

						// Shadeless: remove vertex color shading for textures with alpha = 251/255.
						String shadelessDeferred = IRISW_SHADELESS_DEFERRED.replace("ALBEDO_VAR", glintAlbedoVar);
						Collection<? extends Statement> shadelessStmts = t.parseStatements(root, shadelessDeferred);
						int shadelessStmtsCount = shadelessStmts.size();
						mainBody.getStatements().addAll(glintIdx + skyboxStmtsCount, shadelessStmts);

						// Glint + nearfade + optional entity lighting tweaks — guarded by skybox flag
						String entityLightTweaks = applyEntityLightTweaks ?
								"if (!irisW_skipEntityLightTweaks) { bool irisW_eE = irisW_isEmissiveSignal(" + glintAlbedoVar + ");" +
							"  bool irisW_sT = iris_wynncraft_glint >= 15 && iris_wynncraft_glint <= 24;" +
							"  if (irisW_eE && !irisW_sT) {" +
							"  vec3 irisW_eT = texture(Sampler0, iris_wynncraft_texcoord).rgb;" +
							"  " + glintAlbedoVar + ".rgb = mix(" + glintAlbedoVar + ".rgb, max(" + glintAlbedoVar + ".rgb, irisW_eT), iris_wynncraftEntityEmissivity);" +
							"  } else {" +
							"  float irisW_bL = dot(" + glintAlbedoVar + ".rgb, vec3(0.2126, 0.7152, 0.0722));" +
							"  float irisW_bS = mix(iris_wynncraftEntityBoost, 1.0, smoothstep(0.3, 0.8, irisW_bL));" +
							"  float irisW_bM = max(max(" + glintAlbedoVar + ".r, " + glintAlbedoVar + ".g), " + glintAlbedoVar + ".b);" +
							"  if (irisW_bM * irisW_bS > 1.0) { irisW_bS = 1.0 / max(irisW_bM, 1e-5); }" +
							"  " + glintAlbedoVar + ".rgb *= irisW_bS; } }" : "";
						mainBody.getStatements().addAll(glintIdx + skyboxStmtsCount + shadelessStmtsCount,
							t.parseStatements(root,
								"if (!irisW_skyboxApplied) {" +
								IRISW_DEFERRED_GLINT_CODE.replace("ALBEDO_VAR", glintAlbedoVar) +
								glintAlbedoVar + ".rgb *= iris_wynncraft_nearfade;" +
								entityLightTweaks +
								"}"));
					} else {
						// No anchors found — fallback: discard skybox entities (prepended near top).
						// This prevents raw signal quads from leaking through.
						net.irisshaders.iris.gui.option.WynncraftDebugLog.info("skybox-deferred-fallback",
							"[WynnIris] Deferred skybox: no anchors found, falling back to discard");
						tree.prependMainFunctionBody(t, IRISW_SKYBOX_FALLBACK_DISCARD);
					}
				} else {
					// No main body accessible — fallback to discard
					tree.prependMainFunctionBody(t, IRISW_SKYBOX_FALLBACK_DISCARD);
				}

				// Deferred packs multiply albedo by scene lighting, crushing skybox
				// procedural colors to black in dark areas. After the pack writes its
				// gbuffer, overwrite the light channel to max for skybox pixels.
				// layout(location=0).w holds packed light_levels in Photon-style packs;
				// 1.0 = max block + sky light in pack_unorm_2x8 encoding.
				String deferredGbufferOut = resolveLayoutLocation0Name(root);
				if (deferredGbufferOut != null) {
					tree.appendMainFunctionBody(t,
						"if (irisW_skyboxApplied) " + deferredGbufferOut + ".w = 1.0;");
				}
			}

			// Different output name to avoid a name collision in the geometry or tessellation stage.
			if (parameters.hasGeometry) {
				root.rename("entityColor", "entityColorGS");
				root.rename("iris_vertexColor", "iris_vertexColorGS");
				root.rename("iris_wynncraft_glint", "iris_wynncraft_glintGS");
				root.rename("iris_wynncraft_translucency", "iris_wynncraft_translucencyGS");
				root.rename("iris_wynncraft_texcoord", "iris_wynncraft_texcoordGS");
				root.rename("iris_wynncraft_midtex", "iris_wynncraft_midtexGS");
				root.rename("iris_wynncraft_position", "iris_wynncraft_positionGS");
				root.rename("iris_wynncraft_nearfade", "iris_wynncraft_nearfadeGS");
			} else if (parameters.hasTesselation) {
				root.rename("entityColor", "entityColorTES");
				root.rename("iris_vertexColor", "iris_vertexColorTES");
				root.rename("iris_wynncraft_glint", "iris_wynncraft_glintTES");
				root.rename("iris_wynncraft_translucency", "iris_wynncraft_translucencyTES");
				root.rename("iris_wynncraft_texcoord", "iris_wynncraft_texcoordTES");
				root.rename("iris_wynncraft_midtex", "iris_wynncraft_midtexTES");
				root.rename("iris_wynncraft_position", "iris_wynncraft_positionTES");
				root.rename("iris_wynncraft_nearfade", "iris_wynncraft_nearfadeTES");
			}
		}
	}

	// ====================================================================================
	// DEFERRED RENDERING SUPPORT — Mid-main injection anchors
	// ====================================================================================
	// Deferred packs (like Photon) use packed GBuffer outputs instead of simple RGBA
	// fragment outputs. resolveFragOutput() returns null for these packs. Instead of
	// modifying the packed output, we inject effects directly into main() targeting
	// the albedo variable before it gets packed.
	//
	// Two anchors for two different injection points:
	// 1. OverlayAnchor: finds entityColor overlay, used for glint + nearFade (AFTER overlay)
	// 2. AlphaDiscardAnchor: finds alpha test, used for translucency (BEFORE discard)

	private record OverlayAnchor(String albedoVar, int topLevelInsertAfterIndex) {}
	private record AlphaDiscardAnchor(String albedoVar, int topLevelInsertBeforeIndex) {}

	// Find the entityColor overlay assignment in main():
	//   albedo.rgb = mix(albedo.rgb, entityColor.rgb, entityColor.a);
	// Returns the albedo variable name and insertion index (AFTER the overlay), or null.
	private static OverlayAnchor findOverlayAnchorInMain(Root root, TranslationUnit tree) {
		CompoundStatement mainBody;
		try {
			mainBody = tree.getOneMainDefinitionBody();
		} catch (Exception e) {
			return null;
		}

		ChildNodeList<Statement> statements = mainBody.getStatements();
		String lastAlbedoVar = null;
		int lastTopLevelIndex = -1;

		for (int i = 0; i < statements.size(); i++) {
			Statement stmt = statements.get(i);
			String found = findOverlayInStatement(stmt, true);
			if (found == null) {
				found = findOverlayInStatement(stmt, false); // relaxed fallback
			}
			if (found != null) {
				lastAlbedoVar = found;
				// Walk up to find the top-level index if this is nested
				lastTopLevelIndex = i;
			}
		}

		if (lastAlbedoVar == null) return null;

		// Validate: albedo var must be declared at main() top-level scope
		if (!isVarDeclaredInScope(statements, lastAlbedoVar, lastTopLevelIndex)) return null;

		return new OverlayAnchor(lastAlbedoVar, lastTopLevelIndex + 1);
	}

	// Recursively search a statement (and nested blocks) for the entityColor overlay pattern.
	// Returns the albedo variable name if found, null otherwise.
	// strict=true: require mix() arg0 to match LHS base variable
	// strict=false: only require mix(..., entityColor.rgb, entityColor.a)
	private static String findOverlayInStatement(Statement stmt, boolean strict) {
		if (stmt instanceof ExpressionStatement exprStmt) {
			Expression expr = exprStmt.getExpression();
			if (expr instanceof AssignmentExpression assign) {
				return checkOverlayAssignment(assign, strict);
			}
		} else if (stmt instanceof SelectionStatement sel) {
			// Check inside if-blocks
			String found = findOverlayInBody(sel.getIfTrue(), strict);
			if (found != null) return found;
			if (sel.hasIfFalse()) {
				found = findOverlayInBody(sel.getIfFalse(), strict);
				if (found != null) return found;
			}
		} else if (stmt instanceof CompoundStatement compound) {
			for (Statement child : compound.getStatements()) {
				String found = findOverlayInStatement(child, strict);
				if (found != null) return found;
			}
		}
		return null;
	}

	private static String findOverlayInBody(Statement body, boolean strict) {
		if (body instanceof CompoundStatement compound) {
			for (Statement child : compound.getStatements()) {
				String found = findOverlayInStatement(child, strict);
				if (found != null) return found;
			}
		} else {
			return findOverlayInStatement(body, strict);
		}
		return null;
	}

	// Check if an assignment matches: albedo.rgb = mix(arg0, entityColor.rgb, entityColor.a)
	private static String checkOverlayAssignment(AssignmentExpression assign, boolean strict) {
		Expression lhs = assign.getLeft();
		Expression rhs = assign.getRight();

		// LHS must be .rgb member access
		if (!(lhs instanceof MemberAccessExpression lhsMember)) return null;
		if (!"rgb".equals(lhsMember.getMember().getName())) return null;

		// Extract base variable from LHS
		Expression lhsBase = lhsMember.getOperand();
		if (!(lhsBase instanceof ReferenceExpression lhsRef)) return null;
		String albedoVar = lhsRef.getIdentifier().getName();

		// RHS must be mix() call with entityColor args
		if (!(rhs instanceof FunctionCallExpression funcCall)) return null;
		if (funcCall.getFunctionName() == null) return null;
		if (!"mix".equals(funcCall.getFunctionName().getName())) return null;
		if (funcCall.getParameters().size() != 3) return null;

		Expression arg1 = funcCall.getParameters().get(1);
		Expression arg2 = funcCall.getParameters().get(2);

		// arg1 must be entityColor.rgb
		if (!isMemberAccess(arg1, "entityColor", "rgb")) return null;
		// arg2 must be entityColor.a
		if (!isMemberAccess(arg2, "entityColor", "a")) return null;

		// Strict mode: arg0 must reference the same base variable as LHS
		if (strict) {
			Expression arg0 = funcCall.getParameters().get(0);
			if (!(arg0 instanceof MemberAccessExpression arg0Member)) return null;
			Expression arg0Base = arg0Member.getOperand();
			if (!(arg0Base instanceof ReferenceExpression arg0Ref)) return null;
			if (!albedoVar.equals(arg0Ref.getIdentifier().getName())) return null;
		}

		return albedoVar;
	}

	// Check if an expression is `base.member` (e.g., entityColor.rgb)
	private static boolean isMemberAccess(Expression expr, String baseName, String memberName) {
		if (!(expr instanceof MemberAccessExpression member)) return false;
		if (!memberName.equals(member.getMember().getName())) return false;
		Expression operand = member.getOperand();
		if (!(operand instanceof ReferenceExpression ref)) return false;
		return baseName.equals(ref.getIdentifier().getName());
	}

	// Find the earliest alpha-discard statement in main():
	//   if (albedo.a < threshold) { discard; }
	// Returns the albedo variable name and insertion index (BEFORE the discard), or null.
	// If requiredVar is non-null, only matches if the base variable matches.
	private static AlphaDiscardAnchor findAlphaDiscardAnchorInMain(
		Root root, TranslationUnit tree, String requiredVar) {
		CompoundStatement mainBody;
		try {
			mainBody = tree.getOneMainDefinitionBody();
		} catch (Exception e) {
			return null;
		}

		ChildNodeList<Statement> statements = mainBody.getStatements();

		for (int i = 0; i < statements.size(); i++) {
			Statement stmt = statements.get(i);
			if (!(stmt instanceof SelectionStatement sel)) continue;

			// Check if true-body contains discard
			if (!containsDiscard(sel.getIfTrue())) continue;

			// Check if condition contains .a member access, extract base variable
			String alphaVar = findAlphaAccessInExpression(sel.getCondition());
			if (alphaVar == null) continue;

			// If requiredVar specified, must match
			if (requiredVar != null && !requiredVar.equals(alphaVar)) continue;

			// Validate: variable must be declared as vec4 in main() scope before this index
			if (!isVarDeclaredInScope(statements, alphaVar, i)) continue;

			// Reject read-only variables (e.g., `in vec4 tint` / `flat in vec4 tint`).
			// These are input varyings that can't be assigned to. The pack typically
			// copies them to a local (e.g., `vec4 base_color = tint;`) after the discard.
			if (isReadOnlyVariable(root, alphaVar)) continue;

			return new AlphaDiscardAnchor(alphaVar, i);
		}

		return null;
	}

	// Recursively check if a statement contains a discard
	private static boolean containsDiscard(Statement stmt) {
		if (stmt instanceof DiscardStatement) return true;
		if (stmt instanceof CompoundStatement compound) {
			for (Statement child : compound.getStatements()) {
				if (containsDiscard(child)) return true;
			}
		}
		if (stmt instanceof SelectionStatement sel) {
			if (containsDiscard(sel.getIfTrue())) return true;
			if (sel.hasIfFalse() && containsDiscard(sel.getIfFalse())) return true;
		}
		return false;
	}

	// Recursively search an expression for .a member access in an ALPHA TEST context.
	// Only matches `var.a < threshold` or `var.a <= threshold` patterns — NOT `var.a > 0`
	// (which is a presence check, not an alpha test). This prevents false positives from
	// compound conditions like `(mask.a > 0.0) && (base_color.a < cutoff)`.
	private static String findAlphaAccessInExpression(Expression expr) {
		// Alpha test pattern: var.a < threshold — LEFT side only.
		// Do NOT check right side — `0.0 < mask.a` is a presence check, not an alpha test.
		if (expr instanceof io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanExpression ltExpr) {
			return extractAlphaVarDirect(ltExpr.getLeft());
		}
		if (expr instanceof io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanEqualExpression lteExpr) {
			return extractAlphaVarDirect(lteExpr.getLeft());
		}
		// Also accept reversed: threshold > var.a (alpha var on RIGHT only)
		// Do NOT check left side — `mask.a > 0.0` is a presence check, not an alpha test.
		if (expr instanceof io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanExpression gtExpr) {
			return extractAlphaVarDirect(gtExpr.getRight());
		}
		if (expr instanceof io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanEqualExpression gteExpr) {
			return extractAlphaVarDirect(gteExpr.getRight());
		}
		// Recurse into logical operators (&&, ||) to find alpha tests in compound conditions
		if (expr instanceof io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression binExpr) {
			String found = findAlphaAccessInExpression(binExpr.getLeft());
			if (found != null) return found;
			return findAlphaAccessInExpression(binExpr.getRight());
		}
		return null;
	}

	// Extract the base variable name from a direct .a member access (e.g., base_color.a → "base_color")
	private static String extractAlphaVarDirect(Expression expr) {
		if (expr instanceof MemberAccessExpression member) {
			if ("a".equals(member.getMember().getName())) {
				Expression operand = member.getOperand();
				if (operand instanceof ReferenceExpression ref) {
					return ref.getIdentifier().getName();
				}
			}
		}
		return null;
	}

	// Check if a variable is visible at the top-level scope of main() at the given index.
	// The variable must either be declared at file scope (in/uniform/varying) or as a local
	// in main() at the top level (not inside a nested block) before the insertion point.
	private static boolean isVarDeclaredInScope(ChildNodeList<Statement> mainStatements, String varName, int beforeIndex) {
		// Check top-level statements in main() for a declaration of the variable.
		// Declaration statements in GLSL: `vec4 varName = ...;` or `vec4 varName;`
		// These appear as ExpressionStatement wrapping an init, or as declaration nodes.
		for (int i = 0; i < beforeIndex && i < mainStatements.size(); i++) {
			Statement stmt = mainStatements.get(i);
			// Walk all identifiers in this statement looking for the variable name
			// in a declaration context (TypeAndInitDeclaration member)
			if (containsDeclarationOf(stmt, varName)) return true;
		}
		// If not found in a local declaration, check if the variable is referenced
		// in any top-level statement (not just ExpressionStatement). This catches
		// variables declared at file scope (in/uniform/varying) which are always visible.
		// If the variable is ONLY found inside nested blocks (e.g., block-local), it
		// might not be in scope at our top-level insertion point — reject it.
		for (int i = 0; i < mainStatements.size(); i++) {
			Statement stmt = mainStatements.get(i);
			if (stmt instanceof ExpressionStatement exprStmt) {
				if (expressionReferencesVar(exprStmt.getExpression(), varName)) return true;
			} else if (stmt instanceof SelectionStatement sel) {
				// Check the condition (top-level scope) — not the body (nested scope)
				if (expressionReferencesVar(sel.getCondition(), varName)) return true;
			}
		}
		return false;
	}

	// Check if a variable is declared as an input varying (read-only in fragment shader).
	// Scans file-scope declarations for `in vec4 varName` or `flat in vec4 varName`.
	private static boolean isReadOnlyVariable(Root root, String varName) {
		for (DeclarationExternalDeclaration decl : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (!(decl.getDeclaration() instanceof TypeAndInitDeclaration typeDecl)) continue;
			// Check if this declaration contains the variable name
			boolean hasVar = false;
			for (var member : typeDecl.getMembers()) {
				if (varName.equals(member.getName().getName())) {
					hasVar = true;
					break;
				}
			}
			if (!hasVar) continue;
			// Check if it has an `in` storage qualifier (input varying = read-only)
			var fullySpecified = typeDecl.getType();
			if (fullySpecified == null) continue;
			var qualifier = fullySpecified.getTypeQualifier();
			if (qualifier == null) continue;
			for (var part : qualifier.getParts()) {
				if (part instanceof io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier sq
					&& sq.storageType == io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType.IN) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean containsDeclarationOf(Statement stmt, String varName) {
		// Check if this statement declares the given variable name.
		// In glsl-transformer, local variable declarations in function bodies appear as
		// DeclarationStatement nodes. We check via identifier matching on TypeAndInitDeclaration.
		if (stmt instanceof io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement declStmt) {
			var decl = declStmt.getDeclaration();
			if (decl instanceof TypeAndInitDeclaration typeDecl) {
				for (var member : typeDecl.getMembers()) {
					if (varName.equals(member.getName().getName())) return true;
				}
			}
		}
		return false;
	}

	// Check if an expression tree references a variable name (shallow check for top-level usage)
	private static boolean expressionReferencesVar(Expression expr, String varName) {
		if (expr instanceof ReferenceExpression ref) {
			return varName.equals(ref.getIdentifier().getName());
		}
		if (expr instanceof MemberAccessExpression member) {
			return expressionReferencesVar(member.getOperand(), varName);
		}
		if (expr instanceof AssignmentExpression assign) {
			return expressionReferencesVar(assign.getLeft(), varName)
				|| expressionReferencesVar(assign.getRight(), varName);
		}
		if (expr instanceof FunctionCallExpression func) {
			for (Expression param : func.getParameters()) {
				if (expressionReferencesVar(param, varName)) return true;
			}
		}
		return false;
	}

	private record FragOutput(String name, boolean premultiplied) {}

	// Find the name of a layout(location=0) out vec4 variable (the primary gbuffer output
	// on deferred packs). Returns null if not found. Used to overwrite light_levels for
	// skybox pixels after the pack's gbuffer writes.
	private static String resolveLayoutLocation0Name(Root root) {
		for (DeclarationExternalDeclaration decl : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (!(decl.getDeclaration() instanceof TypeAndInitDeclaration typeDecl)) continue;
			var fullySpecified = typeDecl.getType();
			if (fullySpecified == null) continue;
			if (!(fullySpecified.getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier numericType)
				|| numericType.type != Type.F32VEC4) continue;
			var qualifier = fullySpecified.getTypeQualifier();
			if (qualifier == null) continue;
			boolean hasOut = false, hasLocation0 = false;
			for (var part : qualifier.getParts()) {
				if (part instanceof io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier sq
					&& sq.storageType == io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType.OUT)
					hasOut = true;
				if (part instanceof io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifier layout) {
					for (var lp : layout.getParts()) {
						if (lp instanceof io.github.douira.glsl_transformer.ast.node.type.qualifier.NamedLayoutQualifierPart named
							&& "location".equals(named.getName().getName())
							&& named.getExpression() instanceof io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression lit
							&& lit.isInteger() && lit.getInteger() == 0)
							hasLocation0 = true;
					}
				}
			}
			if (hasOut && hasLocation0) {
				for (var member : typeDecl.getMembers()) {
					return member.getName().getName();
				}
			}
		}
		return null;
	}

	// Resolve the fragment output variable name.
	// EntityPatcher runs BEFORE CommonTransformer, so gl_FragData[0] hasn't been renamed
	// to iris_FragData0 yet. We check for the pre-rename names too, but always return
	// the post-rename name since our appended code executes after all transformations.
	// Also scans for layout-qualified output declarations (e.g., Photon's translucent pass
	// uses `layout(location=0) out vec4 fragment_color` which is a forward RGBA output).
	// Returns premultiplied=true for layout-scanned outputs (deferred pack forward passes
	// typically use premultiplied alpha blending: ONE, ONE_MINUS_SRC_ALPHA).
	private static FragOutput resolveFragOutput(Root root) {
		if (root.identifierIndex.has("iris_FragData0")) {
			return new FragOutput("iris_FragData0", false);
		} else if (root.identifierIndex.has("outColor0")) {
			return new FragOutput("outColor0", false);
		} else if (root.identifierIndex.has("gl_FragData") || root.identifierIndex.has("gl_FragColor")) {
			return new FragOutput("iris_FragData0", false);
		}
		// Fallback: scan for layout(location=0) out vec4 declarations.
		// This catches forward translucent passes in deferred packs (e.g., Photon's
		// gbuffers_entities_translucent outputs `fragment_color` with alpha blending).
		// Only match location=0 outputs — these are the primary color target.
		for (DeclarationExternalDeclaration decl : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (!(decl.getDeclaration() instanceof TypeAndInitDeclaration typeDecl)) continue;
			var fullySpecified = typeDecl.getType();
			if (fullySpecified == null) continue;
			// Only match vec4 outputs — vec3 (e.g., shadow shaders) has no .a channel
			var typeSpecifier = fullySpecified.getTypeSpecifier();
			if (!(typeSpecifier instanceof BuiltinNumericTypeSpecifier numericType)
				|| numericType.type != Type.F32VEC4) {
				continue;
			}
			var qualifier = fullySpecified.getTypeQualifier();
			if (qualifier == null) continue;
			boolean hasOut = false;
			boolean hasLocation0 = false;
			for (var part : qualifier.getParts()) {
				if (part instanceof io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier sq
					&& sq.storageType == io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType.OUT) {
					hasOut = true;
				}
				if (part instanceof io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifier layout) {
					for (var layoutPart : layout.getParts()) {
						if (layoutPart instanceof io.github.douira.glsl_transformer.ast.node.type.qualifier.NamedLayoutQualifierPart named
							&& "location".equals(named.getName().getName())
							&& named.getExpression() instanceof io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression lit
							&& lit.isInteger() && lit.getInteger() == 0) {
							hasLocation0 = true;
						}
					}
				}
			}
			if (hasOut && hasLocation0) {
				// Found layout(location=0) out — get the variable name.
				// Only use it if the variable is assigned as a WHOLE vec4 somewhere in
				// the shader (forward rendering). Deferred GBuffer outputs are only assigned
				// component-wise (e.g., gbuffer_data_0.x = pack(...)), so this distinguishes
				// forward translucent passes from deferred solid passes.
				for (var member : typeDecl.getMembers()) {
					String name = member.getName().getName();
					// Check for whole-variable assignment (forward) vs component-only (deferred)
					for (Identifier id : root.identifierIndex.get(name)) {
						if (id.getParent() instanceof ReferenceExpression ref
							&& ref.getParent() instanceof AssignmentExpression assign
							&& assign.getLeft() == ref) {
							// Direct assignment to the variable (not .x, .rgb, etc.) — forward output.
							// Layout-scanned outputs are from deferred pack forward passes which
							// typically use premultiplied alpha (ONE, ONE_MINUS_SRC_ALPHA).
							return new FragOutput(name, true);
						}
					}
				}
			}
		}
		return null;
	}

	private static boolean hasGlFragDataIndex(Root root, int targetIndex) {
		for (Identifier id : root.identifierIndex.get("gl_FragData")) {
			ArrayAccessExpression accessExpression = id.getAncestor(ArrayAccessExpression.class);
			if (accessExpression != null
				&& accessExpression.getRight() instanceof LiteralExpression literalExpression
				&& literalExpression.isInteger()
				&& literalExpression.getInteger() == targetIndex) {
				return true;
			}
		}
		return false;
	}

	// Append translucency alpha reduction to the fragment shader. Shared by both
	// patchOverlayColor and patchTranslucencyOnly to keep the logic in sync.
	// premultiplied=false (standard packs like BSL): only modify .a — the blend equation
	//   (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) handles the rgb weighting via alpha.
	// premultiplied=true (deferred forward passes like Photon translucent): multiply ALL
	//   channels (rgba) — the blend equation (ONE, ONE_MINUS_SRC_ALPHA) expects rgb to
	//   already be scaled by alpha.
	private static void appendTranslucencyAlpha(ASTParser t, TranslationUnit tree, String fragOutput, boolean premultiplied) {
		// Target alpha mirrors Wynncraft RP include/translucency.glsl:
		//   applyTranslucent(level/100.0) => color.a = mix(color.a, 0.0, level/100.0)
		//                                 = color.a * (1.0 - level/100.0)
		// We CLAMP to the target rather than multiply so this composes safely with vertex-side
		// propagation in VanillaCoreTransformer / VanillaTransformer: if the pack already
		// honored reduced vaColor.a / gl_Color.a, FRAG_OUTPUT.a is already ≤ target and min(...)
		// leaves it untouched; if the pack overwrote alpha back to 1.0, min(...) restores the
		// target. This avoids the prior double-multiplication that pushed alpha below pack
		// discard thresholds and invisibilized low-VFX particles.
		// premultiplied=true (deferred-forward blend ONE, ONE_MINUS_SRC_ALPHA): scale rgb to
		// match the clamped alpha so pre-multiplication stays consistent.
		if (premultiplied) {
			tree.appendMainFunctionBody(t, """
				if (iris_wynncraft_translucency > 0) {
				    float irisW_targetA = max(0.10, 1.0 - float(iris_wynncraft_translucency) / 100.0);
				    float irisW_newA = min(FRAG_OUTPUT.a, irisW_targetA);
				    // When no clamp is needed (newA == oldA), scale is 1.0 — RGB stays put.
				    // When oldA is exactly 0.0, rgb is already 0 under premultiplied so any scale
				    // is a no-op; use 0.0 to avoid a division.
				    float irisW_scale = FRAG_OUTPUT.a > 0.0 ? irisW_newA / FRAG_OUTPUT.a : 0.0;
				    FRAG_OUTPUT.rgb *= irisW_scale;
				    FRAG_OUTPUT.a = irisW_newA;
				}
				""".replace("FRAG_OUTPUT", fragOutput));
		} else {
			tree.appendMainFunctionBody(t, """
				if (iris_wynncraft_translucency > 0) {
				    FRAG_OUTPUT.a = min(FRAG_OUTPUT.a, max(0.10, 1.0 - float(iris_wynncraft_translucency) / 100.0));
				}
				""".replace("FRAG_OUTPUT", fragOutput));
		}
	}

	// Standalone translucency patcher for shaders that have Color but NOT overlay.
	// Display entities may render through non-overlay shader paths, so EntityPatcher's
	// patchOverlayColor (which requires overlay) doesn't run for them. This method adds
	// just the translucency varying and alpha reduction, without glint/overlay handling.
	public static void patchTranslucencyOnly(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		VanillaParameters parameters) {

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat out int iris_wynncraft_translucency;");
			tree.prependMainFunctionBody(t,
				IRISW_TRANSLUCENCY_DETECT,
				"iris_wynncraft_translucency = iris_wynn_isTranslucent ? int(round(iris_Color.r * 255.0)) : 0;");
		} else if (parameters.type.glShaderType == ShaderType.TESSELATION_CONTROL) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat in int iris_wynncraft_translucency[];",
				"flat out int iris_wynncraft_translucencyTCS[];");
			tree.prependMainFunctionBody(t,
				"iris_wynncraft_translucencyTCS[gl_InvocationID] = iris_wynncraft_translucency[gl_InvocationID];");
		} else if (parameters.type.glShaderType == ShaderType.TESSELATION_EVAL) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat in int iris_wynncraft_translucencyTCS[];",
				"flat out int iris_wynncraft_translucencyTES;");
			tree.prependMainFunctionBody(t,
				"iris_wynncraft_translucencyTES = iris_wynncraft_translucencyTCS[0];");
		} else if (parameters.type.glShaderType == ShaderType.GEOMETRY) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat in int iris_wynncraft_translucency" + (parameters.hasTesselation ? "TES" : "") + "[];",
				"flat out int iris_wynncraft_translucencyGS;");
			tree.prependMainFunctionBody(t,
				"iris_wynncraft_translucencyGS = iris_wynncraft_translucency" + (parameters.hasTesselation ? "TES" : "") + "[0];");
		} else if (parameters.type.glShaderType == ShaderType.FRAGMENT) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat in int iris_wynncraft_translucency;");
			// Apply translucency alpha reduction in fragment.
			// Forward packs: append to end of main() targeting fragment output.
			// Deferred packs: inject before alpha-discard targeting the albedo variable.
			FragOutput fragOutput = resolveFragOutput(root);
			if (fragOutput != null) {
				// FORWARD PATH: existing behavior
				appendTranslucencyAlpha(t, tree, fragOutput.name(), fragOutput.premultiplied());
			} else {
				// DEFERRED PATH: find alpha-discard anchor
				// Use dithered discard for deferred packs (no alpha blending at GBuffer stage).
				AlphaDiscardAnchor anchor = findAlphaDiscardAnchorInMain(root, tree, null);
				if (anchor != null) {
					CompoundStatement mainBody = tree.getOneMainDefinitionBody();
					String av = anchor.albedoVar();
					mainBody.getStatements().addAll(anchor.topLevelInsertBeforeIndex(),
						t.parseStatements(root, IRISW_DEFERRED_TRANSLUCENCY_CODE.replace("ALBEDO_VAR", av)));
				}
				// else: no-op — deferred pack without recognized alpha-discard pattern
			}

			if (parameters.hasGeometry) {
				root.rename("iris_wynncraft_translucency", "iris_wynncraft_translucencyGS");
			} else if (parameters.hasTesselation) {
				root.rename("iris_wynncraft_translucency", "iris_wynncraft_translucencyTES");
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
				irisw_decodeBlockEntityId("iris_entityInfo[0].y"));

			root.replaceReferenceExpressions(t, "currentRenderedItemId",
				"iris_entityInfo[0].z");
		} else {
			root.replaceReferenceExpressions(t, "entityId",
				"iris_entityInfo.x");

			root.replaceReferenceExpressions(t, "blockEntityId",
				irisw_decodeBlockEntityId("iris_entityInfo.y"));

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

	private static String irisw_decodeBlockEntityId(String blockEntityIdExpression) {
		return "(" + blockEntityIdExpression + " - (" + blockEntityIdExpression + " / 16384) * 16384)";
	}
}
