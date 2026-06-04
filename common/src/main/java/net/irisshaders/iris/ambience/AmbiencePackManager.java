package net.irisshaders.iris.ambience;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.WynncraftDebugLog;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shaderpack.option.OptionSet;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class AmbiencePackManager {
	private static final Gson GSON = new Gson();
	private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final AmbiencePackManager INSTANCE = new AmbiencePackManager();
	private static final String MANIFEST_FILE = "manifest.json";
	private static final String PROFILES_FILE = "profiles.json";
	private static final String REGIONS_FILE = "regions.json";
	private static final String OVERRIDES_DIRECTORY = "overrides";
	private static final String PACK_ZIP_EXTENSION = ".wynnambience.zip";

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
					.filter(this::isAmbiencePackSource)
					.sorted()
					.forEach(this::loadSource);
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

	private boolean isAmbiencePackSource(Path path) {
		String fileName = path.getFileName().toString();
		if (Files.isDirectory(path)) {
			return !OVERRIDES_DIRECTORY.equals(fileName) && Files.isRegularFile(path.resolve(MANIFEST_FILE));
		}
		if (!Files.isRegularFile(path)) {
			return false;
		}
		return fileName.endsWith(PACK_ZIP_EXTENSION)
			|| (fileName.endsWith(".json") && !MANIFEST_FILE.equals(fileName) && !PROFILES_FILE.equals(fileName) && !REGIONS_FILE.equals(fileName));
	}

	private void loadSource(Path path) {
		if (Files.isDirectory(path)) {
			loadDirectoryPack(path);
			return;
		}

		String fileName = path.getFileName().toString();
		if (fileName.endsWith(PACK_ZIP_EXTENSION)) {
			loadZipPack(path);
			return;
		}

		loadLegacyFile(path);
	}

	private void loadLegacyFile(Path path) {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			AmbiencePack pack = GSON.fromJson(reader, AmbiencePack.class);
			normalizePackId(pack, stripJsonExtension(path.getFileName().toString()));
			loadValidatedPack(pack, path, PackStorage.legacy(path));
		} catch (JsonSyntaxException | IOException e) {
			Iris.logger.warn("Failed to load ambience pack {}", path, e);
		}
	}

	private void loadDirectoryPack(Path path) {
		try {
			AmbiencePack pack = readDirectoryPack(path);
			normalizePackId(pack, path.getFileName().toString());
			loadValidatedPack(pack, path, PackStorage.directory(path));
		} catch (JsonSyntaxException | IOException e) {
			Iris.logger.warn("Failed to load ambience pack directory {}", path, e);
		}
	}

	private void loadZipPack(Path path) {
		try (ZipFile zip = new ZipFile(path.toFile())) {
			String fallbackId = stripPackZipExtension(path.getFileName().toString());
			AmbiencePack pack = readZipPack(zip);
			normalizePackId(pack, fallbackId);
			String overrideId = pack == null || pack.id == null || pack.id.isBlank() ? fallbackId : pack.id;
			Path overrideDirectory = overrideDirectory(overrideId);
			if (pack != null && pack.id != null && !pack.id.isBlank()) {
				applyZipOverrides(pack, overrideDirectory);
			}
			loadValidatedPack(pack, path, PackStorage.zip(path, overrideDirectory));
		} catch (JsonSyntaxException | IOException e) {
			Iris.logger.warn("Failed to load ambience pack zip {}", path, e);
		}
	}

	private void loadValidatedPack(AmbiencePack pack, Path path, PackStorage storage) {
		List<String> errors = validate(pack);
		if (!errors.isEmpty()) {
			Iris.logger.warn("Skipping ambience pack {}: {}", path.getFileName(), String.join("; ", errors));
			return;
		}
		packs.put(pack.id, new LoadedAmbiencePack(pack, storage, pack.profilesById(), pack.dependenciesById()));
	}

	private List<String> validate(AmbiencePack pack) {
		List<String> errors = new ArrayList<>();
		if (pack == null) {
			errors.add("file did not contain a JSON object");
			return errors;
		}
		if (pack.schema != 1) {
			errors.add("unsupported schema " + pack.schema);
		}
		if (pack.id == null || pack.id.isBlank()) {
			errors.add("missing pack id");
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
			validateRegionShape(errors, region);
		}
		return errors;
	}

	private void validateRegionShape(List<String> errors, AmbienceRegion region) {
		if (region.shape == null) {
			errors.add("region " + regionId(region) + " is missing a shape");
			return;
		}
		String shapeType = region.shape.type == null ? "box" : region.shape.type.toLowerCase(java.util.Locale.ROOT);
		if ("sphere".equals(shapeType)) {
			validateXzArray(errors, region, "center", region.shape.center, true);
			return;
		}
		if ("polygon".equals(shapeType)) {
			validateXzArray(errors, region, "min", region.shape.min, false);
			validateXzArray(errors, region, "max", region.shape.max, false);
			return;
		}
		validateXzArray(errors, region, "min", region.shape.min, true);
		validateXzArray(errors, region, "max", region.shape.max, true);
	}

	private void validateXzArray(List<String> errors, AmbienceRegion region, String field, double[] values, boolean required) {
		if (values == null) {
			if (required) {
				errors.add("region " + regionId(region) + " shape." + field + " must be [x,z]");
			}
			return;
		}
		if (values.length != 2) {
			errors.add("region " + regionId(region) + " shape." + field + " must be [x,z], not legacy [x,y,z]");
		}
	}

	private String regionId(AmbienceRegion region) {
		return region.id == null || region.id.isBlank() ? "<unnamed>" : region.id;
	}

	private static String stripJsonExtension(String name) {
		return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
	}

	private static String stripPackZipExtension(String name) {
		return name.endsWith(PACK_ZIP_EXTENSION) ? name.substring(0, name.length() - PACK_ZIP_EXTENSION.length()) : name;
	}

	private static void normalizePackId(AmbiencePack pack, String fallbackId) {
		if (pack != null && (pack.id == null || pack.id.isBlank())) {
			pack.id = fallbackId;
		}
	}

	private AmbiencePack readDirectoryPack(Path path) throws IOException {
		AmbiencePackManifest manifest = readJson(path.resolve(MANIFEST_FILE), AmbiencePackManifest.class);
		AmbienceProfilesFile profiles = readOptionalJson(path.resolve(PROFILES_FILE), AmbienceProfilesFile.class, new AmbienceProfilesFile());
		AmbienceRegionsFile regions = readOptionalJson(path.resolve(REGIONS_FILE), AmbienceRegionsFile.class, new AmbienceRegionsFile());
		return assemblePack(manifest, profiles, regions);
	}

	private AmbiencePack readZipPack(ZipFile zip) throws IOException {
		AmbiencePackManifest manifest = readZipJson(zip, MANIFEST_FILE, AmbiencePackManifest.class);
		AmbienceProfilesFile profiles = readOptionalZipJson(zip, PROFILES_FILE, AmbienceProfilesFile.class, new AmbienceProfilesFile());
		AmbienceRegionsFile regions = readOptionalZipJson(zip, REGIONS_FILE, AmbienceRegionsFile.class, new AmbienceRegionsFile());
		return assemblePack(manifest, profiles, regions);
	}

	private AmbiencePack assemblePack(AmbiencePackManifest manifest, AmbienceProfilesFile profiles, AmbienceRegionsFile regions) {
		AmbiencePack pack = manifest == null ? null : manifest.toPack();
		if (pack == null) {
			return null;
		}
		pack.profiles = profiles == null || profiles.profiles == null ? new ArrayList<>() : profiles.profiles;
		pack.regions = regions == null || regions.regions == null ? new ArrayList<>() : regions.regions;
		return pack;
	}

	private void applyZipOverrides(AmbiencePack pack, Path overrideDirectory) throws IOException {
		AmbiencePackManifest manifestOverride = readOptionalJson(overrideDirectory.resolve(MANIFEST_FILE), AmbiencePackManifest.class, null);
		if (manifestOverride != null) {
			mergeManifestOverride(pack, manifestOverride);
		}

		AmbienceProfileOverridesFile profileOverrides = readOptionalJson(overrideDirectory.resolve(PROFILES_FILE), AmbienceProfileOverridesFile.class, null);
		if (profileOverrides != null && profileOverrides.profiles != null) {
			mergeProfileOverrides(pack, profileOverrides.profiles);
		}

		AmbienceRegionsFile regionOverrides = readOptionalJson(overrideDirectory.resolve(REGIONS_FILE), AmbienceRegionsFile.class, null);
		if (regionOverrides != null && regionOverrides.regions != null) {
			pack.regions = regionOverrides.regions;
		}
	}

	private void mergeManifestOverride(AmbiencePack pack, AmbiencePackManifest override) {
		if (override.name != null && !override.name.isBlank()) {
			pack.name = override.name;
		}
		if (override.version != null && !override.version.isBlank()) {
			pack.version = override.version;
		}
		if (override.authors != null && !override.authors.isEmpty()) {
			pack.authors = new ArrayList<>(override.authors);
		}
		if (override.minecraftVersions != null && !override.minecraftVersions.isEmpty()) {
			pack.minecraftVersions = new ArrayList<>(override.minecraftVersions);
		}
		if (override.defaultProfile != null && !override.defaultProfile.isBlank()) {
			pack.defaultProfile = override.defaultProfile;
		}
		if (override.dependencies != null && !override.dependencies.isEmpty()) {
			Map<String, AmbienceDependency> dependencies = pack.dependenciesById();
			for (AmbienceDependency dependency : override.dependencies) {
				if (dependency != null && dependency.id != null && !dependency.id.isBlank()) {
					dependencies.put(dependency.id, dependency);
				}
			}
			pack.dependencies = new ArrayList<>(dependencies.values());
		}
	}

	private void mergeProfileOverrides(AmbiencePack pack, List<AmbienceProfileOverride> profileOverrides) {
		Map<String, AmbienceProfile> merged = new LinkedHashMap<>();
		for (AmbienceProfile profile : pack.profiles) {
			if (profile != null && profile.id != null && !profile.id.isBlank()) {
				merged.put(profile.id, profile);
			}
		}
		for (AmbienceProfileOverride override : profileOverrides) {
			if (override == null || override.id == null || override.id.isBlank()) {
				continue;
			}
			if (override.deleted) {
				merged.remove(override.id);
				continue;
			}
			AmbienceProfile profile = merged.get(override.id);
			if (profile == null) {
				profile = new AmbienceProfile();
				profile.id = override.id;
			} else {
				profile = profile.copy();
			}
			if (override.shaderPack != null && !override.shaderPack.isBlank()) {
				profile.shaderPack = override.shaderPack;
			}
			if (profile.options == null) {
				profile.options = new LinkedHashMap<>();
			}
			if (override.resetOptions != null) {
				for (String key : override.resetOptions) {
					if (key != null && !key.isBlank()) {
						profile.options.remove(key);
					}
				}
			}
			if (override.options != null) {
				Map<String, String> targetOptions = profile.options;
				override.options.entrySet().stream()
					.filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null)
					.forEach(entry -> targetOptions.put(entry.getKey(), entry.getValue()));
			}
			merged.put(profile.id, profile);
		}
		pack.profiles = new ArrayList<>(merged.values());
	}

	private <T> T readJson(Path path, Class<T> type) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, type);
		}
	}

	private <T> T readOptionalJson(Path path, Class<T> type, T fallback) throws IOException {
		if (!Files.isRegularFile(path)) {
			return fallback;
		}
		return readJson(path, type);
	}

	private <T> T readZipJson(ZipFile zip, String name, Class<T> type) throws IOException {
		ZipEntry entry = zip.getEntry(name);
		if (entry == null) {
			throw new IOException("Missing required " + name);
		}
		try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, type);
		}
	}

	private <T> T readOptionalZipJson(ZipFile zip, String name, Class<T> type, T fallback) throws IOException {
		ZipEntry entry = zip.getEntry(name);
		if (entry == null) {
			return fallback;
		}
		try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, type);
		}
	}

	private Path overrideDirectory(String packId) {
		String encodedId = URLEncoder.encode(packId, StandardCharsets.UTF_8).replace("+", "%20");
		return getDirectory().resolve(OVERRIDES_DIRECTORY).resolve(encodedId);
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

	public synchronized Optional<ResolvedProfile> resolveProfile(String selectedPackId, String profileId) {
		Optional<LoadedAmbiencePack> loadedPack = getPack(selectedPackId);
		if (loadedPack.isEmpty() || profileId == null || profileId.isBlank()) {
			return Optional.empty();
		}

		LoadedAmbiencePack loaded = loadedPack.get();
		AmbienceProfile profile = loaded.profilesById().get(profileId);
		if (profile == null) {
			return Optional.empty();
		}

		String shaderPack = resolveShaderPackName(loaded, profile);
		if (shaderPack == null || shaderPack.isBlank()) {
			return Optional.empty();
		}

		return Optional.of(new ResolvedProfile(loaded, profileId, profile, shaderPack, null));
	}

	public synchronized LoadedAmbiencePack createEmptyPack(String displayName) throws IOException {
		return createPack(new AmbiencePackMetadata(displayName, "1.0.0", List.of()));
	}

	public synchronized LoadedAmbiencePack createPack(AmbiencePackMetadata metadata) throws IOException {
		reloadIfNeeded();

		String name = metadata == null || metadata.name == null || metadata.name.isBlank() ? "New Ambience Pack" : metadata.name.trim();
		String id = uniquePackId(slugify(name));
		Path packDirectory = getDirectory().resolve(id);

		AmbiencePack pack = new AmbiencePack();
		pack.id = id;
		pack.name = name;
		pack.version = metadata == null || metadata.version == null || metadata.version.isBlank() ? "1.0.0" : metadata.version.trim();
		pack.authors = metadata == null || metadata.authors == null ? new ArrayList<>() : new ArrayList<>(metadata.authors);

		Files.createDirectories(packDirectory);
		LoadedAmbiencePack loaded = new LoadedAmbiencePack(pack, PackStorage.directory(packDirectory), pack.profilesById(), pack.dependenciesById());
		writeAll(loaded);
		packs.put(pack.id, loaded);
		lastLoadMillis = System.currentTimeMillis();
		this.loaded = true;
		return loaded;
	}

	public synchronized void updatePackMetadata(String selectedPackId, AmbiencePackMetadata metadata) throws IOException {
		LoadedAmbiencePack loaded = requirePack(selectedPackId);
		if (metadata == null || metadata.name == null || metadata.name.isBlank()) {
			throw new IOException("Pack name is required");
		}
		loaded.pack().name = metadata.name.trim();
		loaded.pack().version = metadata.version == null || metadata.version.isBlank() ? "1.0.0" : metadata.version.trim();
		loaded.pack().authors = metadata.authors == null ? new ArrayList<>() : new ArrayList<>(metadata.authors);
		writeManifest(loaded);
		lastLoadMillis = System.currentTimeMillis();
	}

	public synchronized AmbienceProfile addProfile(String selectedPackId, String profileId, String shaderPackName) throws IOException {
		return addProfile(selectedPackId, profileId, shaderPackName, AmbienceDependencyResolver.localDependency(shaderPackName));
	}

	public synchronized AmbienceProfile addProfile(String selectedPackId, String profileId, String shaderPackName, AmbienceDependency dependency) throws IOException {
		Optional<LoadedAmbiencePack> loadedPack = getPack(selectedPackId);
		if (loadedPack.isEmpty()) {
			throw new IOException("Ambience pack is not installed: " + selectedPackId);
		}
		if (profileId == null || profileId.isBlank()) {
			throw new IOException("Preset name is required");
		}
		if (shaderPackName == null || shaderPackName.isBlank()) {
			throw new IOException("Shader pack is required");
		}

		LoadedAmbiencePack loaded = loadedPack.get();
		if (loaded.storage() == null || !loaded.storage().canWriteProfiles()) {
			throw new IOException("Ambience pack does not have a writable profiles file: " + selectedPackId);
		}

		String id = sanitizeProfileId(profileId);
		if (id.isBlank()) {
			throw new IOException("Preset name must contain letters, numbers, dashes, underscores, or periods");
		}
		if (loaded.profilesById().containsKey(id)) {
			throw new IOException("Ambience preset already exists: " + id);
		}

		AmbienceProfile profile = new AmbienceProfile();
		profile.id = id;
		AmbienceDependency savedDependency = dependency == null ? AmbienceDependencyResolver.localDependency(shaderPackName) : dependency;
		mergeDependency(loaded.pack(), savedDependency);
		profile.shaderPack = savedDependency.id == null || savedDependency.id.isBlank() ? shaderPackName : savedDependency.id;
		profile.options = new LinkedHashMap<>();

		if (loaded.pack().profiles == null) {
			loaded.pack().profiles = new ArrayList<>();
		}
		loaded.pack().profiles.add(profile);
		loaded.profilesById().put(id, profile);
		if (loaded.pack().defaultProfile == null || loaded.pack().defaultProfile.isBlank()) {
			loaded.pack().defaultProfile = id;
		}

		writeManifest(loaded);
		writeProfile(loaded, id);
		lastLoadMillis = System.currentTimeMillis();
		return profile;
	}

	public synchronized AmbienceProfile renameProfile(String selectedPackId, String profileId, String requestedId) throws IOException {
		LoadedAmbiencePack loaded = requireWritableProfiles(selectedPackId);
		AmbienceProfile profile = requireProfile(loaded, profileId);
		String newId = sanitizeProfileId(requestedId);
		if (newId.isBlank()) {
			throw new IOException("Preset name must contain letters, numbers, dashes, underscores, or periods");
		}
		if (!profileId.equals(newId) && loaded.profilesById().containsKey(newId)) {
			throw new IOException("Ambience preset already exists: " + newId);
		}
		if (profileId.equals(newId)) {
			return profile;
		}

		loaded.profilesById().remove(profileId);
		profile.id = newId;
		loaded.profilesById().put(newId, profile);
		if (profileId.equals(loaded.pack().defaultProfile)) {
			loaded.pack().defaultProfile = newId;
		}
		for (AmbienceRegion region : loaded.pack().regions) {
			if (region != null && profileId.equals(region.profile)) {
				region.profile = newId;
			}
		}

		writeProfileDelete(loaded, profileId);
		writeProfile(loaded, newId);
		writeManifest(loaded);
		writeRegions(loaded);
		lastLoadMillis = System.currentTimeMillis();
		return profile;
	}

	public synchronized AmbienceProfile duplicateProfile(String selectedPackId, String profileId, String requestedId) throws IOException {
		LoadedAmbiencePack loaded = requireWritableProfiles(selectedPackId);
		AmbienceProfile source = requireProfile(loaded, profileId);
		String newId = uniqueProfileId(loaded, sanitizeProfileId(requestedId == null || requestedId.isBlank() ? profileId + "_copy" : requestedId));
		AmbienceProfile copy = source.copy();
		copy.id = newId;
		loaded.pack().profiles.add(copy);
		loaded.profilesById().put(newId, copy);
		writeProfile(loaded, newId);
		lastLoadMillis = System.currentTimeMillis();
		return copy;
	}

	public synchronized void deleteProfile(String selectedPackId, String profileId) throws IOException {
		LoadedAmbiencePack loaded = requireWritableProfiles(selectedPackId);
		requireProfile(loaded, profileId);
		loaded.pack().profiles.removeIf(profile -> profile != null && profileId.equals(profile.id));
		loaded.profilesById().remove(profileId);
		loaded.pack().regions.removeIf(region -> region != null && profileId.equals(region.profile));
		if (profileId.equals(loaded.pack().defaultProfile)) {
			loaded.pack().defaultProfile = loaded.pack().profiles.stream()
				.filter(profile -> profile != null && profile.id != null && !profile.id.isBlank())
				.map(profile -> profile.id)
				.findFirst()
				.orElse("");
		}
		writeProfileDelete(loaded, profileId);
		writeManifest(loaded);
		writeRegions(loaded);
		lastLoadMillis = System.currentTimeMillis();
	}

	public synchronized AmbienceProfile changeProfileShader(String selectedPackId, String profileId, String shaderPackName, AmbienceDependency dependency) throws IOException {
		LoadedAmbiencePack loaded = requireWritableProfiles(selectedPackId);
		AmbienceProfile profile = requireProfile(loaded, profileId);
		if (shaderPackName == null || shaderPackName.isBlank()) {
			throw new IOException("Shader pack is required");
		}
		AmbienceDependency savedDependency = dependency == null ? AmbienceDependencyResolver.localDependency(shaderPackName) : dependency;
		mergeDependency(loaded.pack(), savedDependency);
		profile.shaderPack = savedDependency.id == null || savedDependency.id.isBlank() ? shaderPackName : savedDependency.id;
		profile.options = filterOptionsForShaderPack(shaderPackName, profile.options);
		writeManifest(loaded);
		writeProfile(loaded, profileId);
		lastLoadMillis = System.currentTimeMillis();
		return profile;
	}

	public synchronized void setDefaultProfile(String selectedPackId, String profileId) throws IOException {
		LoadedAmbiencePack loaded = requireWritableProfiles(selectedPackId);
		requireProfile(loaded, profileId);
		loaded.pack().defaultProfile = profileId;
		writeManifest(loaded);
		lastLoadMillis = System.currentTimeMillis();
	}

	public synchronized void saveProfileOptions(String selectedPackId, String profileId, Map<String, String> options) throws IOException {
		Optional<LoadedAmbiencePack> loadedPack = getPack(selectedPackId);
		if (loadedPack.isEmpty()) {
			throw new IOException("Ambience pack is not installed: " + selectedPackId);
		}

		LoadedAmbiencePack loaded = loadedPack.get();
		if (loaded.storage() == null || !loaded.storage().canWriteProfiles()) {
			throw new IOException("Ambience pack does not have a writable profiles file: " + selectedPackId);
		}

		AmbienceProfile profile = loaded.profilesById().get(profileId);
		if (profile == null) {
			throw new IOException("Ambience profile is not present: " + profileId);
		}

		profile.options = new LinkedHashMap<>();
		if (options != null) {
			options.entrySet().stream()
				.filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null)
				.sorted(Map.Entry.comparingByKey())
				.forEach(entry -> profile.options.put(entry.getKey(), entry.getValue()));
		}

		writeProfile(loaded, profileId);
		lastLoadMillis = System.currentTimeMillis();
	}

	public synchronized void saveRegions(String selectedPackId, List<AmbienceRegion> regions) throws IOException {
		Optional<LoadedAmbiencePack> loadedPack = getPack(selectedPackId);
		if (loadedPack.isEmpty()) {
			throw new IOException("Ambience pack is not installed: " + selectedPackId);
		}

		LoadedAmbiencePack loaded = loadedPack.get();
		if (loaded.storage() == null || !loaded.storage().canWriteRegions()) {
			throw new IOException("Ambience pack does not have a writable regions file: " + selectedPackId);
		}

		List<AmbienceRegion> savedRegions = new ArrayList<>();
		if (regions != null) {
			for (AmbienceRegion region : regions) {
				if (region != null) {
					savedRegions.add(region.copy());
				}
			}
		}
		loaded.pack().regions = savedRegions;

		writeRegions(loaded);
		lastLoadMillis = System.currentTimeMillis();
		lastResolvedRegionCount = savedRegions.size();
	}

	public synchronized LoadedAmbiencePack importPack(Path source) throws IOException {
		reloadIfNeeded();
		if (source == null || !Files.exists(source)) {
			throw new IOException("Ambience pack file does not exist");
		}
		Files.createDirectories(getDirectory());
		Path destination;
		if (Files.isDirectory(source)) {
			AmbiencePack pack = readDirectoryPack(source);
			normalizePackId(pack, source.getFileName().toString());
			List<String> errors = validate(pack);
			if (!errors.isEmpty()) {
				throw new IOException("Invalid ambience pack: " + String.join("; ", errors));
			}
			if (packs.containsKey(pack.id)) {
				throw new IOException("Ambience pack is already installed: " + pack.id);
			}
			destination = getDirectory().resolve(pack.id);
			if (Files.exists(destination)) {
				throw new IOException("Ambience pack destination already exists: " + destination.getFileName());
			}
			copyDirectory(source, destination);
		} else if (source.getFileName().toString().endsWith(PACK_ZIP_EXTENSION)) {
			try (ZipFile zip = new ZipFile(source.toFile())) {
				AmbiencePack pack = readZipPack(zip);
				normalizePackId(pack, stripPackZipExtension(source.getFileName().toString()));
				List<String> errors = validate(pack);
				if (!errors.isEmpty()) {
					throw new IOException("Invalid ambience pack: " + String.join("; ", errors));
				}
				if (packs.containsKey(pack.id)) {
					throw new IOException("Ambience pack is already installed: " + pack.id);
				}
				destination = getDirectory().resolve(pack.id + PACK_ZIP_EXTENSION);
			}
			if (Files.exists(destination)) {
				throw new IOException("Ambience pack destination already exists: " + destination.getFileName());
			}
			Files.copy(source, destination);
		} else {
			throw new IOException("Ambience packs must be folders or " + PACK_ZIP_EXTENSION + " files");
		}
		reload();
		return getPacks().stream()
			.filter(pack -> destination.equals(pack.path()))
			.findFirst()
			.orElseThrow(() -> new IOException("Imported ambience pack could not be loaded"));
	}

	public synchronized void exportPack(String selectedPackId, Path destination) throws IOException {
		LoadedAmbiencePack loaded = requirePack(selectedPackId);
		if (destination == null) {
			throw new IOException("Export destination is required");
		}
		Path target = destination;
		if (!target.getFileName().toString().endsWith(PACK_ZIP_EXTENSION)) {
			target = target.resolveSibling(target.getFileName() + PACK_ZIP_EXTENSION);
		}
		Files.createDirectories(target.toAbsolutePath().getParent());
		Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".tmp");
		try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING);
			 ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
			writeZipJson(zip, MANIFEST_FILE, AmbiencePackManifest.fromPack(loaded.pack()));
			writeZipJson(zip, PROFILES_FILE, AmbienceProfilesFile.fromPack(loaded.pack()));
			writeZipJson(zip, REGIONS_FILE, AmbienceRegionsFile.fromPack(loaded.pack()));
		}
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicMoveFailure) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public synchronized List<AmbienceDependency> localOnlyDependencies(String selectedPackId) throws IOException {
		LoadedAmbiencePack loaded = requirePack(selectedPackId);
		return loaded.pack().dependencies.stream()
			.filter(dependency -> dependency != null && !"modrinth".equalsIgnoreCase(dependency.type))
			.toList();
	}

	public synchronized void replaceDependency(String selectedPackId, String oldDependencyId, AmbienceDependency replacement) throws IOException {
		LoadedAmbiencePack loaded = requirePack(selectedPackId);
		if (replacement == null || replacement.id == null || replacement.id.isBlank()) {
			throw new IOException("Replacement dependency is missing an id");
		}
		String oldId = oldDependencyId == null ? "" : oldDependencyId;
		if (loaded.pack().dependencies == null) {
			loaded.pack().dependencies = new ArrayList<>();
		}
		if (!oldId.isBlank() && !oldId.equals(replacement.id)) {
			loaded.pack().dependencies.removeIf(dependency -> dependency != null && oldId.equals(dependency.id));
		}
		mergeDependency(loaded.pack(), replacement);
		loaded.dependenciesById().clear();
		loaded.dependenciesById().putAll(loaded.pack().dependenciesById());

		for (AmbienceProfile profile : loaded.pack().profiles) {
			if (profile != null && oldId.equals(profile.shaderPack)) {
				profile.shaderPack = replacement.id;
				writeProfile(loaded, profile.id);
			}
		}
		writeManifest(loaded);
		lastLoadMillis = System.currentTimeMillis();
	}

	private void writeProfile(LoadedAmbiencePack loaded, String profileId) throws IOException {
		PackStorage storage = loaded.storage();
		if (storage.kind() == PackSourceKind.LEGACY_JSON) {
			writeJsonAtomic(storage.sourcePath(), loaded.pack());
			return;
		}
		if (storage.kind() == PackSourceKind.ZIP) {
			AmbienceProfile profile = loaded.profilesById().get(profileId);
			if (profile == null) {
				throw new IOException("Ambience profile is not present: " + profileId);
			}
			AmbienceProfileOverridesFile overrides = readOptionalJson(storage.profilesWritePath(), AmbienceProfileOverridesFile.class, new AmbienceProfileOverridesFile());
			if (overrides.profiles == null) {
				overrides.profiles = new ArrayList<>();
			}
			overrides.profiles.removeIf(existing -> existing == null || profileId.equals(existing.id));
			overrides.profiles.add(createSparseProfileOverride(loaded, profile));
			writeJsonAtomic(storage.profilesWritePath(), overrides);
			return;
		}

		writeJsonAtomic(storage.profilesWritePath(), AmbienceProfilesFile.fromPack(loaded.pack()));
	}

	private void writeProfileDelete(LoadedAmbiencePack loaded, String profileId) throws IOException {
		PackStorage storage = loaded.storage();
		if (storage.kind() == PackSourceKind.LEGACY_JSON) {
			writeJsonAtomic(storage.sourcePath(), loaded.pack());
			return;
		}
		if (storage.kind() == PackSourceKind.ZIP) {
			AmbienceProfileOverridesFile overrides = readOptionalJson(storage.profilesWritePath(), AmbienceProfileOverridesFile.class, new AmbienceProfileOverridesFile());
			if (overrides.profiles == null) {
				overrides.profiles = new ArrayList<>();
			}
			overrides.profiles.removeIf(existing -> existing == null || profileId.equals(existing.id));
			AmbienceProfileOverride deleted = new AmbienceProfileOverride();
			deleted.id = profileId;
			deleted.deleted = true;
			overrides.profiles.add(deleted);
			writeJsonAtomic(storage.profilesWritePath(), overrides);
			return;
		}
		writeJsonAtomic(storage.profilesWritePath(), AmbienceProfilesFile.fromPack(loaded.pack()));
	}

	private AmbienceProfileOverride createSparseProfileOverride(LoadedAmbiencePack loaded, AmbienceProfile profile) throws IOException {
		AmbienceProfileOverride override = new AmbienceProfileOverride();
		override.id = profile.id;
		AmbienceProfile base = readBaseZipProfile(loaded, profile.id);
		if (base == null || !safeEquals(base.shaderPack, profile.shaderPack)) {
			override.shaderPack = profile.shaderPack;
		}
		Map<String, String> baseOptions = base == null || base.options == null ? Map.of() : base.options;
		Map<String, String> currentOptions = profile.options == null ? Map.of() : profile.options;
		currentOptions.entrySet().stream()
			.filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null)
			.sorted(Map.Entry.comparingByKey())
			.filter(entry -> !safeEquals(baseOptions.get(entry.getKey()), entry.getValue()))
			.forEach(entry -> override.options.put(entry.getKey(), entry.getValue()));
		baseOptions.keySet().stream()
			.filter(key -> key != null && !key.isBlank() && !currentOptions.containsKey(key))
			.sorted()
			.forEach(override.resetOptions::add);
		return override;
	}

	private AmbienceProfile readBaseZipProfile(LoadedAmbiencePack loaded, String profileId) throws IOException {
		if (loaded.storage().kind() != PackSourceKind.ZIP) {
			return null;
		}
		try (ZipFile zip = new ZipFile(loaded.storage().sourcePath().toFile())) {
			AmbiencePack base = readZipPack(zip);
			normalizePackId(base, stripPackZipExtension(loaded.storage().sourcePath().getFileName().toString()));
			return base.profilesById().get(profileId);
		}
	}

	private String uniquePackId(String requestedId) {
		String base = requestedId == null || requestedId.isBlank() ? "new-ambience-pack" : requestedId;
		String candidate = base;
		int suffix = 2;
		while (packs.containsKey(candidate) || Files.exists(getDirectory().resolve(candidate)) || Files.exists(getDirectory().resolve(candidate + PACK_ZIP_EXTENSION))) {
			candidate = base + "-" + suffix++;
		}
		return candidate;
	}

	private String slugify(String value) {
		String slug = value.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9._-]+", "-")
			.replaceAll("^-+|-+$", "");
		return slug.isBlank() ? "new-ambience-pack" : slug;
	}

	private String sanitizeProfileId(String value) {
		return value.trim()
			.replaceAll("\\s+", "_")
			.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private void writeRegions(LoadedAmbiencePack loaded) throws IOException {
		PackStorage storage = loaded.storage();
		if (storage.kind() == PackSourceKind.LEGACY_JSON) {
			writeJsonAtomic(storage.sourcePath(), loaded.pack());
			return;
		}

		writeJsonAtomic(storage.regionsWritePath(), AmbienceRegionsFile.fromPack(loaded.pack()));
	}

	private void writeManifest(LoadedAmbiencePack loaded) throws IOException {
		PackStorage storage = loaded.storage();
		if (storage.kind() == PackSourceKind.LEGACY_JSON) {
			writeJsonAtomic(storage.sourcePath(), loaded.pack());
			return;
		}
		writeJsonAtomic(storage.manifestWritePath(), AmbiencePackManifest.fromPack(loaded.pack()));
	}

	private void writeAll(LoadedAmbiencePack loaded) throws IOException {
		PackStorage storage = loaded.storage();
		if (storage.kind() == PackSourceKind.LEGACY_JSON) {
			writeJsonAtomic(storage.sourcePath(), loaded.pack());
			return;
		}
		writeManifest(loaded);
		writeJsonAtomic(storage.profilesWritePath(), AmbienceProfilesFile.fromPack(loaded.pack()));
		writeRegions(loaded);
	}

	private void writeZipJson(ZipOutputStream zip, String name, Object value) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		byte[] bytes = (PRETTY_GSON.toJson(value) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
		zip.write(bytes);
		zip.closeEntry();
	}

	private void writeJsonAtomic(Path target, Object value) throws IOException {
		Files.createDirectories(target.getParent());
		Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
		try {
			Files.writeString(temp, PRETTY_GSON.toJson(value) + System.lineSeparator(), StandardCharsets.UTF_8);
			try {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicMoveFailure) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private void copyDirectory(Path source, Path destination) throws IOException {
		try (var paths = Files.walk(source)) {
			for (Path path : paths.toList()) {
				Path relative = source.relativize(path);
				Path target = destination.resolve(relative);
				if (Files.isDirectory(path)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private LoadedAmbiencePack requirePack(String selectedPackId) throws IOException {
		Optional<LoadedAmbiencePack> loadedPack = getPack(selectedPackId);
		if (loadedPack.isEmpty()) {
			throw new IOException("Ambience pack is not installed: " + selectedPackId);
		}
		return loadedPack.get();
	}

	private LoadedAmbiencePack requireWritableProfiles(String selectedPackId) throws IOException {
		LoadedAmbiencePack loaded = requirePack(selectedPackId);
		if (loaded.storage() == null || !loaded.storage().canWriteProfiles()) {
			throw new IOException("Ambience pack does not have a writable profiles file: " + selectedPackId);
		}
		return loaded;
	}

	private AmbienceProfile requireProfile(LoadedAmbiencePack loaded, String profileId) throws IOException {
		AmbienceProfile profile = loaded.profilesById().get(profileId);
		if (profile == null) {
			throw new IOException("Ambience profile is not present: " + profileId);
		}
		return profile;
	}

	private void mergeDependency(AmbiencePack pack, AmbienceDependency dependency) {
		if (dependency == null || dependency.id == null || dependency.id.isBlank()) {
			return;
		}
		if (dependency.localNames == null) {
			dependency.localNames = new ArrayList<>();
		}
		Map<String, AmbienceDependency> dependencies = pack.dependenciesById();
		AmbienceDependency existing = dependencies.get(dependency.id);
		if (existing != null) {
			if (existing.localNames == null) {
				existing.localNames = new ArrayList<>();
			}
			for (String localName : dependency.localNames) {
				if (localName != null && !localName.isBlank() && !existing.localNames.contains(localName)) {
					existing.localNames.add(localName);
				}
			}
			if ("modrinth".equalsIgnoreCase(dependency.type)) {
				existing.type = dependency.type;
				existing.projectId = dependency.projectId;
				existing.versionId = dependency.versionId;
				existing.fileSha512 = dependency.fileSha512;
			}
			return;
		}
		if (pack.dependencies == null) {
			pack.dependencies = new ArrayList<>();
		}
		pack.dependencies.add(dependency);
	}

	private Map<String, String> filterOptionsForShaderPack(String shaderPackName, Map<String, String> options) {
		if (options == null || options.isEmpty()) {
			return new LinkedHashMap<>();
		}
		try (Iris.LoadedShaderPackOptions loadedOptions = Iris.loadShaderPackForOptionEditing(shaderPackName, Map.of())) {
			OptionSet optionSet = loadedOptions.pack().getShaderPackOptions().getOptionSet();
			Set<String> allowed = new LinkedHashSet<>();
			allowed.addAll(optionSet.getStringOptions().keySet());
			allowed.addAll(optionSet.getBooleanOptions().keySet());
			Map<String, String> filtered = new LinkedHashMap<>();
			options.entrySet().stream()
				.filter(entry -> allowed.contains(entry.getKey()) && entry.getValue() != null)
				.sorted(Map.Entry.comparingByKey())
				.forEach(entry -> filtered.put(entry.getKey(), entry.getValue()));
			return filtered;
		} catch (IOException | RuntimeException e) {
			Iris.logger.warn("Failed to filter ambience profile options for shader pack {}", shaderPackName, e);
			return new LinkedHashMap<>();
		}
	}

	private String uniqueProfileId(LoadedAmbiencePack loaded, String requestedId) {
		String base = requestedId == null || requestedId.isBlank() ? "new_preset" : requestedId;
		String candidate = base;
		int suffix = 2;
		while (loaded.profilesById().containsKey(candidate)) {
			candidate = base + "_" + suffix++;
		}
		return candidate;
	}

	private boolean safeEquals(String first, String second) {
		if (first == null) {
			return second == null;
		}
		return first.equals(second);
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

	public enum PackSourceKind {
		LEGACY_JSON,
		DIRECTORY,
		ZIP
	}

	public record PackStorage(PackSourceKind kind, Path sourcePath, Path manifestWritePath, Path profilesWritePath, Path regionsWritePath) {
		private static PackStorage legacy(Path path) {
			return new PackStorage(PackSourceKind.LEGACY_JSON, path, path, path, path);
		}

		private static PackStorage directory(Path path) {
			return new PackStorage(PackSourceKind.DIRECTORY, path, path.resolve(MANIFEST_FILE), path.resolve(PROFILES_FILE), path.resolve(REGIONS_FILE));
		}

		private static PackStorage zip(Path path, Path overrideDirectory) {
			return new PackStorage(PackSourceKind.ZIP, path, overrideDirectory.resolve(MANIFEST_FILE), overrideDirectory.resolve(PROFILES_FILE), overrideDirectory.resolve(REGIONS_FILE));
		}

		public boolean canWriteManifest() {
			return manifestWritePath != null;
		}

		public boolean canWriteProfiles() {
			return profilesWritePath != null;
		}

		public boolean canWriteRegions() {
			return regionsWritePath != null;
		}
	}

	public record LoadedAmbiencePack(AmbiencePack pack, PackStorage storage, Map<String, AmbienceProfile> profilesById,
									 Map<String, AmbienceDependency> dependenciesById) {
		public Path path() {
			return storage == null ? null : storage.sourcePath();
		}
	}

	public record ResolvedProfile(LoadedAmbiencePack loadedPack, String profileId, AmbienceProfile profile,
								  String resolvedShaderPack, String regionId) {
		public String key() {
			return loadedPack.pack().id + ":" + profileId + ":" + profile.cacheKey(resolvedShaderPack);
		}
	}
}
