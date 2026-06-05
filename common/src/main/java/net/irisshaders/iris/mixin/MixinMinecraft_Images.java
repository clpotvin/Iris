package net.irisshaders.iris.mixin;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.targets.backed.NativeImageBackedCustomTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.resources.Identifier;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.io.InputStream;

/**
 * This Mixin is responsible for registering the "widgets" texture used in Iris' GUI's.
 * Normally Fabric API would do this automatically, but we don't use it here, so it must be done manually.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft_Images {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void iris$setupImages(GameConfig arg, CallbackInfo ci) {
		if (!IrisPlatformHelpers.getInstance().isModLoaded("fabric-resource-loader-v0")) {
			try {
				iris$registerGuiTexture("widgets.png");
				iris$registerGuiTexture("config-icon.png");
				iris$registerGuiTexture("config-icon-mono.png");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private static void iris$registerGuiTexture(String filename) throws IOException {
		String path = "/assets/iris/textures/gui/" + filename;
		try (InputStream stream = Iris.class.getResourceAsStream(path)) {
			if (stream == null) {
				throw new IOException("Missing bundled Iris GUI texture: " + path);
			}
			Minecraft.getInstance().getTextureManager().register(Identifier.fromNamespaceAndPath("iris", "textures/gui/" + filename),
				new NativeImageBackedCustomTexture(new CustomTextureData.PngData(new TextureFilteringData(false, false), IOUtils.toByteArray(stream))));
		}
	}
}
