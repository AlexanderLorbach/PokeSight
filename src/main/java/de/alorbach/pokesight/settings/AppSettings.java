package de.alorbach.pokesight.settings;

import de.alorbach.pokesight.ruleset.Language;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App-wide settings, stored in {@code settings.yaml}.
 *
 * @param game        id of the last selected game; null if none yet
 * @param language    last selected language; null if none yet
 * @param window      main window position and size; null until the window was closed once
 * @param updateCheck ask GitHub on start whether a newer version exists; null (not set) counts as yes
 * @param gameOptions values of the games' options (Platinum Together's MODERN POKéMON) by game id and option id;
 *                    options that are not set use their default
 */
public record AppSettings(String game, Language language, WindowBounds window, Boolean updateCheck,
                          Map<String, Map<String, Boolean>> gameOptions) {

    public static final AppSettings DEFAULT = new AppSettings(null, null, null, null, null);

    public AppSettings {
        Map<String, Map<String, Boolean>> copy = new LinkedHashMap<>();
        if (gameOptions != null) {
            gameOptions.forEach((gameId, options) -> copy.put(gameId, options == null ? Map.of() : Map.copyOf(options)));
        }
        gameOptions = Map.copyOf(copy);
    }

    public AppSettings withGame(String newGame, Language newLanguage) {
        return new AppSettings(newGame, newLanguage, window, updateCheck, gameOptions);
    }

    public AppSettings withWindow(WindowBounds newWindow) {
        return new AppSettings(game, language, newWindow, updateCheck, gameOptions);
    }

    /** The saved option values of a game; empty if none were set. */
    public Map<String, Boolean> options(String gameId) {
        return gameOptions.getOrDefault(gameId, Map.of());
    }

    public AppSettings withOption(String gameId, String optionId, boolean value) {
        Map<String, Map<String, Boolean>> all = new LinkedHashMap<>(gameOptions);
        Map<String, Boolean> options = new LinkedHashMap<>(options(gameId));
        options.put(optionId, value);
        all.put(gameId, options);
        return new AppSettings(game, language, window, updateCheck, all);
    }

    public boolean checksForUpdates() {
        return updateCheck == null || updateCheck;
    }

    /** @param maximized the window was maximized; the bounds are then its last normal (not maximized) ones */
    public record WindowBounds(double x, double y, double width, double height, boolean maximized) {
    }
}
