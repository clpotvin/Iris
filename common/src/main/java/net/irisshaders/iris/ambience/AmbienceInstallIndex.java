package net.irisshaders.iris.ambience;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.platform.IrisPlatformHelpers;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class AmbienceInstallIndex {
	private static final Gson GSON = new Gson();
	private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
	private static Map<String, String> dependencyToShaderPack;

	private AmbienceInstallIndex() {
	}

	static synchronized Optional<String> getInstalledName(String dependencyId) {
		load();
		return Optional.ofNullable(dependencyToShaderPack.get(dependencyId));
	}

	static synchronized void remember(String dependencyId, String shaderPackFileName) {
		if (dependencyId == null || dependencyId.isBlank() || shaderPackFileName == null || shaderPackFileName.isBlank()) {
			return;
		}
		load();
		dependencyToShaderPack.put(dependencyId, shaderPackFileName);
		save();
	}

	private static void load() {
		if (dependencyToShaderPack != null) {
			return;
		}
		dependencyToShaderPack = new LinkedHashMap<>();
		Path path = path();
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
			if (loaded != null) {
				dependencyToShaderPack.putAll(loaded);
			}
		} catch (IOException | RuntimeException e) {
			Iris.logger.warn("Failed to load ambience dependency install index {}", path, e);
		}
	}

	private static void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(dependencyToShaderPack, writer);
			}
		} catch (IOException e) {
			Iris.logger.warn("Failed to save ambience dependency install index {}", path, e);
		}
	}

	private static Path path() {
		return IrisPlatformHelpers.getInstance().getConfigDir().resolve("wynniris").resolve("ambience-dependencies.json");
	}
}
