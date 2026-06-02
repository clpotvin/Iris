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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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

	public static LoadedShaderPackOptions loadShaderPackForOptionEditing(String name, Map<String, String> optionOverrides) throws IOException {
		if (name == null || name.isBlank()) {
			throw new IOException("No shader pack selected");
		}

		Path shaderPackRoot;
		try {
			shaderPackRoot = getShaderpacksDirectory().resolve(name);
		} catch (InvalidPathException e) {
			throw new IOException("Shader pack path contains invalid characters: " + name, e);
		}

		if (!Files.exists(shaderPackRoot)) {
			throw new IOException("Shader pack does not exist: " + name);
		}

		if (!Files.isDirectory(shaderPackRoot) && shaderPackRoot.toString().endsWith(".zip")) {
			ZipShaderpackRoot zipRoot = openZipShaderpackRoot(shaderPackRoot, false);
			if (zipRoot.shaderPath() == null) {
				zipRoot.closeIfOwned();
				throw new IOException("Shader pack lacks a shaders directory: " + name);
			}
			try {
				ShaderPack pack = new ShaderPack(zipRoot.shaderPath(), optionOverrides == null ? Map.of() : optionOverrides,
					StandardMacros.createStandardEnvironmentDefines(), true);
				return new LoadedShaderPackOptions(pack, zipRoot.fileSystem(), zipRoot.closeWhenDone());
			} catch (IOException | RuntimeException e) {
				zipRoot.closeIfOwned();
				throw e;
			}
		}

		Path shaderPackPath = shaderPackRoot.resolve("shaders");
		if (!Files.exists(shaderPackPath)) {
			throw new IOException("Shader pack lacks a shaders directory: " + name);
		}

		ShaderPack pack = new ShaderPack(shaderPackPath, optionOverrides == null ? Map.of() : optionOverrides,
			StandardMacros.createStandardEnvironmentDefines(), false);
		return new LoadedShaderPackOptions(pack, null, false);
	}

	public static boolean applyCachedTransientShaderPack(String cacheKey, String name, Map<String, String> optionOverrides) throws IOException {
		return applyCachedTransientShaderPack(cacheKey, name, optionOverrides, false);
	}

	public static boolean applyCachedTransientShaderPackForRuntimeSwitch(String cacheKey, String name, Map<String, String> optionOverrides) throws IOException {
		return applyCachedTransientShaderPack(cacheKey, name, optionOverrides, true);
	}

	private static boolean applyCachedTransientShaderPack(String cacheKey, String name, Map<String, String> optionOverrides, boolean refreshLevelRendererOnSwitch) throws IOException {
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
			activateShaderRuntimeContext(cached, cacheKey, "cache-hit", refreshLevelRendererOnSwitch);
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
				switchTiming.setSameShaderPackAsPrevious(Objects.equals(previous.packName(), currentPackName));
				prepareTransientPipelineContext(switchTiming, refreshLevelRendererOnSwitch);
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
		restoreConfiguredShaderPack(false);
	}

	public static void restoreConfiguredShaderPackForRuntimeSwitch() throws IOException {
		restoreConfiguredShaderPack(true);
	}

	private static void restoreConfiguredShaderPack(boolean refreshLevelRendererOnSwitch) throws IOException {
		if (configuredShaderPackContext != null) {
			long startNanos = System.nanoTime();
			activateShaderRuntimeContext(configuredShaderPackContext, null, "restore-cached", refreshLevelRendererOnSwitch);
			logAmbienceContextTiming("ambience-profile-restore-cached", "Restored configured shader profile from ambience cache", null, startNanos);
			return;
		}

		suppressAmbienceInvalidation = true;
		try {
			reload();
			if (refreshLevelRendererOnSwitch) {
				refreshLevelRendererAfterAmbienceSwitch(null, "restore-configured-reload", "configured");
			}
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

	public static String getActiveTransientShaderPackContextKey() {
		return activeTransientShaderPackContextKey;
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
		ZipShaderpackRoot root = openZipShaderpackRoot(shaderpackPath, true);
		return Optional.ofNullable(root.shaderPath());
	}

	private static ZipShaderpackRoot openZipShaderpackRoot(Path shaderpackPath, boolean assignCurrentZipFileSystem) throws IOException {
		FileSystem zipSystem;
		boolean closeWhenDone = false;
		try {
			zipSystem = FileSystems.newFileSystem(shaderpackPath, Iris.class.getClassLoader());
			closeWhenDone = !assignCurrentZipFileSystem;
		} catch (FileSystemAlreadyExistsException e) {
			zipSystem = FileSystems.getFileSystem(URI.create("jar:" + shaderpackPath.toUri()));
		}
		if (assignCurrentZipFileSystem) {
			zipFileSystem = zipSystem;
		}

		// Should only be one root directory for a zip shaderpack
		Path root = zipSystem.getRootDirectories().iterator().next();

		Path potentialShaderDir = zipSystem.getPath("shaders");

		// If the shaders dir was immediately found return it
		// Otherwise, manually search through each directory path until it ends with "shaders"
		if (Files.exists(potentialShaderDir)) {
			return new ZipShaderpackRoot(potentialShaderDir, zipSystem, closeWhenDone);
		}

		// Sometimes shaderpacks have their shaders directory within another folder in the shaderpack
		// For example Sildurs-Vibrant-Shaders.zip/shaders
		// While other packs have Trippy-Shaderpack-master.zip/Trippy-Shaderpack-master/shaders
		// This makes it hard to determine what is the actual shaders dir
		try (Stream<Path> stream = Files.walk(root)) {
			Path shaderPath = stream
				.filter(Files::isDirectory)
				.filter(path -> path.endsWith("shaders"))
				.findFirst()
				.orElse(null);
			return new ZipShaderpackRoot(shaderPath, zipSystem, closeWhenDone);
		}
	}

	public record LoadedShaderPackOptions(ShaderPack pack, FileSystem zipFileSystem, boolean closeZipFileSystem) implements AutoCloseable {
		@Override
		public void close() throws IOException {
			if (closeZipFileSystem && zipFileSystem != null && zipFileSystem.isOpen()
				&& zipFileSystem != Iris.zipFileSystem
				&& !Iris.isZipFileSystemReferencedByRuntimeContext(zipFileSystem)) {
				zipFileSystem.close();
			}
		}
	}

	private record ZipShaderpackRoot(Path shaderPath, FileSystem fileSystem, boolean closeWhenDone) {
		private void closeIfOwned() {
			if (closeWhenDone && fileSystem != null && fileSystem.isOpen()
				&& fileSystem != Iris.zipFileSystem
				&& !Iris.isZipFileSystemReferencedByRuntimeContext(fileSystem)) {
				try {
					fileSystem.close();
				} catch (IOException ignored) {
				}
			}
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
		prepareTransientPipelineContext(timing, false);
	}

	private static void prepareTransientPipelineContext(AmbienceSwitchTiming timing, boolean refreshLevelRendererOnSwitch) {
		NamespacedId dimension = Iris.getCurrentDimension();
		if (dimension == null) {
			dimension = DimensionId.OVERWORLD;
		}

		long prepareStartNanos = System.nanoTime();
		Iris.getPipelineManager().preparePipeline(dimension);
		if (timing != null) {
			timing.addPreparePipelineNanos(System.nanoTime() - prepareStartNanos);
		}

		reapplyCurrentPipelineSettings(timing, refreshLevelRendererOnSwitch);
	}

	private static ShaderRuntimeContext snapshotShaderRuntimeContext() {
		return new ShaderRuntimeContext(currentPackName, currentPack, pipelineManager, zipFileSystem, fallback);
	}

	private static void activateShaderRuntimeContext(ShaderRuntimeContext context, String transientKey) {
		activateShaderRuntimeContext(context, transientKey, transientKey == null ? "restore" : "cache-hit");
	}

	private static void activateShaderRuntimeContext(ShaderRuntimeContext context, String transientKey, String action) {
		activateShaderRuntimeContext(context, transientKey, action, false);
	}

	private static void activateShaderRuntimeContext(ShaderRuntimeContext context, String transientKey, String action, boolean refreshLevelRendererOnSwitch) {
		String previousPackName = currentPackName;
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
		timing.setSameShaderPackAsPrevious(Objects.equals(previousPackName, currentPackName));
		try {
			prepareTransientPipelineContext(timing, refreshLevelRendererOnSwitch);
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
		reapplyCurrentPipelineSettings(null, false);
	}

	private static void reapplyCurrentPipelineSettings(AmbienceSwitchTiming timing, boolean refreshLevelRendererOnSwitch) {
		long reapplyStartNanos = System.nanoTime();
		if (pipelineManager != null && pipelineManager.getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
			pipeline.applyWorldRenderingSettings();
		}
		boolean reloadRequired = WorldRenderingSettings.INSTANCE.isReloadRequired();
		if (reloadRequired) {
			String reloadReasons = WorldRenderingSettings.INSTANCE.getReloadReasonSummary();
			if (timing != null && WynncraftDebugLog.shouldLog("ambience-world-settings-reload")) {
				WynncraftDebugLog.info("ambience-world-settings-reload",
					"Ambience world rendering reload required: action={} profile={} reasons={}",
					timing.action(), timing.profileKey(), reloadReasons);
			}
			forceLevelRendererReload(timing, timing == null ? "settings" : timing.action(), timing == null ? "unknown" : timing.profileKey(), reloadReasons);
			WorldRenderingSettings.INSTANCE.clearReloadRequired();
		} else if (refreshLevelRendererOnSwitch) {
			refreshLevelRendererAfterAmbienceSwitch(timing, timing == null ? "settings" : timing.action(), timing == null ? "unknown" : timing.profileKey());
		}
		if (timing != null) {
			timing.addReapplySettingsNanos(System.nanoTime() - reapplyStartNanos);
		}
	}

	private static void refreshLevelRendererAfterAmbienceSwitch(AmbienceSwitchTiming timing, String action, String profileKey) {
		long refreshStartNanos = System.nanoTime();
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.levelRenderer != null) {
			minecraft.levelRenderer.getCloudRenderer().markForRebuild();
		}
		VoxyRefreshResult voxyRefresh = refreshVoxyRendererForActivePipeline(profileKey);
		long refreshNanos = System.nanoTime() - refreshStartNanos;
		if (timing != null) {
			timing.addRenderStateRefreshNanos(refreshNanos);
		}
		if (WynncraftDebugLog.shouldLog("ambience-world-settings-reload")) {
			WynncraftDebugLog.info("ambience-world-settings-reload",
				"Ambience render state refresh: action={} profile={} duration={}us voxyRefresh={}",
				action, profileKey, refreshNanos / 1_000L, voxyRefresh);
		}
	}

	private enum VoxyRefreshResult {
		NONE("none"),
		PIPELINE_PRESERVED_CHUNKS("pipeline-preserved-chunks"),
		FAILED("failed");

		private final String label;

		VoxyRefreshResult(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static VoxyRefreshResult refreshVoxyRendererForActivePipeline(String profileKey) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.levelRenderer == null) {
			return VoxyRefreshResult.NONE;
		}

		Object levelRenderer = minecraft.levelRenderer;
		Method getRenderSystem = findNoArgMethod(levelRenderer.getClass(), "voxy$getRenderSystem");
		if (getRenderSystem == null) {
			return VoxyRefreshResult.NONE;
		}

		try {
			Object renderSystem = getRenderSystem.invoke(levelRenderer);
			if (renderSystem == null) {
				return VoxyRefreshResult.NONE;
			}

			VoxyPipelineRefreshStats stats = refreshVoxyPipelinePreservingChunkBounds(renderSystem);
			if (WynncraftDebugLog.shouldLog("ambience-voxy-refresh-pipeline")) {
				WynncraftDebugLog.info("ambience-voxy-refresh-pipeline",
					"Voxy renderer rebound to active ambience pipeline: profile={} oldPipeline={} newPipeline={} oldViewportSelector={} chunkBoundPipelineRebound={}",
					profileKey, stats.oldPipelineClass(), stats.newPipelineClass(), stats.oldViewportSelectorClass(), stats.chunkBoundPipelineRebound());
			}
			return VoxyRefreshResult.PIPELINE_PRESERVED_CHUNKS;
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (WynncraftDebugLog.shouldLog("ambience-voxy-refresh-failed")) {
				WynncraftDebugLog.info("ambience-voxy-refresh-failed",
					"Failed to refresh Voxy renderer after ambience switch: {}", e.toString());
			}
			return VoxyRefreshResult.FAILED;
		}
	}

	private static VoxyPipelineRefreshStats refreshVoxyPipelinePreservingChunkBounds(Object renderSystem) throws ReflectiveOperationException {
		Class<?> renderSystemClass = renderSystem.getClass();
		Field pipelineField = findField(renderSystemClass, "pipeline");
		Field viewportSelectorField = findField(renderSystemClass, "viewportSelector");
		Field propertiesField = findField(renderSystemClass, "properties");
		Field nodeManagerField = findField(renderSystemClass, "nodeManager");
		Field nodeCleanerField = findField(renderSystemClass, "nodeCleaner");
		Field traversalField = findField(renderSystemClass, "traversal");
		Field modelServiceField = findField(renderSystemClass, "modelService");
		Field geometryDataField = findField(renderSystemClass, "geometryData");
		Field chunkBoundRendererField = findField(renderSystemClass, "chunkBoundRenderer");

		Object oldPipeline = pipelineField.get(renderSystem);
		Object oldViewportSelector = viewportSelectorField.get(renderSystem);
		Object properties = propertiesField.get(renderSystem);
		Object nodeManager = nodeManagerField.get(renderSystem);
		Object nodeCleaner = nodeCleanerField.get(renderSystem);
		Object traversal = traversalField.get(renderSystem);
		Object modelService = modelServiceField.get(renderSystem);
		Object geometryData = geometryDataField.get(renderSystem);
		Object chunkBoundRenderer = chunkBoundRendererField.get(renderSystem);

		Method frexStillHasWork = findNoArgMethod(renderSystemClass, "frexStillHasWork");
		if (frexStillHasWork == null) {
			throw new NoSuchMethodException(renderSystemClass.getName() + ".frexStillHasWork()");
		}
		BooleanSupplier frexSupplier = () -> {
			try {
				return Boolean.TRUE.equals(frexStillHasWork.invoke(renderSystem));
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Failed to query Voxy Frex state", e);
			}
		};

		Object newPipeline = null;
		Object newViewportSelector = null;
		Object pipelineData = null;
		Object oldTraversalShader = null;
		Object oldTraversalPipeline = null;
		Field traversalShaderField = null;
		Field traversalPipelineField = null;
		boolean swapped = false;
		try {
			pipelineData = getActiveVoxyPipelineData(oldPipeline);
			newPipeline = createIrisVoxyPipeline(properties, pipelineData, nodeManager, nodeCleaner, traversal, frexSupplier, oldPipeline);

			Method setupExtraModelBakeryData = findMethod(newPipeline.getClass(), "setupExtraModelBakeryData", 1);
			setupExtraModelBakeryData.invoke(newPipeline, modelService);

			traversalShaderField = findField(traversal.getClass(), "traversal");
			oldTraversalShader = traversalShaderField.get(traversal);
			traversalPipelineField = findField(traversal.getClass(), "pipeline");
			oldTraversalPipeline = traversalPipelineField.get(traversal);
			Method lateStageCompile = findMethod(traversal.getClass(), "lateStageCompile", 1);
			lateStageCompile.invoke(traversal, newPipeline);
			traversalPipelineField.set(traversal, newPipeline);

			Method getRenderBackendFactory = findNoArgMethod(renderSystemClass, "getRenderBackendFactory");
			if (getRenderBackendFactory == null) {
				throw new NoSuchMethodException(renderSystemClass.getName() + ".getRenderBackendFactory()");
			}
			Object backendFactory = getRenderBackendFactory.invoke(null);
			Method getStore = findNoArgMethod(modelService.getClass(), "getStore");
			if (getStore == null) {
				throw new NoSuchMethodException(modelService.getClass().getName() + ".getStore()");
			}
			Object modelStore = getStore.invoke(modelService);
			Method createSectionRenderer = findMethod(backendFactory.getClass(), "create", 3);
			Object sectionRenderer = createSectionRenderer.invoke(backendFactory, newPipeline, modelStore, geometryData);

			Method setSectionRenderer = findMethod(newPipeline.getClass(), "setSectionRenderer", 1);
			setSectionRenderer.invoke(newPipeline, sectionRenderer);

			Method createViewport = findNoArgMethod(sectionRenderer.getClass(), "createViewport");
			if (createViewport == null) {
				throw new NoSuchMethodException(sectionRenderer.getClass().getName() + ".createViewport()");
			}
			Class<?> viewportSelectorClass = viewportSelectorField.getType();
			Constructor<?> viewportSelectorConstructor = viewportSelectorClass.getConstructor(Supplier.class);
			Supplier<Object> viewportSupplier = () -> {
				try {
					return createViewport.invoke(sectionRenderer);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("Failed to create Voxy viewport", e);
				}
			};
			newViewportSelector = viewportSelectorConstructor.newInstance(viewportSupplier);

			boolean chunkBoundPipelineRebound = rebindVoxyChunkBoundPipeline(chunkBoundRenderer, newPipeline);
			pipelineField.set(renderSystem, newPipeline);
			viewportSelectorField.set(renderSystem, newViewportSelector);
			swapped = true;

			freeVoxyObject(oldViewportSelector);
			freeOldVoxyPipeline(oldPipeline, newPipeline, pipelineData);
			freeVoxyObject(oldTraversalShader);

			return new VoxyPipelineRefreshStats(
				oldPipeline == null ? "null" : oldPipeline.getClass().getSimpleName(),
				newPipeline.getClass().getSimpleName(),
				oldViewportSelector == null ? "null" : oldViewportSelector.getClass().getSimpleName(),
				chunkBoundPipelineRebound);
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (!swapped) {
				restoreVoxyRenderSystemFields(renderSystem, pipelineField, oldPipeline, viewportSelectorField, oldViewportSelector,
					chunkBoundRenderer, oldPipeline);
				restoreVoxyTraversal(traversal, traversalShaderField, oldTraversalShader, traversalPipelineField, oldTraversalPipeline);
				freeVoxyObject(newViewportSelector);
				freeVoxyObject(newPipeline);
				restoreVoxyPipelineData(pipelineData, oldPipeline);
			}
			throw e;
		}
	}

	private static Object getActiveVoxyPipelineData(Object oldPipeline) throws ReflectiveOperationException {
		if (pipelineManager == null || pipelineManager.getPipelineNullable() == null) {
			throw new IllegalStateException("No active Iris pipeline for Voxy refresh");
		}
		Object irisPipeline = pipelineManager.getPipelineNullable();
		Method getPipelineData = findNoArgMethod(irisPipeline.getClass(), "voxy$getPipelineData");
		if (getPipelineData == null) {
			throw new NoSuchMethodException(irisPipeline.getClass().getName() + ".voxy$getPipelineData()");
		}
		Object pipelineData = getPipelineData.invoke(irisPipeline);
		if (pipelineData == null) {
			throw new IllegalStateException("Active Iris pipeline has no Voxy pipeline data");
		}
		Field boundPipelineField = findField(pipelineData.getClass(), "thePipeline");
		Object boundPipeline = boundPipelineField.get(pipelineData);
		if (boundPipeline != null && boundPipeline != oldPipeline) {
			throw new IllegalStateException("Active Voxy pipeline data is already bound to another pipeline: " + boundPipeline.getClass().getName());
		}
		return pipelineData;
	}

	private static Object createIrisVoxyPipeline(Object properties, Object pipelineData, Object nodeManager, Object nodeCleaner,
												 Object traversal, BooleanSupplier frexSupplier, Object oldPipeline) throws ReflectiveOperationException {
		Field boundPipelineField = findField(pipelineData.getClass(), "thePipeline");
		Object previousBoundPipeline = boundPipelineField.get(pipelineData);
		boundPipelineField.set(pipelineData, null);
		try {
			Class<?> irisVoxyPipelineClass = Class.forName("me.cortex.voxy.client.core.IrisVoxyRenderPipeline");
			Constructor<?> constructor = findConstructor(irisVoxyPipelineClass, 6);
			return constructor.newInstance(properties, pipelineData, nodeManager, nodeCleaner, traversal, frexSupplier);
		} catch (ReflectiveOperationException | RuntimeException e) {
			boundPipelineField.set(pipelineData, previousBoundPipeline);
			throw e;
		}
	}

	private static void freeOldVoxyPipeline(Object oldPipeline, Object newPipeline, Object pipelineData) throws ReflectiveOperationException {
		if (oldPipeline == null) {
			return;
		}
		Field boundPipelineField = findField(pipelineData.getClass(), "thePipeline");
		Object currentBoundPipeline = boundPipelineField.get(pipelineData);
		boundPipelineField.set(pipelineData, oldPipeline);
		freeVoxyObject(oldPipeline);
		if (boundPipelineField.get(pipelineData) == null || boundPipelineField.get(pipelineData) == oldPipeline) {
			boundPipelineField.set(pipelineData, newPipeline);
		} else {
			boundPipelineField.set(pipelineData, currentBoundPipeline);
		}
	}

	private static void restoreVoxyPipelineData(Object pipelineData, Object pipeline) {
		if (pipelineData == null) {
			return;
		}
		try {
			Field boundPipelineField = findField(pipelineData.getClass(), "thePipeline");
			boundPipelineField.set(pipelineData, pipeline);
		} catch (ReflectiveOperationException | RuntimeException restoreFailure) {
			if (WynncraftDebugLog.shouldLog("ambience-voxy-restore-failed")) {
				WynncraftDebugLog.info("ambience-voxy-restore-failed",
					"Failed to restore Voxy pipeline data after ambience refresh failure: {}", restoreFailure.toString());
			}
		}
	}

	private static void restoreVoxyRenderSystemFields(Object renderSystem, Field pipelineField, Object oldPipeline,
													  Field viewportSelectorField, Object oldViewportSelector,
													  Object chunkBoundRenderer, Object oldChunkBoundPipeline) {
		try {
			if (pipelineField != null) {
				pipelineField.set(renderSystem, oldPipeline);
			}
			if (viewportSelectorField != null) {
				viewportSelectorField.set(renderSystem, oldViewportSelector);
			}
			restoreVoxyChunkBoundPipeline(chunkBoundRenderer, oldChunkBoundPipeline);
		} catch (ReflectiveOperationException | RuntimeException restoreFailure) {
			if (WynncraftDebugLog.shouldLog("ambience-voxy-restore-failed")) {
				WynncraftDebugLog.info("ambience-voxy-restore-failed",
					"Failed to restore Voxy render system after ambience refresh failure: {}", restoreFailure.toString());
			}
		}
	}

	private static void restoreVoxyTraversal(Object traversal, Field traversalShaderField, Object oldTraversalShader,
											 Field traversalPipelineField, Object oldTraversalPipeline) {
		if (traversal == null || traversalShaderField == null || traversalPipelineField == null) {
			return;
		}
		try {
			Object newTraversalShader = traversalShaderField.get(traversal);
			traversalShaderField.set(traversal, oldTraversalShader);
			traversalPipelineField.set(traversal, oldTraversalPipeline);
			if (newTraversalShader != oldTraversalShader) {
				freeVoxyObject(newTraversalShader);
			}
		} catch (ReflectiveOperationException | RuntimeException restoreFailure) {
			if (WynncraftDebugLog.shouldLog("ambience-voxy-restore-failed")) {
				WynncraftDebugLog.info("ambience-voxy-restore-failed",
					"Failed to restore Voxy traversal after ambience refresh failure: {}", restoreFailure.toString());
			}
		}
	}

	private static void restoreVoxyChunkBoundPipeline(Object chunkBoundRenderer, Object pipeline) throws ReflectiveOperationException {
		if (chunkBoundRenderer == null) {
			return;
		}
		Field pipelineField = findField(chunkBoundRenderer.getClass(), "pipeline");
		if (pipelineField.get(chunkBoundRenderer) != null || pipeline != null) {
			pipelineField.set(chunkBoundRenderer, pipeline);
		}
	}

	private static boolean rebindVoxyChunkBoundPipeline(Object chunkBoundRenderer, Object newPipeline) throws ReflectiveOperationException {
		if (chunkBoundRenderer == null || newPipeline == null) {
			return false;
		}
		Field pipelineField = findField(chunkBoundRenderer.getClass(), "pipeline");
		if (pipelineField.get(chunkBoundRenderer) == null) {
			return false;
		}
		pipelineField.set(chunkBoundRenderer, newPipeline);
		return true;
	}

	private static void freeVoxyObject(Object object) {
		if (object == null) {
			return;
		}
		Method free = findNoArgMethod(object.getClass(), "free");
		if (free == null) {
			return;
		}
		try {
			free.invoke(object);
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (WynncraftDebugLog.shouldLog("ambience-voxy-free-failed")) {
				WynncraftDebugLog.info("ambience-voxy-free-failed",
					"Failed to free old Voxy object {} after ambience refresh: {}", object.getClass().getSimpleName(), e.toString());
			}
		}
	}

	private record VoxyPipelineRefreshStats(String oldPipelineClass,
											String newPipelineClass,
											String oldViewportSelectorClass,
											boolean chunkBoundPipelineRebound) {
	}

	private static Method findNoArgMethod(Class<?> type, String name) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Method method = current.getDeclaredMethod(name);
				method.setAccessible(true);
				return method;
			} catch (NoSuchMethodException ignored) {
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			for (Method method : current.getDeclaredMethods()) {
				if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
					method.setAccessible(true);
					return method;
				}
			}
		}
		throw new NoSuchMethodException(type.getName() + "." + name + "/" + parameterCount);
	}

	private static Constructor<?> findConstructor(Class<?> type, int parameterCount) throws NoSuchMethodException {
		for (Constructor<?> constructor : type.getDeclaredConstructors()) {
			if (constructor.getParameterCount() == parameterCount) {
				constructor.setAccessible(true);
				return constructor;
			}
		}
		throw new NoSuchMethodException(type.getName() + ".<init>/" + parameterCount);
	}

	private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new NoSuchFieldException(type.getName() + "." + name);
	}

	private static void forceLevelRendererReload(AmbienceSwitchTiming timing, String action, String profileKey, String reloadReasons) {
		if (Minecraft.getInstance().levelRenderer != null) {
			long reloadStartNanos = System.nanoTime();
			Minecraft.getInstance().levelRenderer.allChanged();
			long reloadNanos = System.nanoTime() - reloadStartNanos;
			if (timing != null) {
				timing.addLevelRendererReloadNanos(reloadNanos);
			}
			if (timing == null && WynncraftDebugLog.shouldLog("ambience-world-settings-reload")) {
				WynncraftDebugLog.info("ambience-world-settings-reload",
					"Ambience world rendering reload required: action={} profile={} reasons={} duration={}us",
					action, profileKey, reloadReasons, reloadNanos / 1_000L);
			}
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
