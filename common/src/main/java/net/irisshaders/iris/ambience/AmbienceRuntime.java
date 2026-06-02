package net.irisshaders.iris.ambience;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.gui.option.WynncraftDebugLog;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AmbienceRuntime {
	private static final long SWITCH_COOLDOWN_MILLIS = 1500;
	private static final long FAILURE_BACKOFF_MILLIS = 30000;

	private static AmbiencePackManager.ResolvedProfile activeProfile;
	private static AmbiencePackManager.ResolvedProfile pendingProfile;
	private static AmbiencePackManager.ResolvedProfile deferredProfile;
	private static boolean pendingRestore;
	private static boolean deferredRestore;
	private static long lastSwitchMillis;
	private static String failedProfileKey;
	private static long failedProfileRetryAfterMillis;
	private static String firstFrameSwitchAction;
	private static String firstFrameSwitchProfileKey;
	private static long firstFrameSwitchCompleteNanos;

	private AmbienceRuntime() {
	}

	public static void onClientTick(Minecraft minecraft) {
		long tickStartNanos = System.nanoTime();
		if (minecraft.level == null || minecraft.player == null) {
			pendingProfile = null;
			pendingRestore = false;
			deferredProfile = null;
			deferredRestore = false;
			logDiagnostics(tickStartNanos, 0, "no_level");
			return;
		}

		long now = System.currentTimeMillis();
		if (!Iris.getIrisConfig().areShadersEnabled()) {
			pendingProfile = null;
			deferredProfile = null;
			if (deferredRestore) {
				applyDeferredRestore(now);
				logDiagnostics(tickStartNanos, 0, "restore_shaders_disabled");
				return;
			}
			if (activeProfile != null && !pendingRestore) {
				pendingRestore = true;
			}
			logDiagnostics(tickStartNanos, 0, "shaders_disabled");
			return;
		}

		if (deferredRestore) {
			applyDeferredRestore(now);
			logDiagnostics(tickStartNanos, 0, "restore_deferred");
			return;
		}
		if (deferredProfile != null) {
			applyDeferredProfileSwitch(now);
			logDiagnostics(tickStartNanos, 0, "switch_deferred");
			return;
		}

		if (!IrisVideoSettings.wynncraftAmbienceEnabled) {
			if (activeProfile != null && !pendingRestore) {
				pendingProfile = null;
				pendingRestore = true;
			}
			logDiagnostics(tickStartNanos, 0, "disabled");
			return;
		}

		AmbiencePackManager manager = AmbiencePackManager.getInstance();
		manager.reloadIfNeeded();
		if (activeProfile != null && !activeProfile.resolvedShaderPack().equals(Iris.getCurrentPackName())) {
			activeProfile = null;
		}
		long resolveStartNanos = System.nanoTime();
		Optional<AmbiencePackManager.ResolvedProfile> resolved = manager.resolveCurrentProfile(minecraft, IrisVideoSettings.wynncraftSelectedAmbiencePack);
		long resolveNanos = System.nanoTime() - resolveStartNanos;
		if (resolved.isEmpty()) {
			if (activeProfile != null && !pendingRestore) {
				pendingProfile = null;
				pendingRestore = true;
			}
			logDiagnostics(tickStartNanos, resolveNanos, "no_profile");
			return;
		}

		AmbiencePackManager.ResolvedProfile target = resolved.get();
		if (activeProfile != null && activeProfile.key().equals(target.key())) {
			logDiagnostics(tickStartNanos, resolveNanos, "same_profile");
			return;
		}
		if (pendingProfile != null && pendingProfile.key().equals(target.key())) {
			logDiagnostics(tickStartNanos, resolveNanos, "pending_profile");
			return;
		}
		if (target.key().equals(failedProfileKey) && now < failedProfileRetryAfterMillis) {
			logDiagnostics(tickStartNanos, resolveNanos, "failed_backoff");
			return;
		}
		if (now - lastSwitchMillis < SWITCH_COOLDOWN_MILLIS) {
			logDiagnostics(tickStartNanos, resolveNanos, "cooldown");
			return;
		}

		pendingRestore = false;
		pendingProfile = target;
		logDiagnostics(tickStartNanos, resolveNanos, "scheduled_switch");
	}

	public static void afterFrameRendered() {
		logFirstFrameAfterSwitch();
		if (pendingRestore) {
			scheduleDeferredRestore();
			return;
		}
		if (pendingProfile != null) {
			scheduleDeferredProfileSwitch();
		}
	}

	public static void reset() {
		pendingProfile = null;
		pendingRestore = false;
		deferredProfile = null;
		deferredRestore = false;
		firstFrameSwitchAction = null;
		firstFrameSwitchProfileKey = null;
		firstFrameSwitchCompleteNanos = 0L;
	}

	public static void invalidateActiveProfile() {
		activeProfile = null;
		pendingProfile = null;
		pendingRestore = false;
		deferredProfile = null;
		deferredRestore = false;
		firstFrameSwitchAction = null;
		firstFrameSwitchProfileKey = null;
		firstFrameSwitchCompleteNanos = 0L;
	}

	public static WarmupResult warmSelectedPackProfiles() {
		long warmStartNanos = System.nanoTime();
		AmbiencePackManager manager = AmbiencePackManager.getInstance();
		manager.reloadIfNeeded();
		List<AmbiencePackManager.ResolvedProfile> profiles = manager.resolveProfiles(IrisVideoSettings.wynncraftSelectedAmbiencePack);
		AmbiencePackManager.ResolvedProfile restoreProfile = activeProfile;
		int beforePoolResources = Iris.getAmbienceRenderTargetPoolResourceCount();
		long beforePoolBytes = Iris.getAmbienceRenderTargetPoolEstimatedBytes();
		int warmed = 0;
		int failed = 0;

		for (AmbiencePackManager.ResolvedProfile profile : profiles) {
			try {
				Map<String, String> options = profile.profile().options == null ? Map.of() : profile.profile().options;
				if (Iris.applyCachedTransientShaderPackForRuntimeSwitch(profile.key(), profile.resolvedShaderPack(), options)) {
					warmed++;
				} else {
					failed++;
				}
			} catch (IOException | RuntimeException e) {
				failed++;
				Iris.logger.warn("Failed to warm ambience profile {}", profile.profileId(), e);
			}
		}

		try {
			if (restoreProfile == null) {
				Iris.restoreConfiguredShaderPackForRuntimeSwitch();
			} else {
				Map<String, String> restoreOptions = restoreProfile.profile().options == null ? Map.of() : restoreProfile.profile().options;
				Iris.applyCachedTransientShaderPackForRuntimeSwitch(restoreProfile.key(), restoreProfile.resolvedShaderPack(), restoreOptions);
			}
		} catch (IOException | RuntimeException e) {
			failed++;
			Iris.logger.warn("Failed to restore ambience profile after cache warmup", e);
		}

		pendingProfile = null;
		pendingRestore = false;
		deferredProfile = null;
		deferredRestore = false;
		Iris.trimTransientShaderPackCacheToBudget();
		lastSwitchMillis = System.currentTimeMillis();
		logWarmupSummary(profiles.size(), warmed, failed, beforePoolResources, beforePoolBytes, System.nanoTime() - warmStartNanos);
		return new WarmupResult(warmed, failed);
	}

	public static void markFirstFrameAfterSwitch(String action, String profileKey) {
		firstFrameSwitchAction = action == null || action.isBlank() ? "unknown" : action;
		firstFrameSwitchProfileKey = profileKey == null || profileKey.isBlank() ? "unknown" : profileKey;
		firstFrameSwitchCompleteNanos = System.nanoTime();
	}

	private static void scheduleDeferredRestore() {
		deferredRestore = true;
		pendingProfile = null;
		pendingRestore = false;
	}

	private static void applyDeferredRestore(long now) {
		deferredRestore = false;
		try {
			Iris.restoreConfiguredShaderPackForRuntimeSwitch();
		} catch (IOException e) {
			Iris.logger.warn("Failed to restore configured shader pack after ambience was disabled", e);
		}
		activeProfile = null;
		pendingProfile = null;
		pendingRestore = false;
		lastSwitchMillis = now;
	}

	private static void scheduleDeferredProfileSwitch() {
		AmbiencePackManager.ResolvedProfile target = pendingProfile;
		pendingProfile = null;
		deferredProfile = target;
	}

	private static void applyDeferredProfileSwitch(long now) {
		AmbiencePackManager.ResolvedProfile target = deferredProfile;
		deferredProfile = null;

		boolean loaded = false;
		try {
			Map<String, String> options = target.profile().options == null ? Map.of() : target.profile().options;
			loaded = Iris.applyCachedTransientShaderPackForRuntimeSwitch(target.key(), target.resolvedShaderPack(), options);
		} catch (IOException e) {
			Iris.logger.warn("Failed to apply ambience profile {}", target.profileId(), e);
		}

		if (loaded) {
			activeProfile = target;
			if (target.key().equals(failedProfileKey)) {
				failedProfileKey = null;
				failedProfileRetryAfterMillis = 0;
			}
		} else {
			failedProfileKey = target.key();
			failedProfileRetryAfterMillis = now + FAILURE_BACKOFF_MILLIS;
			WynncraftDebugLog.info("ambience-profile-load-failed",
				"Ambience profile {} failed to load; retrying after {} ms", target.profileId(), FAILURE_BACKOFF_MILLIS);
		}
		lastSwitchMillis = now;
	}

	private static void logDiagnostics(long tickStartNanos, long resolveNanos, String state) {
		if (!WynncraftDebugLog.shouldLog("ambience-runtime-diagnostics")) {
			return;
		}
		AmbiencePackManager manager = AmbiencePackManager.getInstance();
		long tickMicros = (System.nanoTime() - tickStartNanos) / 1_000L;
		WynncraftDebugLog.info("ambience-runtime-diagnostics",
			"Ambience diagnostics: state={} active={} pending={} resolve={}us tick={}us regions={} installedShaderPacks={} transientContexts={} transientContextBudget={} retainedContexts={} programBinarySupported={} programBinaryFormats={} programBinaries={} programBinaryBytes={} targetPoolResources={} targetPoolBytes={} targetPoolBreakdown={} targetPoolHits={} targetPoolMisses={} targetPoolReleases={} targetPoolDestroyed={} profilePressures={}",
			state,
			activeProfile == null ? "none" : activeProfile.profileId(),
			pendingProfile == null ? "none" : pendingProfile.profileId(),
			resolveNanos / 1_000L,
			tickMicros,
			manager.getLastResolvedRegionCount(),
			manager.getInstalledShaderPackCount(),
			Iris.getTransientShaderPackContextCount(),
			Iris.getTransientShaderPackContextBudgetLabel(),
			Iris.getRetainedShaderRuntimeContextCount(),
			Iris.isProgramBinaryCacheAvailable(),
			Iris.getProgramBinaryCacheFormatCount(),
			Iris.getProgramBinaryCacheEntryCount(),
			Iris.getProgramBinaryCacheBytes(),
			Iris.getAmbienceRenderTargetPoolResourceCount(),
			Iris.getAmbienceRenderTargetPoolEstimatedBytes(),
			Iris.getAmbienceRenderTargetPoolBreakdownSummary(),
			Iris.getAmbienceRenderTargetPoolHits(),
			Iris.getAmbienceRenderTargetPoolMisses(),
			Iris.getAmbienceRenderTargetPoolReleases(),
			Iris.getAmbienceRenderTargetPoolDestroyedResources(),
			Iris.getAmbienceRenderTargetPoolProfilePressureSummary());
	}

	private static void logFirstFrameAfterSwitch() {
		if (firstFrameSwitchCompleteNanos == 0L) {
			return;
		}
		long elapsedMicros = (System.nanoTime() - firstFrameSwitchCompleteNanos) / 1_000L;
		String action = firstFrameSwitchAction;
		String profileKey = firstFrameSwitchProfileKey;
		firstFrameSwitchAction = null;
		firstFrameSwitchProfileKey = null;
		firstFrameSwitchCompleteNanos = 0L;

		if (!WynncraftDebugLog.shouldLog("ambience-first-frame-after-switch")) {
			return;
		}
		WynncraftDebugLog.info("ambience-first-frame-after-switch",
			"Ambience first frame after switch: action={} profile={} elapsedSinceSwitch={}us poolResources={} poolBytes={} poolBreakdown={} profilePressures={}",
			action,
			profileKey,
			elapsedMicros,
			Iris.getAmbienceRenderTargetPoolResourceCount(),
			Iris.getAmbienceRenderTargetPoolEstimatedBytes(),
			Iris.getAmbienceRenderTargetPoolBreakdownSummary(),
			Iris.getAmbienceRenderTargetPoolProfilePressureSummary());
	}

	private static void logWarmupSummary(int requestedProfiles, int warmed, int failed, int beforePoolResources, long beforePoolBytes, long warmNanos) {
		if (!WynncraftDebugLog.shouldLog("ambience-warm-cache-summary")) {
			return;
		}
		WynncraftDebugLog.info("ambience-warm-cache-summary",
			"Ambience warm cache summary: requested={} warmed={} failed={} duration={}ms retainedContexts={} beforePoolResources={} afterPoolResources={} beforePoolBytes={} afterPoolBytes={} afterPoolBreakdown={} profilePressures={}",
			requestedProfiles,
			warmed,
			failed,
			warmNanos / 1_000_000L,
			Iris.getRetainedShaderRuntimeContextCount(),
			beforePoolResources,
			Iris.getAmbienceRenderTargetPoolResourceCount(),
			beforePoolBytes,
			Iris.getAmbienceRenderTargetPoolEstimatedBytes(),
			Iris.getAmbienceRenderTargetPoolBreakdownSummary(),
			Iris.getAmbienceRenderTargetPoolProfilePressureSummary());
	}

	public record WarmupResult(int warmed, int failed) {
	}
}
