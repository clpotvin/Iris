package net.irisshaders.iris.mixin.entity_render_context;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Makes text display entities render at full brightness when a Wynncraft
 * custom skybox is active. Overrides the packedLight parameter in submitInner()
 * to FULL_BRIGHT so the lightmap lookup produces maximum brightness.
 * <p>
 * The parent DisplayRenderer.submit() reads state.lightCoords and passes it as
 * the packedLight parameter to submitInner(). We intercept this parameter directly
 * because modifying state.lightCoords inside submitInner() is too late — the value
 * has already been extracted into the parameter.
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public class MixinTextDisplayRenderer {
	@ModifyVariable(
		method = "submitInner(Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
		at = @At("HEAD"),
		ordinal = 0,
		argsOnly = true
	)
	private int iris$boostTextLight(int packedLight) {
		if (IrisRenderingPipeline.skyboxFogColor != null) {
			return 0xF000F0; // LightTexture.FULL_BRIGHT
		}
		return packedLight;
	}
}
