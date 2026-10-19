package de.alorbach.pokesight.sprite;

import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.ruleset.Ruleset;

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
 * them. A base form's sprite is its dex number; alternate forms name their sprite in the ruleset.
 */
public final class SpriteCache {

    /** Placeholder in a game's {@code spriteUrl} for the sprite name. */
    public static final String NAME_PLACEHOLDER = "{name}";

    private static final System.Logger LOG = System.getLogger(SpriteCache.class.getName());
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9-]+");
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

    public SpriteCache(Path directory, String urlTemplate, Fetcher fetcher) {
        this.directory = Objects.requireNonNull(directory, "directory");
        if (urlTemplate == null || !urlTemplate.contains(NAME_PLACEHOLDER)) {
            throw new IllegalArgumentException("Sprite URL must contain " + NAME_PLACEHOLDER + ": " + urlTemplate);
        }
        this.urlTemplate = urlTemplate;
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /** The game's sprite cache under {@code %LOCALAPPDATA%\PokeSight\sprites}; empty if the game has no sprites. */
    public static Optional<SpriteCache> forGame(GameDefinition game) {
        if (game.spriteUrl() == null) {
            return Optional.empty();
        }
        return Optional.of(new SpriteCache(defaultRoot().resolve(game.id()), game.spriteUrl(), httpFetcher()));
    }

    static Path defaultRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData != null
                ? Path.of(localAppData, "PokeSight", "sprites")
                : Path.of(System.getProperty("user.home"), ".pokesight", "sprites");
    }

    /** Names of all sprites the ruleset needs: every species' dex number plus the sprites of alternate forms. */
    public static List<String> spriteNames(Ruleset ruleset) {
        Set<String> names = new LinkedHashSet<>();
        ruleset.species().forEach((id, species) -> {
            names.add(String.valueOf(id));
            species.forms().values().forEach(form -> {
                if (form.sprite() != null) {
                    names.add(form.sprite());
                }
            });
        });
        return List.copyOf(names);
    }

    /** The sprite's file, if it has been downloaded. */
    public Optional<Path> file(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        Path file = directory.resolve(name + ".png");
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
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
        Path target = directory.resolve(name + ".png");
        Path temp = Files.createTempFile(directory, name, ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
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
