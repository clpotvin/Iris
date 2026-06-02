package net.irisshaders.iris.ambience;

public final class AmbienceSwitchTiming {
	private final String action;
	private final String profileKey;
	private final long startNanos;
	private long preparePipelineNanos;
	private long reapplySettingsNanos;
	private long levelRendererReloadNanos;
	private long renderStateRefreshNanos;
	private long profileActivationNanos;
	private long forceMainClearNanos;
	private long rebuildMainClearPassesNanos;
	private long forceShadowClearNanos;
	private long shadowSamplerRefreshNanos;
	private long customImageClearNanos;
	private long totalNanos;
	private boolean sameShaderPackAsPrevious;

	public AmbienceSwitchTiming(String action, String profileKey) {
		this.action = action == null || action.isBlank() ? "unknown" : action;
		this.profileKey = profileKey == null || profileKey.isBlank() ? "unknown" : profileKey;
		this.startNanos = System.nanoTime();
	}

	public String action() {
		return action;
	}

	public String profileKey() {
		return profileKey;
	}

	public boolean sameShaderPackAsPrevious() {
		return sameShaderPackAsPrevious;
	}

	public void setSameShaderPackAsPrevious(boolean sameShaderPackAsPrevious) {
		this.sameShaderPackAsPrevious = sameShaderPackAsPrevious;
	}

	public void addPreparePipelineNanos(long nanos) {
		preparePipelineNanos += Math.max(0L, nanos);
	}

	public void addReapplySettingsNanos(long nanos) {
		reapplySettingsNanos += Math.max(0L, nanos);
	}

	public void addLevelRendererReloadNanos(long nanos) {
		levelRendererReloadNanos += Math.max(0L, nanos);
	}

	public void addRenderStateRefreshNanos(long nanos) {
		renderStateRefreshNanos += Math.max(0L, nanos);
	}

	public void addProfileActivationNanos(long nanos) {
		profileActivationNanos += Math.max(0L, nanos);
	}

	public void addForceMainClearNanos(long nanos) {
		forceMainClearNanos += Math.max(0L, nanos);
	}

	public void addRebuildMainClearPassesNanos(long nanos) {
		rebuildMainClearPassesNanos += Math.max(0L, nanos);
	}

	public void addForceShadowClearNanos(long nanos) {
		forceShadowClearNanos += Math.max(0L, nanos);
	}

	public void addShadowSamplerRefreshNanos(long nanos) {
		shadowSamplerRefreshNanos += Math.max(0L, nanos);
	}

	public void addCustomImageClearNanos(long nanos) {
		customImageClearNanos += Math.max(0L, nanos);
	}

	public void finish() {
		totalNanos = System.nanoTime() - startNanos;
	}

	public long totalNanos() {
		return totalNanos == 0L ? System.nanoTime() - startNanos : totalNanos;
	}

	public String compactMicros() {
		return "action=" + action
			+ " profile=" + profileKey
			+ " total=" + micros(totalNanos()) + "us"
			+ " preparePipeline=" + micros(preparePipelineNanos) + "us"
			+ " reapplySettings=" + micros(reapplySettingsNanos) + "us"
			+ " levelRendererReload=" + micros(levelRendererReloadNanos) + "us"
			+ " renderStateRefresh=" + micros(renderStateRefreshNanos) + "us"
			+ " profileActivation=" + micros(profileActivationNanos) + "us"
			+ " sameShaderPack=" + sameShaderPackAsPrevious
			+ " forceMainClear=" + micros(forceMainClearNanos) + "us"
			+ " rebuildMainClearPasses=" + micros(rebuildMainClearPassesNanos) + "us"
			+ " forceShadowClear=" + micros(forceShadowClearNanos) + "us"
			+ " shadowSamplerRefresh=" + micros(shadowSamplerRefreshNanos) + "us"
			+ " customImageClear=" + micros(customImageClearNanos) + "us";
	}

	private static long micros(long nanos) {
		return nanos / 1_000L;
	}
}
