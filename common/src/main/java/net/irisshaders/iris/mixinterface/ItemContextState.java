package net.irisshaders.iris.mixinterface;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;

public interface ItemContextState {
	void setDisplayItem(Item itemStack, Identifier location, ItemDisplayContext displayContext);

	Item getDisplayItem();
	Identifier getDisplayItemModel();
	ItemDisplayContext getDisplayContext();
}
