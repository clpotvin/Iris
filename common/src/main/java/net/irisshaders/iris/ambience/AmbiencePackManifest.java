package net.irisshaders.iris.ambience;

import java.util.ArrayList;
import java.util.List;

public class AmbiencePackManifest {
	public int schema = 1;
	public String id = "";
	public String name = "";
	public String version = "";
	public List<String> authors = new ArrayList<>();
	public List<String> minecraftVersions = new ArrayList<>();
	public List<AmbienceDependency> dependencies = new ArrayList<>();
	public List<String> deletedDependencies = new ArrayList<>();
	public String defaultProfile = "";

	public static AmbiencePackManifest fromPack(AmbiencePack pack) {
		AmbiencePackManifest manifest = new AmbiencePackManifest();
		manifest.schema = pack.schema;
		manifest.id = pack.id;
		manifest.name = pack.name;
		manifest.version = pack.version;
		manifest.authors = pack.authors == null ? new ArrayList<>() : new ArrayList<>(pack.authors);
		manifest.minecraftVersions = pack.minecraftVersions == null ? new ArrayList<>() : new ArrayList<>(pack.minecraftVersions);
		manifest.dependencies = pack.dependencies == null ? new ArrayList<>() : new ArrayList<>(pack.dependencies);
		manifest.deletedDependencies = new ArrayList<>();
		manifest.defaultProfile = pack.defaultProfile;
		return manifest;
	}

	public AmbiencePack toPack() {
		AmbiencePack pack = new AmbiencePack();
		pack.schema = schema;
		pack.id = id;
		pack.name = name;
		pack.version = version;
		pack.authors = authors == null ? new ArrayList<>() : new ArrayList<>(authors);
		pack.minecraftVersions = minecraftVersions == null ? new ArrayList<>() : new ArrayList<>(minecraftVersions);
		pack.dependencies = dependencies == null ? new ArrayList<>() : new ArrayList<>(dependencies);
		pack.defaultProfile = defaultProfile;
		return pack;
	}
}
