package net.irisshaders.iris.gui.option;

import net.irisshaders.iris.Iris;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limited debug logging for WynnIris. Each unique log key can fire at most
 * once per second, preventing per-frame spam in hot render paths.
 */
public class WynncraftDebugLog {
	private static final ConcurrentHashMap<String, Long> lastLogTimes = new ConcurrentHashMap<>();
	private static final long MIN_INTERVAL_MS = 1000;

	/** Check if logging is enabled AND this key hasn't been logged recently. Use before expensive arg construction. */
	public static boolean shouldLog(String key) {
		if (!IrisVideoSettings.wynncraftDebugLogging) return false;
		long now = System.currentTimeMillis();
		Long last = lastLogTimes.get(key);
		return last == null || (now - last) >= MIN_INTERVAL_MS;
	}

	/** Log at INFO level, rate-limited to once per second per unique key. */
	public static void info(String key, String message, Object... args) {
		if (!IrisVideoSettings.wynncraftDebugLogging) return;
		long now = System.currentTimeMillis();
		// Atomic check-and-update to prevent double-logging under contention
		boolean[] shouldLog = {false};
		lastLogTimes.compute(key, (k, old) -> {
			if (old == null || (now - old) >= MIN_INTERVAL_MS) {
				shouldLog[0] = true;
				return now;
			}
			return old;
		});
		if (!shouldLog[0]) return;
		Iris.logger.info(message, args);
	}
}
