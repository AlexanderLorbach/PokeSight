package de.alorbach.pokesight.sprite;

import de.alorbach.pokesight.ruleset.RulesetLoader;
import de.alorbach.pokesight.ruleset.Species;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
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
    void namesUseTheSpeciesOwnSpriteAndSkipSpeciesWithout() {
        List<String> names = SpriteCache.spriteNames(RulesetLoader.load("odyssey"));

        assertThat(names).doesNotHaveDuplicates().contains("20", "352", "10166") // B.B. Kecleon shares Kecleon's sprite
                .doesNotContain("5150", "5201", Species.NO_SPRITE);
    }

    @Test
    void keepsTheFirstFrameOfSpriteSheetsWithATransparentBackground() throws IOException {
        // A 4x2 sheet of two 2x2 frames; palette colour 0 (white) is the background
        IndexColorModel palette = new IndexColorModel(4, 3, new byte[]{-1, -1, 0}, new byte[]{-1, 0, 0}, new byte[]{-1, 0, -1});
        BufferedImage sheet = new BufferedImage(4, 2, BufferedImage.TYPE_BYTE_BINARY, palette);
        sheet.getRaster().setPixels(0, 0, 4, 2, new int[]{0, 1, 2, 2, 2, 0, 2, 2});
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(sheet, "png", png);
        SpriteCache cache = new SpriteCache(directory, URL, uri -> png.toByteArray(), true);

        assertThat(cache.downloadMissing(List.of("rattata_alolan"))).isEqualTo(1);

        BufferedImage frame = ImageIO.read(cache.file("rattata_alolan").orElseThrow().toFile());
        assertThat(frame.getWidth()).isEqualTo(2);
        assertThat(frame.getHeight()).isEqualTo(2);
        assertThat(frame.getRGB(0, 0) >>> 24).isZero();             // background: transparent
        assertThat(frame.getRGB(1, 0)).isEqualTo(0xFFFF0000);       // red
        assertThat(frame.getRGB(0, 1)).isEqualTo(0xFF0000FF);       // blue
        assertThat(frame.getRGB(1, 1) >>> 24).isZero();
    }

    @Test
    void keepsTheFirstOfStackedFramesAndStoresNamesWithFolders() throws IOException {
        // pokeemerald: a 2x4 sheet of two 2x2 frames, one above the other, named by its path below the URL's folder
        IndexColorModel palette = new IndexColorModel(4, 3, new byte[]{-1, -1, 0}, new byte[]{-1, 0, 0}, new byte[]{-1, 0, -1});
        BufferedImage sheet = new BufferedImage(2, 4, BufferedImage.TYPE_BYTE_BINARY, palette);
        sheet.getRaster().setPixels(0, 0, 2, 4, new int[]{1, 0, 0, 2, 2, 2, 2, 2});
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(sheet, "png", png);
        SpriteCache cache = new SpriteCache(directory, URL, uri -> {
            requested.add(uri);
            return png.toByteArray();
        }, true);

        assertThat(cache.downloadMissing(List.of("rattata/alola/front"))).isEqualTo(1);

        assertThat(requested).containsExactly(URI.create("https://example.org/sprites/rattata/alola/front.png"));
        Path file = cache.file("rattata/alola/front").orElseThrow();
        assertThat(file.getParent()).isEqualTo(directory);
        BufferedImage frame = ImageIO.read(file.toFile());
        assertThat(frame.getWidth()).isEqualTo(2);
        assertThat(frame.getHeight()).isEqualTo(2);
        assertThat(frame.getRGB(0, 0)).isEqualTo(0xFFFF0000);
        assertThat(frame.getRGB(1, 0) >>> 24).isZero();
        assertThat(frame.getRGB(1, 1)).isEqualTo(0xFF0000FF);
    }

    @Test
    void rejectsSpriteSheetsWithoutAPalette() {
        SpriteCache cache = new SpriteCache(directory, URL, uri -> PNG, true);

        assertThat(cache.downloadMissing(List.of("25"))).isZero();
        assertThat(cache.file("25")).isEmpty();
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

        assertThat(cache.downloadMissing(List.of("../evil", "a/../b", "/abs", "a.b", "a//b"))).isZero();
        assertThat(cache.file("../evil")).isEmpty();
        assertThat(requested).isEmpty();
    }

    @Test
    void requiresANamePlaceholderInTheUrl() {
        assertThatThrownBy(() -> new SpriteCache(directory, "https://example.org/sprite.png", uri -> PNG))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
