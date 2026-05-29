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

import java.util.HashMap;
import java.util.Map;

public final class AmbienceRenderTargetPool {
	private final Map<ColorTargetKey, RenderTarget> mainColorTargets = new HashMap<>();
	private final Map<DepthCopiesKey, DepthCopies> mainDepthCopies = new HashMap<>();
	private final Map<ShadowDepthKey, DepthCopies> shadowDepthCopies = new HashMap<>();
	private final Map<ShadowColorTargetKey, RenderTarget> shadowColorTargets = new HashMap<>();
	private final Map<ImageKey, GlImage> customImages = new HashMap<>();
	private long estimatedBytes;
	private long hits;
	private long misses;
	private boolean destroyed;

	public RenderTarget acquireMainColorTarget(int index, int width, int height, InternalTextureFormat internalFormat, PixelFormat pixelFormat) {
		ColorTargetKey key = new ColorTargetKey(index, width, height, internalFormat, pixelFormat);
		RenderTarget physical = mainColorTargets.get(key);
		if (physical != null) {
			hits++;
			return physical.sharedView();
		}

		misses++;
		physical = RenderTarget.builder()
			.setName("ambience-colortex" + index)
			.setDimensions(width, height)
			.setInternalFormat(internalFormat)
			.setPixelFormat(pixelFormat)
			.build();
		mainColorTargets.put(key, physical);
		estimatedBytes += 2L * width * height * bytesPerPixel(internalFormat);
		// TODO: Evict orphaned exact-size entries after window resize once pooled views are ref-counted.
		logPoolStats("ambience-pool-main-color");
		return physical.sharedView();
	}

	public DepthCopies acquireMainDepthCopies(int width, int height, TextureFormat format) {
		DepthCopiesKey key = new DepthCopiesKey(width, height, format);
		DepthCopies physical = mainDepthCopies.get(key);
		if (physical != null) {
			hits++;
			return physical;
		}

		misses++;
		physical = new DepthCopies(
			RenderSystem.getDevice().createTexture("Ambience Depth / Opaque", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, format, width, height, 1, 1),
			RenderSystem.getDevice().createTexture("Ambience Depth / Before Hand", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, format, width, height, 1, 1)
		);
		mainDepthCopies.put(key, physical);
		estimatedBytes += 2L * width * height * 4L;
		logPoolStats("ambience-pool-main-depth");
		return physical;
	}

	public DepthCopies acquireShadowDepthCopies(int resolution, boolean mainMipped, boolean noTranslucentsMipped) {
		ShadowDepthKey key = new ShadowDepthKey(resolution, mainMipped, noTranslucentsMipped);
		DepthCopies physical = shadowDepthCopies.get(key);
		if (physical != null) {
			hits++;
			return physical;
		}

		misses++;
		physical = new DepthCopies(
			RenderSystem.getDevice().createTexture("Ambience Shadow Map", GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.DEPTH32, resolution, resolution, 1, mainMipped ? shadowMipLevels(resolution) : 1),
			RenderSystem.getDevice().createTexture("Ambience Shadow Map / Opaque", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.DEPTH32, resolution, resolution, 1, noTranslucentsMipped ? shadowMipLevels(resolution) : 1)
		);
		shadowDepthCopies.put(key, physical);
		estimatedBytes += 2L * resolution * resolution * 4L;
		logPoolStats("ambience-pool-shadow-depth");
		return physical;
	}

	public RenderTarget acquireShadowColorTarget(int index, int resolution, InternalTextureFormat internalFormat, PixelFormat pixelFormat) {
		ShadowColorTargetKey key = new ShadowColorTargetKey(index, resolution, internalFormat, pixelFormat);
		RenderTarget physical = shadowColorTargets.get(key);
		if (physical != null) {
			hits++;
			return physical.sharedView();
		}

		misses++;
		physical = RenderTarget.builder()
			.setName("ambience-shadowcolor" + index)
			.setDimensions(resolution, resolution)
			.setInternalFormat(internalFormat)
			.setPixelFormat(pixelFormat)
			.build();
		shadowColorTargets.put(key, physical);
		estimatedBytes += 2L * resolution * resolution * bytesPerPixel(internalFormat);
		logPoolStats("ambience-pool-shadow-color");
		return physical.sharedView();
	}

	public GlImage acquireCustomImage(ImageInformation information) {
		ImageKey key = new ImageKey(information.name(), information.samplerName(), information.target(), information.format(),
			information.internalTextureFormat(), information.type(), information.width(), information.height(),
			information.depth(), information.clear());
		GlImage physical = customImages.get(key);
		if (physical != null) {
			hits++;
			return physical.sharedView();
		}

		misses++;
		physical = new GlImage(information.name(), information.samplerName(), information.target(), information.format(),
			information.internalTextureFormat(), information.type(), information.clear(), information.width(),
			information.height(), information.depth());
		customImages.put(key, physical);
		estimatedBytes += (long) Math.max(1, information.width()) * Math.max(1, information.height()) *
			Math.max(1, information.depth()) * bytesPerPixel(information.internalTextureFormat());
		logPoolStats("ambience-pool-custom-image");
		return physical.sharedView();
	}

	public void destroy() {
		if (destroyed) {
			return;
		}
		destroyed = true;

		mainColorTargets.values().forEach(RenderTarget::destroy);
		shadowColorTargets.values().forEach(RenderTarget::destroy);
		customImages.values().forEach(GlImage::destroy);
		mainDepthCopies.values().forEach(DepthCopies::destroy);
		shadowDepthCopies.values().forEach(DepthCopies::destroy);

		mainColorTargets.clear();
		mainDepthCopies.clear();
		shadowDepthCopies.clear();
		shadowColorTargets.clear();
		customImages.clear();
		estimatedBytes = 0;
		hits = 0;
		misses = 0;
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

	private void logPoolStats(String key) {
		if (!WynncraftDebugLog.shouldLog(key)) {
			return;
		}
		WynncraftDebugLog.info(key,
			"Ambience render target pool: resources={} estimatedBytes={} hits={} misses={}",
			getResourceCount(), estimatedBytes, hits, misses);
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

	public record DepthCopies(GpuTexture first, GpuTexture second) {
		private void destroy() {
			first.close();
			second.close();
		}
	}

	private record ColorTargetKey(int index, int width, int height, InternalTextureFormat internalFormat, PixelFormat pixelFormat) {
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
