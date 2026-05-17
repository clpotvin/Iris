package net.irisshaders.iris.mixin.entity_render_context;

import net.irisshaders.iris.mixinterface.ModelStorage;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SubmitNodeStorage.ModelPartSubmit.class)
public class MixinModelPartSubmit implements ModelStorage {
	@Unique
	private int entityId, beId, itemId;
	@Unique
	private boolean itemInHand;
	@Unique
	private boolean skipItemTint;

	@Unique
	private boolean isRenderingBEs;

	@Override
	public void iris$capture() {
		entityId = CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
		beId = CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity();
		itemId = CapturedRenderingState.INSTANCE.getCurrentRenderedItem();
		itemInHand = CapturedRenderingState.INSTANCE.isCurrentRenderedItemInHand();
		skipItemTint = CapturedRenderingState.INSTANCE.currentRenderedItemSkipsItemTint();
		isRenderingBEs = ImmediateState.isRenderingBEs;
	}

	@Override
	public void iris$set() {
		CapturedRenderingState.INSTANCE.setCurrentEntity(entityId);
		CapturedRenderingState.INSTANCE.setCurrentBlockEntity(beId);
		CapturedRenderingState.INSTANCE.setCurrentRenderedItem(itemId);
		CapturedRenderingState.INSTANCE.setCurrentRenderedItemInHand(itemInHand);
		CapturedRenderingState.INSTANCE.setCurrentRenderedItemSkipsItemTint(skipItemTint);
	}

	@Override
	public boolean iris$wasBE() {
		return isRenderingBEs;
	}
}
