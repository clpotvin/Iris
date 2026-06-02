package net.irisshaders.iris.ambience;

import java.util.ArrayList;
import java.util.List;

public class AmbienceRegionsFile {
	public List<AmbienceRegion> regions = new ArrayList<>();

	public static AmbienceRegionsFile fromPack(AmbiencePack pack) {
		AmbienceRegionsFile file = new AmbienceRegionsFile();
		file.regions = pack.regions == null ? new ArrayList<>() : new ArrayList<>(pack.regions);
		return file;
	}
}
