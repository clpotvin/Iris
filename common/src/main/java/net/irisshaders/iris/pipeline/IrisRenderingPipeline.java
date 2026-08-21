package net.irisshaders.iris.pipeline;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.ambience.AmbienceRenderTargetPool;
import net.irisshaders.iris.ambience.AmbienceSwitchTiming;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeFunction;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.image.ImageClearPass;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.gui.option.WynncraftDebugLog;
import net.irisshaders.iris.helpers.FakeChainedJsonException;
import net.irisshaders.iris.helpers.OptionalBoolean;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.mixin.GlStateManagerAccessor;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.mixinterface.RenderTargetInterface;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pathways.HorizonRenderer;
import net.irisshaders.iris.pathways.WynncraftBiomeFogRenderer;
import net.irisshaders.iris.pathways.WynncraftSkyboxRenderer;
import net.irisshaders.iris.pathways.WynncraftTransitionRenderer;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.pathways.colorspace.ColorSpaceConverter;
import net.irisshaders.iris.pathways.colorspace.ColorSpaceFragmentConverter;
import net.irisshaders.iris.pbr.format.TextureFormat;
import net.irisshaders.iris.pbr.format.TextureFormatLoader;
import net.irisshaders.iris.pbr.texture.PBRTextureHolder;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.pbr.texture.PBRType;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderLoadingMap;
import net.irisshaders.iris.pipeline.programs.ShaderMap;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.samplers.IrisImages;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.FilledIndirectPointer;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowCompositeRenderer;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.targets.Blaze3dRenderTargetExt;
import net.irisshaders.iris.targets.BufferFlipper;
import net.irisshaders.iris.targets.ClearPass;
import net.irisshaders.iris.targets.ClearPassCreator;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.MatrixUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.dimension.DimensionType;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class IrisRenderingPipeline implements WorldRenderingPipeline, ShaderRenderingPipeline {
	private static final int WYNNCRAFT_PHOTON_VFX_TRANSLUCENT_BUFFER = 13;
	private static final int PHOTON_CLOUD_HISTORY_TARGET = 11;
	private static final int PHOTON_CLOUD_DATA_TARGET = 12;
	private static final Vector4f PHOTON_CLOUD_HISTORY_NO_OCCLUSION_CLEAR = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
	private static final Vector4f PHOTON_CLOUD_DATA_FAR_CLEAR = new Vector4f(65504.0F, 0.0F, 0.0F, 0.0F);
	private static final int[] MAIN_COLOR_DRAW_BUFFER = new int[]{0};
	private static final int[] WYNNCRAFT_PHOTON_VFX_DRAW_BUFFER = new int[]{WYNNCRAFT_PHOTON_VFX_TRANSLUCENT_BUFFER};
	private static final BlendModeOverride WYNNCRAFT_PHOTON_VFX_BLEND = new BlendModeOverride(new BlendMode(
		BlendModeFunction.ONE.getGlId(),
		BlendModeFunction.ONE_MINUS_SRC_ALPHA.getGlId(),
		BlendModeFunction.ONE.getGlId(),
		BlendModeFunction.ONE_MINUS_SRC_ALPHA.getGlId()));

	@Nullable
	private final AmbienceRenderTargetPool ambiencePool;
	@Nullable
	private final AmbienceRenderTargetPool.Allocation ambiencePoolAllocation;
	private final List<AmbienceRenderTargetPool.ResourceRef> ambienceCustomImageRefs = new ArrayList<>();
	private final RenderTargets renderTargets;
	private final ShaderMap shaderMap;
	private final CustomUniforms customUniforms;
	private final ShadowCompositeRenderer shadowCompositeRenderer;
	private final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> customTextureMap;
	private final ComputeProgram[] setup;
	private final boolean separateHardwareSamplers;
	private final ProgramFallbackResolver resolver;
	private final boolean wynncraftPhotonShaderPack;
	private final boolean wynncraftFallbackVfxTranslucency;
	private final Supplier<ShadowRenderTargets> shadowTargetsSupplier;
	private final Set<GlProgram> loadedShaders;
	private final List<GbufferFramebufferBinding> gbufferFramebuffers = new ArrayList<>();
	// Distant Horizons framebuffers are tracked separately from gbufferFramebuffers: they need their
	// pooled color attachments refreshed on resize, but must NOT have Minecraft's depth re-attached
	// (DH owns its depth). See refreshPooledFramebufferAttachments / RenderTargets.refreshDHFramebuffer.
	private final List<GbufferFramebufferBinding> dhFramebuffers = new ArrayList<>();
	private final CompositeRenderer beginRenderer;
	private final CompositeRenderer prepareRenderer;
	private final CompositeRenderer deferredRenderer;
	/**
	 * Optional: when the active pack integrates with Voxy and declares aux
	 * translucent colortex targets, this pass clears those targets at entity
	 * pixels before deferred compositing, preventing Voxy LOD water from
	 * bleeding through entities. Null if Voxy is absent or not applicable.
	 */
	private final net.irisshaders.iris.pathways.VoxyEntityDepthClearPass voxyEntityDepthClear;

	/**
	 * Voxy LOD depth texture access for the Wynncraft skybox sky classifier.
	 * Null when Voxy is absent. Present whenever Voxy's Iris pipeline data is
	 * reachable — independent of the aux-target requirement above.
	 */
	private final net.irisshaders.iris.pathways.VoxyLodDepth voxyLodDepth;
	private final CompositeRenderer compositeRenderer;
	private final FinalPassRenderer finalPassRenderer;
	private final CustomTextureManager customTextureManager;
	private final DynamicTexture whitePixel;
	private final FrameUpdateNotifier updateNotifier;
	private final CenterDepthSampler centerDepthSampler;
	private final ColorSpaceConverter colorSpaceConverter;
	private final ImmutableSet<Integer> flippedBeforeShadow;
	private final ImmutableSet<Integer> flippedAfterPrepare;
	private final ImmutableSet<Integer> flippedAfterTranslucent;
	private final HorizonRenderer horizonRenderer = new HorizonRenderer();
	@Nullable
	private WynncraftBiomeFogRenderer wynncraftBiomeFogRenderer;
	@Nullable
	private WynncraftSkyboxRenderer wynncraftSkyboxRenderer;
	@Nullable
	private WynncraftTransitionRenderer wynncraftTransitionRenderer;
	@Nullable
	private final ComputeProgram[] shadowComputes;
	private final float sunPathRotation;
	private final boolean shouldRenderUnderwaterOverlay;
	private final boolean shouldRenderVignette;
	private final boolean shouldWriteRainAndSnowToDepthBuffer;
	private final boolean oldLighting;
	private final OptionalInt forcedShadowRenderDistanceChunks;
	private final boolean frustumCulling;
	private final boolean occlusionCulling;
	private final CloudSetting cloudSetting;
	private final boolean shouldRenderSun;
	private final boolean shouldRenderWeather;
	private final boolean shouldRenderWeatherParticles;
	private final boolean shouldRenderMoon;
	private final boolean shouldRenderStars;
	private final boolean shouldRenderSkyDisc;
	private final boolean allowConcurrentCompute;
	@Nullable
	private final ShadowRenderer shadowRenderer;
	private final int shadowMapResolution;
	private final ParticleRenderingSettings particleRenderingSettings;
	private final PackDirectives packDirectives;
	private final Set<GlImage> customImages;
	private final ImmutableList<ImageClearPass> clearImages;
	private final ShaderPack pack;
	private final PackShadowDirectives shadowDirectives;
	private final DHCompat dhCompat;
	private final int stackSize = 0;
	private final boolean skipAllRendering;
	private final CloudSetting dhCloudSetting;
	private final SodiumPrograms sodiumPrograms;
	public boolean isBeforeTranslucent;
	private boolean initializedBlockIds;
	private ShaderStorageBufferHolder shaderStorageBufferHolder;
	private ShadowRenderTargets shadowRenderTargets;
	private WorldRenderingPhase overridePhase = null;
	private WorldRenderingPhase phase = WorldRenderingPhase.NONE;
	private ImmutableList<ClearPass> clearPassesFull;
	private ImmutableList<ClearPass> clearPasses;
	private ImmutableList<ClearPass> shadowClearPasses;
	private ImmutableList<ClearPass> shadowClearPassesFull;
	private boolean destroyed = false;
	private boolean isRenderingWorld;
	private boolean isMainBound;
	private boolean shouldBindPBR;
	private boolean runSetupComputesOnNextFrame;
	private int ambiencePhotonCloudHistoryReseedFrames;
	private static int ambiencePresentationMaskFrames;
	private static int ambiencePreviousFrameTexture;
	private static int ambiencePreviousFrameWidth;
	private static int ambiencePreviousFrameHeight;
	private static boolean ambiencePreviousFrameReady;
	@Nullable
	private static GlFramebuffer ambiencePreviousFrameFramebuffer;
	private AbstractTexture currentNormalTexture;
	private AbstractTexture currentSpecularTexture;
	private ColorSpace currentColorSpace;
	private GlFramebuffer defaultFB;
	private GlFramebuffer defaultFBAlt;
	private GlFramebuffer defaultFBShadow;
	private final boolean supportsEndFlash;
	private GlSampler normalSampler = GlSampler.MIPPED_NEAREST, specularSampler = GlSampler.MIPPED_NEAREST;

	private int albedoTex;


	// Skybox fog/sky state: tracks active skybox for fog color override + post-process primary.
	// Public for iris_wynncraftPrimarySkyboxId uniform access from CommonUniforms.
	public static int displayedSkyboxId = 0;
	private long lastDetectionTimeMs = 0;
	private long skyboxFadeInStartMs = 0;
	public static float skyboxFadeOpacity = 0.0f;

	public IrisRenderingPipeline(ProgramSet programSet) {
		long constructorStartNanos = System.nanoTime();
		ShaderPrinter.resetPrintState();
		this.ambiencePool = Iris.getAmbienceRenderTargetPoolForPipelineBuild();
		this.ambiencePoolAllocation = ambiencePool == null ? null : ambiencePool.createAllocation(Iris.getAmbienceRenderTargetPoolProfileKeyForPipelineBuild());
		boolean constructed = false;
		try {

		this.shouldRenderUnderwaterOverlay = programSet.getPackDirectives().underwaterOverlay();
		this.supportsEndFlash = programSet.getPackDirectives().supportsEndFlash();
		this.shouldRenderVignette = programSet.getPackDirectives().vignette();
		this.shouldWriteRainAndSnowToDepthBuffer = programSet.getPackDirectives().rainDepth();
		this.oldLighting = programSet.getPackDirectives().isOldLighting();
		this.updateNotifier = new FrameUpdateNotifier();
		this.packDirectives = programSet.getPackDirectives();
		this.customTextureMap = programSet.getPackDirectives().getTextureMap();
		this.separateHardwareSamplers = programSet.getPack().hasFeature(FeatureFlags.SEPARATE_HARDWARE_SAMPLERS);
		this.shadowDirectives = packDirectives.getShadowDirectives();
		this.cloudSetting = programSet.getPackDirectives().getCloudSetting();
		this.dhCloudSetting = programSet.getPackDirectives().getDHCloudSetting();
		this.shouldRenderSun = programSet.getPackDirectives().shouldRenderSun();
		this.shouldRenderWeather = programSet.getPackDirectives().shouldRenderWeather();
		this.shouldRenderWeatherParticles = programSet.getPackDirectives().shouldRenderWeatherParticles();
		this.shouldRenderMoon = programSet.getPackDirectives().shouldRenderMoon();
		this.shouldRenderStars = programSet.getPackDirectives().shouldRenderStars();
		this.shouldRenderSkyDisc = programSet.getPackDirectives().shouldRenderSkyDisc();
		this.allowConcurrentCompute = programSet.getPackDirectives().getConcurrentCompute();
		this.skipAllRendering = programSet.getPackDirectives().skipAllRendering();
		this.frustumCulling = programSet.getPackDirectives().shouldUseFrustumCulling();
		this.occlusionCulling = programSet.getPackDirectives().shouldUseOcclusionCulling();
		this.resolver = new ProgramFallbackResolver(programSet);
		String packName = Iris.getCurrentPackName();
		this.wynncraftPhotonShaderPack = packName != null && packName.toLowerCase(Locale.ROOT).contains("photon");
		boolean hasEntitiesTrans = programSet.get(ProgramId.EntitiesTrans).isPresent();
		this.wynncraftFallbackVfxTranslucency = wynncraftPhotonShaderPack && !hasEntitiesTrans;
		this.pack = programSet.getPack();

		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
		GpuTexture depthTexture  = main.getDepthTexture();
		int internalFormat = GlConst.toGlInternalId(depthTexture.getFormat());
		DepthBufferFormat depthBufferFormat = DepthBufferFormat.fromGlEnumOrDefault(internalFormat);

		if (!pack.getBufferObjects().isEmpty()) {
			if (IrisRenderSystem.supportsSSBO()) {
				this.shaderStorageBufferHolder = new ShaderStorageBufferHolder(pack.getBufferObjects(), main.width, main.height);

				this.shaderStorageBufferHolder.setupBuffers();
			} else {
				throw new IllegalStateException("Shader storage buffers/immutable buffer storage is not supported on this graphics card, however the shaderpack requested them? This shouldn't be possible.");
			}
		} else {
			for (int i = 0; i < Math.min(16, SamplerLimits.get().getMaxShaderStorageUnits()); i++) {
				IrisRenderSystem.bindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, i, 0);
			}
		}

		long customImagesStartNanos = System.nanoTime();
		this.customImages = new HashSet<>();
		for (ImageInformation information : programSet.getPack().getIrisCustomImages()) {
			if (ambiencePool != null && !information.isRelative() && information.clear()) {
				AmbienceRenderTargetPool.AcquiredImage acquired = ambiencePool.acquireCustomImage(ambiencePoolAllocation, information);
				customImages.add(acquired.image());
				ambienceCustomImageRefs.add(acquired.ref());
			} else if (information.isRelative()) {
				customImages.add(new GlImage.Relative(information.name(), information.samplerName(), information.format(), information.internalTextureFormat(), information.type(), information.clear(), information.relativeWidth(), information.relativeHeight(), main.width, main.height));
			} else {
				customImages.add(new GlImage(information.name(), information.samplerName(), information.target(), information.format(), information.internalTextureFormat(), information.type(), information.clear(), information.width(), information.height(), information.depth()));
			}
		}
		long customImagesNanos = System.nanoTime() - customImagesStartNanos;

		this.clearImages = customImages.stream()
			.filter(GlImage::shouldClear)
			.map(ImageClearPass::create)
			.collect(ImmutableList.toImmutableList());

		// Post-process biome fog (mushroom_fields close fog for Mist Woods).
		wynncraftBiomeFogRenderer = new WynncraftBiomeFogRenderer(main.width, main.height);
		// Post-process skybox (primary only — cutouts render in-shader via EntityPatcher).
		wynncraftSkyboxRenderer = new WynncraftSkyboxRenderer(main.width, main.height);
		wynncraftTransitionRenderer = new WynncraftTransitionRenderer(main.width, main.height);

		if (programSet.getPackDirectives().getParticleRenderingSettings() != ParticleRenderingSettings.UNSET) {
			this.particleRenderingSettings = programSet.getPackDirectives().getParticleRenderingSettings();
		} else if (programSet.getComposite(ProgramArrayId.Deferred).length > 0 && !programSet.getPackDirectives().shouldUseSeparateEntityDraws()) {
			this.particleRenderingSettings = ParticleRenderingSettings.AFTER;
		} else {
			this.particleRenderingSettings = ParticleRenderingSettings.MIXED;
		}

		long renderTargetsStartNanos = System.nanoTime();
		this.renderTargets = new RenderTargets(main.width, main.height, depthTexture, ((Blaze3dRenderTargetExt) main).iris$getDepthBufferVersion(), depthBufferFormat, programSet.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings(), programSet.getPackDirectives(), ambiencePool, ambiencePoolAllocation);
		long renderTargetsNanos = System.nanoTime() - renderTargetsStartNanos;
		this.sunPathRotation = programSet.getPackDirectives().getSunPathRotation();

		PackShadowDirectives shadowDirectives = programSet.getPackDirectives().getShadowDirectives();

		if (shadowDirectives.isDistanceRenderMulExplicit()) {
			if (shadowDirectives.getDistanceRenderMul() >= 0.0) {
				// add 15 and then divide by 16 to ensure we're rounding up
				forcedShadowRenderDistanceChunks =
					OptionalInt.of(((int) (shadowDirectives.getDistance() * shadowDirectives.getDistanceRenderMul()) + 15) / 16);
			} else {
				forcedShadowRenderDistanceChunks = OptionalInt.of(-1);
			}
		} else {
			forcedShadowRenderDistanceChunks = OptionalInt.empty();
		}

		this.customUniforms = programSet.getPack().customUniforms.build(
			holder -> CommonUniforms.addNonDynamicUniforms(holder, programSet.getPack().getIdMap(), programSet.getPackDirectives(), this.updateNotifier)
		);

		// Don't clobber anything in texture unit 0. It probably won't cause issues, but we're just being cautious here.
		GlStateManager._activeTexture(GL20C.GL_TEXTURE2);

		customTextureManager = new CustomTextureManager(programSet.getPackDirectives(), programSet.getPack().getCustomTextureDataMap(), programSet.getPack().getIrisCustomTextureDataMap(), programSet.getPack().getCustomNoiseTexture());
		whitePixel = new NativeImageBackedSingleColorTexture(255, 255, 255, 255);

		GlStateManager._activeTexture(GL20C.GL_TEXTURE0);

		BufferFlipper flipper = new BufferFlipper();

		this.centerDepthSampler = new CenterDepthSampler(() -> renderTargets.getDepthTexture().iris$getGlId(), programSet.getPackDirectives().getCenterDepthHalfLife());

		this.shadowMapResolution = programSet.getPackDirectives().getShadowDirectives().getResolution();

		this.shadowTargetsSupplier = () -> {
			if (shadowRenderTargets == null) {
				// TODO: Support more than two shadowcolor render targets
				this.shadowRenderTargets = new ShadowRenderTargets(this, shadowMapResolution, shadowDirectives, ambiencePool, ambiencePoolAllocation);
			}

			return shadowRenderTargets;
		};

		if (shadowDirectives.isShadowEnabled() == OptionalBoolean.TRUE) {
			shadowTargetsSupplier.get();
		}

		this.shadowComputes = createShadowComputes(programSet.getShadowCompute(), programSet);

		if (FullScreenQuadRenderer.init() != -1) throw new IllegalStateException("WHY");

		this.beginRenderer = new CompositeRenderer(this, CompositePass.BEGIN, programSet.getPackDirectives(), programSet.getComposite(ProgramArrayId.Begin), programSet.getCompute(ProgramArrayId.Begin), renderTargets, shaderStorageBufferHolder,
			customTextureManager.getNoiseTexture(), updateNotifier, centerDepthSampler, flipper, shadowTargetsSupplier, TextureStage.BEGIN,
			customTextureManager.getCustomTextureIdMap().getOrDefault(TextureStage.BEGIN, Object2ObjectMaps.emptyMap()), customTextureManager.getIrisCustomTextures(), customImages,
			programSet.getPackDirectives().getExplicitFlips("begin_pre"), customUniforms);

		flippedBeforeShadow = flipper.snapshot();

		this.prepareRenderer = new CompositeRenderer(this, CompositePass.PREPARE, programSet.getPackDirectives(), programSet.getComposite(ProgramArrayId.Prepare), programSet.getCompute(ProgramArrayId.Prepare), renderTargets, shaderStorageBufferHolder,
			customTextureManager.getNoiseTexture(), updateNotifier, centerDepthSampler, flipper, shadowTargetsSupplier, TextureStage.PREPARE,
			customTextureManager.getCustomTextureIdMap().getOrDefault(TextureStage.PREPARE, Object2ObjectMaps.emptyMap()), customTextureManager.getIrisCustomTextures(), customImages,
			programSet.getPackDirectives().getExplicitFlips("prepare_pre"), customUniforms);

		flippedAfterPrepare = flipper.snapshot();

		this.deferredRenderer = new CompositeRenderer(this, CompositePass.DEFERRED, programSet.getPackDirectives(), programSet.getComposite(ProgramArrayId.Deferred), programSet.getCompute(ProgramArrayId.Deferred), renderTargets, shaderStorageBufferHolder,
			customTextureManager.getNoiseTexture(), updateNotifier, centerDepthSampler, flipper, shadowTargetsSupplier, TextureStage.DEFERRED,
			customTextureManager.getCustomTextureIdMap().getOrDefault(TextureStage.DEFERRED, Object2ObjectMaps.emptyMap()), customTextureManager.getIrisCustomTextures(), customImages,
			programSet.getPackDirectives().getExplicitFlips("deferred_pre"), customUniforms);

		flippedAfterTranslucent = flipper.snapshot();

		this.compositeRenderer = new CompositeRenderer(this, CompositePass.COMPOSITE, programSet.getPackDirectives(), programSet.getComposite(ProgramArrayId.Composite), programSet.getCompute(ProgramArrayId.Composite), renderTargets, shaderStorageBufferHolder,
			customTextureManager.getNoiseTexture(), updateNotifier, centerDepthSampler, flipper, shadowTargetsSupplier, TextureStage.COMPOSITE_AND_FINAL,
			customTextureManager.getCustomTextureIdMap().getOrDefault(TextureStage.COMPOSITE_AND_FINAL, Object2ObjectMaps.emptyMap()), customTextureManager.getIrisCustomTextures(), customImages,
			programSet.getPackDirectives().getExplicitFlips("composite_pre"), customUniforms);
		this.finalPassRenderer = new FinalPassRenderer(this, programSet, renderTargets, customTextureManager.getNoiseTexture(), shaderStorageBufferHolder, updateNotifier, flipper.snapshot(),
			centerDepthSampler, shadowTargetsSupplier,
			customTextureManager.getCustomTextureIdMap().getOrDefault(TextureStage.COMPOSITE_AND_FINAL, Object2ObjectMaps.emptyMap()), customTextureManager.getIrisCustomTextures(), customImages,
			this.compositeRenderer.getFlippedAtLeastOnceFinal(), customUniforms);

		Supplier<ImmutableSet<Integer>> flipped =
			() -> isBeforeTranslucent ? flippedAfterPrepare : flippedAfterTranslucent;

		IntFunction<ProgramSamplers> createTerrainSamplers = (programId) -> {
			ProgramSamplers.Builder builder = ProgramSamplers.builder(programId, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);

			ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor = ProgramSamplers.customTextureSamplerInterceptor(builder, customTextureManager.getCustomTextureIdMap().getOrDefault(TextureStage.GBUFFERS_AND_SHADOW, Object2ObjectMaps.emptyMap()));

			IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, flipped, renderTargets, false, this);
			IrisSamplers.addCustomTextures(builder, customTextureManager.getIrisCustomTextures());

			if (!shouldBindPBR) {
				shouldBindPBR = IrisSamplers.hasPBRSamplers(customTextureSamplerInterceptor);
			}

			IrisSamplers.addLevelSamplers(customTextureSamplerInterceptor, this, whitePixel, true, true, false);
			IrisSamplers.addWorldDepthSamplers(customTextureSamplerInterceptor, renderTargets);
			IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, customTextureManager.getNoiseTexture());
			IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);

			if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
				// we compiled the non-Sodium version of this program first... so if this is somehow null, something
				// very odd is going on.
				IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, Objects.requireNonNull(shadowRenderTargets), null, separateHardwareSamplers);
			}

			return builder.build();
		};

		IntFunction<ProgramImages> createTerrainImages = (programId) -> {
			ProgramImages.Builder builder = ProgramImages.builder(programId);

			IrisImages.addRenderTargetImages(builder, flipped, renderTargets);
			IrisImages.addCustomImages(builder, customImages);

			if (IrisImages.hasShadowImages(builder)) {
				// we compiled the non-Sodium version of this program first... so if this is somehow null, something
				// very odd is going on.
				IrisImages.addShadowColorImages(builder, Objects.requireNonNull(shadowRenderTargets), null);
			}

			return builder.build();
		};

		this.dhCompat = new DHCompat(this, shadowDirectives.isDhShadowEnabled().orElse(true));

		this.loadedShaders = new HashSet<>();


		ShaderLoadingMap loadingMap = new ShaderLoadingMap(key -> {
			try {
				if (key.isShadow()) {
					return createShadowShader(key.getName(), resolver.resolve(key.getProgram()), key);
				} else if (key == ShaderKey.WYNNCRAFT_VFX_TRANSLUCENT) {
					return createShader(key.getName(), Optional.empty(), key);
				} else {
					return createShader(key.getName(), resolver.resolve(key.getProgram()), key);
				}
			} catch (FakeChainedJsonException e) {
				destroyShaders();
				throw e.getTrueException();
			} catch (IOException e) {
				destroyShaders();
				throw new RuntimeException(e);
			} catch (RuntimeException e) {
				destroyShaders();
				throw e;
			}
		});

		this.shaderMap = new ShaderMap(loadingMap, (shader) -> {
			if (shader.key().isShadow()) {
				return shadowRenderTargets == null;
			} else {
				return false;
			}
		}, loadedShaders::add);

		initializedBlockIds = false;

		WorldRenderingSettings.INSTANCE.setEntityIds(programSet.getPack().getIdMap().getEntityIdMap());
		WorldRenderingSettings.INSTANCE.setItemIds(programSet.getPack().getIdMap().getItemIdMap());
		WorldRenderingSettings.INSTANCE.setAmbientOcclusionLevel(programSet.getPackDirectives().getAmbientOcclusionLevel());
		WorldRenderingSettings.INSTANCE.setDisableDirectionalShading(shouldDisableDirectionalShading());
		WorldRenderingSettings.INSTANCE.setUseSeparateAo(programSet.getPackDirectives().shouldUseSeparateAo());
		WorldRenderingSettings.INSTANCE.setBreaksAnisotropy(programSet.getPackDirectives().breaksAnisotropy());
		WorldRenderingSettings.INSTANCE.setVoxelizeLightBlocks(programSet.getPackDirectives().shouldVoxelizeLightBlocks());
		WorldRenderingSettings.INSTANCE.setSeparateEntityDraws(programSet.getPackDirectives().shouldUseSeparateEntityDraws());

		if (shadowRenderTargets != null) {
			GlProgram shader = shaderMap.getShader(ShaderKey.SHADOW_TERRAIN_CUTOUT);
			boolean shadowUsesImages = false;

			if (shader instanceof ExtendedShader shader2) {
				shadowUsesImages = shader2.hasActiveImages();
			}

			this.shadowClearPasses = ClearPassCreator.createShadowClearPasses(shadowRenderTargets, false, shadowDirectives);
			this.shadowClearPassesFull = ClearPassCreator.createShadowClearPasses(shadowRenderTargets, true, shadowDirectives);
			this.shadowCompositeRenderer = new ShadowCompositeRenderer(this, programSet.getPackDirectives(), programSet.getComposite(ProgramArrayId.ShadowComposite), programSet.getCompute(ProgramArrayId.ShadowComposite), this.shadowRenderTargets, this.shaderStorageBufferHolder, customTextureManager.getNoiseTexture(), updateNotifier,
				customTextureManager.getCustomTextureIdMap(TextureStage.SHADOWCOMP), customImages, programSet.getPackDirectives().getExplicitFlips("shadowcomp_pre"), customTextureManager.getIrisCustomTextures(), customUniforms);

			if (programSet.getPackDirectives().getShadowDirectives().isShadowEnabled().orElse(true)) {
				this.shadowRenderer = new ShadowRenderer(this, resolver.resolveNullable(ProgramId.ShadowSolid),
					programSet.getPackDirectives(), shadowRenderTargets, shadowCompositeRenderer, customUniforms, programSet.getPack().hasFeature(FeatureFlags.SEPARATE_HARDWARE_SAMPLERS));
			} else {
				shadowRenderer = null;
			}

			defaultFBShadow = shadowRenderTargets.createFramebufferWritingToMain(new int[] {0});
		} else {
			this.shadowClearPasses = ImmutableList.of();
			this.shadowClearPassesFull = ImmutableList.of();
			this.shadowCompositeRenderer = null;
			this.shadowRenderer = null;
		}

		// TODO: Create fallback Sodium shaders if the pack doesn't provide terrain shaders
		//       Currently we use Sodium's shaders but they don't support EXP2 fog underwater.
		this.sodiumPrograms = new SodiumPrograms(this, programSet, resolver, renderTargets, shadowTargetsSupplier, customUniforms);

		this.setup = createSetupComputes(programSet.getSetup(), programSet, TextureStage.SETUP);

		// first optimization pass
		this.customUniforms.optimise();
		boolean hasRun = false;

		this.clearPassesFull = ClearPassCreator.createClearPasses(renderTargets, true,
			programSet.getPackDirectives().getRenderTargetDirectives());
		this.clearPasses = ClearPassCreator.createClearPasses(renderTargets, false,
			programSet.getPackDirectives().getRenderTargetDirectives());

		for (ComputeProgram program : setup) {
			if (program != null) {
				if (!hasRun) {
					hasRun = true;
					renderTargets.onFullClear();
					Vector3d fogColor3 = CapturedRenderingState.INSTANCE.getFogColor();

					// NB: The alpha value must be 1.0 here, or else you will get a bunch of bugs. Sildur's Vibrant Shaders
					//     will give you pink reflections and other weirdness if this is zero.
					Vector4f fogColor = new Vector4f((float) fogColor3.x, (float) fogColor3.y, (float) fogColor3.z, 1.0F);

					clearPassesFull.forEach(clearPass -> clearPass.execute(fogColor));
				}
				program.use();
				program.dispatch(1, 1);
			}
		}

		if (hasRun) {
			ComputeProgram.unbind();
		}

		if (programSet.getPackDirectives().supportsColorCorrection()) {
			colorSpaceConverter = new ColorSpaceConverter() {
				@Override
				public void rebuildProgram(int width, int height, ColorSpace colorSpace) {

				}

				@Override
				public void process(GlTexture target) {

				}
			};
		} else {
			// TODO: Fix grid appearing on some devices with compute converter
			//if (IrisRenderSystem.supportsCompute()) {
			//	colorSpaceConverter = new ColorSpaceComputeConverter(main.width, main.height, IrisVideoSettings.colorSpace);
			//} else {
			colorSpaceConverter = new ColorSpaceFragmentConverter(main.width, main.height, IrisVideoSettings.colorSpace);
			//}
		}

		currentColorSpace = IrisVideoSettings.colorSpace;
		int defaultTex = packDirectives.getFallbackTex();

		defaultFB = flippedAfterPrepare.contains(defaultTex) ? renderTargets.createFramebufferWritingToAlt(new int[] { defaultTex }) : renderTargets.createFramebufferWritingToMain(new int[] { defaultTex });
		defaultFBAlt = flippedAfterTranslucent.contains(defaultTex) ? renderTargets.createFramebufferWritingToAlt(new int[] { defaultTex }) : renderTargets.createFramebufferWritingToMain(new int[] { defaultTex });

		// Voxy LOD-water-through-entity occlusion fix. No-op if Voxy isn't
		// loaded or the active pack has no aux-only translucent targets.
		// The aux targets must be captured at the same flip state Voxy wrote
		// them in, which is flippedAfterPrepare (Voxy injects during the
		// terrain CUTOUT pass, before any flip to the translucent state).
		this.voxyEntityDepthClear = net.irisshaders.iris.pathways.VoxyEntityDepthClearPass.tryCreate(
			this, renderTargets, flippedAfterPrepare);

		// Voxy LOD depth access for the skybox sky classifier. Unlike the pass
		// above this needs no pack-declared aux targets: it only READS Voxy's
		// depth texture, whereas the clear pass writes into pack-declared aux
		// color targets. Any pack rendering Voxy LODs qualifies.
		this.voxyLodDepth = net.irisshaders.iris.pathways.VoxyLodDepth.tryCreate(this);

		if (ambiencePool != null) {
			AmbienceRenderTargetPool.ProfilePressure pressure = getAmbienceProfilePressure();
			WynncraftDebugLog.info("ambience-pipeline-build",
				"Ambience pipeline build: customImages={}ms renderTargets={}ms total={}ms poolResources={} poolBytes={} poolBreakdown={} poolHits={} poolMisses={} poolReleases={} poolDestroyed={} profileResources={} profileSharedBytes={} profileExclusiveBytes={} profileSharedBreakdown={} profileExclusiveBreakdown={}",
				customImagesNanos / 1_000_000L,
				renderTargetsNanos / 1_000_000L,
				(System.nanoTime() - constructorStartNanos) / 1_000_000L,
				ambiencePool.getResourceCount(),
				ambiencePool.getEstimatedBytes(),
				ambiencePool.getBreakdown().compact(),
				ambiencePool.getHits(),
				ambiencePool.getMisses(),
				ambiencePool.getReleases(),
				ambiencePool.getDestroyedResources(),
				pressure == null ? 0 : pressure.resources(),
				pressure == null ? 0L : pressure.sharedBytes(),
				pressure == null ? 0L : pressure.exclusiveBytes(),
				pressure == null ? "none" : pressure.sharedBreakdown().compact(),
				pressure == null ? "none" : pressure.exclusiveBreakdown().compact());
		}
		constructed = true;
		} finally {
			if (!constructed && ambiencePoolAllocation != null) {
				ambiencePoolAllocation.close();
			}
		}
	}

	public void applyWorldRenderingSettings() {
		WorldRenderingSettings.INSTANCE.setEntityIds(pack.getIdMap().getEntityIdMap());
		WorldRenderingSettings.INSTANCE.setItemIds(pack.getIdMap().getItemIdMap());
		WorldRenderingSettings.INSTANCE.setAmbientOcclusionLevel(packDirectives.getAmbientOcclusionLevel());
		WorldRenderingSettings.INSTANCE.setDisableDirectionalShading(shouldDisableDirectionalShading());
		WorldRenderingSettings.INSTANCE.setUseSeparateAo(packDirectives.shouldUseSeparateAo());
		WorldRenderingSettings.INSTANCE.setBreaksAnisotropy(packDirectives.breaksAnisotropy());
		WorldRenderingSettings.INSTANCE.setVoxelizeLightBlocks(packDirectives.shouldVoxelizeLightBlocks());
		WorldRenderingSettings.INSTANCE.setSeparateEntityDraws(packDirectives.shouldUseSeparateEntityDraws());
		WorldRenderingSettings.INSTANCE.setBlockStateIds(
			BlockMaterialMapping.createBlockStateIdMap(pack.getIdMap().getBlockProperties(), pack.getIdMap().getTagEntries()));
		WorldRenderingSettings.INSTANCE.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(pack.getIdMap().getBlockRenderTypeMap()));
		initializedBlockIds = true;
		sodiumPrograms.applyWorldRenderingSettings();
	}

	public void onAmbienceProfileActivated() {
		onAmbienceProfileActivated(null);
	}

	public void onAmbienceProfileActivated(@Nullable AmbienceSwitchTiming timing) {
		if (ambiencePool == null) {
			return;
		}

		runSetupComputesOnNextFrame = true;
		// A cache-hit reactivation reuses a cached pipeline whose DH framebuffer may have been left with a
		// stale/clobbered depth attachment. Force DH to re-bind its own depth next frame even if DH's
		// depth-texture id is unchanged (otherwise reconnectDHTextures' storedDepthTex guard would skip it).
		if (dhCompat != null) {
			dhCompat.markDepthAttachmentDirty();
		}
		CameraUniforms.resetPreviousCameraPositions();
		MatrixUniforms.resetPreviousMatrices();
		ShaderStorageBufferHolder.ResetStats ssboResetStats = shaderStorageBufferHolder == null ? new ShaderStorageBufferHolder.ResetStats(0, 0) : shaderStorageBufferHolder.resetBuffers();
		ambiencePresentationMaskFrames = Math.max(ambiencePresentationMaskFrames, 1);
		if (wynncraftPhotonShaderPack) {
			ambiencePhotonCloudHistoryReseedFrames = Math.max(ambiencePhotonCloudHistoryReseedFrames, 1);
		}
		String mainTargetsBeforeClear = renderTargets.describeCreatedTargets();
		String cloudTargetsBeforeClear = renderTargets.describeTargetPresence(8, 9, 10, 11, 12);
		int createdTargetsBeforeClear = renderTargets.getCreatedTargetCount();
		int fullClearPassesBeforeRebuild = clearPassesFull.size();
		int clearPassesBeforeRebuild = clearPasses.size();

		long phaseStartNanos = System.nanoTime();
		renderTargets.forceFullClear();
		if (timing != null) {
			timing.addForceMainClearNanos(System.nanoTime() - phaseStartNanos);
		}

		phaseStartNanos = System.nanoTime();
		rebuildMainClearPasses();
		if (timing != null) {
			timing.addRebuildMainClearPassesNanos(System.nanoTime() - phaseStartNanos);
		}
		String mainTargetsAfterClearPassRebuild = renderTargets.describeCreatedTargets();
		String cloudTargetsAfterClearPassRebuild = renderTargets.describeTargetPresence(8, 9, 10, 11, 12);
		int createdTargetsAfterClearPassRebuild = renderTargets.getCreatedTargetCount();

		if (shadowRenderTargets != null) {
			phaseStartNanos = System.nanoTime();
			shadowRenderTargets.forceFullClear();
			if (timing != null) {
				timing.addForceShadowClearNanos(System.nanoTime() - phaseStartNanos);
			}
		}
		if (shadowRenderer != null) {
			phaseStartNanos = System.nanoTime();
			shadowRenderer.refreshSamplingSettings();
			if (timing != null) {
				timing.addShadowSamplerRefreshNanos(System.nanoTime() - phaseStartNanos);
			}
		}

		phaseStartNanos = System.nanoTime();
		CustomImageReseedStats customImageReseedStats = clearCustomImagesForAmbienceReseed();
		if (timing != null) {
			timing.addCustomImageClearNanos(System.nanoTime() - phaseStartNanos);
		}

		WynncraftDebugLog.info("ambience-profile-activate-pool",
			"Activated ambience pooled pipeline: poolResources={} poolBytes={} poolHits={} poolMisses={} poolReleases={} poolDestroyed={} sameShaderPack={} presentationMaskFrames={} photonCloudHistoryReseedFrames={} renderSun={} renderMoon={} renderStars={} renderSkyDisc={} sunPathRotation={} cloudSetting={} dhCloudSetting={} renderWeather={} renderWeatherParticles={} pack={} activeKey={} createdTargetsBeforeClear={} createdTargetsAfterClearPassRebuild={} clearPassesBeforeRebuild={}/{} clearPassesAfterRebuild={}/{} cloudTargetsBeforeClear={} cloudTargetsAfterClearPassRebuild={} mainTargetsBeforeClear={} mainTargetsAfterClearPassRebuild={} customImagesCleared={} customImagesSkipped={} ssboResetCount={} ssboResetBytes={} profilePressure={}",
			ambiencePool.getResourceCount(), ambiencePool.getEstimatedBytes(), ambiencePool.getHits(), ambiencePool.getMisses(),
			ambiencePool.getReleases(), ambiencePool.getDestroyedResources(), timing != null && timing.sameShaderPackAsPrevious(),
			ambiencePresentationMaskFrames, ambiencePhotonCloudHistoryReseedFrames, shouldRenderSun, shouldRenderMoon, shouldRenderStars, shouldRenderSkyDisc, sunPathRotation,
			cloudSetting, dhCloudSetting, shouldRenderWeather, shouldRenderWeatherParticles,
			Iris.getCurrentPackName(), Iris.getActiveTransientShaderPackContextKey(),
			createdTargetsBeforeClear, createdTargetsAfterClearPassRebuild, fullClearPassesBeforeRebuild, clearPassesBeforeRebuild,
			clearPassesFull.size(), clearPasses.size(), cloudTargetsBeforeClear, cloudTargetsAfterClearPassRebuild,
			mainTargetsBeforeClear, mainTargetsAfterClearPassRebuild, customImageReseedStats.cleared(), customImageReseedStats.skipped(),
			ssboResetStats.count(), ssboResetStats.bytes(), getAmbienceProfilePressure());
	}

	private CustomImageReseedStats clearCustomImagesForAmbienceReseed() {
		int cleared = 0;
		int skipped = 0;
		for (GlImage image : customImages) {
			if (image.isPooledView()) {
				image.clearTexture();
				cleared++;
			} else {
				skipped++;
			}
		}
		return new CustomImageReseedStats(cleared, skipped);
	}

	private void reseedPhotonCloudHistoryAfterAmbienceClear() {
		if (ambiencePhotonCloudHistoryReseedFrames <= 0) {
			return;
		}

		ambiencePhotonCloudHistoryReseedFrames--;
		int clearedTextures = 0;
		clearedTextures += renderTargets.clearTargetPairIfPresent(PHOTON_CLOUD_HISTORY_TARGET, PHOTON_CLOUD_HISTORY_NO_OCCLUSION_CLEAR);
		clearedTextures += renderTargets.clearTargetPairIfPresent(PHOTON_CLOUD_DATA_TARGET, PHOTON_CLOUD_DATA_FAR_CLEAR);

		WynncraftDebugLog.info("ambience-photon-cloud-history-reseed",
			"Reseeded Photon ambience cloud history: clearedTextures={} remainingFrames={} cloudTargets={} pack={} activeKey={}",
			clearedTextures, ambiencePhotonCloudHistoryReseedFrames, renderTargets.describeTargetPresence(11, 12),
			Iris.getCurrentPackName(), Iris.getActiveTransientShaderPackContextKey());
	}

	private record CustomImageReseedStats(int cleared, int skipped) {
	}

	@Nullable
	public AmbienceRenderTargetPool.ProfilePressure getAmbienceProfilePressure() {
		return ambiencePoolAllocation == null ? null : ambiencePoolAllocation.pressure();
	}

	private void rebuildMainClearPasses() {
		this.clearPassesFull.forEach(clearPass -> renderTargets.destroyFramebuffer(clearPass.getFramebuffer()));
		this.clearPasses.forEach(clearPass -> renderTargets.destroyFramebuffer(clearPass.getFramebuffer()));
		this.clearPassesFull = ClearPassCreator.createClearPasses(renderTargets, true,
			packDirectives.getRenderTargetDirectives());
		this.clearPasses = ClearPassCreator.createClearPasses(renderTargets, false,
			packDirectives.getRenderTargetDirectives());
	}

	private ComputeProgram[] createShadowComputes(ComputeSource[] compute, ProgramSet programSet) {
		ComputeProgram[] programs = new ComputeProgram[compute.length];
		for (int i = 0; i < programs.length; i++) {
			ComputeSource source = compute[i];
			if (source == null || source.getSource().isEmpty()) {
			} else {
				ProgramBuilder builder;

				try {
					String transformed = TransformPatcher.patchCompute(source.getName(), source.getSource().orElse(null), TextureStage.GBUFFERS_AND_SHADOW, customTextureMap);

					ShaderPrinter.printProgram(source.getName()).addSource(PatchShaderType.COMPUTE, transformed).print();

					builder = ProgramBuilder.beginCompute(source.getName(), transformed, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
				} catch (ShaderCompileException e) {
					throw e;
				} catch (RuntimeException e) {
					// TODO: Better error handling
					throw new RuntimeException("Shader compilation failed for compute " + source.getName() + "!", e);
				}

				CommonUniforms.addDynamicUniforms(builder, FogMode.OFF);
				customUniforms.assignTo(builder);

				Supplier<ImmutableSet<Integer>> flipped;

				flipped = () -> flippedBeforeShadow;

				TextureStage textureStage = TextureStage.GBUFFERS_AND_SHADOW;

				ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor =
					ProgramSamplers.customTextureSamplerInterceptor(builder,
						customTextureManager.getCustomTextureIdMap(textureStage));

				IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, flipped, renderTargets, false, this);
				IrisSamplers.addCustomTextures(builder, customTextureManager.getIrisCustomTextures());
				IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
				IrisImages.addRenderTargetImages(builder, flipped, renderTargets);
				IrisImages.addCustomImages(builder, customImages);

				IrisSamplers.addLevelSamplers(customTextureSamplerInterceptor, this, whitePixel, true, true, false);

				IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, customTextureManager.getNoiseTexture());

				if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
					if (shadowRenderTargets != null) {
						IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowRenderTargets, null, separateHardwareSamplers);
						IrisImages.addShadowColorImages(builder, shadowRenderTargets, null);
					}
				}

				programs[i] = builder.buildCompute();

				this.customUniforms.mapholderToPass(builder, programs[i]);

				programs[i].setWorkGroupInfo(source.getWorkGroupRelative(), source.getWorkGroups(), FilledIndirectPointer.basedOff(shaderStorageBufferHolder, source.getIndirectPointer()));
			}
		}


		return programs;
	}

	private ComputeProgram[] createSetupComputes(ComputeSource[] compute, ProgramSet programSet, TextureStage stage) {
		ComputeProgram[] programs = new ComputeProgram[compute.length];
		for (int i = 0; i < programs.length; i++) {
			ComputeSource source = compute[i];
			if (source == null || source.getSource().isEmpty()) {
			} else {
				ProgramBuilder builder;

				try {
					String transformed = TransformPatcher.patchCompute(source.getName(), source.getSource().orElse(null), stage, customTextureMap);

					ShaderPrinter.printProgram(source.getName()).addSource(PatchShaderType.COMPUTE, transformed).print();

					builder = ProgramBuilder.beginCompute(source.getName(), transformed, IrisSamplers.COMPOSITE_RESERVED_TEXTURE_UNITS);
				} catch (RuntimeException e) {
					// TODO: Better error handling
					throw new RuntimeException("Shader compilation failed for setup compute " + source.getName() + "!", e);
				}

				CommonUniforms.addDynamicUniforms(builder, FogMode.OFF);
				customUniforms.assignTo(builder);

				ImmutableSet<Integer> empty = ImmutableSet.of();
				Supplier<ImmutableSet<Integer>> flipped;

				flipped = () -> empty;

				TextureStage textureStage = TextureStage.SETUP;

				ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor =
					ProgramSamplers.customTextureSamplerInterceptor(builder,
						customTextureManager.getCustomTextureIdMap(textureStage));

				IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, flipped, renderTargets, true, this);
				IrisSamplers.addCustomTextures(builder, customTextureManager.getIrisCustomTextures());
				IrisSamplers.addCompositeSamplers(builder, renderTargets);
				IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
				IrisImages.addRenderTargetImages(builder, flipped, renderTargets);
				IrisImages.addCustomImages(builder, customImages);

				IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, customTextureManager.getNoiseTexture());

				if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
					if (shadowRenderTargets != null) {
						IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowRenderTargets, null, separateHardwareSamplers);
						IrisImages.addShadowColorImages(builder, shadowRenderTargets, null);
					}
				}


				programs[i] = builder.buildCompute();

				this.customUniforms.mapholderToPass(builder, programs[i]);

				programs[i].setWorkGroupInfo(source.getWorkGroupRelative(), source.getWorkGroups(), FilledIndirectPointer.basedOff(shaderStorageBufferHolder, source.getIndirectPointer()));
			}
		}


		return programs;
	}

	private ShaderSupplier createShader(String name, Optional<ProgramSource> source, ShaderKey key) throws IOException {
		if (source.isEmpty()) {
			return createFallbackShader(name, key);
		}

		return createShader(name, key, source.get(), key.getProgram(), key.getAlphaTest(), key.getVertexFormat(), key.getFogMode(),
			key.isIntensity(), key.shouldIgnoreLightmap(), key.isGlint(), key.isText(), key == ShaderKey.IE_COMPAT);
	}

	public boolean shouldUseWynncraftFallbackVfxTranslucency() {
		return wynncraftFallbackVfxTranslucency;
	}

	@Override
	public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
		return customTextureMap;
	}

	private ShaderSupplier createShader(String name, ShaderKey key, ProgramSource source, ProgramId programId, AlphaTest fallbackAlpha,
										VertexFormat vertexFormat, FogMode fogMode,
										boolean isIntensity, boolean isFullbright, boolean isGlint, boolean isText, boolean isIE) throws IOException {
		int[] drawBuffers = source.getDirectives().getDrawBuffers();
		GlFramebuffer beforeTranslucent = renderTargets.createGbufferFramebuffer(flippedAfterPrepare, drawBuffers);
		GlFramebuffer afterTranslucent = renderTargets.createGbufferFramebuffer(flippedAfterTranslucent, drawBuffers);
		trackGbufferFramebuffer(beforeTranslucent, flippedAfterPrepare, drawBuffers);
		trackGbufferFramebuffer(afterTranslucent, flippedAfterTranslucent, drawBuffers);
		boolean isLines = programId == ProgramId.Line && resolver.has(ProgramId.Line);


		ShaderAttributeInputs inputs = new ShaderAttributeInputs(vertexFormat, isFullbright, isLines, isGlint, isText, isIE);

		Supplier<ImmutableSet<Integer>> flipped =
			() -> isBeforeTranslucent ? flippedAfterPrepare : flippedAfterTranslucent;


		ShaderSupplier extendedShader = ShaderCreator.create(this, name, key, source, programId, beforeTranslucent, afterTranslucent,
			fallbackAlpha, vertexFormat, inputs, updateNotifier, this, flipped, fogMode, isIntensity, isFullbright, false, isLines, customUniforms);

		return extendedShader;
	}

	private ShaderSupplier createFallbackShader(String name, ShaderKey key) throws IOException {
		boolean wynncraftVfxFallbackKey = key == ShaderKey.WYNNCRAFT_VFX_TRANSLUCENT;
		boolean photonVfxLayer = wynncraftVfxFallbackKey
			&& wynncraftFallbackVfxTranslucency
			&& renderTargets.getRenderTargetCount() > WYNNCRAFT_PHOTON_VFX_TRANSLUCENT_BUFFER;
		int[] drawBuffers = photonVfxLayer ? WYNNCRAFT_PHOTON_VFX_DRAW_BUFFER : MAIN_COLOR_DRAW_BUFFER;
		BlendModeOverride blendModeOverride = photonVfxLayer ? WYNNCRAFT_PHOTON_VFX_BLEND : null;
		GlFramebuffer beforeTranslucent = renderTargets.createGbufferFramebuffer(flippedAfterPrepare, drawBuffers);
		GlFramebuffer afterTranslucent = renderTargets.createGbufferFramebuffer(flippedAfterTranslucent, drawBuffers);
		trackGbufferFramebuffer(beforeTranslucent, flippedAfterPrepare, drawBuffers);
		trackGbufferFramebuffer(afterTranslucent, flippedAfterTranslucent, drawBuffers);

		ShaderSupplier shader = ShaderCreator.createFallback(name, key, beforeTranslucent, afterTranslucent,
			key.getAlphaTest(), key.getVertexFormat(), blendModeOverride, this, key.getFogMode(),
			key.hasDiffuseLighting(), key.isGlint(), key.isText(), key.isIntensity(), key.shouldIgnoreLightmap(),
			photonVfxLayer);

		return shader;
	}

	private void trackGbufferFramebuffer(GlFramebuffer framebuffer, ImmutableSet<Integer> stageWritesToAlt, int[] drawBuffers) {
		if (ambiencePool != null) {
			gbufferFramebuffers.add(new GbufferFramebufferBinding(framebuffer, stageWritesToAlt, drawBuffers.clone()));
		}
	}

	private void trackDHFramebuffer(GlFramebuffer framebuffer, ImmutableSet<Integer> stageWritesToAlt, int[] drawBuffers) {
		if (ambiencePool != null) {
			dhFramebuffers.add(new GbufferFramebufferBinding(framebuffer, stageWritesToAlt, drawBuffers.clone()));
		}
	}

	private void refreshPooledFramebufferAttachments() {
		for (GbufferFramebufferBinding binding : gbufferFramebuffers) {
			renderTargets.refreshGbufferFramebuffer(binding.framebuffer(), binding.stageWritesToAlt(), binding.drawBuffers());
		}

		sodiumPrograms.refreshMainFramebuffers();

		int defaultTex = packDirectives.getFallbackTex();
		renderTargets.refreshGbufferFramebuffer(defaultFB, flippedAfterPrepare, new int[]{defaultTex});
		renderTargets.refreshGbufferFramebuffer(defaultFBAlt, flippedAfterTranslucent, new int[]{defaultTex});

		if (voxyEntityDepthClear != null) {
			voxyEntityDepthClear.refreshFramebufferAttachments();
		}

		// DH framebuffers: re-point their pooled color attachments only. Their depth belongs to Distant
		// Horizons (not Minecraft), so we use the color-only refresh and then ask DH to re-bind its own
		// depth next frame (reconnectDHTextures) — otherwise the swapped pooled textures would leave DH
		// drawing into stale color buffers and against the wrong depth.
		if (!dhFramebuffers.isEmpty()) {
			for (GbufferFramebufferBinding binding : dhFramebuffers) {
				renderTargets.refreshDHFramebuffer(binding.framebuffer(), binding.stageWritesToAlt(), binding.drawBuffers());
			}
			if (dhCompat != null) {
				dhCompat.markDepthAttachmentDirty();
			}
		}
	}

	private record GbufferFramebufferBinding(GlFramebuffer framebuffer, ImmutableSet<Integer> stageWritesToAlt,
											 int[] drawBuffers) {
	}

	private ShaderSupplier createShadowShader(String name, Optional<ProgramSource> source, ShaderKey key) throws IOException {
		if (source.isEmpty()) {
			return createFallbackShadowShader(name, key);
		}

		return createShadowShader(name, key, source.get(), key.getProgram(), key.getAlphaTest(), key.getVertexFormat(),
			key.isIntensity(), key.shouldIgnoreLightmap(), key.isText(), key == ShaderKey.IE_COMPAT_SHADOW);
	}

	private ShaderSupplier createFallbackShadowShader(String name, ShaderKey key) throws IOException {
		ShaderSupplier shader = ShaderCreator.createFallbackShadow(name, key, shadowTargetsSupplier,
			key.getAlphaTest(), key.getVertexFormat(), BlendModeOverride.OFF, this, key.getFogMode(),
			key.hasDiffuseLighting(), key.isGlint(), key.isText(), key.isIntensity(), key.shouldIgnoreLightmap());

		return shader;
	}

	private ShaderSupplier createShadowShader(String name, ShaderKey key, ProgramSource source, ProgramId programId, AlphaTest fallbackAlpha,
											  VertexFormat vertexFormat, boolean isIntensity, boolean isFullbright, boolean isText, boolean isIE) throws IOException {
		boolean isLines = programId == ProgramId.Line && resolver.has(ProgramId.Line);

		ShaderAttributeInputs inputs = new ShaderAttributeInputs(vertexFormat, isFullbright, isLines, false, isText, isIE);

		Supplier<ImmutableSet<Integer>> flipped = () -> flippedBeforeShadow;

		ShaderSupplier extendedShader = ShaderCreator.createShadow(this, name, key, source, programId, shadowTargetsSupplier,
			fallbackAlpha, vertexFormat, inputs, updateNotifier, this, flipped, FogMode.PER_VERTEX, isIntensity, isFullbright, true, isLines, customUniforms);

		return extendedShader;
	}

	public void addGbufferOrShadowSamplers(SamplerHolder samplers, ImageHolder images, Supplier<ImmutableSet<Integer>> flipped,
										   boolean isShadowPass, boolean hasTexture, boolean hasLightmap, boolean hasOverlay) {
		TextureStage textureStage = TextureStage.GBUFFERS_AND_SHADOW;

		ProgramSamplers.CustomTextureSamplerInterceptor samplerHolder =
			ProgramSamplers.customTextureSamplerInterceptor(samplers,
				customTextureManager.getCustomTextureIdMap().getOrDefault(textureStage, Object2ObjectMaps.emptyMap()));

		IrisSamplers.addRenderTargetSamplers(samplerHolder, flipped, renderTargets, false, this);
		IrisSamplers.addCustomTextures(samplerHolder, customTextureManager.getIrisCustomTextures());
		IrisImages.addRenderTargetImages(images, flipped, renderTargets);
		IrisImages.addCustomImages(images, customImages);

		if (!shouldBindPBR) {
			shouldBindPBR = IrisSamplers.hasPBRSamplers(samplerHolder);
		}

		IrisSamplers.addLevelSamplers(samplers, this, whitePixel, hasTexture, hasLightmap, hasOverlay);
		IrisSamplers.addWorldDepthSamplers(samplerHolder, this.renderTargets);
		IrisSamplers.addNoiseSampler(samplerHolder, this.customTextureManager.getNoiseTexture());
		IrisSamplers.addCustomImages(samplerHolder, customImages);

		if (IrisSamplers.hasShadowSamplers(samplerHolder)) {
			IrisSamplers.addShadowSamplers(samplerHolder, shadowTargetsSupplier.get(), null, separateHardwareSamplers);
		}

		if (isShadowPass || IrisImages.hasShadowImages(images)) {
			IrisImages.addShadowColorImages(images, shadowTargetsSupplier.get(), null);
		}
	}

	private boolean shouldRemovePhase = false;

	@Override
	public WorldRenderingPhase getPhase() {
		if (shouldRemovePhase) {
			phase = WorldRenderingPhase.NONE;
			shouldRemovePhase = false;
			GLDebug.popGroup();
		}

		if (overridePhase != null) {
			return overridePhase;
		}

		return phase;
	}

	public void removePhaseIfNeeded() {
		if (shouldRemovePhase) {
			phase = WorldRenderingPhase.NONE;
			shouldRemovePhase = false;
			GLDebug.popGroup();
		}
	}

	@Override
	public void setPhase(WorldRenderingPhase phase) {
		if (phase == WorldRenderingPhase.NONE) {
			if (shouldRemovePhase) GLDebug.popGroup();
			shouldRemovePhase = true;
			return;
		} else {
			shouldRemovePhase = false;
			if (phase == this.phase) {
				return;
			}
		}

		GLDebug.popGroup();
		if (phase != WorldRenderingPhase.NONE && phase != WorldRenderingPhase.TERRAIN_CUTOUT && phase != WorldRenderingPhase.TERRAIN_CUTOUT_MIPPED && phase != WorldRenderingPhase.TRIPWIRE) {
			if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
				GLDebug.pushGroup(phase.ordinal(), "Shadow " + StringUtils.capitalize(phase.name().toLowerCase(Locale.ROOT).replace("_", " ")));
			} else {
				GLDebug.pushGroup(phase.ordinal(), StringUtils.capitalize(phase.name().toLowerCase(Locale.ROOT).replace("_", " ")));
			}
		}
		this.phase = phase;
	}

	@Override
	public void setOverridePhase(WorldRenderingPhase phase) {
		this.overridePhase = phase;
	}

	@Override
	public int getCurrentNormalTexture() {
		return currentNormalTexture == null ? 0 : currentNormalTexture.getTexture().iris$getGlId();
	}

	public GlSampler getNormalSampler() {
		return normalSampler;
	}

	public GlSampler getSpecularSampler() {
		return specularSampler;
	}

	@Override
	public int getCurrentSpecularTexture() {
		return currentSpecularTexture == null ? 0 : currentSpecularTexture.getTexture().iris$getGlId();
	}

	@Override
	public void onSetAlbedoTex(GpuTextureView id) {
		if (id != null) {
			albedoTex = id.texture().iris$getGlId();
			int maxAnisotropy = Minecraft.getInstance().options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC
				? Minecraft.getInstance().options.maxAnisotropyValue()
				: 1;
			if (shouldBindPBR && isRenderingWorld) {
				PBRTextureHolder pbrHolder = PBRTextureManager.INSTANCE.getOrLoadHolder(id.texture().iris$getGlId());
				currentNormalTexture = pbrHolder.normalTexture();
				currentSpecularTexture = pbrHolder.specularTexture();

				TextureFormat textureFormat = TextureFormatLoader.getFormat();
				if (textureFormat != null) {
					this.normalSampler = textureFormat.canInterpolateValues(PBRType.NORMAL) ? IrisSamplers.getTerrainCacheIris(maxAnisotropy) : GlSampler.MIPPED_NEAREST_NEAREST;
					this.specularSampler = textureFormat.canInterpolateValues(PBRType.SPECULAR) ? IrisSamplers.getTerrainCacheIris(maxAnisotropy) : GlSampler.MIPPED_NEAREST_NEAREST;
				} else {
					this.normalSampler = IrisSamplers.getTerrainCacheIris(maxAnisotropy);
					this.specularSampler = IrisSamplers.getTerrainCacheIris(maxAnisotropy);
				}

				PBRTextureManager.notifyPBRTexturesChanged();
			}
		}
	}

	@Override
	public void beginLevelRendering() {
		isRenderingWorld = true;

		if (!initializedBlockIds) {
			WorldRenderingSettings.INSTANCE.setBlockStateIds(
				BlockMaterialMapping.createBlockStateIdMap(pack.getIdMap().getBlockProperties(), pack.getIdMap().getTagEntries()));
			WorldRenderingSettings.INSTANCE.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(pack.getIdMap().getBlockRenderTypeMap()));
			Minecraft.getInstance().levelRenderer.allChanged();
			initializedBlockIds = true;
		}

		// Make sure we're using texture unit 0 for this.
		GlStateManager._activeTexture(GL15C.GL_TEXTURE0);
		Vector4f emptyClearColor = new Vector4f(1.0F);

		GLDebug.pushGroup(100, "Clear textures");

		clearImages.forEach(ImageClearPass::execute);

		if (shadowRenderTargets != null) {
			if (packDirectives.getShadowDirectives().isShadowEnabled() == OptionalBoolean.FALSE) {
				if (shadowRenderTargets.isFullClearRequired()) {
					this.shadowClearPasses = ClearPassCreator.createShadowClearPasses(shadowRenderTargets, false, shadowDirectives);
					this.shadowClearPassesFull = ClearPassCreator.createShadowClearPasses(shadowRenderTargets, true, shadowDirectives);
					shadowRenderTargets.onFullClear();
					for (ClearPass clearPass : shadowClearPassesFull) {
						clearPass.execute(emptyClearColor);
					}
				}
			} else {
				// Clear depth first, regardless of any color clearing.
				shadowRenderTargets.getDepthSourceFb().bind();
				GlStateManager._depthMask(true);
				GlStateManager._clear(GL21C.GL_DEPTH_BUFFER_BIT);

				ImmutableList<ClearPass> passes;

				for (ComputeProgram computeProgram : shadowComputes) {
					if (computeProgram != null) {
						computeProgram.use();
						customUniforms.push(computeProgram);
						computeProgram.dispatch(shadowMapResolution, shadowMapResolution);
					}
				}

				if (shadowRenderTargets.isFullClearRequired()) {
					this.shadowClearPasses = ClearPassCreator.createShadowClearPasses(shadowRenderTargets, false, shadowDirectives);
					this.shadowClearPassesFull = ClearPassCreator.createShadowClearPasses(shadowRenderTargets, true, shadowDirectives);
					passes = shadowClearPassesFull;
					shadowRenderTargets.onFullClear();
				} else {
					passes = shadowClearPasses;
				}

				for (ClearPass clearPass : passes) {
					clearPass.execute(emptyClearColor);
				}
			}
		}

		PBRTextureManager.INSTANCE.onNewFrame();

		// NB: execute this before resizing / clearing so that the center depth sample is retrieved properly.
		updateNotifier.onNewFrame();

		// Update custom uniforms
		this.customUniforms.update();

		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();

		GpuTexture depthTexture = main.getDepthTexture();
		DepthBufferFormat depthBufferFormat = DepthBufferFormat.fromGlEnumOrDefault(GlConst.toGlInternalId(main.getDepthTexture().getFormat()));

		boolean changed = renderTargets.resizeIfNeeded(((Blaze3dRenderTargetExt) main).iris$getDepthBufferVersion(), depthTexture, main.width,
			main.height, depthBufferFormat, packDirectives);

		if (changed) {
			if (ambiencePool != null) {
				refreshPooledFramebufferAttachments();
				// A pooled resize recreates the pooled render targets — including Photon's clear=false temporal
				// cloud-history buffers (colortex 11/12), which come back uninitialized (→ white sky) — and it
				// invalidates the previous-frame reprojection history (→ terrain smears as the camera moves). An
				// ambience *switch* already runs this cleanup after swapping the pooled targets; a plain *resize*
				// did not. Mirror it: reseed the cloud history next frame and reset the reprojection baseline.
				if (wynncraftPhotonShaderPack) {
					ambiencePhotonCloudHistoryReseedFrames = Math.max(ambiencePhotonCloudHistoryReseedFrames, 1);
				}
				CameraUniforms.resetPreviousCameraPositions();
				MatrixUniforms.resetPreviousMatrices();
			}
			beginRenderer.recalculateSizes();
			prepareRenderer.recalculateSizes();
			deferredRenderer.recalculateSizes();
			compositeRenderer.recalculateSizes();
			finalPassRenderer.recalculateSwapPassSize();
			if (shaderStorageBufferHolder != null) {
				shaderStorageBufferHolder.hasResizedScreen(main.width, main.height);
			}

			customImages.forEach(image -> image.updateNewSize(main.width, main.height));

			if (wynncraftBiomeFogRenderer != null) {
				wynncraftBiomeFogRenderer.rebuild(main.width, main.height);
			}
			if (wynncraftSkyboxRenderer != null) {
				wynncraftSkyboxRenderer.rebuild(main.width, main.height);
			}
			if (wynncraftTransitionRenderer != null) {
				wynncraftTransitionRenderer.rebuild(main.width, main.height);
			}

			this.clearPassesFull.forEach(clearPass -> renderTargets.destroyFramebuffer(clearPass.getFramebuffer()));
			this.clearPasses.forEach(clearPass -> renderTargets.destroyFramebuffer(clearPass.getFramebuffer()));

			this.clearPassesFull = ClearPassCreator.createClearPasses(renderTargets, true,
				packDirectives.getRenderTargetDirectives());
			this.clearPasses = ClearPassCreator.createClearPasses(renderTargets, false,
				packDirectives.getRenderTargetDirectives());
		}

		if (changed || IrisVideoSettings.colorSpace != currentColorSpace) {
			currentColorSpace = IrisVideoSettings.colorSpace;
			colorSpaceConverter.rebuildProgram(main.width, main.height, currentColorSpace);
		}

		final ImmutableList<ClearPass> passes;

		if (renderTargets.isFullClearRequired()) {
			renderTargets.onFullClear();
			passes = clearPassesFull;
		} else {
			passes = clearPasses;
		}

		Vector3d fogColor3 = CapturedRenderingState.INSTANCE.getFogColor();

		// NB: The alpha value must be 1.0 here, or else you will get a bunch of bugs. Sildur's Vibrant Shaders
		//     will give you pink reflections and other weirdness if this is zero.
		Vector4f fogColor = new Vector4f((float) fogColor3.x, (float) fogColor3.y, (float) fogColor3.z, 1.0F);

		for (ClearPass clearPass : passes) {
			clearPass.execute(fogColor);
		}

		reseedPhotonCloudHistoryAfterAmbienceClear();

		GLDebug.popGroup();

		// Make sure to switch back to the main framebuffer. If we forget to do this then our alt buffers might be
		// cleared to the fog color, which absolutely is not what we want!
		//
		// If we forget to do this, then weird lines appear at the top of the screen and the right of the screen
		// on Sildur's Vibrant Shaders.
		Minecraft.getInstance().getMainRenderTarget().iris$bindFramebuffer();
		isMainBound = true;

		boolean shouldRunSetupComputes = changed || runSetupComputesOnNextFrame;
		if (shouldRunSetupComputes) {
			String setupReason = changed
				? (runSetupComputesOnNextFrame ? "resize+ambience-activation" : "resize")
				: "ambience-activation";
			runSetupComputesOnNextFrame = false;
			runSetupComputes(setupReason);
		}

		beginRenderer.renderAll();

		isBeforeTranslucent = true;
	}

	private void runSetupComputes(String reason) {
		boolean hasRun = false;
		long setupStartNanos = System.nanoTime();

		for (ComputeProgram program : setup) {
			if (program != null) {
				hasRun = true;
				program.use();
				program.dispatch(1, 1);
			}
		}

		if (hasRun) {
			ComputeProgram.unbind();
			if (WynncraftDebugLog.shouldLog("ambience-setup-computes")) {
				WynncraftDebugLog.info("ambience-setup-computes",
					"Ran setup computes for {} in {}us", reason, (System.nanoTime() - setupStartNanos) / 1_000L);
			}
		}
	}

	@Override
	public void renderShadows(LevelRendererAccessor worldRenderer, Camera playerCamera, CameraRenderState renderState) {
		if (shadowRenderer != null) {
			this.shadowRenderer.renderShadows(worldRenderer, playerCamera, renderState);
		}

		prepareRenderer.renderAll();
	}

	// Skybox fog color override: when non-null, MixinFogRenderer uses this instead of vanilla.
	// Set at end of each frame based on active skybox state. Affects shader pack fog + reflections.
	// Blended with vanilla fog using skyboxFadeOpacity for smooth transitions.
	public static float[] skyboxFogColor = null;
	public static float skyboxFogBlendFactor = 0.0f;

	// Biome fog: set by MixinFogRenderer when player is in mushroom_fields (Mist Woods).
	// Read by finalizeLevelRendering() to drive post-process fog pass.
	public static volatile boolean biomeFogActive = false;
	public static volatile float biomeFogStart = 0.0f;
	public static volatile float biomeFogEnd = 0.0f;
	private float biomeFogOpacity = 0.0f;

	private static final float[][] SKYBOX_FOG_COLORS = {
		null,                          // 0: unused
		null,                          // 1: Memory Mist — light, no darkening
		null,                          // 2: Memory Fog — light, no darkening
		{0.05f, 0.05f, 0.05f},        // 3: Stormy — near-black
		{0.10f, 0.02f, 0.02f},        // 4: War Surface — dark red
		{0.05f, 0.05f, 0.05f},        // 5: War Heights — near-black
		null,                          // 6: Light — bright, no darkening
		{0.08f, 0.02f, 0.02f},        // 7: Red Lightning — dark red
	};

	@Override
	public void addDebugText(DebugScreenDisplayer messages) {
		if (this.shadowRenderer != null) {
			shadowRenderer.addDebugText(messages);
		} else {
			messages.addLine("[Iris] Shadow Maps: not used by shader pack");
		}
	}

	@Override
	public OptionalInt getForcedShadowRenderDistanceChunksForDisplay() {
		return forcedShadowRenderDistanceChunks;
	}

	@Override
	public void beginHand() {
		centerDepthSampler.sampleCenterDepth();

		// We need to copy the current depth texture so that depthtex2 can contain the depth values for
		// all non-translucent content excluding the hand, as required.
		renderTargets.copyPreHandDepth();
	}

	@Override
	public void beginTranslucents() {
		if (destroyed) {
			throw new IllegalStateException("Tried to use a destroyed world rendering pipeline");
		}

		removePhaseIfNeeded();

		isBeforeTranslucent = false;

		// We need to copy the current depth texture so that depthtex1 can contain the depth values for
		// all non-translucent content, as required.
		renderTargets.copyPreTranslucentDepth();

		// If Voxy is integrated and the pack declares aux translucent targets,
		// clear those targets at pixels where vanilla opaque geometry occludes
		// the LOD water. Must run after copyPreTranslucentDepth (so depthtex1
		// is fresh with entity depth) and before deferredRenderer.renderAll
		// (so the composite sees the cleared buffers).
		if (voxyEntityDepthClear != null) {
			voxyEntityDepthClear.render();
		}

		deferredRenderer.renderAll();

		// Paint the Wynncraft procedural skybox into the color buffer BEFORE translucents
		// run. This makes translucent VFX display entities (rifts, memory-mist volumes,
		// etc.) blend over the painted skybox during the translucent pass, exactly like
		// Wynncraft RP where the skybox entity is itself a translucent draw. Running this
		// after beginTranslucents's earlier late post-process caused large VFX to be
		// wiped out: translucents don't write depth, so their pixels stayed at clear
		// depth and got overwritten as sky.
		if (wynncraftSkyboxRenderer != null && displayedSkyboxId > 0 && skyboxFadeOpacity > 0.001f) {
			com.mojang.blaze3d.pipeline.RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
			int dhDepthTex = dhCompat != null ? dhCompat.getDepthTex() : 0;
			int voxyDepthTex = voxyLodDepth != null ? voxyLodDepth.currentDepthTexId() : 0;
			wynncraftSkyboxRenderer.renderSkyPaint(
				main.getDepthTexture().iris$getGlId(),
				(GlTexture) main.getColorTexture(),
				computeWynncraftGameTime(),
				skyboxFadeOpacity,
				displayedSkyboxId,
				dhDepthTex,
				voxyDepthTex);
		}

		// note: we are careful not to touch the lightmap texture unit or overlay color texture unit here,
		// so we don't need to do anything to restore them if needed.
		//
		// Previous versions of the code tried to "restore" things by enabling the lightmap & overlay color
		// but that actually broke rendering of clouds and rain by making them appear red in the case of
		// a pack not overriding those shader programs.
		//
		// Not good!

		// Reset shader or whatever...
	}

	@Override
	public void finalizeLevelRendering() {
		isRenderingWorld = false;
		removePhaseIfNeeded();
		compositeRenderer.renderAll();
		finalPassRenderer.renderFinalPass();

		// Wynncraft skybox state machine.
		// skyboxFadeOpacity tracks DETECTION state only (fade-in/out) — controls post-process sky overlay.
		// Fog/boost state is separately gated by dark skybox type + rain (doesn't affect sky overlay).
		{
			int preferredId = ImmediateState.consumeSkyboxPreferred();
			int fallbackId = ImmediateState.consumeSkyboxFallback();
			long now = System.currentTimeMillis();

			// Sticky primary selection:
			// - Preferred (delta_y range) detection always wins — switch to it
			// - No preferred, fallback MATCHES current → keep current (sticky, refresh timestamp)
			// - No preferred, fallback DIFFERENT from current → don't refresh (let old fade out,
			//   then fallback takes over as initial detection once displayedSkyboxId resets to 0)
			// - No preferred, no current, fallback exists → use fallback as initial detection
			int detectedId;
			if (preferredId > 0) {
				detectedId = preferredId;
			} else if (fallbackId > 0 && displayedSkyboxId > 0 && fallbackId == displayedSkyboxId) {
				// Same ID — keep current, refresh timestamp (entity just drifted out of range)
				detectedId = displayedSkyboxId;
			} else if (fallbackId > 0 && displayedSkyboxId == 0) {
				// No existing primary — use fallback as initial detection
				detectedId = fallbackId;
			} else {
				// Either nothing detected, or fallback has different ID — let current fade out
				detectedId = 0;
			}

			// Track detection state — fade in/out regardless of skybox type or weather
			if (detectedId > 0 && detectedId <= 7) {
				if (detectedId != displayedSkyboxId) {
					displayedSkyboxId = detectedId;
					skyboxFadeInStartMs = now;
				}
				lastDetectionTimeMs = now;
				float fadeIn = Math.min((now - skyboxFadeInStartMs) / 2000.0f, 1.0f);
				skyboxFadeOpacity = fadeIn;
			} else if (displayedSkyboxId > 0) {
				// No detection — hold for 5s then fade out over 3s.
				// Hold prevents flicker from intermittent entity culling/unloading.
				float secondsSince = (now - lastDetectionTimeMs) / 1000.0f;
				if (secondsSince < 5.0f) {
					// Hold at full opacity — entity may just be temporarily culled
					skyboxFadeOpacity = 1.0f;
				} else if (secondsSince < 8.0f) {
					// Fade out over 3s after the hold period
					skyboxFadeOpacity = 1.0f - (secondsSince - 5.0f) / 3.0f;
				} else {
					skyboxFadeOpacity = 0.0f;
					displayedSkyboxId = 0;
				}
			}

			// Fog/sky/boost state — only for dark skyboxes (3,4,5,7), NOT when raining,
			// and scaled by daylight (no darkening at night — scene already dark).
			// This is separate from the sky overlay which always renders.
			boolean isDarkSkybox = (displayedSkyboxId == 3 || displayedSkyboxId == 4
				|| displayedSkyboxId == 5 || displayedSkyboxId == 7);
			boolean mcIsRaining = Minecraft.getInstance().level != null
				&& Minecraft.getInstance().level.getRainLevel(
					CapturedRenderingState.INSTANCE.getTickDelta()) > 0.2f;

			if (isDarkSkybox && !mcIsRaining && skyboxFadeOpacity > 0.001f) {
				// Daylight factor: 1.0 at noon, 0.0 at midnight.
				// No darkening at night since the scene is already dark.
				float daylightFactor = 0.0f;
				if (Minecraft.getInstance().level != null) {
					long dayTime = Minecraft.getInstance().level.getDayTime() % 24000L;
					if (dayTime < 12000) {
						daylightFactor = (float) Math.sin(dayTime * Math.PI / 12000.0);
					} else {
						daylightFactor = Math.max(0.0f,
							-(float) Math.sin((dayTime - 12000) * Math.PI / 12000.0));
					}
				}
				float sceneDarken = IrisVideoSettings.wynncraftSceneDarkening / 100.0f;
				skyboxFogColor = (displayedSkyboxId < SKYBOX_FOG_COLORS.length)
					? SKYBOX_FOG_COLORS[displayedSkyboxId] : null;
				skyboxFogBlendFactor = skyboxFadeOpacity * sceneDarken * daylightFactor;
			} else {
				skyboxFogColor = null;
				skyboxFogBlendFactor = 0.0f;
			}
		}

		// Wynncraft biome fog: post-process close fog for mushroom_fields (Mist Woods).
		// Applied BEFORE skybox so that skybox overlay renders on top of fogged terrain.
		if (wynncraftBiomeFogRenderer != null) {
			// Smooth fade: ramp opacity up/down over ~1 second for biome transitions.
			float targetOpacity = biomeFogActive ? 1.0f : 0.0f;
			biomeFogOpacity += (targetOpacity - biomeFogOpacity) * 0.05f; // ~1s at 60fps
			if (Math.abs(biomeFogOpacity - targetOpacity) < 0.005f) biomeFogOpacity = targetOpacity;

			if (biomeFogOpacity > 0.001f) {
				com.mojang.blaze3d.pipeline.RenderTarget mainRT = Minecraft.getInstance().getMainRenderTarget();

				// Apply minimum fog distance: if user requested a farther fog end than
				// the biome's default, push it out while preserving the fog's thickness.
				// Clamp thickness to a sane positive minimum so degenerate or inverted
				// biome attributes (fogStart >= fogEnd) don't propagate into the shader.
				float fogStart = biomeFogStart;
				float fogEnd = biomeFogEnd;
				int minDistance = IrisVideoSettings.wynncraftMistWoodsFogMinDistance;
				if (minDistance > 0 && minDistance > fogEnd) {
					float thickness = Math.max(1.0f, fogEnd - fogStart);
					fogEnd = minDistance;
					fogStart = fogEnd - thickness;
				}

				// Inverse power curve: slider 0 and 100 stay unchanged, but mid
				// values are pulled up toward 1.0. Compensates for the perceived
				// nonlinearity of fog compositing — small fogFactor reductions
				// below full saturation have a large visual impact, so 80% slider
				// should feel closer to ~90% effective density.
				float fogDensitySlider = IrisVideoSettings.wynncraftMistWoodsFogDensity / 100.0f;
				float fogDensity = 1.0f - (float) Math.pow(1.0f - fogDensitySlider, 1.5);
				float warmthReductionStrength = IrisVideoSettings.wynncraftMistWoodsFogSunTintReduction
					? IrisVideoSettings.wynncraftMistWoodsFogSunTintAmount / 100.0f
					: 0.0f;
				wynncraftBiomeFogRenderer.render(
					mainRT.getDepthTexture().iris$getGlId(),
					renderTargets.getDepthTextureNoTranslucents().iris$getGlId(),
					(GlTexture) mainRT.getColorTexture(),
					fogStart,
					fogEnd,
					biomeFogOpacity,
					fogDensity,
					warmthReductionStrength);
			}
		}

		// Apply atmospheric tint, darkening, and directional fog to terrain/entities.
		// Sky pixels were painted at beginTranslucents — this pass leaves them alone so
		// VFX that blended over the skybox during the translucent pass are preserved.
		if (wynncraftSkyboxRenderer != null && displayedSkyboxId > 0 && skyboxFadeOpacity > 0.001f) {
			com.mojang.blaze3d.pipeline.RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
			int dhDepthTex = dhCompat != null ? dhCompat.getDepthTex() : 0;
			int voxyDepthTex = voxyLodDepth != null ? voxyLodDepth.currentDepthTexId() : 0;
			wynncraftSkyboxRenderer.renderSceneEffects(
				main.getDepthTexture().iris$getGlId(),
				(GlTexture) main.getColorTexture(),
				computeWynncraftGameTime(),
				skyboxFadeOpacity,
				displayedSkyboxId,
				dhDepthTex,
				voxyDepthTex);
		}

		// Wynncraft transition rendering — independent of skybox state.
		// CPU-detected transitions from text display entities OR debug keys.
		if (wynncraftTransitionRenderer != null) {
			ImmediateState.TransitionDetection cpuTrans = ImmediateState.consumeTransitionDetection();

			int transType = cpuTrans.type();
			float transProgress = cpuTrans.opacity() / 255.0f;
			int transColor = cpuTrans.color();

			if (transType > 0 && transProgress > 0.001f) {
				com.mojang.blaze3d.pipeline.RenderTarget mainRT = Minecraft.getInstance().getMainRenderTarget();
				wynncraftTransitionRenderer.render(
					(GlTexture) mainRT.getColorTexture(),
					computeWynncraftGameTime(),
					transType,
					transProgress,
					transColor);
			}
		}
	}

	private float computeWynncraftGameTime() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			// Use modular time to avoid float precision loss on long-running servers.
			// On Wynncraft, getGameTime() can be hundreds of millions of ticks.
			// Without modulo, GameTime * 12000 in the shader produces values too large
			// for float precision, causing noise functions to return static values.
			// Cycle every 24000 ticks (one Minecraft day) — matches vanilla GameTime.
			long ticks = mc.level.getGameTime() % 24000L;
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
			return (float) ((ticks + partial) / 24000.0);
		}
		return 0.0f;
	}

	@Override
	public void finalizeGameRendering() {
		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
		colorSpaceConverter.process((GlTexture) main.getColorTexture());

		boolean restoredPreviousFrame = false;
		if (ambiencePresentationMaskFrames > 0) {
			restoredPreviousFrame = restoreAmbiencePreviousFrame(main);
			ambiencePresentationMaskFrames--;
				if (WynncraftDebugLog.shouldLog("ambience-presentation-mask")) {
					WynncraftDebugLog.info("ambience-presentation-mask",
						"Ambience presentation mask: restoredPreviousFrame={} remainingFrames={} ready={} size={}x{} pack={} activeKey={}",
						restoredPreviousFrame, ambiencePresentationMaskFrames, ambiencePreviousFrameReady, main.width, main.height,
						Iris.getCurrentPackName(), Iris.getActiveTransientShaderPackContextKey());
				}
			}

		if (!restoredPreviousFrame || ambiencePreviousFrameReady) {
			captureAmbiencePreviousFrame(main);
		}
	}

	private boolean restoreAmbiencePreviousFrame(RenderTarget main) {
		if (!ambiencePreviousFrameReady || ambiencePreviousFrameFramebuffer == null
			|| ambiencePreviousFrameWidth != main.width || ambiencePreviousFrameHeight != main.height) {
			return false;
		}

		int mainFramebuffer = ((RenderTargetInterface) main).iris$getFramebufferId();
		IrisRenderSystem.blitFramebuffer(ambiencePreviousFrameFramebuffer.getId(), mainFramebuffer,
			0, 0, main.width, main.height,
			0, 0, main.width, main.height,
			GL30C.GL_COLOR_BUFFER_BIT, GL30C.GL_NEAREST);
		return true;
	}

	private void captureAmbiencePreviousFrame(RenderTarget main) {
		if (!ensureAmbiencePreviousFrameResources(main.width, main.height)) {
			return;
		}

		((RenderTargetInterface) main).iris$bindFramebuffer();
		IrisRenderSystem.copyTexSubImage2D(ambiencePreviousFrameTexture, GL30C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, main.width, main.height);
		ambiencePreviousFrameReady = true;
	}

	private boolean ensureAmbiencePreviousFrameResources(int width, int height) {
		if (width <= 0 || height <= 0) {
			return false;
		}

		if (ambiencePreviousFrameTexture != 0 && ambiencePreviousFrameWidth == width && ambiencePreviousFrameHeight == height) {
			return true;
		}

		destroyAmbiencePreviousFrameResources();
		ambiencePreviousFrameTexture = GlStateManager._genTexture();
		IrisRenderSystem.texImage2D(ambiencePreviousFrameTexture, GL30C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA8, width, height, 0, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE, null);
		IrisRenderSystem.texParameteri(ambiencePreviousFrameTexture, GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_MIN_FILTER, GL30C.GL_NEAREST);
		IrisRenderSystem.texParameteri(ambiencePreviousFrameTexture, GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_MAG_FILTER, GL30C.GL_NEAREST);
		IrisRenderSystem.texParameteri(ambiencePreviousFrameTexture, GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_WRAP_S, GL30C.GL_CLAMP_TO_EDGE);
		IrisRenderSystem.texParameteri(ambiencePreviousFrameTexture, GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_WRAP_T, GL30C.GL_CLAMP_TO_EDGE);

		ambiencePreviousFrameFramebuffer = new GlFramebuffer();
		ambiencePreviousFrameFramebuffer.addColorAttachment(0, ambiencePreviousFrameTexture);
		ambiencePreviousFrameFramebuffer.readBuffer(0);
		ambiencePreviousFrameWidth = width;
		ambiencePreviousFrameHeight = height;
		return true;
	}

	private void destroyAmbiencePreviousFrameResources() {
		ambiencePreviousFrameReady = false;
		ambiencePreviousFrameWidth = 0;
		ambiencePreviousFrameHeight = 0;
		if (ambiencePreviousFrameFramebuffer != null) {
			ambiencePreviousFrameFramebuffer.destroy();
			ambiencePreviousFrameFramebuffer = null;
		}
		if (ambiencePreviousFrameTexture != 0) {
			GlStateManager._deleteTexture(ambiencePreviousFrameTexture);
			ambiencePreviousFrameTexture = 0;
		}
	}

	@Override
	public boolean shouldDisableVanillaEntityShadows() {
		// OptiFine seems to disable vanilla shadows when the shaderpack uses shadow mapping?
		return shadowRenderer != null;
	}

	@Override
	public boolean shouldRenderUnderwaterOverlay() {
		return shouldRenderUnderwaterOverlay;
	}

	@Override
	public boolean shouldRenderVignette() {
		return shouldRenderVignette;
	}

	@Override
	public boolean shouldRenderSun() {
		return shouldRenderSun;
	}

	@Override
	public boolean shouldRenderWeather() {
		return shouldRenderWeather;
	}

	@Override
	public boolean shouldRenderWeatherParticles() {
		return shouldRenderWeatherParticles;
	}

	@Override
	public boolean shouldRenderMoon() {
		return shouldRenderMoon;
	}

	@Override
	public boolean shouldRenderStars() {
		return shouldRenderStars;
	}

	@Override
	public boolean shouldRenderSkyDisc() {
		return shouldRenderSkyDisc;
	}

	@Override
	public boolean shouldWriteRainAndSnowToDepthBuffer() {
		return shouldWriteRainAndSnowToDepthBuffer;
	}

	@Override
	public ParticleRenderingSettings getParticleRenderingSettings() {
		return particleRenderingSettings;
	}

	@Override
	public boolean allowConcurrentCompute() {
		return allowConcurrentCompute;
	}

	@Override
	public boolean hasFeature(FeatureFlags flag) {
		return pack.hasFeature(flag);
	}

	@Override
	public boolean shouldDisableDirectionalShading() {
		return !oldLighting;
	}

	@Override
	public boolean shouldDisableFrustumCulling() {
		return !frustumCulling;
	}

	@Override
	public boolean shouldDisableOcclusionCulling() {
		return !occlusionCulling;
	}

	@Override
	public CloudSetting getCloudSetting() {
		return cloudSetting;
	}

	@Override
	public ShaderMap getShaderMap() {
		return shaderMap;
	}

	private void destroyShaders() {
		// NB: If you forget this, shader reloads won't work!
		loadedShaders.forEach(shader -> {
			shader.close();
		});
	}

	@Override
	public void destroy() {
		destroyed = true;

		destroyShaders();

		// Unbind all textures
		//
		// This is necessary because we don't want destroyed render target textures to remain bound to certain texture
		// units. Vanilla appears to properly rebind all textures as needed, and we do so too, so this does not cause
		// issues elsewhere.
		//
		// Without this code, there will be weird issues when reloading certain shaderpacks.
		for (int i = 0; i < 16; i++) {
			GlStateManager._activeTexture(GL20C.GL_TEXTURE0 + i);
			IrisRenderSystem.unbindAllSamplers();
			GlStateManager._bindTexture(0);
		}

		// Set the active texture unit to unit 0
		//
		// This seems to be what most code expects. It's a sane default in any case.
		GlStateManager._activeTexture(GL20C.GL_TEXTURE0);

		if (shadowCompositeRenderer != null) {
			shadowCompositeRenderer.destroy();
		}

		prepareRenderer.destroy();
		compositeRenderer.destroy();
		deferredRenderer.destroy();
		if (voxyEntityDepthClear != null) {
			voxyEntityDepthClear.destroy();
		}
		finalPassRenderer.destroy();
		centerDepthSampler.destroy();
		customTextureManager.destroy();
		whitePixel.close();

		horizonRenderer.destroy();

		if (wynncraftBiomeFogRenderer != null) {
			wynncraftBiomeFogRenderer.destroy();
			wynncraftBiomeFogRenderer = null;
		}
		if (wynncraftSkyboxRenderer != null) {
			wynncraftSkyboxRenderer.destroy();
			wynncraftSkyboxRenderer = null;
		}
		if (wynncraftTransitionRenderer != null) {
			wynncraftTransitionRenderer.destroy();
			wynncraftTransitionRenderer = null;
		}
		destroyAmbiencePreviousFrameResources();
		// Clear fog override on pipeline destroy (prevents cross-world ghosting)
		skyboxFogColor = null;
		skyboxFogBlendFactor = 0.0f;
		displayedSkyboxId = 0;
		skyboxFadeOpacity = 0.0f;

		GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, 0);
		GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, 0);
		GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);

		renderTargets.destroy();
		dhCompat.clearPipeline();

		clearImages.forEach(ImageClearPass::destroy);
		customImages.forEach(GlImage::destroy);
		releaseAmbienceCustomImageRefs();

		if (shadowRenderTargets != null) {
			shadowRenderTargets.destroy();
		}

		if (shadowRenderer != null) {
			shadowRenderer.destroy();
		}

		if (shaderStorageBufferHolder != null) {
			shaderStorageBufferHolder.destroyBuffers();
		}
		if (ambiencePoolAllocation != null) {
			ambiencePoolAllocation.close();
		}
	}

	private void releaseAmbienceCustomImageRefs() {
		for (AmbienceRenderTargetPool.ResourceRef ref : ambienceCustomImageRefs) {
			ref.close();
		}
		ambienceCustomImageRefs.clear();
	}

	@Override
	public boolean shouldOverrideShaders() {
		return isRenderingWorld && isMainBound;
	}

	@Override
	public SodiumPrograms getSodiumPrograms() {
		return sodiumPrograms;
	}

	@Override
	public FrameUpdateNotifier getFrameUpdateNotifier() {
		return updateNotifier;
	}

	@Override
	public float getSunPathRotation() {
		return sunPathRotation;
	}

	@Override
	public DHCompat getDHCompat() {
		return dhCompat;
	}

	public AbstractTexture getWhitePixel() {
		return whitePixel;
	}

	@Override
	public void setIsMainBound(boolean bound) {
		isMainBound = bound;
	}

	@Override
	public void onBeginClear() {
		setPhase(WorldRenderingPhase.SKY);

		// Render our horizon box before actual sky rendering to avoid being broken by mods that do weird things
		// while rendering the sky.
		//
		// A lot of dimension mods touch sky rendering, FabricSkyboxes injects at HEAD and cancels, etc.
		DimensionType.Skybox skyType = Minecraft.getInstance().level.dimensionType().skybox();

		if (shouldRenderSkyDisc && (skyType == DimensionType.Skybox.OVERWORLD || Minecraft.getInstance().level.dimensionType().hasSkyLight())) {
			Vector3d fogColor3 = CapturedRenderingState.INSTANCE.getFogColor();

			// NB: The alpha value must be 1.0 here, or else you will get a bunch of bugs. Sildur's Vibrant Shaders
			//     will give you pink reflections and other weirdness if this is zero.
			Vector4f fogColor = new Vector4f((float) fogColor3.x, (float) fogColor3.y, (float) fogColor3.z, 1.0F);

			// Override horizon fog color when Wynncraft skybox is active.
			// This makes the GBuffer sky match our skybox mood, affecting pack reflections.
			if (skyboxFogColor != null && skyboxFogBlendFactor > 0.001f) {
				float b = skyboxFogBlendFactor;
				fogColor.x = fogColor.x * (1 - b) + skyboxFogColor[0] * b;
				fogColor.y = fogColor.y * (1 - b) + skyboxFogColor[1] * b;
				fogColor.z = fogColor.z * (1 - b) + skyboxFogColor[2] * b;
			}

			horizonRenderer.renderHorizon(CapturedRenderingState.INSTANCE.getGbufferModelView(), CapturedRenderingState.INSTANCE.getGbufferProjection(), fogColor);
		}
	}

	@Override
	public boolean supportsEndFlash() {
		return supportsEndFlash;
	}

	@Override
	public int getAlbedoTex() {
		return albedoTex;
	}

	public Optional<ProgramSource> getDHTerrainShader() {
		return resolver.resolve(ProgramId.DhTerrain);
	}

	public Optional<ProgramSource> getDHGenericShader() {
		return resolver.resolve(ProgramId.DhGeneric);
	}

	public Optional<ProgramSource> getDHWaterShader() {
		return resolver.resolve(ProgramId.DhWater);
	}

	public Optional<ProgramSource> getDHShadowShader() {
		return resolver.resolve(ProgramId.DhShadow);
	}

	public CustomUniforms getCustomUniforms() {
		return customUniforms;
	}

	public GlFramebuffer createDHFramebuffer(ProgramSource sources, boolean trans) {
		ImmutableSet<Integer> flipped = trans ? flippedAfterTranslucent : flippedAfterPrepare;
		int[] drawBuffers = sources.getDirectives().getDrawBuffers();
		GlFramebuffer framebuffer = renderTargets.createDHFramebuffer(flipped, drawBuffers);
		trackDHFramebuffer(framebuffer, flipped, drawBuffers);
		return framebuffer;
	}

	public ImmutableSet<Integer> getFlippedBeforeShadow() {
		return flippedBeforeShadow;
	}

	public ImmutableSet<Integer> getFlippedAfterPrepare() {
		return flippedAfterPrepare;
	}

	public ImmutableSet<Integer> getFlippedAfterTranslucent() {
		return flippedAfterTranslucent;
	}

	public GlFramebuffer createDHFramebufferShadow(ProgramSource sources) {

		return shadowRenderTargets.createDHFramebuffer(ImmutableSet.of(), new int[]{0, 1});
	}

	public boolean hasShadowRenderTargets() {
		return shadowRenderTargets != null;
	}

	public boolean skipAllRendering() {
		return skipAllRendering;
	}

	public CloudSetting getDHCloudSetting() {
		return dhCloudSetting;
	}

	public void bindDefault() {
		if (isBeforeTranslucent) {
			defaultFB.bind();
		} else {
			defaultFBAlt.bind();
		}
	}

	public void bindDefaultShadow() {
		defaultFBShadow.bind();
	}
}
