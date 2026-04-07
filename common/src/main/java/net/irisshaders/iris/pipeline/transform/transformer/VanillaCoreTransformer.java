package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.util.Type;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.VanillaParameters;

import static net.irisshaders.iris.pipeline.transform.transformer.CommonTransformer.addIfNotExists;

public class VanillaCoreTransformer {
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
			// No overlay but has Color + Normal — display entities may render through this path.
			// Normal check excludes particles/weather (which have Color but no Normal).
			EntityPatcher.patchTranslucencyOnly(t, tree, root, parameters);
		}

		// Wynncraft text display entity brightness boost: text shaders are excluded from
		// EntityPatcher (isText() check above), but still need brightness boost when a
		// custom skybox is active. Text gets 2x the entity boost for readability.
		if (parameters.inputs.isText() && parameters.type.glShaderType == ShaderType.FRAGMENT) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform float iris_wynncraftEntityBoost;");
			// Text gets 2x boost (squared) since text readability is critical.
			// Also luminance-aware: bright text gets less boost.
			tree.appendMainFunctionBody(t, """
				{
				    float irisW_textBoost = iris_wynncraftEntityBoost * iris_wynncraftEntityBoost;
				    float irisW_textLuma = dot(iris_FragData0.rgb, vec3(0.2126, 0.7152, 0.0722));
				    float irisW_textScale = mix(irisW_textBoost, 1.0, smoothstep(0.3, 0.8, irisW_textLuma));
				    iris_FragData0.rgb *= irisW_textScale;
				}
				""");
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
				// Neutralize Wynncraft glint (G=255) and translucency (G=254) signals to white.
				// Both get full alpha — translucency is applied fragment-side only to avoid double-multiplication.
				String signalNeutral = "(iris_Color.g > 0.998 && iris_Color.b < 0.01 && iris_Color.r > 0.002 && iris_Color.r < 0.99 ? vec4(1.0)"
					+ " : iris_Color.g > 0.994 && iris_Color.g < 0.998 && iris_Color.b < 0.01 && iris_Color.r > 0.002 && iris_Color.r < 0.998 ? vec4(1.0)"
					+ " : iris_Color)";
				String translucencyOnlyNeutral = "(iris_Color.g > 0.994 && iris_Color.g < 0.998 && iris_Color.b < 0.01 && iris_Color.r > 0.002 && iris_Color.r < 0.998 ? vec4(1.0) : iris_Color)";
				if (parameters.inputs.hasOverlay() && !parameters.inputs.isText()) {
					// Entity: neutralize both glint and translucency signals
					root.replaceReferenceExpressions(t, "vaColor", signalNeutral + " * iris_transforms.ColorModulator");
					root.replaceReferenceExpressions(t, "gl_Color", signalNeutral + " * iris_transforms.ColorModulator");
				} else if (!parameters.inputs.isText() && parameters.inputs.hasNormal()) {
					// Non-overlay with Normal (entities/display entities): neutralize translucency signals
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
