package net.irisshaders.iris.ambience;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.WynncraftDebugLog;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class AmbiencePackManager {
	private static final Gson GSON = new Gson();
	private static final AmbiencePackManager INSTANCE = new AmbiencePackManager();

	private final Map<String, LoadedAmbiencePack> packs = new LinkedHashMap<>();
	private volatile Map<String, String> installedShaderPacks = Map.of();
	private long lastLoadMillis;
	private long lastInstalledShaderPackRefreshMillis;
	private int lastResolvedRegionCount;
	private boolean loaded;

	private AmbiencePackManager() {
	}

	public static AmbiencePackManager getInstance() {
		return INSTANCE;
	}

	public Path getDirectory() {
		return IrisPlatformHelpers.getInstance().getConfigDir().resolve("wynniris").resolve("ambience-packs");
	}

	public synchronized void reload() {
		packs.clear();
		lastLoadMillis = System.currentTimeMillis();
		refreshInstalledShaderPacks();

		Path directory = getDirectory();
		try {
			Files.createDirectories(directory);
			try (var files = Files.list(directory)) {
				files
					.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
					.sorted()
					.forEach(this::loadFile);
			}
		} catch (IOException e) {
			Iris.logger.error("Failed to load WynnIris ambience packs from {}", directory, e);
		}

		loaded = true;
	}

	public synchronized void reloadIfNeeded() {
		if (!loaded) {
			reload();
		}
	}

	private void loadFile(Path path) {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			AmbiencePack pack = GSON.fromJson(reader, AmbiencePack.class);
			List<String> errors = validate(pack, path);
			if (!errors.isEmpty()) {
				Iris.logger.warn("Skipping ambience pack {}: {}", path.getFileName(), String.join("; ", errors));
				return;
			}
			packs.put(pack.id, new LoadedAmbiencePack(pack, path, pack.profilesById(), pack.dependenciesById()));
		} catch (JsonSyntaxException | IOException e) {
			Iris.logger.warn("Failed to load ambience pack {}", path, e);
		}
	}

	private List<String> validate(AmbiencePack pack, Path path) {
		List<String> errors = new ArrayList<>();
		if (pack == null) {
			errors.add("file did not contain a JSON object");
			return errors;
		}
		if (pack.schema != 1) {
			errors.add("unsupported schema " + pack.schema);
		}
		if (pack.id == null || pack.id.isBlank()) {
			pack.id = stripJsonExtension(path.getFileName().toString());
		}
		if (pack.dependencies == null) {
			pack.dependencies = new ArrayList<>();
		}
		if (pack.profiles == null) {
			pack.profiles = new ArrayList<>();
		}
		if (pack.regions == null) {
			pack.regions = new ArrayList<>();
		}
		Set<String> dependencyIds = new LinkedHashSet<>();
		for (AmbienceDependency dependency : pack.dependencies) {
			if (dependency != null && dependency.id != null && !dependency.id.isBlank() && !dependencyIds.add(dependency.id)) {
				errors.add("duplicate dependency id " + dependency.id);
			}
			if (dependency != null && dependency.localNames == null) {
				dependency.localNames = new ArrayList<>();
			}
		}
		Set<String> profileIds = new LinkedHashSet<>();
		for (AmbienceProfile profile : pack.profiles) {
			if (profile != null && profile.id != null && !profile.id.isBlank() && !profileIds.add(profile.id)) {
				errors.add("duplicate profile id " + profile.id);
			}
			if (profile != null && profile.options == null) {
				profile.options = new LinkedHashMap<>();
			}
		}
		Map<String, AmbienceProfile> profiles = pack.profilesById();
		if (profiles.isEmpty()) {
			errors.add("no profiles");
		}
		if (pack.defaultProfile == null || pack.defaultProfile.isBlank()) {
			if (!profiles.isEmpty()) {
				pack.defaultProfile = profiles.keySet().iterator().next();
			}
		} else if (!profiles.containsKey(pack.defaultProfile)) {
			errors.add("defaultProfile references unknown profile " + pack.defaultProfile);
		}
		for (AmbienceRegion region : pack.regions) {
			if (region == null) {
				continue;
			}
			if (region.profile == null || !profiles.containsKey(region.profile)) {
				errors.add("region " + region.id + " references unknown profile " + region.profile);
			}
		}
		return errors;
	}

	private static String stripJsonExtension(String name) {
		return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
	}

	public synchronized Collection<LoadedAmbiencePack> getPacks() {
		reloadIfNeeded();
		return List.copyOf(packs.values());
	}

	public synchronized Optional<LoadedAmbiencePack> getPack(String id) {
		reloadIfNeeded();
		if (id != null && !id.isBlank()) {
			return Optional.ofNullable(packs.get(id));
		}
		return packs.values().stream().findFirst();
	}

	public synchronized long getLastLoadMillis() {
		return lastLoadMillis;
	}

	public Optional<ResolvedProfile> resolveCurrentProfile(Minecraft minecraft, String selectedPackId) {
		long startNanos = System.nanoTime();
		Optional<LoadedAmbiencePack> loadedPack = getPack(selectedPackId);
		if (loadedPack.isEmpty()) {
			return Optional.empty();
		}

		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return Optional.empty();
		}

		LoadedAmbiencePack loaded = loadedPack.get();
		AmbiencePack pack = loaded.pack();
		Map<String, AmbienceProfile> profiles = loaded.profilesById();
		String dimension = dimensionName();

		lastResolvedRegionCount = pack.regions.size();
		AmbienceRegion chosen = pack.regions.stream()
			.filter(region -> region != null && region.matches(dimension, player.getX(), player.getY(), player.getZ()))
			.max(Comparator.comparingInt(region -> region.priority))
			.orElse(null);

		String profileId = chosen == null ? pack.defaultProfile : chosen.profile;
		AmbienceProfile profile = profiles.get(profileId);
		if (profile == null) {
			return Optional.empty();
		}

		String shaderPack = resolveShaderPackName(loaded, profile);
		if (shaderPack == null || shaderPack.isBlank()) {
			return Optional.empty();
		}

		logResolveDiagnostics(startNanos, pack, chosen, profileId);
		return Optional.of(new ResolvedProfile(loaded, profileId, profile, shaderPack, chosen == null ? null : chosen.id));
	}

	public synchronized List<ResolvedProfile> resolveProfiles(String selectedPackId) {
		Optional<LoadedAmbiencePack> loadedPack = getPack(selectedPackId);
		if (loadedPack.isEmpty()) {
			return List.of();
		}

		LoadedAmbiencePack loaded = loadedPack.get();
		List<ResolvedProfile> resolved = new ArrayList<>();
		loaded.profilesById().forEach((profileId, profile) -> {
			String shaderPack = resolveShaderPackName(loaded, profile);
			if (shaderPack != null && !shaderPack.isBlank()) {
				resolved.add(new ResolvedProfile(loaded, profileId, profile, shaderPack, null));
			}
		});
		return List.copyOf(resolved);
	}

	public AmbienceDependencyStatus dependencyStatus(AmbiencePack pack) {
		List<AmbienceDependency> missing = new ArrayList<>();
		Set<String> missingIds = new LinkedHashSet<>();
		Map<String, String> installed = installedShaderPacksByName();
		Map<String, AmbienceDependency> dependencies = pack.dependenciesById();
		for (AmbienceDependency dependency : pack.dependencies) {
			if (dependency == null || dependency.id == null || dependency.id.isBlank()) {
				continue;
			}
			if (resolveLocalName(dependency, installed).isEmpty() && missingIds.add(dependency.id)) {
				missing.add(dependency);
			}
		}
		for (AmbienceProfile profile : pack.profiles) {
			if (profile == null || profile.shaderPack == null || profile.shaderPack.isBlank() || dependencies.containsKey(profile.shaderPack)) {
				continue;
			}
			if (!installed.containsKey(profile.shaderPack) && missingIds.add(profile.shaderPack)) {
				AmbienceDependency local = new AmbienceDependency();
				local.id = profile.shaderPack;
				local.type = "local";
				local.localNames.add(profile.shaderPack);
				missing.add(local);
			}
		}
		return new AmbienceDependencyStatus(missing);
	}

	private String resolveShaderPackName(AmbiencePack pack, AmbienceProfile profile) {
		return resolveShaderPackName(new LoadedAmbiencePack(pack, null, pack.profilesById(), pack.dependenciesById()), profile);
	}

	private String resolveShaderPackName(LoadedAmbiencePack pack, AmbienceProfile profile) {
		Map<String, String> installed = installedShaderPacksByName();
		AmbienceDependency dependency = pack.dependenciesById().get(profile.shaderPack);
		if (dependency != null) {
			return resolveLocalName(dependency, installed).orElse(null);
		}
		if (profile.shaderPack != null && installed.containsKey(profile.shaderPack)) {
			return installed.get(profile.shaderPack);
		}
		return null;
	}

	private Optional<String> resolveLocalName(AmbienceDependency dependency, Map<String, String> installed) {
		Optional<String> indexed = AmbienceInstallIndex.getInstalledName(dependency.id);
		if (indexed.isPresent() && installed.containsKey(indexed.get())) {
			return indexed;
		}
		if (dependency.localNames != null) {
			for (String localName : dependency.localNames) {
				String found = installed.get(localName);
				if (found != null) {
					return Optional.of(found);
				}
			}
		}
		if (installed.containsKey(dependency.id)) {
			return Optional.of(installed.get(dependency.id));
		}
		return Optional.empty();
	}

	private Map<String, String> installedShaderPacksByName() {
		return installedShaderPacks;
	}

	public synchronized void refreshInstalledShaderPacks() {
		Map<String, String> installed = new LinkedHashMap<>();
		try {
			for (String name : Iris.getShaderpacksDirectoryManager().enumerate()) {
				installed.put(name, name);
			}
		} catch (Throwable e) {
			Iris.logger.warn("Failed to enumerate shader packs for ambience dependency resolution", e);
		}
		installedShaderPacks = Map.copyOf(installed);
		lastInstalledShaderPackRefreshMillis = System.currentTimeMillis();
		WynncraftDebugLog.info("ambience-installed-shaderpack-cache",
			"Refreshed ambience shaderpack cache: {} entries", installedShaderPacks.size());
	}

	public int getInstalledShaderPackCount() {
		return installedShaderPacks.size();
	}

	public long getLastInstalledShaderPackRefreshMillis() {
		return lastInstalledShaderPackRefreshMillis;
	}

	public int getLastResolvedRegionCount() {
		return lastResolvedRegionCount;
	}

	private static String dimensionName() {
		NamespacedId id = Iris.getCurrentDimension();
		return id == null ? "" : id.toString();
	}

	private void logResolveDiagnostics(long startNanos, AmbiencePack pack, AmbienceRegion chosen, String profileId) {
		if (!WynncraftDebugLog.shouldLog("ambience-profile-resolve")) {
			return;
		}
		long elapsedMicros = (System.nanoTime() - startNanos) / 1_000L;
		WynncraftDebugLog.info("ambience-profile-resolve",
			"Resolved ambience pack {} profile {} region {} across {} regions in {} us",
			pack.id, profileId, chosen == null ? "default" : chosen.id, lastResolvedRegionCount, elapsedMicros);
	}

	public record LoadedAmbiencePack(AmbiencePack pack, Path path, Map<String, AmbienceProfile> profilesById,
									 Map<String, AmbienceDependency> dependenciesById) {
	}

	public record ResolvedProfile(LoadedAmbiencePack loadedPack, String profileId, AmbienceProfile profile,
								  String resolvedShaderPack, String regionId) {
		public String key() {
			return loadedPack.pack().id + ":" + profileId + ":" + profile.cacheKey(resolvedShaderPack);
		}
	}
}
