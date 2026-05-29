package net.irisshaders.iris.ambience;

import java.util.LinkedHashMap;
import java.util.Map;

public class AmbienceProfile {
	public String id = "";
	public String shaderPack = "";
	public Map<String, String> options = new LinkedHashMap<>();

	public String cacheKey(String resolvedShaderPack) {
		StringBuilder builder = new StringBuilder(resolvedShaderPack).append('|');
		if (options == null || options.isEmpty()) {
			return builder.append("{}").toString();
		}
		options.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> builder
				.append(entry.getKey())
				.append('=')
				.append(entry.getValue())
				.append(';'));
		return builder.toString();
	}
}
