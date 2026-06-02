package net.irisshaders.iris.gui.screen;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.NativeImage;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

final class AmbienceMapTileLoader implements AutoCloseable {
	private static final Gson GSON = new Gson();
	private static final String MAPS_URL = "https://cdn.wynntils.com/static/Reference/maps.json";
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private static final Pattern MAP_HASH_PATTERN = Pattern.compile("[0-9a-fA-F]{32}");

	private final Minecraft minecraft;
	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
	private final ExecutorService loaderExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "WynnIris Ambience Map Loader");
		thread.setDaemon(true);
		return thread;
	});
	private final List<Tile> tiles = new CopyOnWriteArrayList<>();
	private volatile Bounds bounds = Bounds.empty();
	private volatile Component status = Component.translatable("options.iris.wynncraftAmbienceRegionMapLoading");
	private volatile boolean started;
	private volatile boolean done;
	private volatile boolean closed;
	private volatile int loadedCount;

	AmbienceMapTileLoader(Minecraft minecraft) {
		this.minecraft = minecraft;
	}

	void start() {
		if (started) {
			return;
		}
		started = true;
		CompletableFuture.runAsync(this::load, loaderExecutor);
	}

	List<Tile> tiles() {
		return List.copyOf(tiles);
	}

	Bounds bounds() {
		return bounds;
	}

	Component status() {
		if (done) {
			return Component.translatable("options.iris.wynncraftAmbienceRegionMapLoaded", loadedCount, tiles.size());
		}
		return status;
	}

	private void load() {
		try {
			Path cacheDirectory = mapCacheDirectory();
			Files.createDirectories(cacheDirectory);
			MapPart[] parts = readMapParts(cacheDirectory.resolve("maps.json"));
			List<MapPart> mainParts = new ArrayList<>();
			for (MapPart part : parts) {
				if (part != null && part.name != null && part.name.startsWith("Main ")) {
					mainParts.add(part);
				}
			}
			mainParts.sort(Comparator.comparingInt((MapPart part) -> part.z1).thenComparingInt(part -> part.x1));
			tiles.clear();
			for (MapPart part : mainParts) {
				tiles.add(new Tile(part));
			}
			bounds = Bounds.of(mainParts);
			status = Component.translatable("options.iris.wynncraftAmbienceRegionMapLoadingTiles", 0, tiles.size());

			for (Tile tile : tiles) {
				if (closed) {
					break;
				}
				Path imagePath = resolveTileImage(cacheDirectory, tile.part);
				if (imagePath == null) {
					continue;
				}
				try (InputStream input = Files.newInputStream(imagePath)) {
					NativeImage image = NativeImage.read(input);
					register(tile, image);
				} catch (IOException e) {
					status = Component.literal("Failed to load map tile " + tile.part.name);
				}
			}
			done = true;
		} catch (Exception e) {
			status = Component.literal("Map unavailable: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
			done = true;
		}
	}

	private MapPart[] readMapParts(Path cachedMapsJson) throws IOException, InterruptedException {
		if (!Files.exists(cachedMapsJson)) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(MAPS_URL))
				.timeout(REQUEST_TIMEOUT)
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("HTTP " + response.statusCode());
			}
			Path temp = Files.createTempFile(cachedMapsJson.getParent(), "maps", ".json.tmp");
			try {
				Files.writeString(temp, response.body(), StandardCharsets.UTF_8);
				try {
					Files.move(temp, cachedMapsJson, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException atomicMoveFailure) {
					Files.move(temp, cachedMapsJson, StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(temp);
			}
		}
		try (var reader = Files.newBufferedReader(cachedMapsJson, StandardCharsets.UTF_8)) {
			MapPart[] parts = GSON.fromJson(reader, MapPart[].class);
			return parts == null ? new MapPart[0] : parts;
		}
	}

	private Path resolveTileImage(Path cacheDirectory, MapPart part) throws IOException, InterruptedException {
		String hash = normalizedMapHash(part.md5);
		if (hash == null) {
			return null;
		}
		Path cached = cacheDirectory.resolve(hash + ".png");
		if (Files.exists(cached)) {
			return cached;
		}
		Path wynntilsCached = wynntilsMapCacheDirectory().resolve(hash + ".png");
		if (Files.exists(wynntilsCached)) {
			return wynntilsCached;
		}
		if (part.url == null || part.url.isBlank()) {
			return null;
		}

		HttpRequest request = HttpRequest.newBuilder(URI.create(part.url))
			.timeout(REQUEST_TIMEOUT)
			.GET()
			.build();
		HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("HTTP " + response.statusCode());
		}
		Path temp = Files.createTempFile(cacheDirectory, hash, ".png.tmp");
		try (InputStream input = response.body()) {
			Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicMoveFailure) {
				Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
		return cached;
	}

	private void register(Tile tile, NativeImage image) {
		minecraft.execute(() -> {
			if (closed) {
				image.close();
				return;
			}
			String hash = normalizedMapHash(tile.part.md5);
			if (hash == null) {
				image.close();
				return;
			}
			Identifier id = Identifier.fromNamespaceAndPath("iris", "wynniris/ambience_map/" + hash);
			DynamicTexture texture = new DynamicTexture(() -> "wynniris_ambience_map_" + hash, image);
			minecraft.getTextureManager().register(id, texture);
			tile.identifier = id;
			tile.textureWidth = image.getWidth();
			tile.textureHeight = image.getHeight();
			loadedCount++;
			status = Component.translatable("options.iris.wynncraftAmbienceRegionMapLoadingTiles", loadedCount, tiles.size());
		});
	}

	private String normalizedMapHash(String hash) {
		if (hash == null) {
			return null;
		}
		String normalized = hash.trim().toLowerCase(java.util.Locale.ROOT);
		return MAP_HASH_PATTERN.matcher(normalized).matches() ? normalized : null;
	}

	private Path mapCacheDirectory() {
		return IrisPlatformHelpers.getInstance().getConfigDir().resolve("wynniris").resolve("map-cache");
	}

	private Path wynntilsMapCacheDirectory() {
		Path configDirectory = IrisPlatformHelpers.getInstance().getConfigDir();
		Path gameDirectory = configDirectory.getParent();
		return gameDirectory == null ? configDirectory.resolve("wynntils").resolve("cache").resolve("maps") : gameDirectory.resolve("wynntils").resolve("cache").resolve("maps");
	}

	@Override
	public void close() {
		closed = true;
		httpClient.shutdownNow();
		loaderExecutor.shutdownNow();
		minecraft.execute(() -> {
			for (Tile tile : tiles) {
				if (tile.identifier != null) {
					minecraft.getTextureManager().release(tile.identifier);
					tile.identifier = null;
				}
			}
		});
	}

	static final class Tile {
		private final MapPart part;
		private volatile Identifier identifier;
		private volatile int textureWidth;
		private volatile int textureHeight;

		private Tile(MapPart part) {
			this.part = part;
		}

		boolean ready() {
			return identifier != null;
		}

		Identifier identifier() {
			return identifier;
		}

		int x1() {
			return part.x1;
		}

		int z1() {
			return part.z1;
		}

		int x2() {
			return part.x2;
		}

		int z2() {
			return part.z2;
		}

		int textureWidth() {
			return textureWidth;
		}

		int textureHeight() {
			return textureHeight;
		}
	}

	static final class Bounds {
		private final double minX;
		private final double minZ;
		private final double maxX;
		private final double maxZ;

		private Bounds(double minX, double minZ, double maxX, double maxZ) {
			this.minX = minX;
			this.minZ = minZ;
			this.maxX = maxX;
			this.maxZ = maxZ;
		}

		static Bounds empty() {
			return new Bounds(-2560, -6144, 2047, -1);
		}

		static Bounds of(List<MapPart> parts) {
			if (parts.isEmpty()) {
				return empty();
			}
			double minX = Double.POSITIVE_INFINITY;
			double minZ = Double.POSITIVE_INFINITY;
			double maxX = Double.NEGATIVE_INFINITY;
			double maxZ = Double.NEGATIVE_INFINITY;
			for (MapPart part : parts) {
				minX = Math.min(minX, part.x1);
				minZ = Math.min(minZ, part.z1);
				maxX = Math.max(maxX, part.x2);
				maxZ = Math.max(maxZ, part.z2);
			}
			return new Bounds(minX, minZ, maxX, maxZ);
		}

		double minX() {
			return minX;
		}

		double minZ() {
			return minZ;
		}

		double maxX() {
			return maxX;
		}

		double maxZ() {
			return maxZ;
		}

		double width() {
			return maxX - minX + 1;
		}

		double height() {
			return maxZ - minZ + 1;
		}
	}

	private static final class MapPart {
		private String name;
		private String url;
		private int x1;
		private int z1;
		private int x2;
		private int z2;
		private String md5;
	}
}
