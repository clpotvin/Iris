package net.irisshaders.iris.targets;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.irisshaders.iris.ambience.AmbienceRenderTargetPool;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.gl.texture.DepthCopyStrategy;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import org.joml.Vector2i;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class RenderTargets {
	private final RenderTarget[] targets;
	private final AmbienceRenderTargetPool.ResourceRef[] targetRefs;
	private GpuTexture noTranslucents;
	private GpuTexture noHand;
	private AmbienceRenderTargetPool.ResourceRef depthCopiesRef;
	private final GlFramebuffer depthSourceFb;
	private final GlFramebuffer noTranslucentsDestFb;
	private final GlFramebuffer noHandDestFb;
	private final List<GlFramebuffer> ownedFramebuffers;
	private final Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> targetSettingsMap;
	private final PackDirectives packDirectives;
	private final AmbienceRenderTargetPool ambiencePool;
	private final AmbienceRenderTargetPool.Allocation ambienceAllocation;
	private GpuTexture currentDepthTexture;
	private DepthBufferFormat currentDepthFormat;
	private DepthCopyStrategy copyStrategy;
	private int cachedWidth;
	private int cachedHeight;
	private boolean fullClearRequired;
	private boolean translucentDepthDirty;
	private boolean handDepthDirty;

	private int cachedDepthBufferVersion;
	private boolean destroyed;

	public RenderTargets(int width, int height, GpuTexture depthTexture, int depthBufferVersion, DepthBufferFormat depthFormat, Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> renderTargets, PackDirectives packDirectives) {
		this(width, height, depthTexture, depthBufferVersion, depthFormat, renderTargets, packDirectives, null, null);
	}

	public RenderTargets(int width, int height, GpuTexture depthTexture, int depthBufferVersion, DepthBufferFormat depthFormat, Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> renderTargets, PackDirectives packDirectives, AmbienceRenderTargetPool ambiencePool, AmbienceRenderTargetPool.Allocation ambienceAllocation) {
		targets = new RenderTarget[renderTargets.size()];
		targetRefs = new AmbienceRenderTargetPool.ResourceRef[renderTargets.size()];

		targetSettingsMap = renderTargets;
		this.packDirectives = packDirectives;
		this.ambiencePool = ambiencePool;
		this.ambienceAllocation = ambienceAllocation;
		if (ambiencePool != null && ambienceAllocation == null) {
			throw new IllegalArgumentException("Pooled render targets require an ambience allocation owner");
		}

		this.currentDepthTexture = depthTexture;
		this.currentDepthFormat = depthFormat;
		this.copyStrategy = DepthCopyStrategy.fastest(currentDepthFormat.isCombinedStencil());

		this.cachedWidth = width;
		this.cachedHeight = height;
		this.cachedDepthBufferVersion = depthBufferVersion;

		this.ownedFramebuffers = new ArrayList<>();

		// NB: Make sure all buffers are cleared so that they don't contain undefined
		// data. Otherwise very weird things can happen.
		fullClearRequired = true;

		this.depthSourceFb = createFramebufferWritingToMain(new int[]{0});

		TextureFormat mojangDepthFormat = IrisPlatformHelpers.getInstance().mojangDepthFormat(depthFormat);

		if (ambiencePool == null) {
			this.noTranslucents = RenderSystem.getDevice().createTexture("Depth / Opaque", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, mojangDepthFormat, width, height, 1, 1);
			this.noHand = RenderSystem.getDevice().createTexture("Depth / Before Hand", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, mojangDepthFormat, width, height, 1, 1);
		} else {
			AmbienceRenderTargetPool.AcquiredDepthCopies acquired = ambiencePool.acquireMainDepthCopies(ambienceAllocation, width, height, mojangDepthFormat);
			this.noTranslucents = acquired.copies().first();
			this.noHand = acquired.copies().second();
			this.depthCopiesRef = acquired.ref();
		}

		this.noTranslucentsDestFb = createFramebufferWritingToMain(new int[]{0});
		this.noTranslucentsDestFb.addDepthAttachment(this.noTranslucents);

		this.noHandDestFb = createFramebufferWritingToMain(new int[]{0});
		this.noHandDestFb.addDepthAttachment(this.noHand);

		this.translucentDepthDirty = true;
		this.handDepthDirty = true;
	}

	public void destroy() {
		destroyed = true;

		for (GlFramebuffer owned : ownedFramebuffers) {
			owned.destroy();
		}

		for (RenderTarget target : targets) {
			if (target != null) {
				target.destroy();
			}
		}
		for (int i = 0; i < targetRefs.length; i++) {
			if (targetRefs[i] != null) {
				targetRefs[i].close();
				targetRefs[i] = null;
			}
		}

		if (ambiencePool == null) {
			noTranslucents.close();
			noHand.close();
		} else if (depthCopiesRef != null) {
			depthCopiesRef.close();
			depthCopiesRef = null;
		}
	}

	public int getRenderTargetCount() {
		return targets.length;
	}

	public RenderTarget get(int index) {
		if (destroyed) {
			throw new IllegalStateException("Tried to use destroyed RenderTargets");
		}

		if (targets[index] == null) {
			return null;
		}

		return targets[index];
	}

	public RenderTarget getOrCreate(int index) {
		if (destroyed) {
			throw new IllegalStateException("Tried to use destroyed RenderTargets");
		}

		if (targets[index] != null) return targets[index];

		create(index);

		return targets[index];
	}

	private void create(int index) {
		PackRenderTargetDirectives.RenderTargetSettings settings = targetSettingsMap.get(index);
		Vector2i dimensions = packDirectives.getTextureScaleOverride(index, cachedWidth, cachedHeight);
		RenderTarget.Builder builder = RenderTarget.builder().setDimensions(dimensions.x, dimensions.y)
			.setName("colortex" + index)
			.setInternalFormat(settings.getInternalFormat())
			.setPixelFormat(settings.getInternalFormat().getPixelFormat());

		if (ambiencePool == null) {
			targets[index] = builder.build();
		} else {
			AmbienceRenderTargetPool.AcquiredRenderTarget acquired = ambiencePool.acquireMainColorTarget(ambienceAllocation, index, dimensions.x, dimensions.y,
				settings.getInternalFormat(), settings.getInternalFormat().getPixelFormat());
			RenderTarget previousTarget = targets[index];
			AmbienceRenderTargetPool.ResourceRef previousRef = targetRefs[index];
			targets[index] = acquired.target();
			targetRefs[index] = acquired.ref();
			if (previousTarget != null) {
				previousTarget.destroy();
			}
			if (previousRef != null) {
				previousRef.close();
			}
		}
	}

	public GpuTexture getDepthTexture() {
		return currentDepthTexture;
	}

	public GpuTexture getDepthTextureNoTranslucents() {
		if (destroyed) {
			throw new IllegalStateException("Tried to use destroyed RenderTargets");
		}

		return noTranslucents;
	}

	public GpuTexture getDepthTextureNoHand() {
		return noHand;
	}

	public boolean resizeIfNeeded(int newDepthBufferVersion, GpuTexture newDepthTextureId, int newWidth, int newHeight, DepthBufferFormat newDepthFormat, PackDirectives packDirectives) {
		boolean recreateDepth = false;
		if (cachedDepthBufferVersion != newDepthBufferVersion) {
			recreateDepth = true;
			currentDepthTexture = newDepthTextureId;
			cachedDepthBufferVersion = newDepthBufferVersion;
		}

		boolean sizeChanged = newWidth != cachedWidth || newHeight != cachedHeight;
		boolean depthFormatChanged = newDepthFormat != currentDepthFormat;

		if (depthFormatChanged) {
			currentDepthFormat = newDepthFormat;
			// Might need a new copy strategy
			copyStrategy = DepthCopyStrategy.fastest(currentDepthFormat.isCombinedStencil());
		}

		if (depthFormatChanged || sizeChanged) {
			TextureFormat mojangDepthFormat = IrisPlatformHelpers.getInstance().mojangDepthFormat(newDepthFormat);
			AmbienceRenderTargetPool.ResourceRef previousDepthCopiesRef = null;

			if (ambiencePool == null) {
				// Reallocate depth buffers
				noTranslucents.close();
				noHand.close();

				this.noTranslucents = RenderSystem.getDevice().createTexture("Depth / Opaque", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, newDepthTextureId.getFormat(), newWidth, newHeight, 1, 1);
				this.noHand = RenderSystem.getDevice().createTexture("Depth / Before Hand", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, newDepthTextureId.getFormat(), newWidth, newHeight, 1, 1);
			} else {
				previousDepthCopiesRef = depthCopiesRef;
				AmbienceRenderTargetPool.AcquiredDepthCopies acquired = ambiencePool.acquireMainDepthCopies(ambienceAllocation, newWidth, newHeight, mojangDepthFormat);
				this.noTranslucents = acquired.copies().first();
				this.noHand = acquired.copies().second();
				this.depthCopiesRef = acquired.ref();
			}

			// TODO: linear horrors

			this.noTranslucentsDestFb.addDepthAttachment(this.noTranslucents);
			this.noHandDestFb.addDepthAttachment(this.noHand);
			if (previousDepthCopiesRef != null) {
				previousDepthCopiesRef.close();
			}

			this.translucentDepthDirty = true;
			this.handDepthDirty = true;

			recreateDepth = true;
		}

		if (recreateDepth) {
			// Re-attach the depth textures with the new depth texture ID, since Minecraft re-creates
			// the depth texture when resizing its render targets.
			//
			// I'm not sure if our framebuffers holding on to the old depth texture between frames
			// could be a concern, in the case of resizing and similar. I think it should work
			// based on what I've seen of the spec, though - it seems like deleting a texture
			// automatically detaches it from its framebuffers.
			for (GlFramebuffer framebuffer : ownedFramebuffers) {
				if (framebuffer.hasDepthAttachment()) {
					framebuffer.addDepthAttachment(newDepthTextureId);
				}
			}
		}

		if (sizeChanged) {
			cachedWidth = newWidth;
			cachedHeight = newHeight;

			for (int i = 0; i < targets.length; i++) {
				if (targets[i] != null) {
					if (ambiencePool == null) {
						targets[i].resize(packDirectives.getTextureScaleOverride(i, newWidth, newHeight));
					} else {
						create(i);
					}
				}
			}

			if (ambiencePool != null) {
				refreshDepthCopyFramebufferColorAttachments();
			}

			fullClearRequired = true;
		}

		return sizeChanged;
	}

	public void copyPreTranslucentDepth() {
		if (translucentDepthDirty) {
			translucentDepthDirty = false;
			GlStateManager._bindTexture(noTranslucents.iris$getGlId());
			depthSourceFb.bindAsReadBuffer();
			IrisRenderSystem.copyTexImage2D(GL20C.GL_TEXTURE_2D, 0, currentDepthFormat.getGlInternalFormat(), 0, 0, cachedWidth, cachedHeight, 0);
		} else {
			copyStrategy.copy(depthSourceFb, getDepthTexture().iris$getGlId(), noTranslucentsDestFb, noTranslucents.iris$getGlId(),
				getCurrentWidth(), getCurrentHeight());
		}
	}

	public void copyPreHandDepth() {
		if (handDepthDirty) {
			handDepthDirty = false;
			GlStateManager._bindTexture(noHand.iris$getGlId());
			depthSourceFb.bindAsReadBuffer();
			IrisRenderSystem.copyTexImage2D(GL20C.GL_TEXTURE_2D, 0, currentDepthFormat.getGlInternalFormat(), 0, 0, cachedWidth, cachedHeight, 0);
		} else {
			copyStrategy.copy(depthSourceFb, getDepthTexture().iris$getGlId(), noHandDestFb, noHand.iris$getGlId(),
				getCurrentWidth(), getCurrentHeight());
		}
	}

	public boolean isFullClearRequired() {
		return fullClearRequired;
	}

	public void onFullClear() {
		fullClearRequired = false;
	}

	public void forceFullClear() {
		fullClearRequired = true;
		translucentDepthDirty = true;
		handDepthDirty = true;
	}

	private void refreshDepthCopyFramebufferColorAttachments() {
		int mainColor = getOrCreate(0).getMainTexture();
		depthSourceFb.addColorAttachment(0, mainColor);
		noTranslucentsDestFb.addColorAttachment(0, mainColor);
		noHandDestFb.addColorAttachment(0, mainColor);
	}

	public GlFramebuffer createFramebufferWritingToMain(int[] drawBuffers) {
		return createFullFramebuffer(false, drawBuffers);
	}

	public GlFramebuffer createFramebufferWritingToAlt(int[] drawBuffers) {
		return createFullFramebuffer(true, drawBuffers);
	}

	public GlFramebuffer createClearFramebuffer(boolean alt, int[] clearBuffers) {
		ImmutableSet<Integer> stageWritesToMain = ImmutableSet.of();

		if (!alt) {
			stageWritesToMain = invert(ImmutableSet.of(), clearBuffers);
		}

		return createColorFramebuffer(stageWritesToMain, clearBuffers);
	}

	private ImmutableSet<Integer> invert(ImmutableSet<Integer> base, int[] relevant) {
		ImmutableSet.Builder<Integer> inverted = ImmutableSet.builder();

		for (int i : relevant) {
			if (!base.contains(i)) {
				inverted.add(i);
			}
		}

		return inverted.build();
	}

	private GlFramebuffer createEmptyFramebuffer() {
		GlFramebuffer framebuffer = new GlFramebuffer();
		ownedFramebuffers.add(framebuffer);

		framebuffer.addDepthAttachment(currentDepthTexture);

		// NB: Before OpenGL 3.0, all framebuffers are required to have a color
		// attachment no matter what.
		framebuffer.addColorAttachment(0, getOrCreate(0).getMainTexture());
		framebuffer.noDrawBuffers();

		return framebuffer;
	}

	public GlFramebuffer createDHFramebuffer(ImmutableSet<Integer> stageWritesToAlt, int[] drawBuffers) {
		if (drawBuffers.length == 0) {
			return createEmptyFramebuffer();
		}

		ImmutableSet<Integer> stageWritesToMain = invert(stageWritesToAlt, drawBuffers);

		return createColorFramebuffer(stageWritesToMain, drawBuffers);
	}


	public GlFramebuffer createGbufferFramebuffer(ImmutableSet<Integer> stageWritesToAlt, int[] drawBuffers) {
		if (drawBuffers.length == 0) {
			return createEmptyFramebuffer();
		}

		ImmutableSet<Integer> stageWritesToMain = invert(stageWritesToAlt, drawBuffers);

		GlFramebuffer framebuffer = createColorFramebuffer(stageWritesToMain, drawBuffers);

		framebuffer.addDepthAttachment(currentDepthTexture);

		return framebuffer;
	}

	public void refreshGbufferFramebuffer(GlFramebuffer framebuffer, ImmutableSet<Integer> stageWritesToAlt, int[] drawBuffers) {
		if (drawBuffers.length == 0) {
			framebuffer.addDepthAttachment(currentDepthTexture);
			framebuffer.addColorAttachment(0, getOrCreate(0).getMainTexture());
			framebuffer.noDrawBuffers();
			return;
		}

		refreshColorFramebuffer(framebuffer, stageWritesToAlt, drawBuffers);
		framebuffer.addDepthAttachment(currentDepthTexture);
	}

	public void refreshColorFramebuffer(GlFramebuffer framebuffer, ImmutableSet<Integer> stageWritesToAlt, int[] drawBuffers) {
		if (drawBuffers.length == 0) {
			throw new IllegalArgumentException("Framebuffer must have at least one color buffer");
		}

		ImmutableSet<Integer> stageWritesToMain = invert(stageWritesToAlt, drawBuffers);
		int[] actualDrawBuffers = new int[drawBuffers.length];

		for (int i = 0; i < drawBuffers.length; i++) {
			actualDrawBuffers[i] = i;

			if (drawBuffers[i] >= getRenderTargetCount()) {
				throw new IllegalStateException("Render target with index " + drawBuffers[i] + " is not supported, only "
					+ getRenderTargetCount() + " render targets are supported.");
			}

			RenderTarget target = this.getOrCreate(drawBuffers[i]);
			int textureId = stageWritesToMain.contains(drawBuffers[i]) ? target.getMainTexture() : target.getAltTexture();

			framebuffer.addColorAttachment(i, textureId);
		}

		framebuffer.drawBuffers(actualDrawBuffers);
		framebuffer.readBuffer(0);

		int status = framebuffer.getStatus();
		if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException("Unexpected error while refreshing framebuffer: Draw buffers " + Arrays.toString(actualDrawBuffers) + " Status: " + status);
		}
	}

	private GlFramebuffer createFullFramebuffer(boolean clearsAlt, int[] drawBuffers) {
		if (drawBuffers.length == 0) {
			return createEmptyFramebuffer();
		}

		ImmutableSet<Integer> stageWritesToMain = ImmutableSet.of();

		if (!clearsAlt) {
			stageWritesToMain = invert(ImmutableSet.of(), drawBuffers);
		}

		return createColorFramebufferWithDepth(stageWritesToMain, drawBuffers);
	}

	public GlFramebuffer createColorFramebufferWithDepth(ImmutableSet<Integer> stageWritesToMain, int[] drawBuffers) {
		GlFramebuffer framebuffer = createColorFramebuffer(stageWritesToMain, drawBuffers);

		framebuffer.addDepthAttachment(currentDepthTexture);

		return framebuffer;
	}

	public GlFramebuffer createColorFramebuffer(ImmutableSet<Integer> stageWritesToMain, int[] drawBuffers) {
		if (drawBuffers.length == 0) {
			throw new IllegalArgumentException("Framebuffer must have at least one color buffer");
		}

		GlFramebuffer framebuffer = new GlFramebuffer();
		ownedFramebuffers.add(framebuffer);

		int[] actualDrawBuffers = new int[drawBuffers.length];

		for (int i = 0; i < drawBuffers.length; i++) {
			actualDrawBuffers[i] = i;

			if (drawBuffers[i] >= getRenderTargetCount()) {
				// TODO: This causes resource leaks, also we should really verify this in the shaderpack parser...
				framebuffer.destroy();
				ownedFramebuffers.remove(framebuffer);
				throw new IllegalStateException("Render target with index " + drawBuffers[i] + " is not supported, only "
					+ getRenderTargetCount() + " render targets are supported.");
			}

			RenderTarget target = this.getOrCreate(drawBuffers[i]);

			int textureId = stageWritesToMain.contains(drawBuffers[i]) ? target.getMainTexture() : target.getAltTexture();

			framebuffer.addColorAttachment(i, textureId);
		}

		framebuffer.drawBuffers(actualDrawBuffers);
		framebuffer.readBuffer(0);


		int status = framebuffer.getStatus();
		if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException("Unexpected error while creating framebuffer: Draw buffers " + Arrays.toString(actualDrawBuffers) + " Status: " + status);
		}

		return framebuffer;
	}

	public void destroyFramebuffer(GlFramebuffer framebuffer) {
		framebuffer.destroy();
		ownedFramebuffers.remove(framebuffer);
	}

	public int getCurrentWidth() {
		return cachedWidth;
	}

	public int getCurrentHeight() {
		return cachedHeight;
	}

	public void createIfUnsure(int index) {
		if (targets[index] == null) {
			create(index);
		}
	}
}
