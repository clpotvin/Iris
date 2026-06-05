package net.irisshaders.iris.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.irisshaders.iris.compat.sodium.config.IrisConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VideoSettingsScreen.class)
public class MixinSodiumGameOptions {
	@WrapOperation(method = "renderIconWithSpacing", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"))
	private static void iris$useMonoConfigIcon(GuiGraphics instance, RenderPipeline renderPipeline, Identifier identifier, int i, int j, float f, float g, int k, int l, int m, int n, int o, int p, int q, Operation<Void> original) {
		Identifier newIdentifier = identifier;

		if (identifier.getNamespace().equals("iris")) {
			newIdentifier = IrisConfig.WYNNIRIS_CONFIG_ICON_MONO;
		}

		original.call(instance, renderPipeline, newIdentifier, i, j, f, g, k, l, m, n, o, p, q);
	}

	@WrapOperation(method = "renderIconWithSpacing", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIII)V"))
	private static void iris$useColorConfigIcon(GuiGraphics instance, RenderPipeline renderPipeline, Identifier identifier, int i, int j, float f, float g, int k, int l, int m, int n, int o, int p, Operation<Void> original) {
		Identifier newIdentifier = identifier;

		if (identifier.getNamespace().equals("iris")) {
			newIdentifier = IrisConfig.WYNNIRIS_CONFIG_ICON;
		}

		original.call(instance, renderPipeline, newIdentifier, i, j, f, g, k, l, m, n, o, p);
	}
}
