package de.alorbach.pokesight.sprite;

import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpriteCacheTest {

    private static final String URL = "https://example.org/sprites/{name}.png";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3};

    @TempDir
    Path directory;

    private final ConcurrentLinkedQueue<URI> requested = new ConcurrentLinkedQueue<>();

    @Test
    void namesCoverEverySpeciesAndAlternateForm() {
        List<String> names = SpriteCache.spriteNames(RulesetLoader.load("gen4"));

        assertThat(names).hasSize(512).doesNotHaveDuplicates().contains("1", "493", "10004"); // 10004 = Sandy Wormadam
    }

    @Test
    void downloadsMissingSpritesOnce() throws IOException {
        SpriteCache cache = new SpriteCache(directory, URL, uri -> {
            requested.add(uri);
            return PNG;
        });

        assertThat(cache.file("25")).isEmpty();
        assertThat(cache.downloadMissing(List.of("25", "10004"))).isEqualTo(2);
        assertThat(cache.downloadMissing(List.of("25", "10004"))).isZero();

        assertThat(requested).containsExactlyInAnyOrder(
                URI.create("https://example.org/sprites/25.png"), URI.create("https://example.org/sprites/10004.png"));
        assertThat(cache.file("25")).hasValueSatisfying(file -> assertThat(file).hasBinaryContent(PNG));
        try (var files = Files.list(directory)) {
            assertThat(files).hasSize(2); // no temp files left over
        }
    }

    @Test
    void skipsFailedAndInvalidDownloadsAndRetriesLater() {
        SpriteCache offline = new SpriteCache(directory, URL, uri -> {
            throw new IOException("offline");
        });
        SpriteCache notPng = new SpriteCache(directory, URL, uri -> "<html>".getBytes());

        assertThat(offline.downloadMissing(List.of("25"))).isZero();
        assertThat(notPng.downloadMissing(List.of("25"))).isZero();
        assertThat(offline.file("25")).isEmpty();

        SpriteCache online = new SpriteCache(directory, URL, uri -> PNG);
        assertThat(online.downloadMissing(List.of("25"))).isEqualTo(1);
    }

    @Test
    void rejectsNamesOutsideTheCacheFolder() {
        SpriteCache cache = new SpriteCache(directory, URL, uri -> {
            requested.add(uri);
            return PNG;
        });

        assertThat(cache.downloadMissing(List.of("../evil", "a/b"))).isZero();
        assertThat(cache.file("../evil")).isEmpty();
        assertThat(requested).isEmpty();
    }

    @Test
    void requiresANamePlaceholderInTheUrl() {
        assertThatThrownBy(() -> new SpriteCache(directory, "https://example.org/sprite.png", uri -> PNG))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
