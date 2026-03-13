package net.irisshaders.iris.pipeline.programs;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.jetbrains.annotations.Nullable;

public class ShaderOverrides {
	@Nullable
	public static ProgramId detectProgramId(IrisRenderingPipeline pipeline) {
		if (pipeline == null) return null;

		WorldRenderingPhase phase = pipeline.getPhase();
		return switch (phase) {
			case NONE -> null;
			case SKY, SUNSET, SUN, MOON, STARS, VOID, CUSTOM_SKY -> ProgramId.SkyBasic;
			case TERRAIN_SOLID, TERRAIN_CUTOUT, TERRAIN_CUTOUT_MIPPED -> ProgramId.Terrain;
			case TERRAIN_TRANSLUCENT, TRIPWIRE -> ProgramId.Water;
			case ENTITIES -> pipeline.isBeforeTranslucent ? ProgramId.Entities : ProgramId.EntitiesTrans;
			case BLOCK_ENTITIES -> pipeline.isBeforeTranslucent ? ProgramId.Block : ProgramId.BlockTrans;
			case PARTICLES -> pipeline.isBeforeTranslucent ? ProgramId.Particles : ProgramId.ParticlesTrans;
			case CLOUDS -> ProgramId.Clouds;
			case RAIN_SNOW -> ProgramId.Weather;
			case HAND_SOLID -> ProgramId.Hand;
			case HAND_TRANSLUCENT -> ProgramId.HandWater;
			case DESTROY -> ProgramId.DamagedBlock;
			case DEBUG, OUTLINE, WORLD_BORDER -> ProgramId.Basic;
		};
	}

	@Nullable
	public static ProgramId detectShadowProgramId(IrisRenderingPipeline pipeline) {
		if (pipeline == null) return null;

		WorldRenderingPhase phase = pipeline.getPhase();
		return switch (phase) {
			case NONE -> null;
			case TERRAIN_SOLID, TERRAIN_CUTOUT, TERRAIN_CUTOUT_MIPPED -> ProgramId.ShadowCutout;
			case TERRAIN_TRANSLUCENT, TRIPWIRE -> ProgramId.ShadowWater;
			case ENTITIES, HAND_SOLID, HAND_TRANSLUCENT -> ProgramId.ShadowEntities;
			case BLOCK_ENTITIES -> ProgramId.ShadowBlock;
			default -> ProgramId.Shadow;
		};
	}

	public static ShaderKey getSkyShader(IrisRenderingPipeline pipeline) {
		if (isSky(pipeline)) {
			return ShaderKey.SKY_BASIC;
		} else {
			return ShaderKey.BASIC;
		}
	}

	public static ShaderKey getSkyTexShader(IrisRenderingPipeline pipeline) {
		if (isSky(pipeline)) {
			return ShaderKey.SKY_TEXTURED;
		} else {
			return ShaderKey.TEXTURED;
		}
	}

	public static ShaderKey getSkyTexColorShader(IrisRenderingPipeline pipeline) {
		if (isSky(pipeline)) {
			return ShaderKey.SKY_TEXTURED_COLOR;
		} else {
			return ShaderKey.TEXTURED_COLOR;
		}
	}

	public static ShaderKey getSkyColorShader(IrisRenderingPipeline pipeline) {
		if (isSky(pipeline)) {
			return ShaderKey.SKY_BASIC_COLOR;
		} else {
			return ShaderKey.BASIC_COLOR;
		}
	}

	public static boolean isBlockEntities(IrisRenderingPipeline pipeline) {
		return pipeline != null && pipeline.getPhase() == WorldRenderingPhase.BLOCK_ENTITIES;
	}

	public static boolean isEntities(IrisRenderingPipeline pipeline) {
		return pipeline != null && pipeline.getPhase() == WorldRenderingPhase.ENTITIES;
	}

	public static boolean isSky(IrisRenderingPipeline pipeline) {
		if (pipeline != null) {
			return switch (pipeline.getPhase()) {
				case CUSTOM_SKY, SKY, SUNSET, SUN, STARS, VOID, MOON -> true;
				default -> false;
			};
		} else {
			return false;
		}
	}

	// ignored: getRendertypeEndGatewayShader (we replace the end portal rendering for shaders)
	// ignored: getRendertypeEndPortalShader (we replace the end portal rendering for shaders)

	public static boolean isPhase(IrisRenderingPipeline pipeline, WorldRenderingPhase phase) {
		if (pipeline != null) {
			return pipeline.getPhase() == phase;
		} else {
			return false;
		}
	}
}
