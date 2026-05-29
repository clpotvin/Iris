package net.irisshaders.iris;

import com.google.common.base.Throwables;
import com.mojang.blaze3d.opengl.GlDebug;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializerRegistry;
import net.irisshaders.iris.ambience.AmbienceRenderTargetPool;
import net.irisshaders.iris.ambience.AmbienceRuntime;
import net.irisshaders.iris.ambience.AmbienceSwitchTiming;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import net.irisshaders.iris.gl.shader.ProgramBinaryCache;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gui.debug.DebugLoadFailedGridScreen;
import net.irisshaders.iris.gui.option.WynncraftDebugLog;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.irisshaders.iris.helpers.OptionalBoolean;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.discovery.ShaderpackDirectoryManager;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.option.OptionSet;
import net.irisshaders.iris.shaderpack.option.Profile;
import net.irisshaders.iris.shaderpack.option.values.MutableOptionValues;
import net.irisshaders.iris.shaderpack.option.values.OptionValues;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.irisshaders.iris.vertices.sodium.EntityToTerrainVertexSerializer;
import net.irisshaders.iris.vertices.sodium.GlyphExtVertexSerializer;
import net.irisshaders.iris.vertices.sodium.IrisEntityToTerrainVertexSerializer;
import net.irisshaders.iris.vertices.sodium.ModelToEntityVertexSerializer;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ARBParallelShaderCompile;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.KHRParallelShaderCompile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipError;
import java.util.zip.ZipException;

public class Iris {
	public static final String MODID = "iris";

