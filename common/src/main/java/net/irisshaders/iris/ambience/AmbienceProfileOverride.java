package net.irisshaders.iris.ambience;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AmbienceProfileOverride {
	public String id = "";
	public String shaderPack = "";
	public Map<String, String> options = new LinkedHashMap<>();
	public List<String> resetOptions = new ArrayList<>();
	public boolean deleted;
}
