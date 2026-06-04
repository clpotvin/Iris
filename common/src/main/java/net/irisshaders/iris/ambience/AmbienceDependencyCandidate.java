package net.irisshaders.iris.ambience;

public class AmbienceDependencyCandidate {
	public String id = "";
	public String title = "";
	public String description = "";
	public String projectId = "";
	public String versionId = "";
	public String fileSha512 = "";
	public String localName = "";
	public boolean exactHashMatch;

	public String displayName() {
		if (title != null && !title.isBlank()) {
			return title;
		}
		if (id != null && !id.isBlank()) {
			return id;
		}
		return localName == null ? "" : localName;
	}
}
