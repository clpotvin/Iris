package net.irisshaders.iris.ambience;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gui.option.WynncraftDebugLog;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.targets.RenderTarget;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class AmbienceRenderTargetPool {
	private final Map<ColorTargetKey, Entry<RenderTarget>> mainColorTargets = new HashMap<>();
	private final Map<DepthCopiesKey, Entry<DepthCopies>> mainDepthCopies = new HashMap<>();
	private final Map<ShadowDepthKey, Entry<DepthCopies>> shadowDepthCopies = new HashMap<>();
	private final Map<ShadowColorTargetKey, Entry<RenderTarget>> shadowColorTargets = new HashMap<>();
	private final Map<ImageKey, Entry<GlImage>> customImages = new HashMap<>();
	private final Set<Allocation> allocations = Collections.newSetFromMap(new IdentityHashMap<>());
	private long estimatedBytes;
	private long hits;
	private long misses;
	private long releases;
	private long destroyedResources;
	private boolean destroyed;

	public Allocation createAllocation(String profileKey) {
		requireOpen();
		Allocation allocation = new Allocation(profileKey);
		allocations.add(allocation);
		return allocation;
	}

	public AcquiredRenderTarget acquireMainColorTarget(Allocation allocation, int index, int width, int height, InternalTextureFormat internalFormat, PixelFormat pixelFormat) {
		requireOpen();
		requireAllocation(allocation);
		// Keyed by (index, format) WITHOUT size: a window resize must reuse the same physical target and resize it
		// IN PLACE, keeping its GL texture ids stable. Consumers that cache those ids across frames -- framebuffer
		// attachments, and especially Voxy's LOD draw-target ids -- then stay valid, exactly like a non-pooled
		// RenderTarget. Putting size in the key instead made a resize allocate a NEW texture id, leaving Voxy bound
		// to the dead one (white sky + terrain smear that even a profile switch couldn't recover).
		ColorTargetKey key = new ColorTargetKey(index, internalFormat, pixelFormat);
		Entry<RenderTarget> entry = mainColorTargets.get(key);
		if (entry != null) {
			RenderTarget physical = entry.value;
			if (physical.getWidth() != width || physical.getHeight() != height) {
				long newBytes = 2L * width * height * bytesPerPixel(internalFormat);
				estimatedBytes += newBytes - entry.bytes;
				entry.bytes = newBytes;
				physical.resize(width, height);
			}
			hits++;
			return new AcquiredRenderTarget(physical.sharedView(), allocation.retain(entry));
		}

		misses++;
		RenderTarget physical = RenderTarget.builder()
			.setName("ambience-colortex" + index)
			.setDimensions(width, height)
			.setInternalFormat(internalFormat)
			.setPixelFormat(pixelFormat)
			.build();
		long bytes = 2L * width * height * bytesPerPixel(internalFormat);
		entry = new Entry<>(ResourceType.MAIN_COLOR, physical, bytes, physical::destroy, () -> mainColorTargets.remove(key));
		mainColorTargets.put(key, entry);
		estimatedBytes += bytes;
		logPoolStats("ambience-pool-main-color");
		return new AcquiredRenderTarget(physical.sharedView(), allocation.retain(entry));
	}

	public AcquiredDepthCopies acquireMainDepthCopies(Allocation allocation, int width, int height, TextureFormat format) {
		requireOpen();
		requireAllocation(allocation);
		DepthCopiesKey key = new DepthCopiesKey(width, height, format);
		Entry<DepthCopies> entry = mainDepthCopies.get(key);
		if (entry != null) {
			hits++;
			return new AcquiredDepthCopies(entry.value, allocation.retain(entry));
		}

		misses++;
		DepthCopies physical = new DepthCopies(
			RenderSystem.getDevice().createTexture("Ambience Depth / Opaque", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, format, width, height, 1, 1),
			RenderSystem.getDevice().createTexture("Ambience Depth / Before Hand", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, format, width, height, 1, 1)
		);
		long bytes = 2L * width * height * 4L;
		entry = new Entry<>(ResourceType.MAIN_DEPTH, physical, bytes, physical::destroy, () -> mainDepthCopies.remove(key));
		mainDepthCopies.put(key, entry);
		estimatedBytes += bytes;
		logPoolStats("ambience-pool-main-depth");
		return new AcquiredDepthCopies(physical, allocation.retain(entry));
	}

	public AcquiredDepthCopies acquireShadowDepthCopies(Allocation allocation, int resolution, boolean mainMipped, boolean noTranslucentsMipped) {
		requireOpen();
		requireAllocation(allocation);
		ShadowDepthKey key = new ShadowDepthKey(resolution, mainMipped, noTranslucentsMipped);
		Entry<DepthCopies> entry = shadowDepthCopies.get(key);
		if (entry != null) {
			hits++;
			return new AcquiredDepthCopies(entry.value, allocation.retain(entry));
		}

		misses++;
		DepthCopies physical = new DepthCopies(
			RenderSystem.getDevice().createTexture("Ambience Shadow Map", GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.DEPTH32, resolution, resolution, 1, mainMipped ? shadowMipLevels(resolution) : 1),
			RenderSystem.getDevice().createTexture("Ambience Shadow Map / Opaque", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.DEPTH32, resolution, resolution, 1, noTranslucentsMipped ? shadowMipLevels(resolution) : 1)
		);
		long bytes = 2L * resolution * resolution * 4L;
		entry = new Entry<>(ResourceType.SHADOW_DEPTH, physical, bytes, physical::destroy, () -> shadowDepthCopies.remove(key));
		shadowDepthCopies.put(key, entry);
		estimatedBytes += bytes;
		logPoolStats("ambience-pool-shadow-depth");
		return new AcquiredDepthCopies(physical, allocation.retain(entry));
	}

	public AcquiredRenderTarget acquireShadowColorTarget(Allocation allocation, int index, int resolution, InternalTextureFormat internalFormat, PixelFormat pixelFormat) {
		requireOpen();
		requireAllocation(allocation);
		ShadowColorTargetKey key = new ShadowColorTargetKey(index, resolution, internalFormat, pixelFormat);
		Entry<RenderTarget> entry = shadowColorTargets.get(key);
		if (entry != null) {
			hits++;
			return new AcquiredRenderTarget(entry.value.sharedView(), allocation.retain(entry));
		}

		misses++;
		RenderTarget physical = RenderTarget.builder()
			.setName("ambience-shadowcolor" + index)
			.setDimensions(resolution, resolution)
			.setInternalFormat(internalFormat)
			.setPixelFormat(pixelFormat)
			.build();
		long bytes = 2L * resolution * resolution * bytesPerPixel(internalFormat);
		entry = new Entry<>(ResourceType.SHADOW_COLOR, physical, bytes, physical::destroy, () -> shadowColorTargets.remove(key));
		shadowColorTargets.put(key, entry);
		estimatedBytes += bytes;
		logPoolStats("ambience-pool-shadow-color");
		return new AcquiredRenderTarget(physical.sharedView(), allocation.retain(entry));
	}

	public AcquiredImage acquireCustomImage(Allocation allocation, ImageInformation information) {
		requireOpen();
		requireAllocation(allocation);
		ImageKey key = new ImageKey(information.name(), information.samplerName(), information.target(), information.format(),
			information.internalTextureFormat(), information.type(), information.width(), information.height(),
			information.depth(), information.clear());
		Entry<GlImage> entry = customImages.get(key);
		if (entry != null) {
			hits++;
			return new AcquiredImage(entry.value.sharedView(), allocation.retain(entry));
		}

		misses++;
		GlImage physical = new GlImage(information.name(), information.samplerName(), information.target(), information.format(),
			information.internalTextureFormat(), information.type(), information.clear(), information.width(),
			information.height(), information.depth());
		long bytes = (long) Math.max(1, information.width()) * Math.max(1, information.height()) *
			Math.max(1, information.depth()) * bytesPerPixel(information.internalTextureFormat());
		entry = new Entry<>(ResourceType.CUSTOM_IMAGE, physical, bytes, physical::destroy, () -> customImages.remove(key));
		customImages.put(key, entry);
		estimatedBytes += bytes;
		logPoolStats("ambience-pool-custom-image");
		return new AcquiredImage(physical.sharedView(), allocation.retain(entry));
	}

	public void destroy() {
		if (destroyed) {
			return;
		}
		destroyed = true;
		allocations.clear();

		mainColorTargets.values().forEach(Entry::destroy);
		shadowColorTargets.values().forEach(Entry::destroy);
		customImages.values().forEach(Entry::destroy);
		mainDepthCopies.values().forEach(Entry::destroy);
		shadowDepthCopies.values().forEach(Entry::destroy);

		mainColorTargets.clear();
		mainDepthCopies.clear();
		shadowDepthCopies.clear();
		shadowColorTargets.clear();
		customImages.clear();
		estimatedBytes = 0;
		hits = 0;
		misses = 0;
		releases = 0;
		destroyedResources = 0;
	}

	public int getResourceCount() {
		return mainColorTargets.size() + mainDepthCopies.size() + shadowDepthCopies.size() + shadowColorTargets.size() + customImages.size();
	}

	public long getEstimatedBytes() {
		return estimatedBytes;
	}

	public long getHits() {
		return hits;
	}

	public long getMisses() {
		return misses;
	}

	public long getReleases() {
		return releases;
	}

	public long getDestroyedResources() {
		return destroyedResources;
	}

	public PoolBreakdown getBreakdown() {
		return new PoolBreakdown(
			summarize(mainColorTargets.values()),
			summarize(mainDepthCopies.values()),
			summarize(shadowDepthCopies.values()),
			summarize(shadowColorTargets.values()),
			summarize(customImages.values())
		);
	}

	public Map<String, ProfilePressure> getProfilePressures() {
		Map<String, Set<Entry<?>>> entriesByProfile = new LinkedHashMap<>();
		Map<String, Integer> allocationsByProfile = new LinkedHashMap<>();

		for (Allocation allocation : allocations) {
			if (allocation.closed) {
				continue;
			}
			entriesByProfile.computeIfAbsent(allocation.profileKey, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
				.addAll(allocation.entries.keySet());
			allocationsByProfile.merge(allocation.profileKey, 1, Integer::sum);
		}

		Map<String, ProfilePressure> pressures = new LinkedHashMap<>();
		for (Map.Entry<String, Set<Entry<?>>> profileEntry : entriesByProfile.entrySet()) {
			String profileKey = profileEntry.getKey();
			long sharedBytes = 0;
			long exclusiveBytes = 0;
			BreakdownBuilder sharedBreakdown = new BreakdownBuilder();
			BreakdownBuilder exclusiveBreakdown = new BreakdownBuilder();
			for (Entry<?> entry : profileEntry.getValue()) {
				sharedBytes += entry.bytes;
				sharedBreakdown.add(entry);
				if (isEntryExclusiveToProfile(entry, profileKey)) {
					exclusiveBytes += entry.bytes;
					exclusiveBreakdown.add(entry);
				}
			}
			pressures.put(profileKey, new ProfilePressure(profileKey, allocationsByProfile.getOrDefault(profileKey, 0),
				profileEntry.getValue().size(), sharedBytes, exclusiveBytes, sharedBreakdown.build(), exclusiveBreakdown.build()));
		}

		return Collections.unmodifiableMap(pressures);
	}

	private ResourceBreakdown summarize(Iterable<? extends Entry<?>> entries) {
		int count = 0;
		long bytes = 0;
		for (Entry<?> entry : entries) {
			count++;
			bytes += entry.bytes;
		}
		return new ResourceBreakdown(count, bytes);
	}

	private void requireOpen() {
		if (destroyed) {
			throw new IllegalStateException("Ambience render target pool has already been destroyed");
		}
	}

	private void requireAllocation(Allocation allocation) {
		if (allocation == null) {
			throw new IllegalArgumentException("Ambience render target pool acquisition requires an allocation owner");
		}
	}

	private void release(Entry<?> entry) {
		if (destroyed || entry.entryDestroyed) {
			return;
		}

		entry.references--;
		releases++;

		if (entry.references < 0) {
			throw new IllegalStateException("Ambience render target pool resource released too many times");
		}
		if (entry.references == 0) {
			entry.destroy();
			entry.remove.run();
			estimatedBytes = Math.max(0L, estimatedBytes - entry.bytes);
			destroyedResources++;
			logPoolStats("ambience-pool-release");
		}
	}

	private boolean isEntryExclusiveToProfile(Entry<?> entry, String profileKey) {
		for (Allocation allocation : allocations) {
			if (!allocation.closed && !allocation.profileKey.equals(profileKey) && allocation.entries.containsKey(entry)) {
				return false;
			}
		}
		return true;
	}

	private void logPoolStats(String key) {
		if (!WynncraftDebugLog.shouldLog(key)) {
			return;
		}
		WynncraftDebugLog.info(key,
			"Ambience render target pool: resources={} estimatedBytes={} hits={} misses={} releases={} destroyed={} breakdown={}",
			getResourceCount(), estimatedBytes, hits, misses, releases, destroyedResources, getBreakdown().compact());
	}

	private static int shadowMipLevels(int resolution) {
		return (int) Math.floor(Math.log(resolution) / Math.log(2.0));
	}

	private static int bytesPerPixel(InternalTextureFormat format) {
		return switch (format) {
			case R8, R8_SNORM, R8I, R8UI, R3_G3_B2, RGBA2 -> 1;
			case RG8, RG8_SNORM, RG8I, RG8UI, R16, R16_SNORM, R16F, R16I, R16UI, RGBA4, RGB5_A1, RGB565 -> 2;
			case RGB8, RGB8_SNORM, RGB8I, RGB8UI -> 3;
			case RG16, RG16_SNORM, RG16F, RG16I, RG16UI, R32F, R32I, R32UI, RGBA8, RGBA, RGBA8_SNORM, RGBA8I, RGBA8UI, RGB10_A2, RGB10_A2UI, R11F_G11F_B10F, RGB9_E5 -> 4;
			case RGB16, RGB16_SNORM, RGB16F, RGB16I, RGB16UI -> 6;
			case RG32F, RG32I, RG32UI, RGBA16, RGBA16_SNORM, RGBA16F, RGBA16I, RGBA16UI -> 8;
			case RGB32F, RGB32I, RGB32UI -> 12;
			case RGBA32F, RGBA32I, RGBA32UI -> 16;
		};
	}

	private enum ResourceType {
		MAIN_COLOR,
		MAIN_DEPTH,
		SHADOW_DEPTH,
		SHADOW_COLOR,
		CUSTOM_IMAGE
	}

	private final class BreakdownBuilder {
		private int mainColorCount;
		private long mainColorBytes;
		private int mainDepthCount;
		private long mainDepthBytes;
		private int shadowDepthCount;
		private long shadowDepthBytes;
		private int shadowColorCount;
		private long shadowColorBytes;
		private int customImageCount;
		private long customImageBytes;

		private void add(Entry<?> entry) {
			switch (entry.type) {
				case MAIN_COLOR -> {
					mainColorCount++;
					mainColorBytes += entry.bytes;
				}
				case MAIN_DEPTH -> {
					mainDepthCount++;
					mainDepthBytes += entry.bytes;
				}
				case SHADOW_DEPTH -> {
					shadowDepthCount++;
					shadowDepthBytes += entry.bytes;
				}
				case SHADOW_COLOR -> {
					shadowColorCount++;
					shadowColorBytes += entry.bytes;
				}
				case CUSTOM_IMAGE -> {
					customImageCount++;
					customImageBytes += entry.bytes;
				}
			}
		}

		private PoolBreakdown build() {
			return new PoolBreakdown(
				new ResourceBreakdown(mainColorCount, mainColorBytes),
				new ResourceBreakdown(mainDepthCount, mainDepthBytes),
				new ResourceBreakdown(shadowDepthCount, shadowDepthBytes),
				new ResourceBreakdown(shadowColorCount, shadowColorBytes),
				new ResourceBreakdown(customImageCount, customImageBytes)
			);
		}
	}

	public final class Allocation implements AutoCloseable {
		private final String profileKey;
		private final Map<Entry<?>, Integer> entries = new IdentityHashMap<>();
		private boolean closed;

		private Allocation(String profileKey) {
			this.profileKey = profileKey == null || profileKey.isBlank() ? "unknown" : profileKey;
		}

		private ResourceRef retain(Entry<?> entry) {
			if (closed) {
				throw new IllegalStateException("Ambience render target pool allocation has already been closed");
			}
			AmbienceRenderTargetPool.this.requireOpen();

			int count = entries.getOrDefault(entry, 0);
			entries.put(entry, count + 1);
			if (count == 0) {
				entry.references++;
			}
			return new ResourceRef(this, entry);
		}

		private void release(Entry<?> entry) {
			Integer count = entries.get(entry);
			if (count == null) {
				if (closed) {
					return;
				}
				throw new IllegalStateException("Ambience render target pool allocation released an unknown resource");
			}
			if (count > 1) {
				entries.put(entry, count - 1);
				return;
			}

			entries.remove(entry);
			AmbienceRenderTargetPool.this.release(entry);
		}

		public ProfilePressure pressure() {
			long sharedBytes = 0;
			long exclusiveBytes = 0;
			BreakdownBuilder sharedBreakdown = new BreakdownBuilder();
			BreakdownBuilder exclusiveBreakdown = new BreakdownBuilder();
			for (Entry<?> entry : entries.keySet()) {
				sharedBytes += entry.bytes;
				sharedBreakdown.add(entry);
				if (entry.references == 1) {
					exclusiveBytes += entry.bytes;
					exclusiveBreakdown.add(entry);
				}
			}
			return new ProfilePressure(profileKey, 1, entries.size(), sharedBytes, exclusiveBytes, sharedBreakdown.build(), exclusiveBreakdown.build());
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			closed = true;
			allocations.remove(this);

			for (Entry<?> entry : entries.keySet().toArray(new Entry<?>[0])) {
				entries.remove(entry);
				AmbienceRenderTargetPool.this.release(entry);
			}
		}
	}

	public static final class ResourceRef implements AutoCloseable {
		private final Allocation allocation;
		private final Entry<?> entry;
		private boolean closed;

		private ResourceRef(Allocation allocation, Entry<?> entry) {
			this.allocation = allocation;
			this.entry = entry;
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			closed = true;
			allocation.release(entry);
		}
	}

	private final class Entry<T> {
		private final ResourceType type;
		private final T value;
		// Mutable: a pooled main-color target can be resized in place on a window resize, which changes its size.
		private long bytes;
		private final Runnable destroy;
		private final Runnable remove;
		private int references;
		private boolean entryDestroyed;

		private Entry(ResourceType type, T value, long bytes, Runnable destroy, Runnable remove) {
			this.type = type;
			this.value = value;
			this.bytes = bytes;
			this.destroy = destroy;
			this.remove = remove;
		}

		private void destroy() {
			if (entryDestroyed) {
				return;
			}
			entryDestroyed = true;
			destroy.run();
		}
	}

	public record AcquiredRenderTarget(RenderTarget target, ResourceRef ref) {
	}

	public record AcquiredDepthCopies(DepthCopies copies, ResourceRef ref) {
	}

	public record AcquiredImage(GlImage image, ResourceRef ref) {
	}

	public record ResourceBreakdown(int count, long bytes) {
		public String compact() {
			return count + "/" + bytes;
		}
	}

	public record PoolBreakdown(ResourceBreakdown mainColor, ResourceBreakdown mainDepth,
								ResourceBreakdown shadowDepth, ResourceBreakdown shadowColor,
								ResourceBreakdown customImages) {
		public String compact() {
			return "mainColor=" + mainColor.compact()
				+ "|mainDepth=" + mainDepth.compact()
				+ "|shadowDepth=" + shadowDepth.compact()
				+ "|shadowColor=" + shadowColor.compact()
				+ "|customImages=" + customImages.compact();
		}
	}

	public record ProfilePressure(String profileKey, int allocations, int resources, long sharedBytes, long exclusiveBytes,
								  PoolBreakdown sharedBreakdown, PoolBreakdown exclusiveBreakdown) {
	}

	public record DepthCopies(GpuTexture first, GpuTexture second) {
		private void destroy() {
			first.close();
			second.close();
		}
	}

	private record ColorTargetKey(int index, InternalTextureFormat internalFormat, PixelFormat pixelFormat) {
	}

	private record DepthCopiesKey(int width, int height, TextureFormat format) {
	}

	private record ShadowDepthKey(int resolution, boolean mainMipped, boolean noTranslucentsMipped) {
	}

	private record ShadowColorTargetKey(int index, int resolution, InternalTextureFormat internalFormat, PixelFormat pixelFormat) {
	}

	private record ImageKey(String name, String samplerName, TextureType target, PixelFormat format,
							InternalTextureFormat internalTextureFormat, PixelType pixelType, int width, int height,
							int depth, boolean clear) {
	}
}
