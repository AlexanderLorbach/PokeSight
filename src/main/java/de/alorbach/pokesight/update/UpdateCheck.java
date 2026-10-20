package de.alorbach.pokesight.update;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks GitHub once per start whether a newer release exists. The portable app has no updater, so users would
 * otherwise stay on old versions. One anonymous request to the GitHub API; nothing about the user is sent. Any failure
 * (offline, rate limit, changed API) just means no notice.
 */
public final class UpdateCheck {

    static final URI LATEST_RELEASE = URI.create("https://api.github.com/repos/AlexanderLorbach/PokeSight/releases/latest");
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final System.Logger LOG = System.getLogger(UpdateCheck.class.getName());

    /** A release on GitHub: its version ("1.3.0") and its page with the download. */
    public record Release(String version, URI page) {
    }

    private UpdateCheck() {
    }

    /** The version this app was built as (from the build), or empty when run without one (e.g. from the IDE's classes). */
    public static Optional<String> currentVersion() {
        try (InputStream in = UpdateCheck.class.getResourceAsStream("version.txt")) {
            return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The latest release if it is newer than {@code current}; completes on a background thread, never exceptionally. */
    public static CompletableFuture<Optional<Release>> newerThan(String current) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(LATEST_RELEASE)
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PokeSight (https://github.com/AlexanderLorbach/PokeSight)")
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> response.statusCode() == 200 ? parse(response.body()) : Optional.<Release>empty())
                .thenApply(release -> release.filter(latest -> isNewer(latest.version(), current)))
                .exceptionally(e -> {
                    LOG.log(System.Logger.Level.INFO, "Update check failed: " + e.getMessage());
                    return Optional.empty();
                })
                .whenComplete((result, e) -> client.close());
    }

    static Optional<Release> parse(String json) {
        try {
            JsonNode release = JsonMapper.builder().build().readTree(json);
            Matcher version = VERSION.matcher(release.path("tag_name").asString(""));
            String page = release.path("html_url").asString("");
            if (!version.matches() || !page.startsWith("https://github.com/")) {
                return Optional.empty();
            }
            return Optional.of(new Release(version.group(1) + "." + version.group(2) + "." + version.group(3), URI.create(page)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Compares "major.minor.patch" numerically; anything else is never newer. */
    static boolean isNewer(String candidate, String current) {
        Matcher a = VERSION.matcher(candidate);
        Matcher b = VERSION.matcher(current);
        if (!a.matches() || !b.matches()) {
            return false;
        }
        for (int group = 1; group <= 3; group++) {
            int difference = Integer.compare(Integer.parseInt(a.group(group)), Integer.parseInt(b.group(group)));
            if (difference != 0) {
                return difference > 0;
            }
        }
        return false;
    }
}
