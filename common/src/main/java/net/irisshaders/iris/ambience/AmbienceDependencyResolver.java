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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class AmbienceDependencyResolver {
	private static final Gson GSON = new Gson();
	private static final String API = "https://api.modrinth.com/v2";
	private static final String USER_AGENT = "WynnIris/" + Iris.getVersionSimple() + " (https://github.com/clptvn/WynnIris)";
	private static final Pattern ZIP_EXTENSION = Pattern.compile("(?i)\\.zip$");
	private static final Pattern VERSION_TOKEN = Pattern.compile("(?i)(?:^|[\\s_+.-]+)[vr]?\\d+(?:\\.\\d+)+(?:[a-z]+)?(?=$|[\\s_+.-]+|\\))");
	private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[\\p{Ll}\\d])(?=\\p{Lu})|(?<=[\\p{Lu}])(?=\\p{Lu}\\p{Ll})");
	private static final Pattern WORD_SEPARATOR = Pattern.compile("[\\s_+.-]+");
	private static final int SEARCH_LIMIT_PER_QUERY = 8;

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

	public Optional<AmbienceDependencyCandidate> findExactDependency(Path shaderPackPath, String localName) throws IOException, InterruptedException {
		String sha512 = digest(shaderPackPath, "SHA-512");
		ModrinthVersion version = getOptional("/version_file/" + urlEncode(sha512) + "?algorithm=sha512", ModrinthVersion.class);
		if (version == null || version.project_id == null || version.project_id.isBlank()) {
			return Optional.empty();
		}

		ModrinthProject project = getOptional("/project/" + urlEncode(version.project_id), ModrinthProject.class);
		AmbienceDependencyCandidate candidate = new AmbienceDependencyCandidate();
		candidate.id = project == null || project.slug == null || project.slug.isBlank() ? version.project_id : project.slug;
		candidate.title = project == null || project.title == null || project.title.isBlank() ? candidate.id : project.title;
		candidate.description = project == null ? "" : project.description;
		candidate.projectId = version.project_id;
		candidate.versionId = version.id;
		candidate.fileSha512 = sha512;
		candidate.localName = localName;
		candidate.exactHashMatch = true;
		return Optional.of(candidate);
	}

	public List<AmbienceDependencyCandidate> searchDependencies(String query, String localName) throws IOException, InterruptedException {
		List<String> queries = searchQueries(query);
		if (queries.isEmpty()) {
			return List.of();
		}
		String facets = URLEncoder.encode("[[\"project_type:shader\"]]", StandardCharsets.UTF_8);
		Map<String, AmbienceDependencyCandidate> candidates = new LinkedHashMap<>();
		for (String cleaned : queries) {
			ModrinthSearchResults results = getOptional("/search?query=" + urlEncode(cleaned) + "&facets=" + facets + "&limit=" + SEARCH_LIMIT_PER_QUERY, ModrinthSearchResults.class);
			if (results == null || results.hits == null || results.hits.length == 0) {
				continue;
			}
			for (ModrinthSearchHit hit : results.hits) {
				if (hit == null || hit.project_id == null || hit.project_id.isBlank()) {
					continue;
				}
				candidates.computeIfAbsent(hit.project_id, ignored -> {
					AmbienceDependencyCandidate candidate = new AmbienceDependencyCandidate();
					candidate.id = hit.slug == null || hit.slug.isBlank() ? hit.project_id : hit.slug;
					candidate.title = hit.title == null || hit.title.isBlank() ? candidate.id : hit.title;
					candidate.description = hit.description == null ? "" : hit.description;
					candidate.projectId = hit.project_id;
					candidate.localName = localName;
					return candidate;
				});
			}
		}
		return new ArrayList<>(candidates.values());
	}

	public AmbienceDependency dependencyFromCandidate(AmbienceDependencyCandidate candidate) {
		AmbienceDependency dependency = new AmbienceDependency();
		dependency.id = candidate.id == null || candidate.id.isBlank() ? candidate.projectId : candidate.id;
		dependency.type = "modrinth";
		dependency.projectId = candidate.projectId;
		dependency.versionId = candidate.versionId == null ? "" : candidate.versionId;
		dependency.fileSha512 = candidate.fileSha512 == null ? "" : candidate.fileSha512;
		if (candidate.localName != null && !candidate.localName.isBlank()) {
			dependency.localNames.add(candidate.localName);
		}
		return dependency;
	}

	public static AmbienceDependency localDependency(String localName) {
		AmbienceDependency dependency = new AmbienceDependency();
		dependency.id = localName == null ? "" : localName;
		dependency.type = "local";
		if (localName != null && !localName.isBlank()) {
			dependency.localNames.add(localName);
		}
		return dependency;
	}

	public static String cleanSearchQuery(String value) {
		List<String> queries = searchQueries(value);
		return queries.isEmpty() ? "" : queries.getFirst();
	}

	public static List<String> searchQueries(String value) {
		if (value == null) {
			return List.of();
		}

		String stripped = stripVersionTokens(stripExtension(value));
		String trimmed = trimBracketedSuffix(stripped);
		Set<String> queries = new LinkedHashSet<>();
		if (hasCamelBoundary(trimmed)) {
			addQueryVariant(queries, insertCamelSpaces(trimmed));
			addQueryVariant(queries, trimmed);
		} else if (hasWordSeparator(trimmed)) {
			addQueryVariant(queries, trimmed);
			addQueryVariant(queries, insertCamelSpaces(trimmed));
		} else {
			addQueryVariant(queries, insertCamelSpaces(trimmed));
			addQueryVariant(queries, trimmed);
		}
		addQueryVariant(queries, compactSeparators(trimmed));

		return new ArrayList<>(queries);
	}

	private static String stripExtension(String value) {
		return ZIP_EXTENSION.matcher(value).replaceFirst("");
	}

	private static String stripVersionTokens(String value) {
		return VERSION_TOKEN.matcher(value).replaceAll(" ").trim();
	}

	private static String insertCamelSpaces(String value) {
		return CAMEL_BOUNDARY.matcher(value).replaceAll(" ");
	}

	private static String trimBracketedSuffix(String value) {
		int firstBracket = firstPositiveIndex(value.indexOf('('), value.indexOf('['));
		return firstBracket < 0 ? value : value.substring(0, firstBracket);
	}

	private static int firstPositiveIndex(int first, int second) {
		if (first < 0) {
			return second;
		}
		if (second < 0) {
			return first;
		}
		return Math.min(first, second);
	}

	private static String compactSeparators(String value) {
		return value
			.replaceAll("[\\s_+.-]+", "")
			.trim();
	}

	private static boolean hasWordSeparator(String value) {
		return value != null && WORD_SEPARATOR.matcher(value).find();
	}

	private static boolean hasCamelBoundary(String value) {
		return value != null && CAMEL_BOUNDARY.matcher(value).find();
	}

	private static void addQueryVariant(Set<String> queries, String value) {
		String normalized = normalizeQuery(value);
		if (!normalized.isBlank()) {
			queries.add(normalized);
		}
	}

	private static String normalizeQuery(String value) {
		if (value == null) {
			return "";
		}
		return value
			.replaceAll("[_+.-]+", " ")
			.replaceAll("\\s+", " ")
			.trim();
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

	private <T> T getOptional(String path, Class<T> type) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
			.timeout(Duration.ofSeconds(30))
			.header("User-Agent", USER_AGENT)
			.GET()
			.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() == 404) {
			return null;
		}
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
		String id;
		String project_id;
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
		String slug;
		String title;
		String description;
		String project_type;
	}

	private static class ModrinthSearchResults {
		ModrinthSearchHit[] hits;
	}

	private static class ModrinthSearchHit {
		String project_id;
		String slug;
		String title;
		String description;
	}
}
