package net.irisshaders.iris.shaderpack.materialmap;

import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class WorldRenderingSettings {
	public static final WorldRenderingSettings INSTANCE = new WorldRenderingSettings();

	private boolean reloadRequired;
	private final StringBuilder reloadReasons = new StringBuilder();
	private Object2IntMap<BlockState> blockStateIds;
	private Map<Block, BlockRenderType> blockTypeIds;
	private Object2IntFunction<NamespacedId> entityIds;
	private Object2IntFunction<NamespacedId> itemIds;
	private float ambientOcclusionLevel;
	private boolean disableDirectionalShading;
	private boolean hasVillagerConversionId;
	private boolean useSeparateAo;
	private boolean separateEntityDraws;
	private boolean voxelizeLightBlocks;
	private ChunkVertexType chunkVertexFormat;
	private boolean breaksAnisotropy = false;

	public WorldRenderingSettings() {
		reloadRequired = false;
		blockStateIds = null;
		blockTypeIds = null;
		ambientOcclusionLevel = 1.0F;
		disableDirectionalShading = false;
		useSeparateAo = false;
		chunkVertexFormat = ChunkMeshFormats.COMPACT;
		separateEntityDraws = false;
		voxelizeLightBlocks = false;
		hasVillagerConversionId = false;
	}

	public boolean isReloadRequired() {
		return reloadRequired;
	}

	public void clearReloadRequired() {
		reloadRequired = false;
		reloadReasons.setLength(0);
	}

	public String getReloadReasonSummary() {
		if (reloadReasons.length() == 0) {
			return "none";
		}

		return reloadReasons.toString();
	}

	@Nullable
	public Object2IntMap<BlockState> getBlockStateIds() {
		return blockStateIds;
	}

	public void setBlockStateIds(Object2IntMap<BlockState> blockStateIds) {
		if (this.blockStateIds != null && this.blockStateIds.equals(blockStateIds)) {
			return;
		}

		markReloadRequired("blockStateIds", describeMap(this.blockStateIds), describeMap(blockStateIds));
		this.blockStateIds = blockStateIds;
	}

	public Map<Block, BlockRenderType> getBlockTypeIds() {
		return blockTypeIds;
	}

	public void setBlockTypeIds(Map<Block, BlockRenderType> blockTypeIds) {
		if (this.blockTypeIds != null && this.blockTypeIds.equals(blockTypeIds)) {
			return;
		}

		markReloadRequired("blockTypeIds", describeMap(this.blockTypeIds), describeMap(blockTypeIds));
		this.blockTypeIds = blockTypeIds;
	}

	@Nullable
	public Object2IntFunction<NamespacedId> getEntityIds() {
		return entityIds;
	}

	public void setEntityIds(Object2IntFunction<NamespacedId> entityIds) {
		// note: no reload needed, entities are rebuilt every frame.
		this.entityIds = entityIds;
		this.hasVillagerConversionId = entityIds.containsKey(new NamespacedId("minecraft", "zombie_villager_converting"));
	}

	@Nullable
	public Object2IntFunction<NamespacedId> getItemIds() {
		return itemIds;
	}

	public void setItemIds(Object2IntFunction<NamespacedId> itemIds) {
		// note: no reload needed, entities are rebuilt every frame.
		this.itemIds = itemIds;
	}

	public float getAmbientOcclusionLevel() {
		return ambientOcclusionLevel;
	}

	public void setAmbientOcclusionLevel(float ambientOcclusionLevel) {
		if (ambientOcclusionLevel == this.ambientOcclusionLevel) {
			return;
		}

		markReloadRequired("ambientOcclusionLevel", Float.toString(this.ambientOcclusionLevel), Float.toString(ambientOcclusionLevel));
		this.ambientOcclusionLevel = ambientOcclusionLevel;
	}

	public boolean shouldDisableDirectionalShading() {
		return disableDirectionalShading;
	}

	public void setDisableDirectionalShading(boolean disableDirectionalShading) {
		if (disableDirectionalShading == this.disableDirectionalShading) {
			return;
		}

		markReloadRequired("disableDirectionalShading", Boolean.toString(this.disableDirectionalShading), Boolean.toString(disableDirectionalShading));
		this.disableDirectionalShading = disableDirectionalShading;
	}

	public boolean shouldUseSeparateAo() {
		return useSeparateAo;
	}

	public void setUseSeparateAo(boolean useSeparateAo) {
		if (useSeparateAo == this.useSeparateAo) {
			return;
		}

		markReloadRequired("useSeparateAo", Boolean.toString(this.useSeparateAo), Boolean.toString(useSeparateAo));
		this.useSeparateAo = useSeparateAo;
	}

	public ChunkVertexType getVertexFormat() {
		return chunkVertexFormat;
	}

	public void setVertexFormat(ChunkVertexType chunkVertexFormat) {
		if (chunkVertexFormat == this.chunkVertexFormat) {
			return;
		}

		markReloadRequired("chunkVertexFormat", describeValue(this.chunkVertexFormat), describeValue(chunkVertexFormat));
		this.chunkVertexFormat = chunkVertexFormat;
	}

	public boolean shouldVoxelizeLightBlocks() {
		return voxelizeLightBlocks;
	}

	public void setVoxelizeLightBlocks(boolean voxelizeLightBlocks) {
		if (voxelizeLightBlocks == this.voxelizeLightBlocks) {
			return;
		}

		markReloadRequired("voxelizeLightBlocks", Boolean.toString(this.voxelizeLightBlocks), Boolean.toString(voxelizeLightBlocks));
		this.voxelizeLightBlocks = voxelizeLightBlocks;
	}

	public boolean shouldSeparateEntityDraws() {
		return separateEntityDraws;
	}

	public void setSeparateEntityDraws(boolean separateEntityDraws) {
		this.separateEntityDraws = separateEntityDraws;
	}

	public boolean hasVillagerConversionId() {
		return hasVillagerConversionId;
	}

	public void setBreaksAnisotropy(boolean b) {
		this.breaksAnisotropy = b;
	}

	public boolean breaksAnisotropy() {
		return breaksAnisotropy;
	}

	private void markReloadRequired(String field, String before, String after) {
		this.reloadRequired = true;
		if (reloadReasons.length() > 0) {
			reloadReasons.append("; ");
		}
		reloadReasons.append(field).append('(').append(before).append(" -> ").append(after).append(')');
	}

	private static String describeMap(@Nullable Map<?, ?> map) {
		if (map == null) {
			return "null";
		}

		return map.getClass().getSimpleName() + "[size=" + map.size() + ",id=" + System.identityHashCode(map) + ']';
	}

	private static String describeValue(@Nullable Object value) {
		return value == null ? "null" : value.toString();
	}
}
