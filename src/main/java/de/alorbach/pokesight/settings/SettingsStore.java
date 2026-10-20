package de.alorbach.pokesight.settings;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * The user's settings as readable YAML files in {@code %APPDATA%\PokeSight} (Windows' roaming app data folder):
 * {@code settings.yaml} for the app and {@code games/<game id>.yaml} per game. Files are written atomically; a file
 * that cannot be read is ignored (defaults are used) and only replaced on the next save.
 */
public final class SettingsStore {

    private static final System.Logger LOG = System.getLogger(SettingsStore.class.getName());
    private static final Pattern GAME_ID = Pattern.compile("[a-z0-9-]+");
    private static final String HEADER = "# PokeSight settings, written by the app. Edit only while PokeSight is closed.\n";
    private static final ObjectMapper MAPPER = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // files from newer versions stay readable
            .build();

    private final Path directory;

    public SettingsStore(Path directory) {
        this.directory = directory;
    }

    /** {@code %APPDATA%\PokeSight}; moves settings of older versions out of the registry on first use. */
    public static SettingsStore openDefault() {
        String appData = System.getenv("APPDATA");
        Path directory = appData != null ? Path.of(appData, "PokeSight") : Path.of(System.getProperty("user.home"), ".pokesight");
        SettingsStore store = new SettingsStore(directory);
        LegacyPreferences.migrateTo(store);
        return store;
    }

    public Path directory() {
        return directory;
    }

    public AppSettings app() {
        return read(appFile(), AppSettings.class, AppSettings.DEFAULT);
    }

    public void saveApp(AppSettings settings) {
        write(appFile(), settings);
    }

    public GameSettings game(String gameId) {
        return read(gameFile(gameId), GameSettings.class, GameSettings.DEFAULT);
    }

    public void saveGame(String gameId, GameSettings settings) {
        write(gameFile(gameId), settings);
    }

    boolean hasAppFile() {
        return Files.exists(appFile());
    }

    private Path appFile() {
        return directory.resolve("settings.yaml");
    }

    private Path gameFile(String gameId) {
        if (!GAME_ID.matcher(gameId).matches()) {
            throw new IllegalArgumentException("Not a game id: " + gameId);
        }
        return directory.resolve("games").resolve(gameId + ".yaml");
    }

    private static <T> T read(Path file, Class<T> type, T fallback) {
        if (!Files.exists(file)) {
            return fallback;
        }
        try (InputStream in = Files.newInputStream(file)) {
            T value = MAPPER.readValue(in, type);
            return value != null ? value : fallback;
        } catch (IOException | JacksonException | IllegalArgumentException e) { // also invalid values (tiny areas)
            LOG.log(System.Logger.Level.WARNING, "Ignoring unreadable settings file " + file + ": " + e.getMessage());
            return fallback;
        }
    }

    private static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temp, HEADER + MAPPER.writeValueAsString(value), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp); // left over only if writing or moving failed
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save settings to " + file, e);
        }
    }
}
