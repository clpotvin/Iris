package net.irisshaders.iris.ambience;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.gui.option.WynncraftDebugLog;
import net.irisshaders.iris.gui.screen.AmbienceWarmupScreen;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AmbienceRuntime {
	private static final long SWITCH_COOLDOWN_MILLIS = 1500;
	private static final long FAILURE_BACKOFF_MILLIS = 30000;
	private static final int WARMUP_FRAME_DELAY = 1;

	private static AmbiencePackManager.ResolvedProfile activeProfile;
	private static AmbiencePackManager.ResolvedProfile pendingProfile;
	private static AmbiencePackManager.ResolvedProfile deferredProfile;
	private static WarmupJob warmupJob;
	private static boolean pendingRestore;
	private static boolean deferredRestore;
	private static long lastSwitchMillis;
	private static String failedProfileKey;
	private static long failedProfileRetryAfterMillis;
	private static String firstFrameSwitchAction;
	private static String firstFrameSwitchProfileKey;
	private static long firstFrameSwitchCompleteNanos;
	private static String lastAutoWarmAttemptKey;

	private AmbienceRuntime() {
	}

	public static void onClientTick(Minecraft minecraft) {
		long tickStartNanos = System.nanoTime();
		if (minecraft.level == null || minecraft.player == null) {
			discardWarmupJob();
			pendingProfile = null;
			pendingRestore = false;
			deferredProfile = null;
			deferredRestore = false;
			logDiagnostics(tickStartNanos, 0, "no_level");
			return;
		}

		long now = System.currentTimeMillis();
		if (!Iris.getIrisConfig().areShadersEnabled()) {
			discardWarmupJob();
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

		// Cache warming is independent of the ambience toggle: users can prepare
		// the cache before enabling the feature.
		if (isWarmupRunning()) {
			logDiagnostics(tickStartNanos, 0, "warmup_running");
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

		if (maybeStartAutoWarmScreen(minecraft)) {
			logDiagnostics(tickStartNanos, 0, "auto_warm_scheduled");
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
		if (isWarmupRunning()) {
			processWarmupJob();
			return;
		}
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
		warmupJob = null;
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
		warmupJob = null;
		firstFrameSwitchAction = null;
		firstFrameSwitchProfileKey = null;
		firstFrameSwitchCompleteNanos = 0L;
	}

	public static WarmupStartResult startWarmSelectedPackProfiles(String reason) {
		return startWarmSelectedPackProfiles(reason, false);
	}

	public static WarmupProgress getWarmupProgress() {
		WarmupJob job = warmupJob;
		return job == null ? WarmupProgress.idle() : job.progress();
	}

	public static boolean isWarmupRunning() {
		return warmupJob != null && !warmupJob.complete;
	}

	public static void cancelWarmup() {
		WarmupJob job = warmupJob;
		if (job == null || job.complete) {
			return;
		}
		restoreWarmupJob(job, true);
	}

	private static WarmupStartResult startWarmSelectedPackProfiles(String reason, boolean auto) {
		// Warmup renders pipelines against the live level; onClientTick discards
		// the job as soon as the level is gone, so starting without one just
		// wedges the warmup screen.
		if (Minecraft.getInstance().level == null) {
			return new WarmupStartResult(false, false, 0, 0, "");
		}
		AmbiencePackManager manager = AmbiencePackManager.getInstance();
		manager.reloadIfNeeded();
		List<AmbiencePackManager.ResolvedProfile> profiles = manager.resolveProfiles(IrisVideoSettings.wynncraftSelectedAmbiencePack);
		String packId = IrisVideoSettings.wynncraftSelectedAmbiencePack == null ? "" : IrisVideoSettings.wynncraftSelectedAmbiencePack;
		String packDisplayName = profiles.isEmpty() ? packId : profiles.getFirst().loadedPack().pack().displayName();
		String warmupKey = createWarmupKey(packId, profiles);

		if (auto && warmupKey.equals(lastAutoWarmAttemptKey)) {
			return new WarmupStartResult(false, false, profiles.size(), countUncachedProfiles(profiles), packDisplayName);
		}
		if (auto) {
			lastAutoWarmAttemptKey = warmupKey;
		}
		if (profiles.isEmpty()) {
			return new WarmupStartResult(false, false, 0, 0, packDisplayName);
		}

		WarmupJob existing = warmupJob;
		if (existing != null && !existing.complete && existing.warmupKey.equals(warmupKey)) {
			return new WarmupStartResult(true, false, existing.profiles.size(), existing.remainingProfiles(), existing.packDisplayName);
		}

		int remainingProfiles = countUncachedProfiles(profiles);
		if (remainingProfiles == 0) {
			return new WarmupStartResult(false, true, profiles.size(), 0, packDisplayName);
		}

		warmupJob = new WarmupJob(
			warmupKey,
			packId,
			packDisplayName,
			profiles,
			activeProfile,
			reason == null || reason.isBlank() ? "manual" : reason,
			Iris.getAmbienceRenderTargetPoolResourceCount(),
			Iris.getAmbienceRenderTargetPoolEstimatedBytes());
		pendingProfile = null;
		pendingRestore = false;
		deferredProfile = null;
		deferredRestore = false;
		return new WarmupStartResult(true, false, profiles.size(), remainingProfiles, packDisplayName);
	}

	private static boolean maybeStartAutoWarmScreen(Minecraft minecraft) {
		if (!IrisVideoSettings.wynncraftAmbienceAutoWarmCache || !IrisVideoSettings.wynncraftAmbienceEnabled || minecraft.screen != null) {
			return false;
		}
		WarmupStartResult result = startWarmSelectedPackProfiles("auto", true);
		if (!result.started()) {
			return false;
		}
		minecraft.setScreen(new AmbienceWarmupScreen(null, true));
		return true;
	}

	private static int countUncachedProfiles(List<AmbiencePackManager.ResolvedProfile> profiles) {
		int count = 0;
		for (AmbiencePackManager.ResolvedProfile profile : profiles) {
			if (!Iris.hasTransientShaderPackContext(profile.key())) {
				count++;
			}
		}
		return count;
	}

	private static String createWarmupKey(String packId, List<AmbiencePackManager.ResolvedProfile> profiles) {
		StringBuilder builder = new StringBuilder(packId == null ? "" : packId);
		builder.append('|');
		for (AmbiencePackManager.ResolvedProfile profile : profiles) {
			builder.append(profile.key()).append(';');
		}
		return builder.toString();
	}

	private static void processWarmupJob() {
		WarmupJob job = warmupJob;
		if (job == null || job.complete) {
			return;
		}
		if (job.frameDelay > 0) {
			job.frameDelay--;
			return;
		}
		if (job.index < job.profiles.size()) {
			warmupNextProfile(job);
			return;
		}
		if (!job.restored) {
			restoreWarmupJob(job, false);
		}
	}

	private static void warmupNextProfile(WarmupJob job) {
		AmbiencePackManager.ResolvedProfile profile = job.profiles.get(job.index);
		long profileStartNanos = System.nanoTime();
		boolean skipped = false;
		boolean loaded = false;
		try {
			if (Iris.hasTransientShaderPackContext(profile.key())) {
				job.skipped++;
				skipped = true;
			} else {
				Map<String, String> options = profile.profile().options == null ? Map.of() : profile.profile().options;
				loaded = Iris.applyCachedTransientShaderPack(profile.key(), profile.resolvedShaderPack(), options);
				if (loaded) {
					job.warmed++;
				} else {
					job.failed++;
				}
			}
		} catch (IOException | RuntimeException e) {
			job.failed++;
			Iris.logger.warn("Failed to warm ambience profile {}", profile.profileId(), e);
		}

		job.index++;
		job.lastProfileId = profile.profileId();
		job.lastProfileMillis = (System.nanoTime() - profileStartNanos) / 1_000_000L;
		job.frameDelay = WARMUP_FRAME_DELAY;
		logWarmupProfileStep(job, profile, skipped, loaded);
	}

	private static void restoreWarmupJob(WarmupJob job, boolean cancelled) {
		long restoreStartNanos = System.nanoTime();
		job.restoring = true;
		try {
			if (job.restoreProfile == null) {
				Iris.restoreConfiguredShaderPackForRuntimeSwitch();
				activeProfile = null;
			} else {
				Map<String, String> restoreOptions = job.restoreProfile.profile().options == null ? Map.of() : job.restoreProfile.profile().options;
				if (Iris.applyCachedTransientShaderPackForRuntimeSwitch(job.restoreProfile.key(), job.restoreProfile.resolvedShaderPack(), restoreOptions)) {
					activeProfile = job.restoreProfile;
				}
			}
		} catch (IOException | RuntimeException e) {
			job.failed++;
			Iris.logger.warn("Failed to restore ambience profile after cache warmup", e);
		}

		pendingProfile = null;
		pendingRestore = false;
		deferredProfile = null;
		deferredRestore = false;
		Iris.trimTransientShaderPackCacheToBudget();
		lastSwitchMillis = System.currentTimeMillis();
		job.restoreMillis = (System.nanoTime() - restoreStartNanos) / 1_000_000L;
		job.restoring = false;
		job.restored = true;
		job.cancelled = cancelled;
		job.complete = true;
		logWarmupSummary(job.profiles.size(), job.warmed, job.failed, job.beforePoolResources, job.beforePoolBytes, System.nanoTime() - job.startNanos);
	}

	private static void discardWarmupJob() {
		WarmupJob job = warmupJob;
		if (job != null && !job.complete) {
			job.cancelled = true;
			job.complete = true;
			lastAutoWarmAttemptKey = null;
		}
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

	private static void logWarmupProfileStep(WarmupJob job, AmbiencePackManager.ResolvedProfile profile, boolean skipped, boolean loaded) {
		if (!WynncraftDebugLog.shouldLog("ambience-warm-cache-profile")) {
			return;
		}
		WynncraftDebugLog.info("ambience-warm-cache-profile",
			"Ambience warm cache profile: pack={} profile={} index={}/{} skipped={} loaded={} duration={}ms warmed={} cached={} failed={} retainedContexts={} poolResources={} poolBytes={} profilePressures={}",
			job.packDisplayName,
			profile.profileId(),
			job.index,
			job.profiles.size(),
			skipped,
			loaded,
			job.lastProfileMillis,
			job.warmed,
			job.skipped,
			job.failed,
			Iris.getRetainedShaderRuntimeContextCount(),
			Iris.getAmbienceRenderTargetPoolResourceCount(),
			Iris.getAmbienceRenderTargetPoolEstimatedBytes(),
			Iris.getAmbienceRenderTargetPoolProfilePressureSummary());
	}

	private static final class WarmupJob {
		private final String warmupKey;
		private final String packId;
		private final String packDisplayName;
		private final List<AmbiencePackManager.ResolvedProfile> profiles;
		private final AmbiencePackManager.ResolvedProfile restoreProfile;
		private final String reason;
		private final int beforePoolResources;
		private final long beforePoolBytes;
		private final long startNanos = System.nanoTime();
		private int index;
		private int warmed;
		private int skipped;
		private int failed;
		private int frameDelay = WARMUP_FRAME_DELAY;
		private long lastProfileMillis;
		private long restoreMillis;
		private String lastProfileId = "";
		private boolean restoring;
		private boolean restored;
		private boolean complete;
		private boolean cancelled;

		private WarmupJob(String warmupKey, String packId, String packDisplayName,
				List<AmbiencePackManager.ResolvedProfile> profiles,
				AmbiencePackManager.ResolvedProfile restoreProfile, String reason,
				int beforePoolResources, long beforePoolBytes) {
			this.warmupKey = warmupKey;
			this.packId = packId;
			this.packDisplayName = packDisplayName;
			this.profiles = profiles;
			this.restoreProfile = restoreProfile;
			this.reason = reason;
			this.beforePoolResources = beforePoolResources;
			this.beforePoolBytes = beforePoolBytes;
		}

		private int remainingProfiles() {
			int remaining = 0;
			for (int i = index; i < profiles.size(); i++) {
				if (!Iris.hasTransientShaderPackContext(profiles.get(i).key())) {
					remaining++;
				}
			}
			return remaining;
		}

		private WarmupProgress progress() {
			String nextProfileId = "";
			if (index < profiles.size()) {
				nextProfileId = profiles.get(index).profileId();
			}
			return new WarmupProgress(
				true,
				!complete,
				complete,
				cancelled,
				restoring,
				reason,
				packId,
				packDisplayName,
				profiles.size(),
				Math.min(index, profiles.size()),
				warmed,
				skipped,
				failed,
				nextProfileId,
				lastProfileId,
				(System.nanoTime() - startNanos) / 1_000_000L,
				lastProfileMillis,
				restoreMillis);
		}
	}

	public record WarmupResult(int warmed, int failed) {
	}

	public record WarmupStartResult(boolean started, boolean alreadyWarm, int totalProfiles, int remainingProfiles, String packDisplayName) {
	}

	public record WarmupProgress(boolean active, boolean running, boolean complete, boolean cancelled, boolean restoring,
			String reason, String packId, String packDisplayName, int totalProfiles, int processedProfiles,
			int warmedProfiles, int cachedProfiles, int failedProfiles, String nextProfileId, String lastProfileId,
			long elapsedMillis, long lastProfileMillis, long restoreMillis) {
		private static WarmupProgress idle() {
			return new WarmupProgress(false, false, false, false, false, "", "", "", 0, 0, 0, 0, 0, "", "", 0, 0, 0);
		}
	}
}
