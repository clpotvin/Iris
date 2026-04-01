package net.irisshaders.iris.vertices;

/**
 * Some annoying global state needed for rendering.
 */
public class ImmediateState {
	public static final ThreadLocal<Boolean> skipExtension = ThreadLocal.withInitial(() -> false);
	public static boolean isRenderingLevel = false;
	public static boolean usingTessellation = false;
	public static boolean renderWithExtendedVertexFormat = true;
	public static boolean bypass;
	public static boolean temporarilyIgnorePass;
	public static boolean safeToMultiply;
	public static boolean isRenderingBEs;
	// When true, endBatch() calls for ITEM_ENTITY_TRANSLUCENT_CULL are deferred
	// past beginTranslucents() so translucent entities render with sky already composited.
	public static boolean deferItemEntityTranslucentCull;
}
