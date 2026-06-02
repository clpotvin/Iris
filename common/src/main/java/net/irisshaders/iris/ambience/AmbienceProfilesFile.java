package net.irisshaders.iris.ambience;

import java.util.ArrayList;
import java.util.List;

public class AmbienceProfilesFile {
	public List<AmbienceProfile> profiles = new ArrayList<>();

	public static AmbienceProfilesFile fromPack(AmbiencePack pack) {
		AmbienceProfilesFile file = new AmbienceProfilesFile();
		file.profiles = pack.profiles == null ? new ArrayList<>() : new ArrayList<>(pack.profiles);
		return file;
	}
}
