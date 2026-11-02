package de.alorbach.pokesight.sprite;

import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.Species;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A game's Pokémon sprites, downloaded on first use into a local folder ({@code <root>/<game id>/<name>.png}).
 * The sprites are © Nintendo / Game Freak / The Pokémon Company, so neither the repository nor the release contains
 * them. A base form's sprite is its dex number unless the ruleset names it; alternate forms name their sprite in the
 * ruleset.
 */
public final class SpriteCache {

    /** Placeholder in a game's {@code spriteUrl} for the sprite name. */
    public static final String NAME_PLACEHOLDER = "{name}";

    private static final System.Logger LOG = System.getLogger(SpriteCache.class.getName());
    // PokeAPI "10004", hg-engine "rattata_alolan", pokeemerald "rattata/alola/front" (a path below the URL's folder),
    // DPE "frontspr/gFrontSprite019Rattata"
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*");
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final int PARALLEL_DOWNLOADS = 6;

    /** Loads the bytes behind a URL; throws if the server does not deliver them. */
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch(URI uri) throws IOException, InterruptedException;
    }

    private final Path directory;
    private final String urlTemplate;
    private final Fetcher fetcher;
    private final boolean sheets;

    public SpriteCache(Path directory, String urlTemplate, Fetcher fetcher) {
        this(directory, urlTemplate, fetcher, false);
    }

    /** @param sheets the downloads are sprite sheets: only their first frame is stored (see {@link #firstFrame}) */
    public SpriteCache(Path directory, String urlTemplate, Fetcher fetcher, boolean sheets) {
        this.directory = Objects.requireNonNull(directory, "directory");
        if (urlTemplate == null || !urlTemplate.contains(NAME_PLACEHOLDER)) {
            throw new IllegalArgumentException("Sprite URL must contain " + NAME_PLACEHOLDER + ": " + urlTemplate);
        }
        this.urlTemplate = urlTemplate;
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.sheets = sheets;
    }

    /** The game's sprite cache under {@code %LOCALAPPDATA%\PokeSight\sprites}; empty if the game has no sprites. */
    public static Optional<SpriteCache> forGame(GameDefinition game) {
        if (game.spriteUrl() == null) {
            return Optional.empty();
        }
        return Optional.of(new SpriteCache(defaultRoot().resolve(game.id()), game.spriteUrl(), httpFetcher(), game.spriteSheets()));
    }

    static Path defaultRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData != null
                ? Path.of(localAppData, "PokeSight", "sprites")
                : Path.of(System.getProperty("user.home"), ".pokesight", "sprites");
    }

    /**
     * Names of all sprites the ruleset needs: every species' dex number (or its own sprite name) plus the sprites of
     * alternate forms; species without a sprite ({@link Species#NO_SPRITE}) need none.
     */
    public static List<String> spriteNames(Ruleset ruleset) {
        Set<String> names = new LinkedHashSet<>();
        ruleset.species().forEach((id, species) -> {
            names.add(species.sprite() != null ? species.sprite() : String.valueOf(id));
            species.forms().values().forEach(form -> {
                if (form.sprite() != null) {
                    names.add(form.sprite());
                }
            });
        });
        names.remove(Species.NO_SPRITE);
        return List.copyOf(names);
    }

    /** The sprite's file, if it has been downloaded. */
    public Optional<Path> file(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        Path file = target(name);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** The cache file of a valid name: one folder, "/" becomes "." (not allowed in names, so names stay unique). */
    private Path target(String name) {
        return directory.resolve(name.replace('/', '.') + ".png");
    }

    /**
     * Downloads the sprites that are not cached yet. Failures (offline, server errors) are logged and skipped, so the
     * next call tries again. Blocks until all downloads are done.
     *
     * @return the number of newly downloaded sprites
     */
    public int downloadMissing(Collection<String> names) {
        List<String> missing = names.stream().filter(name -> file(name).isEmpty()).distinct().toList();
        if (missing.isEmpty()) {
            return 0;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot create the sprite folder " + directory, e);
            return 0;
        }
        AtomicInteger downloaded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Semaphore slots = new Semaphore(PARALLEL_DOWNLOADS);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) { // close() waits for all tasks
            for (String name : missing) {
                executor.submit(() -> {
                    slots.acquire();
                    try {
                        download(name);
                        downloaded.incrementAndGet();
                    } catch (IOException | RuntimeException e) {
                        failed.incrementAndGet();
                        LOG.log(System.Logger.Level.DEBUG, "Sprite " + name + " not downloaded", e);
                    } finally {
                        slots.release();
                    }
                    return null;
                });
            }
        }
        if (failed.get() > 0) {
            LOG.log(System.Logger.Level.WARNING, failed.get() + " of " + missing.size()
                    + " sprites could not be downloaded from " + urlTemplate + "; they are retried on the next start");
        }
        return downloaded.get();
    }

    private void download(String name) throws IOException, InterruptedException {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid sprite name: " + name);
        }
        byte[] bytes = fetcher.fetch(URI.create(urlTemplate.replace(NAME_PLACEHOLDER, name)));
        if (!isPng(bytes)) {
            throw new IOException("Sprite " + name + " is not a PNG image");
        }
        if (sheets) {
            bytes = firstFrame(bytes);
        }
        Path target = target(name);
        Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * The first frame of a sprite sheet as a PNG: the sheet holds square frames side by side (hg-engine: 160x80 = two
     * 80x80 frames) or stacked (pokeemerald: 64x128, or a single 64x64 frame) in a palette whose colour 0 is the
     * background, which becomes transparent.
     */
    static byte[] firstFrame(byte[] sheetPng) throws IOException {
        BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(sheetPng));
        if (sheet == null || !(sheet.getColorModel() instanceof IndexColorModel)) {
            throw new IOException("Not an indexed-colour sprite sheet");
        }
        int size = Math.min(sheet.getWidth(), sheet.getHeight()); // the top left frame
        Raster palette = sheet.getRaster();
        BufferedImage frame = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                frame.setRGB(x, y, palette.getSample(x, y, 0) == 0 ? 0 : sheet.getRGB(x, y) | 0xFF000000);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(frame, "png", png);
        return png.toByteArray();
    }

    private static boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "PokeSight (https://github.com/AlexanderLorbach/PokeSight)")
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + uri);
            }
            return response.body();
        };
    }
}
