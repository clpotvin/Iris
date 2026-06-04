package net.irisshaders.iris.ambience;

import java.util.ArrayList;
import java.util.List;

public class AmbiencePackMetadata {
	public String name = "";
	public String version = "1.0.0";
	public List<String> authors = new ArrayList<>();

	public AmbiencePackMetadata() {
	}

	public AmbiencePackMetadata(String name, String version, List<String> authors) {
		this.name = name == null ? "" : name.trim();
		this.version = version == null || version.isBlank() ? "1.0.0" : version.trim();
		this.authors = authors == null ? new ArrayList<>() : new ArrayList<>(authors);
	}
}
