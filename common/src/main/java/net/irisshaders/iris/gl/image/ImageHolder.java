package net.irisshaders.iris.gl.image;

import net.irisshaders.iris.gl.texture.InternalTextureFormat;

import java.util.function.IntSupplier;

public interface ImageHolder {
	boolean hasImage(String name);

	void addTextureImage(IntSupplier textureID, InternalTextureFormat internalFormat, String name);

	/**
	 * Like {@link #addTextureImage} but returns false instead of throwing when
	 * image units are exhausted or the uniform is not found. Used for optional
	 * image bindings (e.g., Wynncraft skybox detection) that should degrade
	 * gracefully when capacity is exceeded.
	 */
	default boolean tryAddTextureImage(IntSupplier textureID, InternalTextureFormat internalFormat, String name) {
		if (!hasImage(name)) return false;
		try {
			addTextureImage(textureID, internalFormat, name);
			return true;
		} catch (IllegalStateException e) {
			return false;
		}
	}
}
