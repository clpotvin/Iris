package net.irisshaders.iris.gl.image;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.TextureType;
import org.lwjgl.opengl.ARBClearTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;

public class GlImage extends GlResource {
	protected final String name;
	protected final String samplerName;
	protected final TextureType target;
	protected final PixelFormat format;
	protected final InternalTextureFormat internalTextureFormat;
	protected final PixelType pixelType;
	private final boolean clear;
	private final boolean ownsTexture;
	private final boolean pooledView;

	public GlImage(String name, String samplerName, TextureType target, PixelFormat format, InternalTextureFormat internalFormat, PixelType pixelType, boolean clear, int width, int height, int depth) {
		this(IrisRenderSystem.createTexture(target.getGlType()), name, samplerName, target, format, internalFormat, pixelType, clear, width, height, depth, true, false, true);
	}

	private GlImage(int texture, String name, String samplerName, TextureType target, PixelFormat format, InternalTextureFormat internalFormat, PixelType pixelType, boolean clear, int width, int height, int depth, boolean ownsTexture, boolean pooledView, boolean setupTexture) {
		super(texture);

		this.name = name;
		this.samplerName = samplerName;
		this.target = target;
		this.format = format;
		this.internalTextureFormat = internalFormat;
		this.pixelType = pixelType;
		this.clear = clear;
		this.ownsTexture = ownsTexture;
		this.pooledView = pooledView;

		GLDebug.nameObject(GL43C.GL_TEXTURE, getGlId(), name);

		if (setupTexture) {
			IrisRenderSystem.bindTextureForSetup(target.getGlType(), getGlId());
			target.apply(getGlId(), width, height, depth, internalFormat.getGlFormat(), format.getGlFormat(), pixelType.getGlFormat(), null);

			setup(texture, width, height, depth);

			IrisRenderSystem.bindTextureForSetup(target.getGlType(), 0);
		}
	}

	public GlImage sharedView() {
		return new GlImage(getGlId(), name, samplerName, target, format, internalTextureFormat, pixelType, clear, 1, 1, 0, false, true, false);
	}

	protected void setup(int texture, int width, int height, int depth) {
		boolean isInteger = internalTextureFormat.getPixelFormat().isInteger();
		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL11C.GL_TEXTURE_MIN_FILTER, isInteger ? GL11C.GL_NEAREST : GL11C.GL_LINEAR);
		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL11C.GL_TEXTURE_MAG_FILTER, isInteger ? GL11C.GL_NEAREST : GL11C.GL_LINEAR);
		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL11C.GL_TEXTURE_WRAP_S, GL13C.GL_CLAMP_TO_EDGE);

		if (height > 0) {
			IrisRenderSystem.texParameteri(texture, target.getGlType(), GL11C.GL_TEXTURE_WRAP_T, GL13C.GL_CLAMP_TO_EDGE);
		}

		if (depth > 0) {
			IrisRenderSystem.texParameteri(texture, target.getGlType(), GL30C.GL_TEXTURE_WRAP_R, GL13C.GL_CLAMP_TO_EDGE);
		}

		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL20C.GL_TEXTURE_MAX_LEVEL, 0);
		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL20C.GL_TEXTURE_MIN_LOD, 0);
		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL20C.GL_TEXTURE_MAX_LOD, 0);
		IrisRenderSystem.texParameterf(texture, target.getGlType(), GL20C.GL_TEXTURE_LOD_BIAS, 0.0F);

		clearTexture(texture);
	}

	public String getName() {
		return name;
	}

	public String getSamplerName() {
		return samplerName;
	}

	public TextureType getTarget() {
		return target;
	}

	public boolean shouldClear() {
		return clear;
	}

	public boolean isPooledView() {
		return pooledView;
	}

	public void clearTexture() {
		clearTexture(getGlId());
	}

	private void clearTexture(int texture) {
		ARBClearTexture.glClearTexImage(texture, 0, format.getGlFormat(), pixelType.getGlFormat(), (int[]) null);
	}

	public int getId() {
		return getGlId();
	}

	/**
	 * This makes the image aware of a new render target. Depending on the image's properties, it may not follow these targets.
	 *
	 * @param width  The width of the main render target.
	 * @param height The height of the main render target.
	 */
	public void updateNewSize(int width, int height) {

	}

	@Override
	protected void destroyInternal() {
		if (ownsTexture) {
			GlStateManager._deleteTexture(getGlId());
		}
	}

	public InternalTextureFormat getInternalFormat() {
		return internalTextureFormat;
	}

	@Override
	public String toString() {
		return "GlImage name " + name + " format " + format + "internalformat " + internalTextureFormat + " pixeltype " + pixelType;
	}

	public PixelFormat getFormat() {
		return format;
	}

	public PixelType getPixelType() {
		return pixelType;
	}

	public static class Relative extends GlImage {

		private final float relativeHeight;
		private final float relativeWidth;

		public Relative(String name, String samplerName, PixelFormat format, InternalTextureFormat internalFormat, PixelType pixelType, boolean clear, float relativeWidth, float relativeHeight, int currentWidth, int currentHeight) {
			super(name, samplerName, TextureType.TEXTURE_2D, format, internalFormat, pixelType, clear, (int) (currentWidth * relativeWidth), (int) (currentHeight * relativeHeight), 0);

			this.relativeWidth = relativeWidth;
			this.relativeHeight = relativeHeight;
		}

		@Override
		public void updateNewSize(int width, int height) {
			IrisRenderSystem.bindTextureForSetup(target.getGlType(), getGlId());
			target.apply(getGlId(), (int) (width * relativeWidth), (int) (height * relativeHeight), 0, internalTextureFormat.getGlFormat(), format.getGlFormat(), pixelType.getGlFormat(), null);

			int texture = getGlId();

			setup(texture, width, height, 0);

			IrisRenderSystem.bindTextureForSetup(target.getGlType(), 0);
		}
	}
}
