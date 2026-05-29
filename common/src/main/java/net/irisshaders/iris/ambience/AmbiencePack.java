package net.irisshaders.iris.ambience;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AmbiencePack {
	public int schema = 1;
	public String id = "";
	public String name = "";
	public String version = "";
	public List<String> authors = new ArrayList<>();
	public List<String> minecraftVersions = new ArrayList<>();
	public List<AmbienceDependency> dependencies = new ArrayList<>();
	public List<AmbienceProfile> profiles = new ArrayList<>();
	public List<AmbienceRegion> regions = new ArrayList<>();
	public String defaultProfile = "";

	public String displayName() {
		return name == null || name.isBlank() ? id : name;
	}

	public Map<String, AmbienceDependency> dependenciesById() {
		Map<String, AmbienceDependency> result = new LinkedHashMap<>();
		if (dependencies == null) {
			return result;
		}
		for (AmbienceDependency dependency : dependencies) {
			if (dependency != null && dependency.id != null && !dependency.id.isBlank()) {
				result.put(dependency.id, dependency);
			}
		}
		return result;
	}

	public Map<String, AmbienceProfile> profilesById() {
		Map<String, AmbienceProfile> result = new LinkedHashMap<>();
		if (profiles == null) {
			return result;
		}
		for (AmbienceProfile profile : profiles) {
			if (profile != null && profile.id != null && !profile.id.isBlank()) {
				result.put(profile.id, profile);
			}
		}
		return result;
	}
}
