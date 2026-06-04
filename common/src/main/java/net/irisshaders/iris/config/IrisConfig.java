package net.irisshaders.iris.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * A class dedicated to storing the config values of shaderpacks. Right now it only stores the path to the current shaderpack
 */
public class IrisConfig {
	private static final String COMMENT =
		"This file stores configuration options for Iris, such as the currently active shaderpack";
	private final Path propertiesPath;
	private final Path excludedPath;
	/**
	 * The path to the current shaderpack. Null if the internal shaderpack is being used.
	 */
	private String shaderPackName;
	/**
	 * Whether or not shaders are used for rendering. False to disable all shader-based rendering, true to enable it.
	 */
	private boolean enableShaders;
	/**
	 * Whether or not to allow core shaders to draw to the main color texture.
	 */
	private boolean allowUnknownShaders;
	/**
	 * If debug features should be enabled. Gives much more detailed OpenGL error outputs at the cost of performance.
	 */
	private boolean enableDebugOptions;
	/**
	 * What shaders should be nuked.
	 */
	private List<Identifier> shadersToSkip = new ArrayList<>();
	/**
	 * If the update notification should be disabled or not.
	 */
	private boolean disableUpdateMessage;

	public IrisConfig(Path propertiesPath, Path excluded) {
		shaderPackName = null;
		enableShaders = true;
		allowUnknownShaders = false;
		enableDebugOptions = false;
		disableUpdateMessage = false;
		this.propertiesPath = propertiesPath;
		this.excludedPath = excluded;
	}

	/**
	 * Initializes the configuration, loading it if it is present and creating a default config otherwise.
	 *
	 * @throws IOException file exceptions
	 */
	public void initialize() throws IOException {
		load();
		if (!Files.exists(propertiesPath)) {
			save();
		}
	}

	/**
	 * returns whether or not the current shaderpack is internal
	 *
	 * @return if the shaderpack is internal
	 */
	public boolean isInternal() {
		return false;
	}

	/**
	 * Returns the name of the current shaderpack
	 *
	 * @return Returns the current shaderpack name - if internal shaders are being used it returns "(internal)"
	 */
	public Optional<String> getShaderPackName() {
		return Optional.ofNullable(shaderPackName);
	}

	/**
	 * Sets the name of the current shaderpack
	 */
	public void setShaderPackName(String name) {
		if (name == null || name.equals("(internal)") || name.isEmpty()) {
			this.shaderPackName = null;
		} else {
			this.shaderPackName = name;
		}
	}

	/**
	 * Determines whether or not shaders are used for rendering.
	 *
	 * @return False to disable all shader-based rendering, true to enable shader-based rendering.
	 */
	public boolean areShadersEnabled() {
		return enableShaders;
	}

	public boolean areDebugOptionsEnabled() {
		return enableDebugOptions;
	}

	public boolean shouldDisableUpdateMessage() {
		return disableUpdateMessage;
	}

	public void setDebugEnabled(boolean enabled) {
		enableDebugOptions = enabled;
	}

	/**
	 * Sets whether shaders should be used for rendering.
	 */
	public void setShadersEnabled(boolean enabled) {
		this.enableShaders = enabled;
	}

	private static Gson GSON = new Gson();

	/**
	 * loads the config file and then populates the string, int, and boolean entries with the parsed entries
	 *
	 * @throws IOException if the file cannot be loaded
	 */

