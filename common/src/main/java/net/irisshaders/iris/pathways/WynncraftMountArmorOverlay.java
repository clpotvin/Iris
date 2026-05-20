package net.irisshaders.iris.pathways;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.BuildConfig;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.gui.option.WynncraftDebugLog;
import net.irisshaders.iris.mixin.EntityRenderDispatcherAccessor;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
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
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.Equippable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class WynncraftMountArmorOverlay {
	public static final int OUTER_SIGNAL_COLOR = 0xFF00FC00;
	public static final int LEGGINGS_SIGNAL_COLOR = 0xFF00FA00;

	private static final Map<ArmorKey, OverlayTextures> CACHE = new HashMap<>();
	private static int seenTextureReloadCount = -1;
	private static long textureSequence;
	private static ArmorSnapshot lastKnownArmor;
	private static final float FAKE_PLAYER_Y_THRESHOLD = 1024.0F;
	private static final float OUTER_SIGNAL_GREEN = 252.0F / 255.0F;
	private static final float LEGGINGS_SIGNAL_GREEN = 250.0F / 255.0F;

	private WynncraftMountArmorOverlay() {
	}

	public static void onClientTick(Minecraft minecraft) {
		if (!BuildConfig.WYNNIRIS_EXPERIMENTAL) {
			return;
		}

		LocalPlayer player = minecraft.player;
		if (minecraft.level == null || player == null) {
			lastKnownArmor = null;
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
				"[WynnIris MountArmor] submitting overlays profile={} context={} outer={} leggings={}",
				profile, displayContext, textures.outerId, textures.leggingsId);
		}

		if (textures.outerId != null) {
			SkullBlockRenderer.submitSkull(null, 180.0F, 0.0F, poseStack, submitNodeCollector, packedLight, modelBase,
				textures.outerRenderType, OUTER_SIGNAL_COLOR, null);
		}
		if (textures.leggingsId != null) {
			SkullBlockRenderer.submitSkull(null, 180.0F, 0.0F, poseStack, submitNodeCollector, packedLight, modelBase,
				textures.leggingsRenderType, LEGGINGS_SIGNAL_COLOR, null);
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
				"[WynnIris MountArmor] submitting item-layer overlays context={} fakeQuads={}/{} outer={} leggings={}",
				displayContext, fakeQuads.size(), quads.size(), textures.outerId, textures.leggingsId);
		}

		if (textures.outerId != null) {
			iris$submitQuadOverlay(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads, textures.outerRenderType, OUTER_SIGNAL_GREEN);
		}
		if (textures.leggingsId != null) {
			iris$submitQuadOverlay(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads, textures.leggingsRenderType, LEGGINGS_SIGNAL_GREEN);
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
											   List<BakedQuad> quads, RenderType renderType, float signalGreen) {
		submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, vertexConsumer) -> {
			for (BakedQuad quad : quads) {
				vertexConsumer.putBulkData(pose, quad, 0.0F, signalGreen, 0.0F, 1.0F, packedLight, packedOverlay);
			}
		});
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
		ArmorKey key = new ArmorKey(reloadCount, modelType,
			ItemStack.hashItemAndComponents(armor.head),
			ItemStack.hashItemAndComponents(armor.chest),
			ItemStack.hashItemAndComponents(armor.legs),
			ItemStack.hashItemAndComponents(armor.feet));

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
		NativeImage outer = new NativeImage(64, 64, true);
		NativeImage leggings = new NativeImage(64, 64, true);
		outer.fillRect(0, 0, 64, 64, 0);
		leggings.fillRect(0, 0, 64, 64, 0);

		boolean hasOuter = false;
		boolean hasLeggings = false;
		try {
			hasOuter |= iris$compositeArmorPiece(minecraft, outer, head, EquipmentClientInfo.LayerType.HUMANOID, CopyTarget.OUTER, modelType);
			hasOuter |= iris$compositeArmorPiece(minecraft, outer, chest, EquipmentClientInfo.LayerType.HUMANOID, CopyTarget.OUTER, modelType);
			hasOuter |= iris$compositeArmorPiece(minecraft, outer, feet, EquipmentClientInfo.LayerType.HUMANOID, CopyTarget.OUTER, modelType);
			hasLeggings |= iris$compositeArmorPiece(minecraft, leggings, legs, EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS, CopyTarget.LEGGINGS, modelType);

			Identifier outerId = hasOuter ? iris$registerTexture(minecraft, "outer", outer) : null;
			Identifier leggingsId = hasLeggings ? iris$registerTexture(minecraft, "leggings", leggings) : null;
			RenderType outerRenderType = outerId == null ? null : RenderTypes.entityTranslucent(outerId);
			RenderType leggingsRenderType = leggingsId == null ? null : RenderTypes.entityTranslucent(leggingsId);

			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-cache")) {
				WynncraftDebugLog.info("mount-armor-overlay-cache",
					"[WynnIris MountArmor] built overlay textures model={} outer={} leggings={} slots=[{},{},{},{}]",
					modelType, outerId, leggingsId, iris$itemName(head), iris$itemName(chest), iris$itemName(legs), iris$itemName(feet));
			}

			return new OverlayTextures(outerId, leggingsId, outerRenderType, leggingsRenderType);
		} finally {
			if (!hasOuter) {
				outer.close();
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
		Optional<ResourceKey<EquipmentAsset>> assetId = equippable == null ? Optional.empty() : equippable.assetId();
		if (assetId.isEmpty()) {
			iris$logMissingEquipment(stack, "no-equippable-asset");
			return false;
		}

		EquipmentAssetManager assets = ((EntityRenderDispatcherAccessor) minecraft.getEntityRenderDispatcher()).iris$getEquipmentAssets();
		EquipmentClientInfo clientInfo = assets.get(assetId.get());
		List<EquipmentClientInfo.Layer> layers = clientInfo.getLayers(layerType);
		if (layers.isEmpty()) {
			iris$logMissingEquipment(stack, "no-layers-" + layerType.getSerializedName());
			return false;
		}

		int dyeColor = DyedItemColor.getOrDefault(stack, 0);
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
		if (copyTarget == CopyTarget.OUTER) {
			iris$copyFacesWithOverlay(source, target, layerColor, SOURCE_HEAD, TARGET_HEAD, 32, 0);
			iris$copyFacesWithOverlay(source, target, layerColor, SOURCE_BODY, TARGET_BODY, 0, 16);
			iris$copyFacesWithOverlay(source, target, layerColor, SOURCE_RIGHT_ARM, modelType == PlayerModelType.SLIM ? TARGET_RIGHT_ARM_ALEX : TARGET_RIGHT_ARM_STEVE, 0, 16);
			iris$copyFacesWithOverlay(source, target, layerColor, SOURCE_RIGHT_ARM_FOR_LEFT_LIMB,
				modelType == PlayerModelType.SLIM ? TARGET_LEFT_ARM_ALEX : TARGET_LEFT_ARM_STEVE, 16, 0, LEFT_ARM_MIRROR_X);
			iris$copyFacesWithOverlay(source, target, layerColor, SOURCE_RIGHT_LEG, TARGET_RIGHT_LEG, 0, 16);
			iris$copyFacesWithOverlay(source, target, layerColor, SOURCE_RIGHT_LEG_FOR_LEFT_LIMB, TARGET_LEFT_LEG, -16, 0);
			return;
		}

		iris$copyFacesWithOverlay(source, target, layerColor, SOURCE_BODY, TARGET_BODY, 0, 16);
		iris$copyFacesWithOverlay(source, target, layerColor, SOURCE_RIGHT_LEG, TARGET_RIGHT_LEG, 0, 16);
		iris$copyFacesWithOverlay(source, target, layerColor, SOURCE_RIGHT_LEG_FOR_LEFT_LIMB, TARGET_LEFT_LEG, -16, 0);
	}

	private static void iris$copyFaces(NativeImage source, NativeImage target, int layerColor, FaceRect[] sourceFaces, FaceRect[] targetFaces) {
		iris$copyFaces(source, target, layerColor, sourceFaces, targetFaces, NO_FACE_MIRROR_X);
	}

	private static void iris$copyFaces(NativeImage source, NativeImage target, int layerColor, FaceRect[] sourceFaces, FaceRect[] targetFaces,
									   boolean[] mirrorX) {
		for (int i = 0; i < sourceFaces.length; i++) {
			iris$copyScaledRect(source, target, sourceFaces[i], targetFaces[i], layerColor, mirrorX[i]);
		}
	}

	private static void iris$copyFacesWithOverlay(NativeImage source, NativeImage target, int layerColor, FaceRect[] sourceFaces, FaceRect[] targetFaces,
												  int overlayX, int overlayY) {
		iris$copyFacesWithOverlay(source, target, layerColor, sourceFaces, targetFaces, overlayX, overlayY, NO_FACE_MIRROR_X);
	}

	private static void iris$copyFacesWithOverlay(NativeImage source, NativeImage target, int layerColor, FaceRect[] sourceFaces, FaceRect[] targetFaces,
												  int overlayX, int overlayY, boolean[] mirrorX) {
		iris$copyFaces(source, target, layerColor, sourceFaces, targetFaces, mirrorX);
		for (int i = 0; i < sourceFaces.length; i++) {
			iris$copyScaledRect(source, target, sourceFaces[i], targetFaces[i].offset(overlayX, overlayY), layerColor, mirrorX[i]);
		}
	}

	private static void iris$copyScaledRect(NativeImage source, NativeImage target, FaceRect sourceRect, FaceRect targetRect, int layerColor,
											boolean mirrorX) {
		for (int y = 0; y < targetRect.height; y++) {
			for (int x = 0; x < targetRect.width; x++) {
				int sourceOffsetX = x * sourceRect.width / targetRect.width;
				int sourceX = sourceRect.x + (mirrorX ? sourceRect.width - 1 - sourceOffsetX : sourceOffsetX);
				int sourceY = sourceRect.y + y * sourceRect.height / targetRect.height;
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
			if (textures.outerId != null) {
				minecraft.getTextureManager().release(textures.outerId);
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
	private static final boolean[] LEFT_ARM_MIRROR_X = iris$faceMirrors(false, false, false, true, false, true);

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
		OUTER,
		LEGGINGS
	}

	private record ArmorKey(int reloadCount, PlayerModelType modelType, int head, int chest, int legs, int feet) {
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

	private record OverlayTextures(Identifier outerId, Identifier leggingsId, RenderType outerRenderType, RenderType leggingsRenderType) {
		boolean isEmpty() {
			return outerId == null && leggingsId == null;
		}
	}
}
