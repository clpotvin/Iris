package net.irisshaders.iris.ambience;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AmbienceDependencyStatus {
	private final List<AmbienceDependency> missing;

	public AmbienceDependencyStatus(List<AmbienceDependency> missing) {
		this.missing = Collections.unmodifiableList(new ArrayList<>(missing));
	}

	public boolean hasMissing() {
		return !missing.isEmpty();
	}

	public List<AmbienceDependency> missing() {
		return missing;
	}

	public boolean hasInstallableMissing() {
		return missing.stream().anyMatch(dependency -> dependency != null && "modrinth".equalsIgnoreCase(dependency.type));
	}
}
