package org.evochora.compiler.frontend.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Centralizes file loading for the compiler: supports local filesystem paths,
 * classpath resources, and HTTP/HTTPS URLs. Used by {@code .SOURCE} and Phase 0
 * dependency scanning.
 */
public final class SourceLoader {

    /**
     * Result of loading a source file.
     *
     * @param content     The file content (line endings normalized to {@code \n}).
     * @param logicalName The canonical name used for deduplication and diagnostics.
     */
    public record LoadResult(String content, String logicalName) {}

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private SourceLoader() {}

    /**
     * Checks whether the given path string represents an HTTP or HTTPS URL.
     */
    public static boolean isHttpUrl(String path) {
        return path != null && (path.startsWith("http://") || path.startsWith("https://"));
    }

    /**
     * Loads content from an HTTP or HTTPS URL.
     *
     * @param url The URL to fetch.
     * @return The loaded content and the URL as logical name.
     * @throws IOException If the fetch fails or returns a non-200 status.
     */
    public static LoadResult loadHttp(String url) throws IOException {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
            }
            String content = normalizeLineEndings(response.body());
            return new LoadResult(content, url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    /**
     * Loads content from a local filesystem path.
     *
     * @param resolvedPath The fully resolved, normalized path.
     * @return The loaded content and the normalized path as logical name.
     * @throws IOException If the file cannot be read.
     */
    public static LoadResult loadFile(Path resolvedPath) throws IOException {
        String logicalName = resolvedPath.toString().replace('\\', '/');
        String content = String.join("\n", Files.readAllLines(resolvedPath)) + "\n";
        return new LoadResult(content, logicalName);
    }

    /**
     * Loads content from a classpath resource.
     *
     * @param resourcePath The classpath resource path.
     * @return The loaded content and the resource path as logical name.
     * @throws IOException If the resource is not found or cannot be read.
     */
    public static LoadResult loadClasspath(String resourcePath) throws IOException {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found in classpath: " + resourcePath);
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String content = br.lines().collect(Collectors.joining("\n")) + "\n";
                return new LoadResult(content, resourcePath);
            }
        }
    }

    /**
     * Resolves a relative path against a base URL, producing a new absolute URL.
     * For example, base {@code https://example.com/lib/macros.evo} and relative
     * {@code ./helpers.evo} yields {@code https://example.com/lib/helpers.evo}.
     *
     * @param baseUrl      The base URL (the file that contains the directive).
     * @param relativePath The relative path from the directive.
     * @return The resolved absolute URL string.
     */
    public static String resolveHttpRelative(String baseUrl, String relativePath) {
        URI base = URI.create(baseUrl);
        URI resolved = base.resolve(relativePath);
        return resolved.toString();
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }
}
