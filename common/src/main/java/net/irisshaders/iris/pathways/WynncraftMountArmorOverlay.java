package net.irisshaders.iris.pathways;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.iris.BuildConfig;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.gui.option.WynncraftDebugLog;
import net.irisshaders.iris.mixin.EntityRenderDispatcherAccessor;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class WynncraftMountArmorOverlay {
	public static final int OUTER_SIGNAL_COLOR = 0xFCFFFFFF;
	public static final int LEGGINGS_SIGNAL_COLOR = 0xFAFFFFFF;

	private static final Map<ArmorKey, OverlayTextures> CACHE = new HashMap<>();
	private static final Map<Integer, Integer> RENDERED_ARMOR_SIGNAL_CACHE = new HashMap<>();
	private static final int MAX_RENDERED_ARMOR_SIGNAL_CACHE = 128;
	private static int seenTextureReloadCount = -1;
	private static long textureSequence;
	private static ArmorSnapshot lastKnownArmor;
	private static final float FAKE_PLAYER_Y_THRESHOLD = 1024.0F;
	private static final int FAKE_PLAYER_Y_POSITION_RADIX = 512;
	private static final int FAKE_PLAYER_STEVE_ALEX_RADIX = 2;
	private static final int FAKE_PLAYER_LIMB_FADE_RADIX = 3;
	private static final int FAKE_PLAYER_LIMB_INDEX_RADIX = 6;
	private static final int FAKE_PLAYER_LEFT_ARM = 2;
	private static final int FAKE_PLAYER_RIGHT_ARM = 3;
	private static final int FAKE_PLAYER_LEFT_LEG = 4;
	private static final int FAKE_PLAYER_RIGHT_LEG = 5;
	private static final float SKIN_OVERLAY_TO_OUTER_ARMOR_EXPAND = 0.75F;
	private static final float SKIN_OVERLAY_TO_OUTER_LEG_ARMOR_EXPAND = 0.65F;
	private static final float SKIN_OVERLAY_TO_BOOTS_ARMOR_EXPAND = 1.25F;
	private static final float SKIN_OVERLAY_TO_LEGGINGS_ARMOR_EXPAND = 0.25F;
	private static final float SKIN_OVERLAY_TO_LEGGINGS_LEG_ARMOR_EXPAND = 0.15F;
	private static final float SLIM_ARM_ARMOR_WIDTH_EXPAND = 0.5F;
	private static final float HEAD_ARMOR_SCALE = 10.0F / 8.5F;
	private static final float BOOTS_ARMOR_SCALE = 11.5F / 8.5F;
	private static final int OUTER_OVERLAY_ALPHA = 252;
	private static final int LEGGINGS_OVERLAY_ALPHA = 250;
	private static final int EFFECT_OVERLAY_ALPHA_BASE = 160;
	private static final int DEFAULT_OVERLAY_RGB = 0x00FFFFFF;
	private static final int MAX_WYNNCRAFT_EFFECT_ID = 32;
	private static final String[] WYNNCRAFT_ARMOR_ASSETS = {
		"hidden", "leather", "tan", "chainmail", "copper", "iron", "gold", "diamond",
		"titanium", "netherite", "pale_leather", "pale_chainmail", "pale_copper", "pale_iron",
		"pale_gold", "pale_diamond", "pale_titanium", "pale_netherite", "shaman", "infernal",
		"phantom", "quartz", "wings"
	};

	private WynncraftMountArmorOverlay() {
	}

	public static void onClientTick(Minecraft minecraft) {
		if (!BuildConfig.WYNNIRIS_EXPERIMENTAL) {
			return;
		}

		LocalPlayer player = minecraft.player;
		if (minecraft.level == null || player == null) {
			lastKnownArmor = null;
			RENDERED_ARMOR_SIGNAL_CACHE.clear();
			iris$clearCache(minecraft);
			return;
		}

		ArmorSnapshot current = iris$readCurrentArmor(player);
		if (!iris$isLikelyMountState(player) || current.hasAnyStack()) {
			iris$rememberArmor(current);
		}
	}

	public static void submitPlayerHeadOverlays(PlayerSkinRenderCache.RenderInfo renderInfo, net.minecraft.world.item.ItemDisplayContext displayContext,
												PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, SkullModelBase modelBase) {
		if (!iris$shouldSubmitOverlays() || modelBase == null || iris$isExcludedDisplayContext(displayContext)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (minecraft.level == null || player == null || !iris$isLikelyMountState(player)) {
			return;
		}
		if (iris$isForeignPlayerHead(renderInfo, player)) {
			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-head-skip")) {
				WynncraftDebugLog.info("mount-armor-overlay-head-skip",
					"[WynnIris MountArmor] skipping non-local player head profile={} local={}",
					renderInfo.gameProfile(), player.getUUID());
			}
			return;
		}

		OverlayTextures textures = iris$getOrCreateTextures(minecraft, player);
		if (textures == null || textures.isEmpty()) {
			return;
		}

		if (WynncraftDebugLog.shouldLog("mount-armor-overlay-submit")) {
			String profile = renderInfo == null || renderInfo.gameProfile() == null ? "none" : renderInfo.gameProfile().toString();
			WynncraftDebugLog.info("mount-armor-overlay-submit",
				"[WynnIris MountArmor] submitting overlays profile={} context={} outerHead={} outerBody={} outerBoots={} leggings={}",
				profile, displayContext, textures.outerHeadId, textures.outerBodyId, textures.outerBootsId, textures.leggingsId);
		}

		if (textures.outerHeadId != null) {
			iris$submitExpandedSkull(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.outerHeadRenderType, textures.outerHeadColor, HEAD_ARMOR_SCALE);
		}
		if (textures.outerBodyId != null) {
			iris$submitExpandedSkull(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.outerBodyRenderType, textures.outerBodyColor, HEAD_ARMOR_SCALE);
		}
		if (textures.outerBootsId != null) {
			iris$submitExpandedSkull(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.outerBootsRenderType, textures.outerBootsColor, BOOTS_ARMOR_SCALE);
		}
		if (textures.leggingsId != null) {
			iris$submitExpandedSkull(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.leggingsRenderType, textures.leggingsColor, HEAD_ARMOR_SCALE);
		}
	}

	public static void submitItemLayerOverlays(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, int packedOverlay,
											   List<BakedQuad> quads, net.minecraft.world.item.ItemDisplayContext displayContext) {
		if (!iris$shouldSubmitOverlays() || quads == null || quads.isEmpty() || iris$isExcludedDisplayContext(displayContext)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (minecraft.level == null || player == null || !iris$isLikelyMountState(player)) {
			return;
		}

		List<BakedQuad> fakeQuads = iris$collectFakePlayerQuads(poseStack, quads);
		if (fakeQuads.isEmpty()) {
			return;
		}

		OverlayTextures textures = iris$getOrCreateTextures(minecraft, player);
		if (textures == null || textures.isEmpty()) {
			return;
		}

		if (WynncraftDebugLog.shouldLog("mount-armor-overlay-item-submit")) {
			WynncraftDebugLog.info("mount-armor-overlay-item-submit",
				"[WynnIris MountArmor] submitting item-layer overlays context={} fakeQuads={}/{} outerBody={} outerBoots={} leggings={}",
				displayContext, fakeQuads.size(), quads.size(), textures.outerBodyId, textures.outerBootsId, textures.leggingsId);
		}

		if (textures.outerBodyId != null) {
			iris$submitQuadOverlay(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads, textures.outerBodyRenderType,
				ArmorLayer.OUTER, textures.outerBodyColor, player.getSkin().model());
		}
		if (textures.outerBootsId != null) {
			iris$submitQuadOverlay(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads, textures.outerBootsRenderType,
				ArmorLayer.BOOTS, textures.outerBootsColor, player.getSkin().model());
		}
		if (textures.leggingsId != null) {
			iris$submitQuadOverlay(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads, textures.leggingsRenderType,
				ArmorLayer.LEGGINGS, textures.leggingsColor, player.getSkin().model());
		}
	}

	private static boolean iris$isExcludedDisplayContext(net.minecraft.world.item.ItemDisplayContext displayContext) {
		return displayContext == net.minecraft.world.item.ItemDisplayContext.GUI
			|| displayContext == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND
			|| displayContext == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
	}

	private static boolean iris$isForeignPlayerHead(PlayerSkinRenderCache.RenderInfo renderInfo, LocalPlayer player) {
		if (renderInfo == null || renderInfo.gameProfile() == null || renderInfo.gameProfile().id() == null) {
			return false;
		}
		return !renderInfo.gameProfile().id().equals(player.getUUID());
	}

	private static boolean iris$shouldSubmitOverlays() {
		return BuildConfig.WYNNIRIS_EXPERIMENTAL && IrisVideoSettings.wynncraftMountArmorOverlay && Iris.isPackInUseQuick();
	}

	private static boolean iris$isLikelyMountState(LocalPlayer player) {
		return player.getVehicle() != null || player.isSpectator() || player.isInvisible();
	}

	private static List<BakedQuad> iris$collectFakePlayerQuads(PoseStack poseStack, List<BakedQuad> quads) {
		Matrix4f pose = poseStack.last().pose();
		Vector3f transformed = new Vector3f();
		List<BakedQuad> fakeQuads = null;
		for (BakedQuad quad : quads) {
			if (iris$isFakePlayerQuad(pose, quad, transformed)) {
				if (fakeQuads == null) {
					fakeQuads = new ArrayList<>();
				}
				fakeQuads.add(quad);
			}
		}
		return fakeQuads == null ? List.of() : fakeQuads;
	}

	private static boolean iris$isFakePlayerQuad(Matrix4f pose, BakedQuad quad, Vector3f transformed) {
		for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
			pose.transformPosition(quad.position(i), transformed);
			if (transformed.y >= FAKE_PLAYER_Y_THRESHOLD) {
				return true;
			}
		}
		return false;
	}

	private static void iris$submitQuadOverlay(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, int packedOverlay,
											   List<BakedQuad> quads, RenderType renderType, ArmorLayer armorLayer, int color, PlayerModelType modelType) {
		submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, vertexConsumer) -> {
			LimbBounds[] slimArmBounds = modelType == PlayerModelType.SLIM && armorLayer.widensSlimArms()
				? iris$collectArmBounds(pose, quads) : null;
			int submitted = 0;
			for (BakedQuad quad : quads) {
				int limbIndex = iris$decodeLimbIndex(pose, quad);
				iris$emitExpandedQuad(pose, vertexConsumer, quad, armorLayer, limbIndex, packedLight, packedOverlay, color, slimArmBounds);
				submitted++;
			}

			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-third-geometry")) {
				WynncraftDebugLog.info("mount-armor-overlay-third-geometry",
					"[WynnIris MountArmor] submitted third-layer armor={} quads={}/{}",
					armorLayer, submitted, quads.size());
			}
		});
	}

	private static void iris$submitExpandedSkull(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight,
												 SkullModelBase modelBase, RenderType renderType, int color, float scale) {
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.scale(-scale, -scale, scale);

		SkullModelBase.State state = new SkullModelBase.State();
		state.yRot = 180.0F;
		state.animationPos = 0.0F;
		submitNodeCollector.submitModel(modelBase, state, poseStack, renderType, packedLight,
			OverlayTexture.NO_OVERLAY, color, null);
		poseStack.popPose();
	}

	private static int iris$decodeLimbIndex(PoseStack.Pose pose, BakedQuad quad) {
		Vector3f transformed = pose.pose().transformPosition(quad.position(0), new Vector3f());
		if (transformed.y < 2.0F * FAKE_PLAYER_Y_POSITION_RADIX) {
			return -1;
		}

		int metadata = (int) transformed.y - 2 * FAKE_PLAYER_Y_POSITION_RADIX;
		return (metadata / FAKE_PLAYER_Y_POSITION_RADIX / FAKE_PLAYER_STEVE_ALEX_RADIX / FAKE_PLAYER_LIMB_FADE_RADIX) % FAKE_PLAYER_LIMB_INDEX_RADIX;
	}

	private static LimbBounds[] iris$collectArmBounds(PoseStack.Pose pose, List<BakedQuad> quads) {
		LimbBounds[] bounds = new LimbBounds[FAKE_PLAYER_LIMB_INDEX_RADIX];
		for (BakedQuad quad : quads) {
			int limbIndex = iris$decodeLimbIndex(pose, quad);
			if (limbIndex != FAKE_PLAYER_LEFT_ARM && limbIndex != FAKE_PLAYER_RIGHT_ARM) {
				continue;
			}
			LimbBounds limbBounds = bounds[limbIndex];
			if (limbBounds == null) {
				limbBounds = new LimbBounds();
				bounds[limbIndex] = limbBounds;
			}
			for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
				limbBounds.include(quad.position(vertex).x());
			}
		}
		return bounds;
	}

	private static void iris$emitExpandedQuad(PoseStack.Pose pose, VertexConsumer vertexConsumer, BakedQuad quad,
											  ArmorLayer armorLayer, int limbIndex, int packedLight, int packedOverlay, int color, LimbBounds[] slimArmBounds) {
		Matrix4f matrix = pose.pose();
		Vector3fc normal = quad.direction().getUnitVec3f();
		Vector3f transformed = new Vector3f();
		int light = LightTexture.lightCoordsWithEmission(packedLight, quad.lightEmission());
		float expand = armorLayer.expandFor(limbIndex);
		LimbBounds limbBounds = slimArmBounds == null || limbIndex < 0 || limbIndex >= slimArmBounds.length ? null : slimArmBounds[limbIndex];

		for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
			Vector3fc position = quad.position(vertex);
			float x = position.x() + normal.x() * expand;
			if (limbBounds != null && limbBounds.hasWidth()) {
				float side = Math.signum(position.x() - limbBounds.centerX());
				x += side * SLIM_ARM_ARMOR_WIDTH_EXPAND;
			}
			matrix.transformPosition(
				x,
				position.y() + normal.y() * expand,
				position.z() + normal.z() * expand,
				transformed);
			long packedUv = quad.packedUV(vertex);
			vertexConsumer.addVertex(transformed.x(), transformed.y(), transformed.z())
				.setColor(color)
				.setUv(UVPair.unpackU(packedUv), UVPair.unpackV(packedUv))
				.setOverlay(packedOverlay)
				.setLight(light)
				.setNormal(pose, normal.x(), normal.y(), normal.z());
		}
	}

	private static OverlayTextures iris$getOrCreateTextures(Minecraft minecraft, LocalPlayer player) {
		int reloadCount = CapturedRenderingState.INSTANCE.getTextureReloadCount();
		if (reloadCount != seenTextureReloadCount) {
			iris$clearCache(minecraft);
			seenTextureReloadCount = reloadCount;
		}

		ArmorSnapshot armor = iris$getArmorForOverlay(player);
		if (armor == null || !armor.hasAnyStack()) {
			return null;
		}

		PlayerModelType modelType = player.getSkin().model();
		int headSignal = iris$getWynncraftEffectSignalRgb(armor.head);
		int chestSignal = iris$getWynncraftEffectSignalRgb(armor.chest);
		int legsSignal = iris$getWynncraftEffectSignalRgb(armor.legs);
		int feetSignal = iris$getWynncraftEffectSignalRgb(armor.feet);
		ArmorKey key = new ArmorKey(reloadCount, modelType,
			ItemStack.hashItemAndComponents(armor.head),
			ItemStack.hashItemAndComponents(armor.chest),
			ItemStack.hashItemAndComponents(armor.legs),
			ItemStack.hashItemAndComponents(armor.feet),
			headSignal, chestSignal, legsSignal, feetSignal);

		OverlayTextures cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		OverlayTextures created = iris$buildTextures(minecraft, modelType, armor.head, armor.chest, armor.legs, armor.feet);
		if (CACHE.size() > 16) {
			iris$clearCache(minecraft);
		}
		CACHE.put(key, created);
		return created;
	}

	private static ArmorSnapshot iris$getArmorForOverlay(LocalPlayer player) {
		ArmorSnapshot current = iris$readCurrentArmor(player);
		if (!iris$isLikelyMountState(player) || current.hasAnyStack()) {
			iris$rememberArmor(current);
			return current;
		}

		if (lastKnownArmor != null && lastKnownArmor.matches(player)) {
			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-cache-fallback")) {
				WynncraftDebugLog.info("mount-armor-overlay-cache-fallback",
					"[WynnIris MountArmor] live armor slots are empty in mount state; using cached slots=[{},{},{},{}]",
					iris$itemName(lastKnownArmor.head), iris$itemName(lastKnownArmor.chest),
					iris$itemName(lastKnownArmor.legs), iris$itemName(lastKnownArmor.feet));
			}
			return lastKnownArmor;
		}

		return current;
	}

	private static ArmorSnapshot iris$readCurrentArmor(LocalPlayer player) {
		return new ArmorSnapshot(
			player.getUUID(),
			iris$copyStack(player.getItemBySlot(EquipmentSlot.HEAD)),
			iris$copyStack(player.getItemBySlot(EquipmentSlot.CHEST)),
			iris$copyStack(player.getItemBySlot(EquipmentSlot.LEGS)),
			iris$copyStack(player.getItemBySlot(EquipmentSlot.FEET))
		);
	}

	private static ItemStack iris$copyStack(ItemStack stack) {
		return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
	}

	private static void iris$rememberArmor(ArmorSnapshot armor) {
		if (lastKnownArmor != null && lastKnownArmor.sameStacks(armor)) {
			return;
		}

		lastKnownArmor = armor;
		if (WynncraftDebugLog.shouldLog("mount-armor-overlay-armor-cache")) {
			WynncraftDebugLog.info("mount-armor-overlay-armor-cache",
				"[WynnIris MountArmor] cached local armor slots=[{},{},{},{}]",
				iris$itemName(armor.head), iris$itemName(armor.chest), iris$itemName(armor.legs), iris$itemName(armor.feet));
		}
	}

	private static OverlayTextures iris$buildTextures(Minecraft minecraft, PlayerModelType modelType, ItemStack head, ItemStack chest,
													  ItemStack legs, ItemStack feet) {
		NativeImage outerHead = new NativeImage(64, 64, true);
		NativeImage outerBody = new NativeImage(64, 64, true);
		NativeImage outerBoots = new NativeImage(64, 64, true);
		NativeImage leggings = new NativeImage(64, 64, true);
		outerHead.fillRect(0, 0, 64, 64, 0);
		outerBody.fillRect(0, 0, 64, 64, 0);
		outerBoots.fillRect(0, 0, 64, 64, 0);
		leggings.fillRect(0, 0, 64, 64, 0);

		boolean hasOuterHead = false;
		boolean hasOuterBody = false;
		boolean hasOuterBoots = false;
		boolean hasLeggings = false;
		try {
			hasOuterHead |= iris$compositeArmorPiece(minecraft, outerHead, head, EquipmentClientInfo.LayerType.HUMANOID, CopyTarget.OUTER_HEAD, modelType);
			hasOuterBody |= iris$compositeArmorPiece(minecraft, outerBody, chest, EquipmentClientInfo.LayerType.HUMANOID, CopyTarget.OUTER_CHEST, modelType);
			hasOuterBoots |= iris$compositeArmorPiece(minecraft, outerBoots, feet, EquipmentClientInfo.LayerType.HUMANOID, CopyTarget.OUTER_FEET, modelType);
			hasLeggings |= iris$compositeArmorPiece(minecraft, leggings, legs, EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS, CopyTarget.LEGGINGS, modelType);

			int headEffectId = iris$getWynncraftEffectId(iris$getWynncraftEffectSignalRgb(head));
			int chestEffectId = iris$getWynncraftEffectId(iris$getWynncraftEffectSignalRgb(chest));
			int legsEffectId = iris$getWynncraftEffectId(iris$getWynncraftEffectSignalRgb(legs));
			int feetEffectId = iris$getWynncraftEffectId(iris$getWynncraftEffectSignalRgb(feet));
			if (hasOuterHead) {
				iris$applyStaticTintEffect(outerHead, headEffectId);
			}
			if (hasOuterBody) {
				iris$applyStaticTintEffect(outerBody, chestEffectId);
			}
			if (hasOuterBoots) {
				iris$applyStaticTintEffect(outerBoots, feetEffectId);
			}
			if (hasLeggings) {
				iris$applyStaticTintEffect(leggings, legsEffectId);
			}

			Identifier outerHeadId = hasOuterHead ? iris$registerTexture(minecraft, "outer_head", outerHead) : null;
			Identifier outerBodyId = hasOuterBody ? iris$registerTexture(minecraft, "outer_body", outerBody) : null;
			Identifier outerBootsId = hasOuterBoots ? iris$registerTexture(minecraft, "outer_boots", outerBoots) : null;
			Identifier leggingsId = hasLeggings ? iris$registerTexture(minecraft, "leggings", leggings) : null;
			RenderType outerHeadRenderType = outerHeadId == null ? null : RenderTypes.entityTranslucent(outerHeadId);
			RenderType outerBodyRenderType = outerBodyId == null ? null : RenderTypes.entityTranslucent(outerBodyId);
			RenderType outerBootsRenderType = outerBootsId == null ? null : RenderTypes.entityTranslucent(outerBootsId);
			RenderType leggingsRenderType = leggingsId == null ? null : RenderTypes.entityTranslucent(leggingsId);
			int outerHeadColor = iris$overlaySignalColor(head, OUTER_OVERLAY_ALPHA);
			int outerBodyColor = iris$overlaySignalColor(chest, OUTER_OVERLAY_ALPHA);
			int outerBootsColor = iris$overlaySignalColor(feet, OUTER_OVERLAY_ALPHA);
			int leggingsColor = iris$overlaySignalColor(legs, LEGGINGS_OVERLAY_ALPHA);

			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-cache")) {
				WynncraftDebugLog.info("mount-armor-overlay-cache",
					"[WynnIris MountArmor] built overlay textures model={} outerHead={} outerBody={} outerBoots={} leggings={} effects=[{},{},{},{}] baked=[{},{},{},{}] colors=[{},{},{},{}] slots=[{},{},{},{}]",
					modelType, outerHeadId, outerBodyId, outerBootsId, leggingsId,
					headEffectId, chestEffectId, feetEffectId, legsEffectId,
					iris$isStaticTintEffect(headEffectId), iris$isStaticTintEffect(chestEffectId),
					iris$isStaticTintEffect(feetEffectId), iris$isStaticTintEffect(legsEffectId),
					String.format("0x%08X", outerHeadColor), String.format("0x%08X", outerBodyColor),
					String.format("0x%08X", outerBootsColor), String.format("0x%08X", leggingsColor),
					iris$itemName(head), iris$itemName(chest), iris$itemName(legs), iris$itemName(feet));
			}

			return new OverlayTextures(outerHeadId, outerBodyId, outerBootsId, leggingsId,
				outerHeadRenderType, outerBodyRenderType, outerBootsRenderType, leggingsRenderType,
				outerHeadColor, outerBodyColor, outerBootsColor, leggingsColor);
		} finally {
			if (!hasOuterHead) {
				outerHead.close();
			}
			if (!hasOuterBody) {
				outerBody.close();
			}
			if (!hasOuterBoots) {
				outerBoots.close();
			}
			if (!hasLeggings) {
				leggings.close();
			}
		}
	}

	private static boolean iris$compositeArmorPiece(Minecraft minecraft, NativeImage target, ItemStack stack, EquipmentClientInfo.LayerType layerType,
													CopyTarget copyTarget, PlayerModelType modelType) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}

		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		Optional<ResourceKey<EquipmentAsset>> fallbackAssetId = equippable == null ? Optional.empty() : equippable.assetId();
		Optional<ResourceKey<EquipmentAsset>> customAssetId = iris$getWynncraftCustomArmorAsset(stack);
		Optional<ResourceKey<EquipmentAsset>> assetId = customAssetId.or(() -> fallbackAssetId);
		if (assetId.isEmpty()) {
			iris$logMissingEquipment(stack, "no-equippable-asset");
			return false;
		}

		EquipmentAssetManager assets = ((EntityRenderDispatcherAccessor) minecraft.getEntityRenderDispatcher()).iris$getEquipmentAssets();
		EquipmentClientInfo clientInfo = assets.get(assetId.get());
		List<EquipmentClientInfo.Layer> layers = clientInfo.getLayers(layerType);
		if (layers.isEmpty() && fallbackAssetId.isPresent() && !fallbackAssetId.equals(assetId)) {
			assetId = fallbackAssetId;
			clientInfo = assets.get(assetId.get());
			layers = clientInfo.getLayers(layerType);
		}
		if (layers.isEmpty()) {
			iris$logMissingEquipment(stack, "no-layers-" + layerType.getSerializedName());
			return false;
		}

		if (WynncraftDebugLog.shouldLog("mount-armor-overlay-asset-choice-" + layerType.getSerializedName() + "-" + iris$itemName(stack))) {
			WynncraftDebugLog.info("mount-armor-overlay-asset-choice-" + layerType.getSerializedName() + "-" + iris$itemName(stack),
				"[WynnIris MountArmor] stack={} layerType={} itemModel={} customModelData={} customAsset={} fallbackAsset={} chosenAsset={} textureLayers={}",
				iris$itemName(stack), layerType.getSerializedName(), stack.get(DataComponents.ITEM_MODEL),
				iris$customModelDataSummary(stack), customAssetId.map(ResourceKey::identifier).orElse(null),
				fallbackAssetId.map(ResourceKey::identifier).orElse(null), assetId.get().identifier(), layers.size());
		}

		int dyeColor = customAssetId.isPresent() ? 0 : DyedItemColor.getOrDefault(stack, 0);
		boolean changed = false;
		for (EquipmentClientInfo.Layer layer : layers) {
			int layerColor = iris$getColorForLayer(layer, dyeColor);
			if (layerColor == 0 || layer.usePlayerTexture()) {
				continue;
			}

			Identifier texture = layer.getTextureLocation(layerType);
			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-assets")) {
				WynncraftDebugLog.info("mount-armor-overlay-assets",
					"[WynnIris MountArmor] stack={} asset={} layerType={} texture={} color={}",
					iris$itemName(stack), assetId.get().identifier(), layerType.getSerializedName(), texture, String.format("0x%08X", layerColor));
			}

			try (NativeImage source = iris$readTexture(minecraft.getResourceManager(), texture)) {
				if (source == null) {
					iris$logMissingEquipment(stack, "missing-texture-" + texture);
					continue;
				}
				iris$copyArmorTexture(source, target, layerColor, copyTarget, modelType);
				changed = true;
			}
		}
		return changed;
	}

	private static Optional<ResourceKey<EquipmentAsset>> iris$getWynncraftCustomArmorAsset(ItemStack stack) {
		Optional<ResourceKey<EquipmentAsset>> itemModelAsset = iris$getWynncraftArmorAsset(stack.get(DataComponents.ITEM_MODEL));
		if (itemModelAsset.isPresent()) {
			return itemModelAsset;
		}

		CustomModelData customModelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (customModelData == null) {
			return Optional.empty();
		}

		String stringModelData = customModelData.getString(0);
		Optional<ResourceKey<EquipmentAsset>> stringAsset = iris$getWynncraftArmorAsset(stringModelData);
		if (stringAsset.isPresent()) {
			return stringAsset;
		}

		Float modelData = customModelData.getFloat(0);
		if (modelData == null || modelData < 1.0F) {
			return Optional.empty();
		}

		int assetIndex = (int) Math.floor(modelData);
		if (assetIndex < 1 || assetIndex > WYNNCRAFT_ARMOR_ASSETS.length) {
			return Optional.empty();
		}

		return Optional.of(EquipmentAssets.createId(WYNNCRAFT_ARMOR_ASSETS[assetIndex - 1]));
	}

	private static Optional<ResourceKey<EquipmentAsset>> iris$getWynncraftArmorAsset(Identifier modelId) {
		if (modelId == null || !modelId.getPath().startsWith("item/wynn/armor/")) {
			return Optional.empty();
		}
		return iris$getWynncraftArmorAsset(modelId.getPath());
	}

	private static Optional<ResourceKey<EquipmentAsset>> iris$getWynncraftArmorAsset(String modelPath) {
		if (modelPath == null || modelPath.isBlank()) {
			return Optional.empty();
		}

		String assetName = modelPath;
		int slash = assetName.lastIndexOf('/');
		if (slash >= 0) {
			assetName = assetName.substring(slash + 1);
		}

		for (String suffix : new String[] { "_helmet", "_chestplate", "_leggings", "_boots" }) {
			if (assetName.endsWith(suffix)) {
				assetName = assetName.substring(0, assetName.length() - suffix.length());
				break;
			}
		}

		for (String knownAsset : WYNNCRAFT_ARMOR_ASSETS) {
			if (knownAsset.equals(assetName)) {
				return Optional.of(EquipmentAssets.createId(knownAsset));
			}
		}
		return Optional.empty();
	}

	private static int iris$overlaySignalColor(ItemStack stack, int overlayAlpha) {
		int signalRgb = iris$getWynncraftEffectSignalRgb(stack);
		int effectId = iris$getWynncraftEffectId(signalRgb);
		int alpha = effectId == 0 || iris$isStaticTintEffect(effectId) ? overlayAlpha : EFFECT_OVERLAY_ALPHA_BASE + effectId;
		return ((alpha & 0xFF) << 24) | DEFAULT_OVERLAY_RGB;
	}

	public static void cacheRenderedArmorSignal(ItemStack stack, int color) {
		if (!BuildConfig.WYNNIRIS_EXPERIMENTAL || stack == null || stack.isEmpty() || iris$getWynncraftCustomArmorAsset(stack).isEmpty()) {
			return;
		}

		int signalRgb = iris$decodeWynncraftEffectSignalRgb(color);
		if (signalRgb == DEFAULT_OVERLAY_RGB) {
			return;
		}

		int stackKey = ItemStack.hashItemAndComponents(stack);
		Integer previous = RENDERED_ARMOR_SIGNAL_CACHE.put(stackKey, signalRgb);
		if (RENDERED_ARMOR_SIGNAL_CACHE.size() > MAX_RENDERED_ARMOR_SIGNAL_CACHE) {
			RENDERED_ARMOR_SIGNAL_CACHE.clear();
			RENDERED_ARMOR_SIGNAL_CACHE.put(stackKey, signalRgb);
		}

		if ((previous == null || previous != signalRgb)
			&& WynncraftDebugLog.shouldLog("mount-armor-overlay-render-signal-" + stackKey)) {
			WynncraftDebugLog.info("mount-armor-overlay-render-signal-" + stackKey,
				"[WynnIris MountArmor] cached render-time signal stack={} itemModel={} customModelData={} raw={} signal={} effect={}",
				iris$itemName(stack), stack.get(DataComponents.ITEM_MODEL), iris$customModelDataSummary(stack),
				String.format("0x%08X", color), String.format("0x%06X", signalRgb), (signalRgb >>> 16) & 0xFF);
		}
	}

	private static int iris$getWynncraftEffectSignalRgb(ItemStack stack) {
		if (stack == null || stack.isEmpty() || iris$getWynncraftCustomArmorAsset(stack).isEmpty()) {
			return DEFAULT_OVERLAY_RGB;
		}

		int stackKey = ItemStack.hashItemAndComponents(stack);
		Integer cachedSignal = RENDERED_ARMOR_SIGNAL_CACHE.get(stackKey);
		if (cachedSignal != null && cachedSignal != DEFAULT_OVERLAY_RGB) {
			iris$logEffectSignal(stack, "render-cache", cachedSignal, cachedSignal);
			return cachedSignal;
		}

		CustomModelData customModelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (customModelData != null) {
			for (int i = 0; i < customModelData.colors().size(); i++) {
				Integer color = customModelData.getColor(i);
				if (color == null) {
					continue;
				}
				int signalRgb = iris$decodeWynncraftEffectSignalRgb(color);
				if (signalRgb != DEFAULT_OVERLAY_RGB) {
					iris$logEffectSignal(stack, "custom-model-data-color-" + i, color, signalRgb);
					return signalRgb;
				}
			}
		}

		int dyeColor = DyedItemColor.getOrDefault(stack, 0);
		int signalRgb = iris$decodeWynncraftEffectSignalRgb(dyeColor);
		if (signalRgb != DEFAULT_OVERLAY_RGB) {
			iris$logEffectSignal(stack, "dyed-item-color", dyeColor, signalRgb);
			return signalRgb;
		}

		iris$logEffectSignal(stack, "none", dyeColor, DEFAULT_OVERLAY_RGB);
		return DEFAULT_OVERLAY_RGB;
	}

	private static int iris$decodeWynncraftEffectSignalRgb(int color) {
		int rgb = color & 0x00FFFFFF;
		int r = (rgb >>> 16) & 0xFF;
		int g = (rgb >>> 8) & 0xFF;
		int b = rgb & 0xFF;
		if (r >= 1 && r <= MAX_WYNNCRAFT_EFFECT_ID && g == 255 && b == 0) {
			return rgb;
		}
		return DEFAULT_OVERLAY_RGB;
	}

	private static int iris$getWynncraftEffectId(int signalRgb) {
		if (signalRgb == DEFAULT_OVERLAY_RGB) {
			return 0;
		}
		int effectId = (signalRgb >>> 16) & 0xFF;
		return effectId >= 1 && effectId <= MAX_WYNNCRAFT_EFFECT_ID ? effectId : 0;
	}

	private static boolean iris$isStaticTintEffect(int effectId) {
		return effectId >= 15 && effectId <= 24;
	}

	private static void iris$applyStaticTintEffect(NativeImage image, int effectId) {
		if (!iris$isStaticTintEffect(effectId)) {
			return;
		}

		int tintRgb = iris$staticTintEffectRgb(effectId);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int pixel = image.getPixel(x, y);
				if (((pixel >>> 24) & 0xFF) == 0) {
					continue;
				}
				image.setPixel(x, y, iris$applyTintEffect(pixel, tintRgb));
			}
		}
	}

	private static int iris$staticTintEffectRgb(int effectId) {
		return switch (effectId) {
			case 15 -> 0x5082E6;
			case 16 -> 0x1EE682;
			case 17 -> 0xEB4646;
			case 18 -> 0x64BEBE;
			case 19 -> 0xFA7814;
			case 20 -> 0x323232;
			case 21 -> 0xFAE6E6;
			case 22 -> 0xFF96C8;
			case 23 -> 0xC83CE6;
			case 24 -> 0xF0F050;
			default -> 0xFFFFFF;
		};
	}

	private static int iris$applyTintEffect(int argb, int tintRgb) {
		int a = (argb >>> 24) & 0xFF;
		float r = ((argb >>> 16) & 0xFF) / 255.0F;
		float g = ((argb >>> 8) & 0xFF) / 255.0F;
		float b = (argb & 0xFF) / 255.0F;
		float brightness = (float) Math.pow(0.2126F * r + 0.7152F * g + 0.0722F * b, 0.7F);
		float mix = iris$smoothstep(0.7F, 1.0F, brightness) * 0.5F;
		float tintR = ((tintRgb >>> 16) & 0xFF) / 255.0F;
		float tintG = ((tintRgb >>> 8) & 0xFF) / 255.0F;
		float tintB = (tintRgb & 0xFF) / 255.0F;
		int outR = iris$toByte(iris$mix(tintR * brightness, 1.0F, mix));
		int outG = iris$toByte(iris$mix(tintG * brightness, 1.0F, mix));
		int outB = iris$toByte(iris$mix(tintB * brightness, 1.0F, mix));
		return (a << 24) | (outR << 16) | (outG << 8) | outB;
	}

	private static float iris$smoothstep(float edge0, float edge1, float value) {
		float x = iris$clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static float iris$mix(float from, float to, float amount) {
		return from * (1.0F - amount) + to * amount;
	}

	private static int iris$toByte(float value) {
		return Math.round(iris$clamp(value, 0.0F, 1.0F) * 255.0F);
	}

	private static float iris$clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static void iris$logEffectSignal(ItemStack stack, String source, int rawColor, int signalRgb) {
		int stackKey = ItemStack.hashItemAndComponents(stack);
		String key = "mount-armor-overlay-effect-signal-" + stackKey;
		if (!WynncraftDebugLog.shouldLog(key)) {
			return;
		}

		WynncraftDebugLog.info(key,
			"[WynnIris MountArmor] effect signal source={} stack={} itemModel={} customModelData={} raw={} selected={} effect={}",
			source, iris$itemName(stack), stack.get(DataComponents.ITEM_MODEL), iris$customModelDataSummary(stack),
			String.format("0x%08X", rawColor), String.format("0x%06X", signalRgb),
			signalRgb == DEFAULT_OVERLAY_RGB ? 0 : (signalRgb >>> 16) & 0xFF);
	}

	private static String iris$customModelDataSummary(ItemStack stack) {
		CustomModelData customModelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (customModelData == null) {
			return "none";
		}
		return "floats=" + customModelData.floats()
			+ ", strings=" + customModelData.strings()
			+ ", flags=" + customModelData.flags()
			+ ", colors=" + customModelData.colors();
	}

	private static int iris$getColorForLayer(EquipmentClientInfo.Layer layer, int dyeColor) {
		if (layer.dyeable().isPresent()) {
			int undyed = layer.dyeable().get().colorWhenUndyed().orElse(0);
			return dyeColor != 0 ? dyeColor : undyed;
		}
		return -1;
	}

	private static NativeImage iris$readTexture(ResourceManager resourceManager, Identifier texture) {
		Optional<Resource> resource = resourceManager.getResource(texture);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream input = resource.get().open()) {
			return NativeImage.read(input);
		} catch (IOException e) {
			WynncraftDebugLog.info("mount-armor-overlay-texture-error",
				"[WynnIris MountArmor] failed reading equipment texture {}: {}", texture, e.toString());
			return null;
		}
	}

	private static void iris$copyArmorTexture(NativeImage source, NativeImage target, int layerColor, CopyTarget copyTarget, PlayerModelType modelType) {
		if (copyTarget == CopyTarget.OUTER_HEAD) {
			iris$copyFacesOffset(source, target, layerColor, SOURCE_HEAD, TARGET_HEAD, 32, 0);
			return;
		}

		if (copyTarget == CopyTarget.OUTER_CHEST) {
			iris$copyFacesOffset(source, target, layerColor, SOURCE_BODY, TARGET_BODY, 0, 16);
			if (modelType == PlayerModelType.SLIM) {
				iris$copyFacesOffset(source, target, layerColor, SOURCE_RIGHT_ARM, TARGET_RIGHT_ARM_ALEX, 0, 16,
					NO_FACE_MIRROR_X, NO_FACE_MIRROR_X, SLIM_ARM_KEEP_OUTER_X);
				iris$copyFacesOffset(source, target, layerColor, SOURCE_RIGHT_ARM_FOR_LEFT_LIMB, TARGET_LEFT_ARM_ALEX, 16, 0,
					LEFT_ARM_MIRROR_X, LEFT_ARM_MIRROR_Y, SLIM_ARM_KEEP_OUTER_X);
			} else {
				iris$copyFacesOffset(source, target, layerColor, SOURCE_RIGHT_ARM, TARGET_RIGHT_ARM_STEVE, 0, 16);
				iris$copyFacesOffset(source, target, layerColor, SOURCE_RIGHT_ARM_FOR_LEFT_LIMB,
					TARGET_LEFT_ARM_STEVE, 16, 0, LEFT_ARM_MIRROR_X, LEFT_ARM_MIRROR_Y);
			}
			return;
		}

		if (copyTarget == CopyTarget.OUTER_FEET) {
			iris$copyFacesOffset(source, target, layerColor, SOURCE_RIGHT_LEG, TARGET_RIGHT_LEG, 0, 16);
			iris$copyFacesOffset(source, target, layerColor, SOURCE_RIGHT_LEG_FOR_LEFT_LIMB, TARGET_LEFT_LEG, -16, 0, LEFT_LEG_BOOT_MIRROR_X);
			return;
		}

		iris$copyFacesOffset(source, target, layerColor, SOURCE_BODY, TARGET_BODY, 0, 16);
		iris$copyFacesOffset(source, target, layerColor, SOURCE_RIGHT_LEG, TARGET_RIGHT_LEG, 0, 16);
		iris$copyFacesOffset(source, target, layerColor, SOURCE_RIGHT_LEG_FOR_LEFT_LIMB, TARGET_LEFT_LEG, -16, 0);
	}

	private static void iris$copyFacesOffset(NativeImage source, NativeImage target, int layerColor, FaceRect[] sourceFaces, FaceRect[] targetFaces,
											 int overlayX, int overlayY) {
		iris$copyFacesOffset(source, target, layerColor, sourceFaces, targetFaces, overlayX, overlayY, NO_FACE_MIRROR_X);
	}

	private static void iris$copyFacesOffset(NativeImage source, NativeImage target, int layerColor, FaceRect[] sourceFaces, FaceRect[] targetFaces,
											 int overlayX, int overlayY, boolean[] mirrorX) {
		iris$copyFacesOffset(source, target, layerColor, sourceFaces, targetFaces, overlayX, overlayY, mirrorX, NO_FACE_MIRROR_X);
	}

	private static void iris$copyFacesOffset(NativeImage source, NativeImage target, int layerColor, FaceRect[] sourceFaces, FaceRect[] targetFaces,
											 int overlayX, int overlayY, boolean[] mirrorX, boolean[] mirrorY) {
		iris$copyFacesOffset(source, target, layerColor, sourceFaces, targetFaces, overlayX, overlayY, mirrorX, mirrorY, NO_FACE_MIRROR_X);
	}

	private static void iris$copyFacesOffset(NativeImage source, NativeImage target, int layerColor, FaceRect[] sourceFaces, FaceRect[] targetFaces,
											 int overlayX, int overlayY, boolean[] mirrorX, boolean[] mirrorY, boolean[] keepOuterX) {
		for (int i = 0; i < sourceFaces.length; i++) {
			iris$copyScaledRect(source, target, sourceFaces[i], targetFaces[i].offset(overlayX, overlayY), layerColor, mirrorX[i], mirrorY[i], keepOuterX[i]);
		}
	}

	private static void iris$copyScaledRect(NativeImage source, NativeImage target, FaceRect sourceRect, FaceRect targetRect, int layerColor,
											boolean mirrorX, boolean mirrorY, boolean keepOuterX) {
		for (int y = 0; y < targetRect.height; y++) {
			for (int x = 0; x < targetRect.width; x++) {
				int sourceOffsetX = x * sourceRect.width / targetRect.width;
				int sourceOffsetY = y * sourceRect.height / targetRect.height;
				if (keepOuterX && !mirrorX && sourceRect.width > targetRect.width) {
					sourceOffsetX += sourceRect.width - targetRect.width;
				}
				int sourceX = sourceRect.x + (mirrorX ? sourceRect.width - 1 - sourceOffsetX : sourceOffsetX);
				int sourceY = sourceRect.y + (mirrorY ? sourceRect.height - 1 - sourceOffsetY : sourceOffsetY);
				if (sourceX < 0 || sourceX >= source.getWidth() || sourceY < 0 || sourceY >= source.getHeight()) {
					continue;
				}
				int targetX = targetRect.x + x;
				int targetY = targetRect.y + y;
				if (targetX < 0 || targetX >= target.getWidth() || targetY < 0 || targetY >= target.getHeight()) {
					continue;
				}

				int pixel = source.getPixel(sourceX, sourceY);
				if (((pixel >>> 24) & 0xFF) == 0) {
					continue;
				}
				iris$alphaComposite(target, targetX, targetY, iris$tint(pixel, layerColor));
			}
		}
	}

	private static int iris$tint(int argb, int color) {
		if (color == -1) {
			return argb;
		}
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >>> 16) & 0xFF;
		int g = (argb >>> 8) & 0xFF;
		int b = argb & 0xFF;
		int cr = (color >>> 16) & 0xFF;
		int cg = (color >>> 8) & 0xFF;
		int cb = color & 0xFF;
		return (a << 24) | ((r * cr / 255) << 16) | ((g * cg / 255) << 8) | (b * cb / 255);
	}

	private static void iris$alphaComposite(NativeImage target, int x, int y, int src) {
		int dst = target.getPixel(x, y);
		int sa = (src >>> 24) & 0xFF;
		int da = (dst >>> 24) & 0xFF;
		int outA = sa + da * (255 - sa) / 255;
		if (outA <= 0) {
			target.setPixel(x, y, 0);
			return;
		}

		int sr = (src >>> 16) & 0xFF;
		int sg = (src >>> 8) & 0xFF;
		int sb = src & 0xFF;
		int dr = (dst >>> 16) & 0xFF;
		int dg = (dst >>> 8) & 0xFF;
		int db = dst & 0xFF;
		int outR = (sr * sa + dr * da * (255 - sa) / 255) / outA;
		int outG = (sg * sa + dg * da * (255 - sa) / 255) / outA;
		int outB = (sb * sa + db * da * (255 - sa) / 255) / outA;
		target.setPixel(x, y, (outA << 24) | (outR << 16) | (outG << 8) | outB);
	}

	private static Identifier iris$registerTexture(Minecraft minecraft, String layer, NativeImage image) {
		Identifier id = Identifier.fromNamespaceAndPath("iris", "dynamic/wynn_mount_armor/" + layer + "_" + textureSequence++);
		DynamicTexture texture = new DynamicTexture(() -> "wynn_mount_armor_" + layer, image);
		minecraft.getTextureManager().register(id, texture);
		texture.upload();
		return id;
	}

	private static void iris$clearCache(Minecraft minecraft) {
		for (OverlayTextures textures : CACHE.values()) {
			if (textures.outerHeadId != null) {
				minecraft.getTextureManager().release(textures.outerHeadId);
			}
			if (textures.outerBodyId != null) {
				minecraft.getTextureManager().release(textures.outerBodyId);
			}
			if (textures.outerBootsId != null) {
				minecraft.getTextureManager().release(textures.outerBootsId);
			}
			if (textures.leggingsId != null) {
				minecraft.getTextureManager().release(textures.leggingsId);
			}
		}
		CACHE.clear();
	}

	private static void iris$logMissingEquipment(ItemStack stack, String reason) {
		WynncraftDebugLog.info("mount-armor-overlay-missing-" + reason,
			"[WynnIris MountArmor] skipping stack={} reason={}", iris$itemName(stack), reason);
	}

	private static String iris$itemName(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "empty";
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id == null ? stack.getItem().toString() : id.toString();
	}

	private static final FaceRect[] SOURCE_HEAD = iris$faces(
		iris$face(8, 0, 8, 8), iris$face(16, 0, 8, 8), iris$face(0, 8, 8, 8),
		iris$face(8, 8, 8, 8), iris$face(16, 8, 8, 8), iris$face(24, 8, 8, 8));
	private static final FaceRect[] SOURCE_BODY = iris$faces(
		iris$face(20, 16, 8, 4), iris$face(28, 16, 8, 4), iris$face(16, 20, 4, 12),
		iris$face(20, 20, 8, 12), iris$face(28, 20, 4, 12), iris$face(32, 20, 8, 12));
	private static final FaceRect[] SOURCE_RIGHT_ARM = iris$faces(
		iris$face(44, 16, 4, 4), iris$face(48, 16, 4, 4), iris$face(40, 20, 4, 12),
		iris$face(44, 20, 4, 12), iris$face(48, 20, 4, 12), iris$face(52, 20, 4, 12));
	private static final FaceRect[] SOURCE_RIGHT_LEG = iris$faces(
		iris$face(4, 16, 4, 4), iris$face(8, 16, 4, 4), iris$face(0, 20, 4, 12),
		iris$face(4, 20, 4, 12), iris$face(8, 20, 4, 12), iris$face(12, 20, 4, 12));
	private static final FaceRect[] SOURCE_RIGHT_ARM_FOR_LEFT_LIMB = iris$faces(
		iris$face(44, 16, 4, 4), iris$face(48, 16, 4, 4), iris$face(48, 20, 4, 12),
		iris$face(44, 20, 4, 12), iris$face(40, 20, 4, 12), iris$face(52, 20, 4, 12));
	private static final FaceRect[] SOURCE_RIGHT_LEG_FOR_LEFT_LIMB = iris$faces(
		iris$face(4, 16, 4, 4), iris$face(8, 16, 4, 4), iris$face(8, 20, 4, 12),
		iris$face(4, 20, 4, 12), iris$face(0, 20, 4, 12), iris$face(12, 20, 4, 12));

	private static final FaceRect[] TARGET_HEAD = iris$faces(
		iris$face(8, 0, 8, 8), iris$face(16, 0, 8, 8), iris$face(0, 8, 8, 8),
		iris$face(8, 8, 8, 8), iris$face(16, 8, 8, 8), iris$face(24, 8, 8, 8));
	private static final FaceRect[] TARGET_BODY = iris$faces(
		iris$face(20, 16, 8, 4), iris$face(28, 16, 8, 4), iris$face(16, 20, 4, 12),
		iris$face(20, 20, 8, 12), iris$face(28, 20, 4, 12), iris$face(32, 20, 8, 12));
	private static final FaceRect[] TARGET_RIGHT_ARM_STEVE = iris$faces(
		iris$face(44, 16, 4, 4), iris$face(48, 16, 4, 4), iris$face(40, 20, 4, 12),
		iris$face(44, 20, 4, 12), iris$face(48, 20, 4, 12), iris$face(52, 20, 4, 12));
	private static final FaceRect[] TARGET_LEFT_ARM_STEVE = iris$faces(
		iris$face(36, 48, 4, 4), iris$face(40, 48, 4, 4), iris$face(32, 52, 4, 12),
		iris$face(36, 52, 4, 12), iris$face(40, 52, 4, 12), iris$face(44, 52, 4, 12));
	private static final FaceRect[] TARGET_RIGHT_ARM_ALEX = iris$faces(
		iris$face(44, 16, 3, 4), iris$face(47, 16, 3, 4), iris$face(40, 20, 4, 12),
		iris$face(44, 20, 3, 12), iris$face(47, 20, 4, 12), iris$face(51, 20, 3, 12));
	private static final FaceRect[] TARGET_LEFT_ARM_ALEX = iris$faces(
		iris$face(36, 48, 3, 4), iris$face(39, 48, 3, 4), iris$face(32, 52, 4, 12),
		iris$face(36, 52, 3, 12), iris$face(39, 52, 4, 12), iris$face(43, 52, 3, 12));
	private static final FaceRect[] TARGET_RIGHT_LEG = iris$faces(
		iris$face(4, 16, 4, 4), iris$face(8, 16, 4, 4), iris$face(0, 20, 4, 12),
		iris$face(4, 20, 4, 12), iris$face(8, 20, 4, 12), iris$face(12, 20, 4, 12));
	private static final FaceRect[] TARGET_LEFT_LEG = iris$faces(
		iris$face(20, 48, 4, 4), iris$face(24, 48, 4, 4), iris$face(16, 52, 4, 12),
		iris$face(20, 52, 4, 12), iris$face(24, 52, 4, 12), iris$face(28, 52, 4, 12));

	private static final boolean[] NO_FACE_MIRROR_X = iris$faceMirrors(false, false, false, false, false, false);
	private static final boolean[] LEFT_ARM_MIRROR_X = iris$faceMirrors(true, false, false, true, false, true);
	private static final boolean[] LEFT_ARM_MIRROR_Y = iris$faceMirrors(true, false, false, false, false, false);
	private static final boolean[] LEFT_LEG_BOOT_MIRROR_X = iris$faceMirrors(false, false, true, false, true, false);
	private static final boolean[] SLIM_ARM_KEEP_OUTER_X = iris$faceMirrors(true, true, false, true, false, true);

	private static FaceRect[] iris$faces(FaceRect top, FaceRect bottom, FaceRect right, FaceRect front, FaceRect left, FaceRect back) {
		return new FaceRect[] { top, bottom, right, front, left, back };
	}

	private static boolean[] iris$faceMirrors(boolean top, boolean bottom, boolean right, boolean front, boolean left, boolean back) {
		return new boolean[] { top, bottom, right, front, left, back };
	}

	private static FaceRect iris$face(int x, int y, int width, int height) {
		return new FaceRect(x, y, width, height);
	}

	private record FaceRect(int x, int y, int width, int height) {
		FaceRect offset(int dx, int dy) {
			return new FaceRect(x + dx, y + dy, width, height);
		}
	}

	private enum CopyTarget {
		OUTER_HEAD,
		OUTER_CHEST,
		OUTER_FEET,
		LEGGINGS
	}

	private enum ArmorLayer {
		OUTER(SKIN_OVERLAY_TO_OUTER_ARMOR_EXPAND, SKIN_OVERLAY_TO_OUTER_LEG_ARMOR_EXPAND),
		BOOTS(SKIN_OVERLAY_TO_BOOTS_ARMOR_EXPAND, SKIN_OVERLAY_TO_BOOTS_ARMOR_EXPAND),
		LEGGINGS(SKIN_OVERLAY_TO_LEGGINGS_ARMOR_EXPAND, SKIN_OVERLAY_TO_LEGGINGS_LEG_ARMOR_EXPAND);

		private final float bodyExpand;
		private final float legExpand;

		ArmorLayer(float bodyExpand, float legExpand) {
			this.bodyExpand = bodyExpand;
			this.legExpand = legExpand;
		}

		private float expandFor(int limbIndex) {
			return limbIndex == FAKE_PLAYER_LEFT_LEG || limbIndex == FAKE_PLAYER_RIGHT_LEG ? legExpand : bodyExpand;
		}

		private boolean widensSlimArms() {
			return this == OUTER;
		}
	}

	private static final class LimbBounds {
		private float minX = Float.POSITIVE_INFINITY;
		private float maxX = Float.NEGATIVE_INFINITY;

		private void include(float x) {
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
		}

		private boolean hasWidth() {
			return maxX > minX;
		}

		private float centerX() {
			return (minX + maxX) * 0.5F;
		}
	}

	private record ArmorKey(int reloadCount, PlayerModelType modelType, int head, int chest, int legs, int feet,
							int headSignal, int chestSignal, int legsSignal, int feetSignal) {
	}

	private record ArmorSnapshot(UUID playerId, ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet) {
		boolean matches(LocalPlayer player) {
			return playerId.equals(player.getUUID());
		}

		boolean hasAnyStack() {
			return !head.isEmpty() || !chest.isEmpty() || !legs.isEmpty() || !feet.isEmpty();
		}

		boolean sameStacks(ArmorSnapshot other) {
			return playerId.equals(other.playerId)
				&& ItemStack.matches(head, other.head)
				&& ItemStack.matches(chest, other.chest)
				&& ItemStack.matches(legs, other.legs)
				&& ItemStack.matches(feet, other.feet);
		}
	}

	private record OverlayTextures(Identifier outerHeadId, Identifier outerBodyId, Identifier outerBootsId, Identifier leggingsId,
								   RenderType outerHeadRenderType, RenderType outerBodyRenderType, RenderType outerBootsRenderType,
								   RenderType leggingsRenderType, int outerHeadColor, int outerBodyColor, int outerBootsColor,
								   int leggingsColor) {
		boolean isEmpty() {
			return outerHeadId == null && outerBodyId == null && outerBootsId == null && leggingsId == null;
		}
	}
}