	public void load() throws IOException {
		if (Files.exists(excludedPath)) {
			JsonArray json = JsonParser.parseString(Files.readString(excludedPath)).getAsJsonObject().getAsJsonArray("excluded");
			for (int i = 0; i < json.size(); i++) {
				Identifier resource = Identifier.tryParse(json.get(i).getAsString());
				if (resource == null) {
					Iris.logger.warn("Unknown shader " + json.get(i).getAsString());
				}

				shadersToSkip.add(resource);
			}
		} else {
			JsonObject defaultV = new JsonObject();
			JsonArray array = new JsonArray();
			array.add("put:valuesHere");
			defaultV.add("excluded", array);
			Files.writeString(excludedPath, GSON.toJson(defaultV));
		}

		if (!Files.exists(propertiesPath)) {
			return;
		}

		Properties properties = new Properties();
		// NB: This uses ISO-8859-1 with unicode escapes as the encoding
		try (InputStream is = Files.newInputStream(propertiesPath)) {
			properties.load(is);
		}

		shaderPackName = properties.getProperty("shaderPack");
		enableShaders = !"false".equals(properties.getProperty("enableShaders"));
		allowUnknownShaders = "true".equals(properties.getProperty("allowUnknownShaders"));
		enableDebugOptions = "true".equals(properties.getProperty("enableDebugOptions"));
		disableUpdateMessage = "true".equals(properties.getProperty("disableUpdateMessage"));
		try {
			IrisVideoSettings.shadowDistance = Integer.parseInt(properties.getProperty("maxShadowRenderDistance", "32"));
			IrisVideoSettings.colorSpace = ColorSpace.valueOf(properties.getProperty("colorSpace", "SRGB"));
			IrisVideoSettings.glintBrightness = Integer.parseInt(properties.getProperty("glintBrightness", "110"));
			IrisVideoSettings.tintBrightness = Integer.parseInt(properties.getProperty("tintBrightness", "75"));
			IrisVideoSettings.wynncraftSceneDarkening = Integer.parseInt(properties.getProperty("wynncraftSceneDarkening", "100"));
			IrisVideoSettings.wynncraftEntityBrightness = Math.max(0, Math.min(200, Integer.parseInt(properties.getProperty("wynncraftEntityBrightness", "100"))));
			IrisVideoSettings.wynncraftEntityEmissivity = Math.max(0, Math.min(100, Integer.parseInt(properties.getProperty("wynncraftEntityEmissivity", "100"))));
			IrisVideoSettings.wynncraftNightVisionDisablesBoost = !"false".equals(properties.getProperty("wynncraftNightVisionDisablesBoost", "true"));
			IrisVideoSettings.wynncraftTextBrightnessFloor = "true".equals(properties.getProperty("wynncraftTextBrightnessFloor", "false"));
			IrisVideoSettings.wynncraftTextBrightnessFloorLevel = Math.max(0, Math.min(15, Integer.parseInt(properties.getProperty("wynncraftTextBrightnessFloorLevel", "10"))));
			IrisVideoSettings.wynncraftMistWoodsFog = !"false".equals(properties.getProperty("wynncraftMistWoodsFog", "true"));
			IrisVideoSettings.wynncraftMistWoodsFogDensity = Math.max(0, Math.min(100, Integer.parseInt(properties.getProperty("wynncraftMistWoodsFogDensity", "100"))));
			IrisVideoSettings.wynncraftMistWoodsFogMinDistance = Math.max(0, Math.min(300, Integer.parseInt(properties.getProperty("wynncraftMistWoodsFogMinDistance", "0"))));
			IrisVideoSettings.wynncraftMistWoodsFogSunTintReduction = "true".equals(properties.getProperty("wynncraftMistWoodsFogSunTintReduction", "false"));
			IrisVideoSettings.wynncraftMistWoodsFogSunTintAmount = Math.max(0, Math.min(100, Integer.parseInt(properties.getProperty("wynncraftMistWoodsFogSunTintAmount", "50"))));
			IrisVideoSettings.wynncraftMountArmorOverlay = "true".equals(properties.getProperty("wynncraftMountArmorOverlay", "false"));
			IrisVideoSettings.wynncraftAmbienceEnabled = "true".equals(properties.getProperty("wynncraftAmbienceEnabled", "false"));
			IrisVideoSettings.wynncraftAmbienceAutoWarmCache = !"false".equals(properties.getProperty("wynncraftAmbienceAutoWarmCache", "true"));
			IrisVideoSettings.wynncraftSelectedAmbiencePack = properties.getProperty("wynncraftSelectedAmbiencePack", "");
		} catch (IllegalArgumentException e) {
			Iris.logger.error("Shadow distance setting reset; value is invalid.");
			IrisVideoSettings.shadowDistance = 32;
			IrisVideoSettings.colorSpace = ColorSpace.SRGB;
			IrisVideoSettings.glintBrightness = 110;
			IrisVideoSettings.tintBrightness = 75;
			IrisVideoSettings.wynncraftSceneDarkening = 100;
			IrisVideoSettings.wynncraftEntityBrightness = 100;
			IrisVideoSettings.wynncraftEntityEmissivity = 100;
			IrisVideoSettings.wynncraftNightVisionDisablesBoost = true;
			IrisVideoSettings.wynncraftTextBrightnessFloor = false;
			IrisVideoSettings.wynncraftTextBrightnessFloorLevel = 10;
			IrisVideoSettings.wynncraftMistWoodsFog = true;
			IrisVideoSettings.wynncraftMistWoodsFogDensity = 100;
			IrisVideoSettings.wynncraftMistWoodsFogMinDistance = 0;
			IrisVideoSettings.wynncraftMistWoodsFogSunTintReduction = false;
			IrisVideoSettings.wynncraftMistWoodsFogSunTintAmount = 50;
			IrisVideoSettings.wynncraftMountArmorOverlay = false;
			IrisVideoSettings.wynncraftAmbienceEnabled = false;
			IrisVideoSettings.wynncraftAmbienceAutoWarmCache = true;
			IrisVideoSettings.wynncraftSelectedAmbiencePack = "";
			save();
		}

		if (shaderPackName != null) {
			if (shaderPackName.equals("(internal)") || shaderPackName.isEmpty()) {
				shaderPackName = null;
			}
		}
	}