	/**
	 * The user-facing name of the mod. Moved into a constant to facilitate
	 * easy branding changes (for forks). You'll still need to change this
	 * separately in mixin plugin classes & the language files.
	 */
	public static final String MODNAME = "WynnIris";
	public static final IrisLogging logger = new IrisLogging(MODNAME);
	public static final boolean IS_FOOL;
	private static final Map<String, String> shaderPackOptionQueue = new HashMap<>();
	private static final int MAX_TRANSIENT_SHADER_PACK_CONTEXTS = 1;
	private static final Map<String, ShaderRuntimeContext> transientShaderPackContexts = new LinkedHashMap<>(16, 0.75f, true);
	// Change this for snapshots!
	private static final String backupVersionNumber = "1.21.9";
	public static NamespacedId lastDimension = null;
	public static boolean testing = false;
	private static Path shaderpacksDirectory;
	private static ShaderpackDirectoryManager shaderpacksDirectoryManager;
	private static ShaderPack currentPack;
	private static String currentPackName;
	private static Optional<Exception> storedError = Optional.empty();
	private static boolean initialized;
	private static PipelineManager pipelineManager;
	private static IrisConfig irisConfig;
	private static FileSystem zipFileSystem;
	private static KeyMapping reloadKeybind;
	private static final KeyMapping.Category irisKeybindCategory = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("iris", "keybinds"));
	private static KeyMapping toggleShadersKeybind;
	private static KeyMapping shaderpackScreenKeybind;
	private static KeyMapping wireframeKeybind;
	// Flag variable used when reloading
	// Used in favor of queueDefaultShaderPackOptionValues() for resetting as the
	// behavior is more concrete and therefore is more likely to repair a user's issues
	private static boolean resetShaderPackOptions = false;
	private static String IRIS_VERSION;
	private static UpdateChecker updateChecker;
	private static boolean fallback;
	private static boolean loadShaderPackWhenPossible;
	private static boolean suppressAmbienceInvalidation;
	private static ShaderRuntimeContext configuredShaderPackContext;
	private static String activeTransientShaderPackContextKey;
	private static AmbienceRenderTargetPool ambienceRenderTargetPool;
	private static AmbienceRenderTargetPool ambiencePipelineBuildPool;
	private static String ambiencePipelineBuildProfileKey;

	static {
		if (!BuildConfig.ACTIVATE_RENDERDOC && IrisPlatformHelpers.getInstance().isDevelopmentEnvironment() && System.getProperty("user.name").contains("ims") && Util.getPlatform() == Util.OS.LINUX) {
		}

		Calendar c = Calendar.getInstance();
		IS_FOOL = c.get(Calendar.MONTH) == Calendar.APRIL && c.get(Calendar.DAY_OF_MONTH) == 1;
	}

	/**
	 * Called once RenderSystem#initRenderer has completed. This means that we can safely access OpenGL.
	 */
	public static void onRenderSystemInit() {
		if (!initialized) {
			Iris.logger.warn("Iris::onRenderSystemInit was called, but Iris::onEarlyInitialize was not called." +
				" Trying to avoid a crash but this is an odd state.");
			return;
		}

		if (GL.getCapabilities().GL_KHR_parallel_shader_compile) {
			KHRParallelShaderCompile.glMaxShaderCompilerThreadsKHR(10);
		} else if (GL.getCapabilities().GL_ARB_parallel_shader_compile) {
			ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(10);
		}

		PBRTextureManager.INSTANCE.init();

		VertexSerializerRegistry.instance().registerSerializer(DefaultVertexFormat.NEW_ENTITY, IrisVertexFormats.TERRAIN, new EntityToTerrainVertexSerializer());
		VertexSerializerRegistry.instance().registerSerializer(IrisVertexFormats.ENTITY, IrisVertexFormats.TERRAIN, new IrisEntityToTerrainVertexSerializer());
		VertexSerializerRegistry.instance().registerSerializer(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, IrisVertexFormats.GLYPH, new GlyphExtVertexSerializer());
		VertexSerializerRegistry.instance().registerSerializer(DefaultVertexFormat.NEW_ENTITY, IrisVertexFormats.ENTITY, new ModelToEntityVertexSerializer());

		// Only load the shader pack when we can access OpenGL
		if (!IrisPlatformHelpers.getInstance().isModLoaded("distanthorizons")) {
			loadShaderpack();
		}
	}

	public static void duringRenderSystemInit() {
		setDebug(irisConfig.areDebugOptionsEnabled());
	}

	/**
	 * Called when the title screen is initialized for the first time.
	 */
	public static void onLoadingComplete() {
		if (!initialized) {
			Iris.logger.warn("Iris::onLoadingComplete was called, but Iris::onEarlyInitialize was not called." +
				" Trying to avoid a crash but this is an odd state.");
			return;
		}

		// Initialize the pipeline now so that we don't increase world loading time. Just going to guess that
		// the player is in the overworld.
		// See: https://github.com/IrisShaders/Iris/issues/323
		lastDimension = DimensionId.OVERWORLD;
		Iris.getPipelineManager().preparePipeline(DimensionId.OVERWORLD);
	}

	public static void handleKeybinds(Minecraft minecraft) {
		if (loadShaderPackWhenPossible) {
			loadShaderPackWhenPossible = false;
			Iris.loadShaderpack();
		}

		if (reloadKeybind.consumeClick()) {
			try {
				reload();

				if (minecraft.player != null) {
					minecraft.player.displayClientMessage(Component.translatable("iris.shaders.reloaded"), false);
				}

			} catch (Exception e) {
				logger.error("Error while reloading Shaders for Iris!", e);

				if (minecraft.player != null) {
					minecraft.player.displayClientMessage(Component.translatable("iris.shaders.reloaded.failure", Throwables.getRootCause(e).getMessage()).withStyle(ChatFormatting.RED), false);
				}
			}
		} else if (toggleShadersKeybind.consumeClick()) {
			try {
				toggleShaders(minecraft, !irisConfig.areShadersEnabled());
			} catch (Exception e) {
				logger.error("Error while toggling shaders!", e);

				if (minecraft.player != null) {
					minecraft.player.displayClientMessage(Component.translatable("iris.shaders.toggled.failure", Throwables.getRootCause(e).getMessage()).withStyle(ChatFormatting.RED), false);
				}
				setShadersDisabled();
				fallback = true;
			}
		} else if (shaderpackScreenKeybind.consumeClick()) {
			minecraft.setScreen(new ShaderPackScreen(null));
		} else if (wireframeKeybind.consumeClick()) {
			if (irisConfig.areDebugOptionsEnabled() && minecraft.player != null && !Minecraft.getInstance().isLocalServer()) {
				minecraft.player.displayClientMessage(Component.literal("No cheating; wireframe only in singleplayer!"), false);
			}
		}
	}

	public static boolean shouldActivateWireframe() {
		return irisConfig.areDebugOptionsEnabled() && wireframeKeybind.isDown();
	}

	public static void toggleShaders(Minecraft minecraft, boolean enabled) throws IOException {
		irisConfig.setShadersEnabled(enabled);
		irisConfig.save();

		reload();
		if (minecraft.player != null) {
			minecraft.player.displayClientMessage(enabled ? Component.translatable("iris.shaders.toggled", currentPackName) : Component.translatable("iris.shaders.disabled"), false);
		}
	}

	public static void loadShaderpack() {
		if (irisConfig == null) {
			if (!initialized) {
				throw new IllegalStateException("Iris::loadShaderpack was called, but Iris::onInitializeClient wasn't" +
					" called yet. How did this happen?");
			} else {
				throw new NullPointerException("Iris.irisConfig was null unexpectedly");
			}
		}

		if (!irisConfig.areShadersEnabled()) {
			logger.info("Shaders are disabled because enableShaders is set to false in iris.properties");

			setShadersDisabled();

			return;
		}

		// Attempt to load an external shaderpack if it is available
		Optional<String> externalName = irisConfig.getShaderPackName();

		if (externalName.isEmpty()) {
			logger.info("Shaders are disabled because no valid shaderpack is selected");

			setShadersDisabled();

			return;
		}

		if (!loadExternalShaderpack(externalName.get())) {
			logger.warn("Falling back to normal rendering without shaders because the shaderpack could not be loaded");
			setShadersDisabled();
			fallback = true;
		}
	}

	private static void loadConfiguredShaderpackWithoutTransientSideEffects() {
		if (!irisConfig.areShadersEnabled()) {
			logger.info("Shaders are disabled because enableShaders is set to false in iris.properties");
			setShadersDisabled();
			return;
		}

		Optional<String> externalName = irisConfig.getShaderPackName();
		if (externalName.isEmpty()) {
			logger.info("Shaders are disabled because no valid shaderpack is selected");
			setShadersDisabled();
			return;
		}

		if (!loadExternalShaderpack(externalName.get(), null, true, false, true)) {
			logger.warn("Falling back to normal rendering without shaders because the shaderpack could not be loaded");
			setShadersDisabled();
			fallback = true;
		}
	}

	private static boolean loadExternalShaderpack(String name) {
		return loadExternalShaderpack(name, null, true, true, true);
	}

	@SuppressWarnings("unchecked")
	private static boolean loadExternalShaderpack(String name, Map<String, String> optionOverrides, boolean readPackOptions, boolean consumeOptionQueue, boolean persistPackOptions) {
		Path shaderPackRoot;
		Path shaderPackConfigTxt;

		try {
			shaderPackRoot = getShaderpacksDirectory().resolve(name);
			shaderPackConfigTxt = getShaderpacksDirectory().resolve(name + ".txt");
		} catch (InvalidPathException e) {
			logger.error("Failed to load the shaderpack \"{}\" because it contains invalid characters in its path", name);

			return false;
		}

		if (!isValidShaderpack(shaderPackRoot)) {
			logger.error("Pack \"{}\" is not valid! Can't load it.", name);
			return false;
		}

		Path shaderPackPath;
		boolean isZip = false;

		if (!Files.isDirectory(shaderPackRoot) && shaderPackRoot.toString().endsWith(".zip")) {
			Optional<Path> optionalPath;

			try {
				optionalPath = loadExternalZipShaderpack(shaderPackRoot);
			} catch (FileSystemNotFoundException | NoSuchFileException e) {
				logger.error("Failed to load the shaderpack \"{}\" because it does not exist in your shaderpacks folder!", name);

				return false;
			} catch (ZipException e) {
				logger.error("The shaderpack \"{}\" appears to be corrupted, please try downloading it again!", name);

				return false;
			} catch (IOException e) {
				logger.error("Failed to load the shaderpack \"{}\"!", name);
				logger.error("", e);

				return false;
			}

			if (optionalPath.isPresent()) {
				shaderPackPath = optionalPath.get();
			} else {
				logger.error("Could not load the shaderpack \"{}\" because it appears to lack a \"shaders\" directory", name);
				return false;
			}
			isZip = true;
		} else {
			if (!Files.exists(shaderPackRoot)) {
				logger.error("Failed to load the shaderpack \"{}\" because it does not exist!", name);
				return false;
			}

			// If it's a folder-based shaderpack, just use the shaders subdirectory
			shaderPackPath = shaderPackRoot.resolve("shaders");
		}

		if (!Files.exists(shaderPackPath)) {
			logger.error("Could not load the shaderpack \"{}\" because it appears to lack a \"shaders\" directory", name);
			return false;
		}

		Map<String, String> changedConfigs = readPackOptions
			? tryReadConfigProperties(shaderPackConfigTxt)
				.map(properties -> (Map<String, String>) (Object) properties)
				.orElse(new HashMap<>())
			: new HashMap<>();

		if (optionOverrides != null) {
			changedConfigs.putAll(optionOverrides);
		}

		if (consumeOptionQueue) {
			changedConfigs.putAll(shaderPackOptionQueue);
			clearShaderPackOptionQueue();

			if (resetShaderPackOptions) {
				changedConfigs.clear();
			}
			resetShaderPackOptions = false;
		}

		try {
			currentPack = new ShaderPack(shaderPackPath, changedConfigs, StandardMacros.createStandardEnvironmentDefines(), isZip);

			if (persistPackOptions) {
				MutableOptionValues changedConfigsValues = currentPack.getShaderPackOptions().getOptionValues().mutableCopy();

				// Store changed values from those currently in use by the shader pack
				Properties configsToSave = new Properties();
				changedConfigsValues.getBooleanValues().forEach((k, v) -> configsToSave.setProperty(k, Boolean.toString(v)));
				changedConfigsValues.getStringValues().forEach(configsToSave::setProperty);

				tryUpdateConfigPropertiesFile(shaderPackConfigTxt, configsToSave);
			}
		} catch (Exception e) {
			logger.error("Failed to load the shaderpack \"{}\"!", name);
			logger.error("", e);
			handleException(e);

			return false;
		}

		fallback = false;
		currentPackName = name;

		logger.info("Using shaderpack: " + name);

		return true;
	}

	public static boolean applyTransientShaderPack(String name, Map<String, String> optionOverrides) throws IOException {
		if (name == null || name.isBlank()) {
			return false;
		}

		CapturedRenderingState.INSTANCE.resetTextureReloadCount();
		destroyEverything();

		boolean loaded;
		AmbienceRenderTargetPool previousBuildPool = ambiencePipelineBuildPool;
		String previousBuildProfileKey = ambiencePipelineBuildProfileKey;
		ambiencePipelineBuildPool = getOrCreateAmbienceRenderTargetPool();
		ambiencePipelineBuildProfileKey = name;
		AmbienceSwitchTiming switchTiming = new AmbienceSwitchTiming("transient", name);
		try {
			loaded = loadExternalShaderpack(name, optionOverrides == null ? Map.of() : optionOverrides, false, false, false);
			if (loaded && Minecraft.getInstance().level != null) {
				prepareTransientPipelineContext(switchTiming);
				if (fallback) {
					loaded = false;
				}
				if (loaded && pipelineManager != null && pipelineManager.getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
					activateAmbiencePipeline(pipeline, switchTiming);
					completeAmbienceSwitchTiming(switchTiming);
				}
			}
		} finally {
			ambiencePipelineBuildPool = previousBuildPool;
			ambiencePipelineBuildProfileKey = previousBuildProfileKey;
		}

		if (!loaded) {
			logger.warn("Restoring configured shaderpack after ambience profile \"{}\" failed to load", name);
			loadConfiguredShaderpackWithoutTransientSideEffects();
			if (Minecraft.getInstance().level != null) {
				prepareTransientPipelineContext();
			}
		}

		return loaded;
	}

	public static boolean applyCachedTransientShaderPack(String cacheKey, String name, Map<String, String> optionOverrides) throws IOException {
		if (cacheKey == null || cacheKey.isBlank()) {
			return applyTransientShaderPack(name, optionOverrides);
		}
		if (name == null || name.isBlank()) {
			return false;
		}

		long startNanos = System.nanoTime();
		if (cacheKey.equals(activeTransientShaderPackContextKey)) {
			logAmbienceContextTiming("ambience-profile-cache-current", "Ambience shader profile {} already active", cacheKey, startNanos);
			return true;
		}

		if (configuredShaderPackContext == null && activeTransientShaderPackContextKey == null) {
			configuredShaderPackContext = snapshotShaderRuntimeContext();
		}

		ShaderRuntimeContext cached = transientShaderPackContexts.get(cacheKey);
		if (cached != null) {
			activateShaderRuntimeContext(cached, cacheKey, "cache-hit");
			logAmbienceContextTiming("ambience-profile-cache-hit", "Activated cached ambience shader profile {}", cacheKey, startNanos);
			return true;
		}

		ShaderRuntimeContext previous = snapshotShaderRuntimeContext();
		String previousTransientKey = activeTransientShaderPackContextKey;

		CapturedRenderingState.INSTANCE.resetTextureReloadCount();
		BlendModeStorage.restoreBlend();
		currentPack = null;
		currentPackName = null;
		zipFileSystem = null;
		pipelineManager = new PipelineManager(Iris::createPipeline);
		fallback = false;
		activeTransientShaderPackContextKey = null;

		boolean loaded = false;
		AmbienceRenderTargetPool previousBuildPool = ambiencePipelineBuildPool;
		String previousBuildProfileKey = ambiencePipelineBuildProfileKey;
		ambiencePipelineBuildPool = getOrCreateAmbienceRenderTargetPool();
		ambiencePipelineBuildProfileKey = cacheKey;
		AmbienceSwitchTiming switchTiming = new AmbienceSwitchTiming("cache-miss", cacheKey);
		try {
			loaded = loadExternalShaderpack(name, optionOverrides == null ? Map.of() : optionOverrides, false, false, false);
			if (loaded) {
				prepareTransientPipelineContext(switchTiming);
				if (fallback) {
					logger.warn("Ambience profile \"{}\" loaded its shader pack, but pipeline creation fell back to vanilla rendering", name);
					loaded = false;
				}
			}
			if (loaded) {
				ShaderRuntimeContext context = snapshotShaderRuntimeContext();
				transientShaderPackContexts.put(cacheKey, context);
				activeTransientShaderPackContextKey = cacheKey;
				if (pipelineManager != null && pipelineManager.getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
					activateAmbiencePipeline(pipeline, switchTiming);
					completeAmbienceSwitchTiming(switchTiming);
				}
				enforceTransientShaderPackContextBudget();
				logAmbienceContextTiming("ambience-profile-cache-miss", "Compiled and cached ambience shader profile {}", cacheKey, startNanos);
				return true;
			}
		} catch (RuntimeException e) {
			ambiencePipelineBuildPool = previousBuildPool;
			ambiencePipelineBuildProfileKey = previousBuildProfileKey;
			destroyCurrentTransientAttempt();
			activateShaderRuntimeContext(previous, previousTransientKey);
			throw e;
		} finally {
			ambiencePipelineBuildPool = previousBuildPool;
			ambiencePipelineBuildProfileKey = previousBuildProfileKey;
		}

		destroyCurrentTransientAttempt();
		activateShaderRuntimeContext(previous, previousTransientKey);
		return false;
	}

	public static void restoreConfiguredShaderPack() throws IOException {
		if (configuredShaderPackContext != null) {
			long startNanos = System.nanoTime();
			activateShaderRuntimeContext(configuredShaderPackContext, null, "restore-cached");
			logAmbienceContextTiming("ambience-profile-restore-cached", "Restored configured shader profile from ambience cache", null, startNanos);
			return;
		}

		suppressAmbienceInvalidation = true;
		try {
			reload();
		} finally {
			suppressAmbienceInvalidation = false;
		}
	}

	public static void trimTransientShaderPackCacheToBudget() {
		enforceTransientShaderPackContextBudget();
	}

	public static int getTransientShaderPackContextBudget() {
		return ambienceRenderTargetPool == null ? MAX_TRANSIENT_SHADER_PACK_CONTEXTS : Integer.MAX_VALUE;
	}

	public static String getTransientShaderPackContextBudgetLabel() {
		return ambienceRenderTargetPool == null ? Integer.toString(MAX_TRANSIENT_SHADER_PACK_CONTEXTS) : "unbounded";
	}

	public static int getTransientShaderPackContextCount() {
		return transientShaderPackContexts.size();
	}

	public static int getRetainedShaderRuntimeContextCount() {
		return transientShaderPackContexts.size() + (configuredShaderPackContext == null ? 0 : 1);
	}

	public static int getProgramBinaryCacheEntryCount() {
		return ProgramBinaryCache.getEntryCount();
	}

	public static boolean isProgramBinaryCacheAvailable() {
		return ProgramBinaryCache.isAvailable();
	}

	public static int getProgramBinaryCacheFormatCount() {
		return ProgramBinaryCache.getBinaryFormatCount();
	}

	public static long getProgramBinaryCacheBytes() {
		return ProgramBinaryCache.getTotalBytes();
	}

	public static AmbienceRenderTargetPool getAmbienceRenderTargetPoolForPipelineBuild() {
		return ambiencePipelineBuildPool;
	}

	public static String getAmbienceRenderTargetPoolProfileKeyForPipelineBuild() {
		return ambiencePipelineBuildProfileKey;
	}

	public static int getAmbienceRenderTargetPoolResourceCount() {
		return ambienceRenderTargetPool == null ? 0 : ambienceRenderTargetPool.getResourceCount();
	}

	public static long getAmbienceRenderTargetPoolEstimatedBytes() {
		return ambienceRenderTargetPool == null ? 0 : ambienceRenderTargetPool.getEstimatedBytes();
	}

	public static long getAmbienceRenderTargetPoolHits() {
		return ambienceRenderTargetPool == null ? 0 : ambienceRenderTargetPool.getHits();
	}

	public static long getAmbienceRenderTargetPoolMisses() {
		return ambienceRenderTargetPool == null ? 0 : ambienceRenderTargetPool.getMisses();
	}

	public static long getAmbienceRenderTargetPoolReleases() {
		return ambienceRenderTargetPool == null ? 0 : ambienceRenderTargetPool.getReleases();
	}

	public static long getAmbienceRenderTargetPoolDestroyedResources() {
		return ambienceRenderTargetPool == null ? 0 : ambienceRenderTargetPool.getDestroyedResources();
	}

	public static String getAmbienceRenderTargetPoolBreakdownSummary() {
		return ambienceRenderTargetPool == null ? "none" : ambienceRenderTargetPool.getBreakdown().compact();
	}

	public static String getAmbienceRenderTargetPoolProfilePressureSummary() {
		if (ambienceRenderTargetPool == null) {
			return "none";
		}

		Map<String, AmbienceRenderTargetPool.ProfilePressure> pressures = ambienceRenderTargetPool.getProfilePressures();
		if (pressures.isEmpty()) {
			return "none";
		}

		StringBuilder builder = new StringBuilder();
		for (AmbienceRenderTargetPool.ProfilePressure pressure : pressures.values()) {
			if (builder.length() > 0) {
				builder.append(",");
			}
			builder.append(summarizeAmbienceProfileKey(pressure.profileKey()))
				.append("(allocs=").append(pressure.allocations())
				.append(",resources=").append(pressure.resources())
				.append(",sharedBytes=").append(pressure.sharedBytes())
				.append(",exclusiveBytes=").append(pressure.exclusiveBytes())
				.append(",shared=").append(pressure.sharedBreakdown().compact())
				.append(",exclusive=").append(pressure.exclusiveBreakdown().compact())
				.append(")");
		}
		return builder.toString();
	}

	private static String summarizeAmbienceProfileKey(String key) {
		if (key == null || key.isBlank()) {
			return "unknown";
		}
		String[] parts = key.split(":", 3);
		if (parts.length >= 2) {
			return parts[0] + ":" + parts[1];
		}
		return key;
	}

	private static void handleException(Exception e) {
		if (irisConfig.areDebugOptionsEnabled()) {
			Minecraft.getInstance().setScreen(new DebugLoadFailedGridScreen(Minecraft.getInstance().screen, Component.literal(e instanceof ShaderCompileException ? "Failed to compile shaders" : "Exception"), e));
		} else {
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.displayClientMessage(Component.translatable(e instanceof ShaderCompileException ? "iris.load.failure.shader" : "iris.load.failure.generic").append(Component.literal("Copy Info").withStyle(arg -> arg.withUnderlined(true).withColor(
					ChatFormatting.BLUE).withClickEvent(new ClickEvent.CopyToClipboard(e.getMessage())).withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.copy.click"))))), false);
			} else {
				storedError = Optional.of(e);
			}
		}
	}

	private static Optional<Path> loadExternalZipShaderpack(Path shaderpackPath) throws IOException {
		FileSystem zipSystem;
		try {
			zipSystem = FileSystems.newFileSystem(shaderpackPath, Iris.class.getClassLoader());
		} catch (FileSystemAlreadyExistsException e) {
			zipSystem = FileSystems.getFileSystem(URI.create("jar:" + shaderpackPath.toUri()));
		}
		zipFileSystem = zipSystem;

		// Should only be one root directory for a zip shaderpack
		Path root = zipSystem.getRootDirectories().iterator().next();

		Path potentialShaderDir = zipSystem.getPath("shaders");

		// If the shaders dir was immediately found return it
		// Otherwise, manually search through each directory path until it ends with "shaders"
		if (Files.exists(potentialShaderDir)) {
			return Optional.of(potentialShaderDir);
		}

		// Sometimes shaderpacks have their shaders directory within another folder in the shaderpack
		// For example Sildurs-Vibrant-Shaders.zip/shaders
		// While other packs have Trippy-Shaderpack-master.zip/Trippy-Shaderpack-master/shaders
		// This makes it hard to determine what is the actual shaders dir
		try (Stream<Path> stream = Files.walk(root)) {
			return stream
				.filter(Files::isDirectory)
				.filter(path -> path.endsWith("shaders"))
				.findFirst();
		}
	}

	private static void setShadersDisabled() {
		currentPack = null;
		fallback = false;
		currentPackName = "(off)";
	}

	public static void setDebug(boolean enable) {
		try {
			irisConfig.setDebugEnabled(enable);
			irisConfig.save();
		} catch (IOException e) {
			Iris.logger.fatal("Failed to save config!", e);
		}

		int success;
		if (enable) {
			success = GLDebug.setupDebugMessageCallback();
		} else {
			GLDebug.reloadDebugState();
			GlDebug.enableDebugCallback(Minecraft.getInstance().options.glDebugVerbosity, false, new HashSet<>(((GlDevice) RenderSystem.getDevice()).getEnabledExtensions()));
			success = 1;
		}

		logger.info("Debug functionality is " + (enable ? "enabled, logging will be more verbose!" : "disabled."));
		if (Minecraft.getInstance().player != null) {
			if (IrisPlatformHelpers.getInstance().useELS()) {
				Minecraft.getInstance().player.displayClientMessage(Component.translatable("iris.shaders.debug.restartNoDebug"), false);
			} else {
				Minecraft.getInstance().player.displayClientMessage(Component.translatable(success != 0 ? (enable ? "iris.shaders.debug.enabled" : "iris.shaders.debug.disabled") : "iris.shaders.debug.failure"), false);
			}

			if (success == 2 && !IrisPlatformHelpers.getInstance().useELS()) {
				Minecraft.getInstance().player.displayClientMessage(Component.translatable("iris.shaders.debug.restart"), false);
			}
		}
	}

	private static Optional<Properties> tryReadConfigProperties(Path path) {
		Properties properties = new Properties();

		if (Files.exists(path)) {
			try (InputStream is = Files.newInputStream(path)) {
				// NB: config properties are specified to be encoded with ISO-8859-1 by OptiFine,
				//     so we don't need to do the UTF-8 workaround here.
				properties.load(is);
			} catch (IOException e) {
				// TODO: Better error handling
				return Optional.empty();
			}
		}

		return Optional.of(properties);
	}

	private static void tryUpdateConfigPropertiesFile(Path path, Properties properties) {
		try {
			if (properties.isEmpty()) {
				// Delete the file or don't create it if there are no changed configs
				if (Files.exists(path)) {
					Files.delete(path);
				}

				return;
			}

			try (OutputStream out = Files.newOutputStream(path)) {
				properties.store(out, null);
			}
		} catch (IOException e) {
			// TODO: Better error handling
		}
	}

	public static boolean isValidToShowPack(Path pack) {
		return Files.isDirectory(pack) || pack.toString().endsWith(".zip");
	}

	public static boolean isValidShaderpack(Path pack) {
		if (Files.isDirectory(pack)) {
			// Sometimes the shaderpack directory itself can be
			// identified as a shader pack due to it containing
			// folders which contain "shaders" folders, this is
			// necessary to check against that
			if (pack.equals(getShaderpacksDirectory())) {
				return false;
			}
			return pack.resolve("shaders").toFile().exists();
		}

		if (pack.toString().endsWith(".zip")) {
			try (FileSystem zipSystem = FileSystems.newFileSystem(pack, Iris.class.getClassLoader())) {
				Path root = zipSystem.getRootDirectories().iterator().next();
				try (Stream<Path> stream = Files.walk(root)) {
					return stream
						.filter(Files::isDirectory)
						.anyMatch(path -> path.endsWith("shaders"));
				}
			} catch (ZipError zipError) {
				// Java 8 seems to throw a ZipError instead of a subclass of IOException
				Iris.logger.warn("The ZIP at " + pack + " is corrupt");
			} catch (IOException ignored) {
				// ignored, not a valid shader pack.
			}
		}

		return false;
	}

	public static Map<String, String> getShaderPackOptionQueue() {
		return shaderPackOptionQueue;
	}

	public static void queueShaderPackOptionsFromProfile(Profile profile) {
		getShaderPackOptionQueue().putAll(profile.optionValues);
	}

	public static void queueShaderPackOptionsFromProperties(Properties properties) {
		queueDefaultShaderPackOptionValues();

		properties.stringPropertyNames().forEach(key ->
			getShaderPackOptionQueue().put(key, properties.getProperty(key)));
	}

	// Used in favor of resetShaderPackOptions as the aforementioned requires the pack to be reloaded
	public static void queueDefaultShaderPackOptionValues() {
		clearShaderPackOptionQueue();

		getCurrentPack().ifPresent(pack -> {
			OptionSet options = pack.getShaderPackOptions().getOptionSet();
			OptionValues values = pack.getShaderPackOptions().getOptionValues();

			options.getStringOptions().forEach((key, mOpt) -> {
				if (values.getStringValue(key).isPresent()) {
					getShaderPackOptionQueue().put(key, mOpt.getOption().getDefaultValue());
				}
			});
			options.getBooleanOptions().forEach((key, mOpt) -> {
				if (values.getBooleanValue(key) != OptionalBoolean.DEFAULT) {
					getShaderPackOptionQueue().put(key, Boolean.toString(mOpt.getOption().getDefaultValue()));
				}
			});
		});
	}

	public static void clearShaderPackOptionQueue() {
		getShaderPackOptionQueue().clear();
	}

	public static void resetShaderPackOptionsOnNextReload() {
		resetShaderPackOptions = true;
	}

	public static boolean shouldResetShaderPackOptionsOnNextReload() {
		return resetShaderPackOptions;
	}

	public static void reload() throws IOException {
		if (!suppressAmbienceInvalidation) {
			AmbienceRuntime.invalidateActiveProfile();
		}
		clearInactiveTransientShaderPackContexts();

		// allows shaderpacks to be changed at runtime
		irisConfig.initialize();

		// Reset the texture reload counter
		CapturedRenderingState.INSTANCE.resetTextureReloadCount();

		// Destroy all allocated resources
		destroyEverything();
		destroyAmbienceRenderTargetPool();

		// Load the new shaderpack
		loadShaderpack();

		// Very important - we need to re-create the pipeline straight away.
		// https://github.com/IrisShaders/Iris/issues/1330
		if (Minecraft.getInstance().level != null) {
			prepareTransientPipelineContext();
		}
	}

	private static void prepareTransientPipelineContext() {
		prepareTransientPipelineContext(null);
	}

	private static void prepareTransientPipelineContext(AmbienceSwitchTiming timing) {
		NamespacedId dimension = Iris.getCurrentDimension();
		if (dimension == null) {
			dimension = DimensionId.OVERWORLD;
		}

		long prepareStartNanos = System.nanoTime();
		Iris.getPipelineManager().preparePipeline(dimension);
		if (timing != null) {
			timing.addPreparePipelineNanos(System.nanoTime() - prepareStartNanos);
		}

		reapplyCurrentPipelineSettings(timing);
	}

	private static ShaderRuntimeContext snapshotShaderRuntimeContext() {
		return new ShaderRuntimeContext(currentPackName, currentPack, pipelineManager, zipFileSystem, fallback);
	}

	private static void activateShaderRuntimeContext(ShaderRuntimeContext context, String transientKey) {
		activateShaderRuntimeContext(context, transientKey, transientKey == null ? "restore" : "cache-hit");
	}

	private static void activateShaderRuntimeContext(ShaderRuntimeContext context, String transientKey, String action) {
		BlendModeStorage.restoreBlend();
		currentPackName = context.packName();
		currentPack = context.pack();
		pipelineManager = context.pipelineManager();
		zipFileSystem = context.zipFileSystem();
		fallback = context.fallback();
		activeTransientShaderPackContextKey = transientKey;

		AmbienceRenderTargetPool previousBuildPool = ambiencePipelineBuildPool;
		String previousBuildProfileKey = ambiencePipelineBuildProfileKey;
		if (transientKey != null) {
			ambiencePipelineBuildPool = getOrCreateAmbienceRenderTargetPool();
			ambiencePipelineBuildProfileKey = transientKey;
		}
		AmbienceSwitchTiming timing = new AmbienceSwitchTiming(action, transientKey == null ? "configured" : transientKey);
		try {
			prepareTransientPipelineContext(timing);
		} finally {
			ambiencePipelineBuildPool = previousBuildPool;
			ambiencePipelineBuildProfileKey = previousBuildProfileKey;
		}
		if (pipelineManager != null && pipelineManager.getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
			activateAmbiencePipeline(pipeline, timing);
			completeAmbienceSwitchTiming(timing);
		}
	}

	private static void reapplyCurrentPipelineSettings() {
		reapplyCurrentPipelineSettings(null);
	}

	private static void reapplyCurrentPipelineSettings(AmbienceSwitchTiming timing) {
		long reapplyStartNanos = System.nanoTime();
		if (pipelineManager != null && pipelineManager.getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
			pipeline.applyWorldRenderingSettings();
		}
		if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
			String reloadReasons = WorldRenderingSettings.INSTANCE.getReloadReasonSummary();
			if (timing != null && WynncraftDebugLog.shouldLog("ambience-world-settings-reload")) {
				WynncraftDebugLog.info("ambience-world-settings-reload",
					"Ambience world rendering reload required: action={} profile={} reasons={}",
					timing.action(), timing.profileKey(), reloadReasons);
			}
			if (Minecraft.getInstance().levelRenderer != null) {
				long reloadStartNanos = System.nanoTime();
				Minecraft.getInstance().levelRenderer.allChanged();
				if (timing != null) {
					timing.addLevelRendererReloadNanos(System.nanoTime() - reloadStartNanos);
				}
			}
			WorldRenderingSettings.INSTANCE.clearReloadRequired();
		}
		if (timing != null) {
			timing.addReapplySettingsNanos(System.nanoTime() - reapplyStartNanos);
		}
	}

	private static void activateAmbiencePipeline(IrisRenderingPipeline pipeline, AmbienceSwitchTiming timing) {
		long activationStartNanos = System.nanoTime();
		pipeline.onAmbienceProfileActivated(timing);
		timing.addProfileActivationNanos(System.nanoTime() - activationStartNanos);
	}

	private static void completeAmbienceSwitchTiming(AmbienceSwitchTiming timing) {
		timing.finish();
		if (WynncraftDebugLog.shouldLog("ambience-profile-switch-phases")) {
			WynncraftDebugLog.info("ambience-profile-switch-phases",
				"Ambience switch phases: {} poolBreakdown={} profilePressures={}",
				timing.compactMicros(),
				getAmbienceRenderTargetPoolBreakdownSummary(),
				getAmbienceRenderTargetPoolProfilePressureSummary());
		}
		AmbienceRuntime.markFirstFrameAfterSwitch(timing.action(), timing.profileKey());
	}

	private static AmbienceRenderTargetPool getOrCreateAmbienceRenderTargetPool() {
		if (ambienceRenderTargetPool == null) {
			ambienceRenderTargetPool = new AmbienceRenderTargetPool();
		}
		return ambienceRenderTargetPool;
	}

	private static void destroyAmbienceRenderTargetPool() {
		if (ambienceRenderTargetPool != null) {
			ambienceRenderTargetPool.destroy();
			ambienceRenderTargetPool = null;
		}
		ambiencePipelineBuildPool = null;
		ambiencePipelineBuildProfileKey = null;
	}

	private static void enforceTransientShaderPackContextBudget() {
		if (ambienceRenderTargetPool != null) {
			return;
		}

		int budget = getTransientShaderPackContextBudget();
		if (transientShaderPackContexts.size() <= budget) {
			return;
		}

		Set<PipelineManager> destroyedPipelineManagers = new HashSet<>();
		Set<FileSystem> closedFileSystems = new HashSet<>();
		transientShaderPackContexts.entrySet().removeIf(entry -> {
			if (transientShaderPackContexts.size() <= budget) {
				return false;
			}
			if (entry.getKey().equals(activeTransientShaderPackContextKey)) {
				return false;
			}
			destroyInactiveShaderRuntimeContext(entry.getValue(), destroyedPipelineManagers, closedFileSystems, true);
			return true;
		});
	}

	private static void clearInactiveTransientShaderPackContexts() {
		Set<PipelineManager> destroyedPipelineManagers = new HashSet<>();
		Set<FileSystem> closedFileSystems = new HashSet<>();

		transientShaderPackContexts.values().forEach(context -> destroyInactiveShaderRuntimeContext(context, destroyedPipelineManagers, closedFileSystems, false));
		destroyInactiveShaderRuntimeContext(configuredShaderPackContext, destroyedPipelineManagers, closedFileSystems, false);
		transientShaderPackContexts.clear();
		configuredShaderPackContext = null;
		activeTransientShaderPackContextKey = null;
	}

	private static void destroyInactiveShaderRuntimeContext(ShaderRuntimeContext context, Set<PipelineManager> destroyedPipelineManagers, Set<FileSystem> closedFileSystems,
														   boolean keepSharedFileSystems) {
		if (context == null) {
			return;
		}
		if (context.pipelineManager() != null && context.pipelineManager() != pipelineManager && destroyedPipelineManagers.add(context.pipelineManager())) {
			context.pipelineManager().destroyPipeline();
		}
		if (context.zipFileSystem() != null
			&& context.zipFileSystem() != zipFileSystem
			&& (!keepSharedFileSystems || !isZipFileSystemReferencedByOtherRuntimeContext(context, context.zipFileSystem()))
			&& closedFileSystems.add(context.zipFileSystem())) {
			try {
				context.zipFileSystem().close();
			} catch (IOException e) {
				logger.warn("Failed to close cached shaderpack zip file system", e);
			}
		}
	}

	private static void destroyCurrentTransientAttempt() {
		if (zipFileSystem != null && isZipFileSystemReferencedByRuntimeContext(zipFileSystem)) {
			zipFileSystem = null;
		}
		destroyEverything();
	}

	private static boolean isZipFileSystemReferencedByRuntimeContext(FileSystem fileSystem) {
		return isZipFileSystemReferencedByOtherRuntimeContext(null, fileSystem);
	}

	private static boolean isZipFileSystemReferencedByOtherRuntimeContext(ShaderRuntimeContext owner, FileSystem fileSystem) {
		if (fileSystem == null) {
			return false;
		}
		if (configuredShaderPackContext != null && configuredShaderPackContext != owner && configuredShaderPackContext.zipFileSystem() == fileSystem) {
			return true;
		}
		for (ShaderRuntimeContext context : transientShaderPackContexts.values()) {
			if (context != owner && context.zipFileSystem() == fileSystem) {
				return true;
			}
		}
		return false;
	}

	private static void logAmbienceContextTiming(String key, String message, String cacheKey, long startNanos) {
		if (!WynncraftDebugLog.shouldLog(key)) {
			return;
		}
		long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
		if (cacheKey == null) {
			WynncraftDebugLog.info(key, message + " in {} ms", elapsedMillis);
		} else {
			WynncraftDebugLog.info(key, message + " in {} ms", cacheKey, elapsedMillis);
		}
	}

	/**
	 * Destroys and deallocates all created OpenGL resources. Useful as part of a reload.
	 */
	private static void destroyEverything() {
		currentPack = null;

		// Clear blend override state before destroying the pipeline to prevent
		// blendUnknown from leaking into vanilla rendering after shader pack toggle.
		BlendModeStorage.restoreBlend();

		getPipelineManager().destroyPipeline();

		// Close the zip filesystem that the shaderpack was loaded from
		//
		// This prevents a FileSystemAlreadyExistsException when reloading shaderpacks.
		if (zipFileSystem != null) {
			try {
				zipFileSystem.close();
			} catch (NoSuchFileException e) {
				logger.warn("Failed to close the shaderpack zip when reloading because it was deleted, proceeding anyways.");
			} catch (IOException e) {
				logger.error("Failed to close zip file system?", e);
			} finally {
				zipFileSystem = null;
			}
		}
	}

	public static NamespacedId getCurrentDimension() {
		ClientLevel level = Minecraft.getInstance().level;

		if (level != null) {
			NamespacedId dimensionId = new NamespacedId(level.dimension().identifier().getNamespace(), level.dimension().identifier().getPath());

			ShaderPack pack = getCurrentPack().orElse(null);

			// If there is an exact match in dimension.properties, don't override using dimension type effects
			if (pack != null && pack.getDimensionMap().containsKey(dimensionId)) {
				return dimensionId;
			}

			// Check if the dimension type of the current level has a custom skybox set (end sky or overworld).
			// This is OVERWORLD by default, but can also be END or NONE.
			// The appropriate shader for the dimension should be used by default in order to prevent buggy results.
			// More information at https://minecraft.wiki/w/Dimension_type
			// https://github.com/IrisShaders/Iris/issues/2200
			DimensionType.Skybox skybox = level.dimensionType().skybox();

			if (skybox == DimensionType.Skybox.END) {
				return DimensionId.END;
			}

			if (skybox == DimensionType.Skybox.OVERWORLD) {
				return DimensionId.OVERWORLD;
			}

			return dimensionId;
		} else {
			// This prevents us from reloading the shaderpack unless we need to. Otherwise, if the player is in the
			// nether and quits the game, we might end up reloading the shaders on exit and on entry to the level
			// because the code thinks that the dimension changed.
			return lastDimension;
		}
	}

	private static WorldRenderingPipeline createPipeline(NamespacedId dimensionId) {
		if (currentPack == null) {
			// Completely disables shader-based rendering
			return new VanillaRenderingPipeline();
		}

		ProgramSet programs = currentPack.getProgramSet(dimensionId);

		// We use DeferredWorldRenderingPipeline on 1.16, and NewWorldRendering pipeline on 1.17 when rendering shaders.
		try {
			return new IrisRenderingPipeline(programs);
		} catch (Exception e) {
			handleException(e);

			ShaderStorageBufferHolder.forceDeleteBuffers();
			logger.error("Failed to create shader rendering pipeline, disabling shaders!", e);
			// TODO: This should be reverted if a dimension change causes shaders to compile again
			fallback = true;

			return new VanillaRenderingPipeline();
		}
	}

	@NotNull
	public static PipelineManager getPipelineManager() {
		if (pipelineManager == null) {
			pipelineManager = new PipelineManager(Iris::createPipeline);
		}

		return pipelineManager;
	}

	public static Optional<Exception> getStoredError() {
		Optional<Exception> stored = Iris.storedError;
		storedError = Optional.empty();
		return stored;
	}

	@NotNull
	public static Optional<ShaderPack> getCurrentPack() {
		return Optional.ofNullable(currentPack);
	}

	public static String getCurrentPackName() {
		return currentPackName;
	}

	public static IrisConfig getIrisConfig() {
		return irisConfig;
	}

	public static UpdateChecker getUpdateChecker() {
		return updateChecker;
	}

	public static boolean isFallback() {
		return fallback;
	}

	public static String getVersion() {
		if (IRIS_VERSION == null) {
			return "Version info unknown!";
		}

		return IRIS_VERSION;
	}

	public static String getFormattedVersion() {
		ChatFormatting color;
		String version = getVersion();

		if (IrisPlatformHelpers.getInstance().isDevelopmentEnvironment()) {
			color = ChatFormatting.GOLD;
			version = version + " (Development Environment)";
		} else if (version.endsWith("-dirty") || version.contains("unknown") || version.endsWith("-nogit")) {
			color = ChatFormatting.RED;
		} else if (version.contains("+rev.")) {
			color = ChatFormatting.LIGHT_PURPLE;
		} else {
			color = ChatFormatting.GREEN;
		}

		return color + version;
	}

	/**
	 * Gets the current release target. Since 1.19.3, Mojang no longer stores this information, so we must manually provide it for snapshots.
	 *
	 * @return Release target
	 */
	public static String getReleaseTarget() {
		// If this is a snapshot, you must change backupVersionNumber!
		SharedConstants.tryDetectVersion();
		return SharedConstants.getCurrentVersion().stable() ? SharedConstants.getCurrentVersion().name() : backupVersionNumber;
	}

	public static String getBackupVersionNumber() {
		return backupVersionNumber;
	}

	public static Path getShaderpacksDirectory() {
		if (shaderpacksDirectory == null) {
			shaderpacksDirectory = IrisPlatformHelpers.getInstance().getGameDir().resolve("shaderpacks");
		}

		return shaderpacksDirectory;
	}

	public static ShaderpackDirectoryManager getShaderpacksDirectoryManager() {
		if (shaderpacksDirectoryManager == null) {
			shaderpacksDirectoryManager = new ShaderpackDirectoryManager(getShaderpacksDirectory());
		}

		return shaderpacksDirectoryManager;
	}

	public static boolean loadedIncompatiblePack() {
		return DHCompat.lastPackIncompatible();
	}

	public static boolean isPackInUseQuick() {
		return getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline;
	}

	public static void loadShaderpackWhenPossible() {
		loadShaderPackWhenPossible = true;
	}

	public static String getVersionSimple() {
		return getVersion().split("\\+")[0];
	}

	private record ShaderRuntimeContext(String packName, ShaderPack pack, PipelineManager pipelineManager,
										FileSystem zipFileSystem, boolean fallback) {
	}

    /**
	 * Called very early on in Minecraft initialization. At this point we *cannot* safely access OpenGL, but we can do
	 * some very basic setup, config loading, and environment checks.
	 *
	 * <p>This is roughly equivalent to Fabric Loader's ClientModInitializer#onInitializeClient entrypoint, except
	 * it's entirely cross platform & we get to decide its exact semantics.</p>
	 *
	 * <p>This is called right before options are loaded, so we can add key bindings here.</p>
	 */
	public void onEarlyInitialize() {
		IRIS_VERSION = IrisPlatformHelpers.getInstance().getVersion();

		updateChecker = new UpdateChecker(IRIS_VERSION);

		reloadKeybind = IrisPlatformHelpers.getInstance().registerKeyBinding(new KeyMapping("iris.keybind.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, irisKeybindCategory));
		toggleShadersKeybind = IrisPlatformHelpers.getInstance().registerKeyBinding(new KeyMapping("iris.keybind.toggleShaders", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, irisKeybindCategory));
		shaderpackScreenKeybind = IrisPlatformHelpers.getInstance().registerKeyBinding(new KeyMapping("iris.keybind.shaderPackSelection", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, irisKeybindCategory));
		wireframeKeybind = IrisPlatformHelpers.getInstance().registerKeyBinding(new KeyMapping("iris.keybind.wireframe", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), irisKeybindCategory));

		DHCompat.run();

		try {
			if (!Files.exists(getShaderpacksDirectory())) {
				Files.createDirectories(getShaderpacksDirectory());
			}
		} catch (IOException e) {
			logger.warn("Failed to create the shaderpacks directory!");
			logger.warn("", e);
		}

		irisConfig = new IrisConfig(IrisPlatformHelpers.getInstance().getConfigDir().resolve("iris.properties"), IrisPlatformHelpers.getInstance().getConfigDir().resolve("iris-excluded.json"));

		try {
			irisConfig.initialize();
		} catch (IOException e) {
			logger.error("Failed to initialize Iris configuration, default values will be used instead");
			logger.error("", e);
		}

		updateChecker.checkForUpdates(irisConfig);

		initialized = true;
	}
}
