package net.irisshaders.iris.mixinterface;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

public interface ItemContextState {
	void setDisplayItem(Item itemStack, Identifier location, int renderSeed);

	Item getDisplayItem();
	Identifier getDisplayItemModel();
	int getDisplayItemRenderSeed();
}
