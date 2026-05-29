package net.irisshaders.iris.ambience;

import com.google.gson.Gson;
import net.irisshaders.iris.Iris;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AmbienceDependencyResolver {
	private static final Gson GSON = new Gson();
	private static final String API = "https://api.modrinth.com/v2";
	private static final String USER_AGENT = "WynnIris/" + Iris.getVersionSimple() + " (https://github.com/clptvn/WynnIris)";

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	public void installMissing(AmbiencePack pack) throws IOException, InterruptedException {
		AmbienceDependencyStatus status = AmbiencePackManager.getInstance().dependencyStatus(pack);
		for (AmbienceDependency dependency : status.missing()) {
			if ("modrinth".equalsIgnoreCase(dependency.type)) {
				install(dependency);
			}
		}
		AmbiencePackManager.getInstance().reload();
	}

	public Path install(AmbienceDependency dependency) throws IOException, InterruptedException {
		if (dependency == null || !"modrinth".equalsIgnoreCase(dependency.type)) {
			throw new IOException("Only Modrinth ambience dependencies can be installed automatically");
		}
		ModrinthVersion version = resolveVersion(dependency);
		ModrinthFile file = version.primaryFile();
		if (file == null || file.url == null || file.url.isBlank() || file.filename == null || file.filename.isBlank()) {
			throw new IOException("Modrinth version did not include a downloadable shader file");
		}
		if (!file.filename.endsWith(".zip")) {
			throw new IOException("Refusing to install non-zip shader dependency " + file.filename);
		}

		Path shaderpacks = Iris.getShaderpacksDirectory();
		Files.createDirectories(shaderpacks);
		Path destination = shaderpacks.resolve(file.filename).normalize();
		if (!destination.getParent().equals(shaderpacks)) {
			throw new IOException("Refusing to install shader pack outside shaderpacks directory: " + file.filename);
		}
		Path temp = Files.createTempFile(shaderpacks, ".wynniris-ambience-", ".download");
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(file.url))
				.timeout(Duration.ofSeconds(90))
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
			HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temp));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("Download failed with HTTP " + response.statusCode());
			}
			verifyHash(dependency, file, temp);
			try {
				Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
			}
			AmbienceInstallIndex.remember(dependency.id, file.filename);
			return destination;
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private ModrinthVersion resolveVersion(AmbienceDependency dependency) throws IOException, InterruptedException {
		if (dependency.versionId != null && !dependency.versionId.isBlank()) {
			return get("/version/" + urlEncode(dependency.versionId), ModrinthVersion.class);
		}
		if (dependency.projectId == null || dependency.projectId.isBlank()) {
			throw new IOException("Missing Modrinth projectId for ambience dependency " + dependency.id);
		}
		ModrinthProject project = get("/project/" + urlEncode(dependency.projectId), ModrinthProject.class);
		if (project == null || !"shader".equals(project.project_type)) {
			throw new IOException("Modrinth project " + dependency.projectId + " is not a shader project");
		}

		String gameVersion = Iris.getReleaseTarget();
		String path = "/project/" + urlEncode(dependency.projectId) + "/version?game_versions=%5B%22" + urlEncode(gameVersion) + "%22%5D";
		ModrinthVersion[] versions = get(path, ModrinthVersion[].class);
		if (versions == null || versions.length == 0) {
			versions = get("/project/" + urlEncode(dependency.projectId) + "/version", ModrinthVersion[].class);
		}
		if (versions == null || versions.length == 0) {
			throw new IOException("No Modrinth versions found for " + dependency.projectId);
		}

		return List.of(versions).stream()
			.filter(version -> version.primaryFile() != null)
			.max(Comparator.comparing(version -> version.date_published == null ? "" : version.date_published))
			.orElseThrow(() -> new IOException("No downloadable files found for " + dependency.projectId));
	}

	private <T> T get(String path, Class<T> type) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
			.timeout(Duration.ofSeconds(30))
			.header("User-Agent", USER_AGENT)
			.GET()
			.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("Modrinth request failed with HTTP " + response.statusCode() + " for " + path);
		}
		return GSON.fromJson(response.body(), type);
	}

	private void verifyHash(AmbienceDependency dependency, ModrinthFile file, Path path) throws IOException {
		String expected = dependency.fileSha512;
		if (expected == null || expected.isBlank()) {
			expected = file.hashes == null ? null : file.hashes.get("sha512");
		}
		if (expected == null || expected.isBlank()) {
			throw new IOException("Modrinth file did not include a SHA-512 hash");
		}

		String actual = digest(path, "SHA-512");
		if (!expected.equalsIgnoreCase(actual)) {
			throw new IOException("Hash verification failed for " + file.filename);
		}
	}

	private static String digest(Path path, String algorithm) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance(algorithm);
			try (InputStream input = Files.newInputStream(path);
				 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
				byte[] buffer = new byte[8192];
				while (digestInput.read(buffer) != -1) {
					// DigestInputStream updates the digest.
				}
			}
			StringBuilder result = new StringBuilder();
			for (byte b : digest.digest()) {
				result.append(String.format(Locale.ROOT, "%02x", b));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Missing digest algorithm " + algorithm, e);
		}
	}

	private static String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static class ModrinthVersion {
		String date_published;
		ModrinthFile[] files;

		ModrinthFile primaryFile() {
			if (files == null || files.length == 0) {
				return null;
			}
			for (ModrinthFile file : files) {
				if (file.primary) {
					return file;
				}
			}
			return files[0];
		}
	}

	private static class ModrinthFile {
		String url;
		String filename;
		boolean primary;
		Map<String, String> hashes;
	}

	private static class ModrinthProject {
		String project_type;
	}
}
