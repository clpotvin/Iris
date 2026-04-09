package net.irisshaders.iris.mixin.entity_render_context;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.layer.BufferSourceWrapper;
import net.irisshaders.iris.layer.EntityRenderStateShard;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps entity rendering functions in order to create additional render layers
 * that provide context to shaders about what entity is currently being
 * rendered.
 */
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {
	@Unique
	private static final NamespacedId CURRENT_PLAYER = new NamespacedId("minecraft", "current_player");

	@Unique
	private static final NamespacedId CONVERTING_VILLAGER = new NamespacedId("minecraft", "zombie_villager_converting");

	@Unique
	private static final Object2ObjectMap<EntityType<?>, NamespacedId> ENTITY_IDS = new Object2ObjectOpenHashMap<>();

	@Unique
	private static long iris$lastAboveEntityDumpMs = 0;
	@Unique
	private static boolean iris$collectingAboveEntities = false;
	@Unique
	private static final java.util.Set<String> iris$aboveEntitySet = new java.util.LinkedHashSet<>();

	// Inject after MatrixStack#push since at this point we know that most cancellation checks have already passed.
	@Inject(method = "submit", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER))
	private <E extends Entity, S extends EntityRenderState> void iris$beginEntityRender(S entity, CameraRenderState cameraRenderState, double d, double e, double f, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
		// Debug: log unique entities above the player, dump every 5 seconds
		if (net.irisshaders.iris.gui.option.IrisVideoSettings.wynncraftDebugLogging && e > 2.0) {
			long now = System.currentTimeMillis();
			// Start a new collection window every 5 seconds
			if (now - iris$lastAboveEntityDumpMs > 5000) {
				if (!iris$aboveEntitySet.isEmpty()) {
					net.irisshaders.iris.Iris.logger.info("[WynnIris Debug] === {} unique entities above player ===", iris$aboveEntitySet.size());
					for (String line : iris$aboveEntitySet) {
						net.irisshaders.iris.Iris.logger.info("[WynnIris Debug] {}", line);
					}
					iris$aboveEntitySet.clear();
				}
				iris$lastAboveEntityDumpMs = now;
				iris$collectingAboveEntities = true;
			}
			// Only collect for 50ms after window opens (roughly 1 frame at 60fps)
			if (iris$collectingAboveEntities && (now - iris$lastAboveEntityDumpMs) < 50) {
				var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
				var camPos = cam.position();
				double wx = camPos.x() + d, wy = camPos.y() + e, wz = camPos.z() + f;
				String entityTypeName = entity.entityType != null
					? BuiltInRegistries.ENTITY_TYPE.getKey(entity.entityType).toString() : "unknown";
				String nameTag = entity.nameTag != null ? entity.nameTag.getString() : "none";
				String cls = entity.getClass().getSimpleName();
				// Key on type+approx position to deduplicate multi-layer renders of same entity
				String key = String.format(
					"type=%s class=%s world=(%.0f,%.0f,%.0f) delta=(%.1f,%.1f,%.1f) name=%s",
					entityTypeName, cls, wx, wy, wz, d, e, f, nameTag);
				iris$aboveEntitySet.add(key);
			}
		}

		Object2IntFunction<NamespacedId> entityIds = WorldRenderingSettings.INSTANCE.getEntityIds();

		if (entityIds == null || !ImmediateState.isRenderingLevel) {
			return;
		}

		int intId;

		// TODO: Add special types

		if (entity instanceof ZombieVillagerRenderState zombie && zombie.isConverting && WorldRenderingSettings.INSTANCE.hasVillagerConversionId()) {
			intId = entityIds.applyAsInt(CONVERTING_VILLAGER);
		} else if (entity instanceof AvatarRenderState ars && Minecraft.getInstance().getCameraEntity() instanceof AbstractClientPlayer acs && acs.getId() == ars.id) {
			if (entityIds.containsKey(CURRENT_PLAYER)) {
				intId = entityIds.getInt(CURRENT_PLAYER);
			} else {
				intId = entityIds.applyAsInt(ENTITY_IDS.computeIfAbsent(entity.entityType, k -> {
					Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.entityType);
					return new NamespacedId(entityId.getNamespace(), entityId.getPath());
				}));
			}
		} else {
			intId = entityIds.applyAsInt(ENTITY_IDS.computeIfAbsent(entity.entityType, k -> {
				Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.entityType);
				return new NamespacedId(entityId.getNamespace(), entityId.getPath());
			}));
		}

		CapturedRenderingState.INSTANCE.setCurrentEntity(intId);
	}

	// Inject before MatrixStack#pop so that our wrapper stack management operations naturally line up
	// with vanilla's MatrixStack management functions.
	@Inject(method = "submit", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"))
	private<E extends Entity, S extends EntityRenderState> void iris$endEntityRender(S entityRenderState, CameraRenderState cameraRenderState, double d, double e, double f, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
		CapturedRenderingState.INSTANCE.setCurrentEntity(0);
		CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
	}
}
