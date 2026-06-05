package net.irisshaders.iris.compat.general;

import net.irisshaders.iris.Iris;
import net.minecraft.world.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Interop with the Wynntils companion mod.
 * <p>
 * Wynntils hides certain things from the world (most notably the lootrun / activity
 * "beacons", which on Wynncraft are server-spawned {@code Display.ItemDisplay} entities)
 * by flipping a per-entity render flag. Its own gate lives in {@code LevelRendererMixin},
 * which wraps {@code EntityRenderDispatcher.submit(...)} inside
 * {@code LevelRenderer.submitEntities(...)} and skips the call when the flag is false.
 * <p>
 * Iris' shadow pass renders entities through its own path
 * ({@code ShadowRenderer.extractVisibleEntities}) and never goes through
 * {@code submitEntities}, so a beacon hidden by Wynntils still casts a shadow. This helper
 * lets the shadow pass honour Wynntils' decision.
 * <p>
 * Wynntils' {@code EntityMixin} implements {@code com.wynntils.mc.extension.EntityExtension}
 * on {@code Entity.class}, so at runtime every entity carries {@code isRendered()}. We read
 * it reflectively so WynnIris keeps working (and this simply no-ops) when Wynntils is absent
 * or changes its internals.
 */
public final class WynntilsCompat {
	private static final String EXTENSION_CLASS = "com.wynntils.mc.extension.EntityExtension";

	private static final Class<?> ENTITY_EXTENSION = resolveExtension();
	private static final MethodHandle IS_RENDERED = resolveIsRendered(ENTITY_EXTENSION);

	private WynntilsCompat() {
	}

	private static Class<?> resolveExtension() {
		try {
			return Class.forName(EXTENSION_CLASS);
		} catch (ClassNotFoundException e) {
			// Wynntils isn't installed - the common case. Stay silent.
			return null;
		} catch (Throwable t) {
			Iris.logger.warn("[WynnIris] Failed to look up Wynntils' EntityExtension; hidden beacons will still cast shadows.", t);
			return null;
		}
	}

	private static MethodHandle resolveIsRendered(Class<?> extension) {
		if (extension == null) {
			return null;
		}

		try {
			return MethodHandles.lookup().findVirtual(extension, "isRendered", MethodType.methodType(boolean.class));
		} catch (ReflectiveOperationException e) {
			Iris.logger.warn("[WynnIris] Found Wynntils' EntityExtension but could not resolve isRendered(); hidden beacons will still cast shadows. (Wynntils API change?)", e);
			return null;
		}
	}

	/**
	 * @return {@code true} if Wynntils has hidden this entity from rendering (e.g. a disabled
	 * beacon), meaning Iris should not cast a shadow for it. Returns {@code false} when
	 * Wynntils is absent, the entity is visible, or the flag can't be read.
	 */
	public static boolean isHiddenByWynntils(Entity entity) {
		if (IS_RENDERED == null || entity == null || !ENTITY_EXTENSION.isInstance(entity)) {
			return false;
		}

		try {
			return !(boolean) IS_RENDERED.invoke(entity);
		} catch (Throwable t) {
			return false;
		}
	}
}
