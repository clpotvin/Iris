package net.irisshaders.iris.mixin.entity_render_context;

import net.irisshaders.iris.mixinterface.ItemContextState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.class)
public class ItemStackStateMixin implements ItemContextState {
	@Unique
	private Item iris_displayStack;
	@Unique
	private Identifier iris_displayModelId;
	@Unique
	private ItemDisplayContext iris_displayContext;

	@Override
	public void setDisplayItem(Item itemStack, Identifier modelId, ItemDisplayContext displayContext) {
		this.iris_displayStack = itemStack;
		this.iris_displayModelId = modelId;
		this.iris_displayContext = displayContext;
	}

	@Override
	public Item getDisplayItem() {
		return iris_displayStack;
	}
	public Identifier getDisplayItemModel() {
		return iris_displayModelId;
	}
	@Override
	public ItemDisplayContext getDisplayContext() {
		return iris_displayContext;
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void clearDisplayStack(CallbackInfo ci) {
		this.iris_displayStack = null;
		this.iris_displayModelId = null;
		this.iris_displayContext = null;
	}
}