	/**
	 * Serializes the config into a file. Should be called whenever any config values are modified.
	 *
	 * @throws IOException file exceptions
	 */
	public void save() throws IOException {
		Properties properties = new Properties();
		properties.setProperty("shaderPack", getShaderPackName().orElse(""));
		properties.setProperty("enableShaders", enableShaders ? "true" : "false");
		properties.setProperty("allowUnknownShaders", allowUnknownShaders ? "true" : "false");
		properties.setProperty("enableDebugOptions", enableDebugOptions ? "true" : "false");
		properties.setProperty("disableUpdateMessage", disableUpdateMessage ? "true" : "false");
		properties.setProperty("maxShadowRenderDistance", String.valueOf(IrisVideoSettings.shadowDistance));
		properties.setProperty("colorSpace", IrisVideoSettings.colorSpace.name());
		properties.setProperty("glintBrightness", String.valueOf(IrisVideoSettings.glintBrightness));
		properties.setProperty("tintBrightness", String.valueOf(IrisVideoSettings.tintBrightness));

		properties.setProperty("wynncraftSceneDarkening", String.valueOf(IrisVideoSettings.wynncraftSceneDarkening));
		properties.setProperty("wynncraftEntityBrightness", String.valueOf(IrisVideoSettings.wynncraftEntityBrightness));
		properties.setProperty("wynncraftEntityEmissivity", String.valueOf(IrisVideoSettings.wynncraftEntityEmissivity));
		properties.setProperty("wynncraftNightVisionDisablesBoost", String.valueOf(IrisVideoSettings.wynncraftNightVisionDisablesBoost));
		properties.setProperty("wynncraftTextBrightnessFloor", String.valueOf(IrisVideoSettings.wynncraftTextBrightnessFloor));
		properties.setProperty("wynncraftTextBrightnessFloorLevel", String.valueOf(IrisVideoSettings.wynncraftTextBrightnessFloorLevel));
		properties.setProperty("wynncraftMistWoodsFog", String.valueOf(IrisVideoSettings.wynncraftMistWoodsFog));
		properties.setProperty("wynncraftMistWoodsFogDensity", String.valueOf(IrisVideoSettings.wynncraftMistWoodsFogDensity));
		properties.setProperty("wynncraftMistWoodsFogMinDistance", String.valueOf(IrisVideoSettings.wynncraftMistWoodsFogMinDistance));
		properties.setProperty("wynncraftMistWoodsFogSunTintReduction", String.valueOf(IrisVideoSettings.wynncraftMistWoodsFogSunTintReduction));
		properties.setProperty("wynncraftMistWoodsFogSunTintAmount", String.valueOf(IrisVideoSettings.wynncraftMistWoodsFogSunTintAmount));
		properties.setProperty("wynncraftMountArmorOverlay", String.valueOf(IrisVideoSettings.wynncraftMountArmorOverlay));
		properties.setProperty("wynncraftAmbienceEnabled", String.valueOf(IrisVideoSettings.wynncraftAmbienceEnabled));
		properties.setProperty("wynncraftAmbienceAutoWarmCache", String.valueOf(IrisVideoSettings.wynncraftAmbienceAutoWarmCache));
		properties.setProperty("wynncraftSelectedAmbiencePack", IrisVideoSettings.wynncraftSelectedAmbiencePack == null ? "" : IrisVideoSettings.wynncraftSelectedAmbiencePack);
		// NB: This uses ISO-8859-1 with unicode escapes as the encoding
		try (OutputStream os = Files.newOutputStream(propertiesPath)) {
			properties.store(os, COMMENT);
		}
	}

	public boolean shouldAllowUnknownShaders() {
		return allowUnknownShaders;
	}

	public boolean shouldSkip(Identifier value) {
		return shadersToSkip.contains(value); // TODO
	}

	public void setUnknown(boolean b) throws IOException {
		this.allowUnknownShaders = b;
		save();
	}
}
