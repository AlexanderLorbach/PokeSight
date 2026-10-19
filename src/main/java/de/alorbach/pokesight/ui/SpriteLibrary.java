package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.sprite.SpriteCache;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The game's Pokémon sprites as JavaFX images, read from the {@link SpriteCache}. Sprites that are not downloaded yet
 * are simply missing; {@link #downloadMissing} fetches them in the background.
 */
final class SpriteLibrary {

    private static final System.Logger LOG = System.getLogger(SpriteLibrary.class.getName());
    private static final int SCALE2X_PASSES = 2;

    private final SpriteCache cache;
    private final Ruleset ruleset;
    private final Map<String, Image> images = new HashMap<>(); // only sprites that were found, so later downloads show up

    /** @param cache null if the game has no sprites */
    SpriteLibrary(SpriteCache cache, Ruleset ruleset) {
        this.cache = cache;
        this.ruleset = Objects.requireNonNull(ruleset);
    }

    Optional<Image> image(PokemonId pokemon) {
        if (cache == null) {
            return Optional.empty();
        }
        String name = ruleset.findSpecies(pokemon.speciesId())
                .map(species -> species.form(pokemon.form()).sprite())
                .orElse(String.valueOf(pokemon.speciesId()));
        Image image = images.get(name);
        if (image == null) {
            image = cache.file(name).map(SpriteLibrary::load).orElse(null);
            if (image != null) {
                images.put(name, image);
            }
        }
        return Optional.ofNullable(image);
    }

    /** Downloads the missing sprites on a background thread; calls {@code onNewSprites} on the FX thread afterwards. */
    void downloadMissing(Runnable onNewSprites) {
        if (cache == null) {
            return;
        }
        Thread.ofVirtual().name("sprite-download").start(() -> {
            if (cache.downloadMissing(SpriteCache.spriteNames(ruleset)) > 0) {
                Platform.runLater(onNewSprites);
            }
        });
    }

    /**
     * Loads the sprite enlarged 4x with Scale2x (twice); views then scale it smoothly. Scaling the original pixel art by
     * an uneven factor such as 3.4 (borderless mode on a large window) would make some pixels 3 and some 4 screen pixels
     * wide and the diagonals jagged; from the enlarged copy, smoothing only softens the edges.
     */
    private static Image load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Image original = new Image(in);
            if (original.isError()) {
                return null;
            }
            int width = (int) original.getWidth();
            int height = (int) original.getHeight();
            int[] pixels = new int[width * height];
            original.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            for (int pass = 0; pass < SCALE2X_PASSES; pass++) {
                pixels = PixelArt.scale2x(pixels, width, height);
                width *= 2;
                height *= 2;
            }
            WritableImage enlarged = new WritableImage(width, height);
            enlarged.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            return enlarged;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read sprite " + file, e);
            return null;
        }
    }
}
