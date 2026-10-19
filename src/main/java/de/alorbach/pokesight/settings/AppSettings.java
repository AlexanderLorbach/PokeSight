package de.alorbach.pokesight.settings;

import de.alorbach.pokesight.ruleset.Language;

/**
 * App-wide settings, stored in {@code settings.yaml}.
 *
 * @param game     id of the last selected game; null if none yet
 * @param language last selected language; null if none yet
 * @param window   main window position and size; null until the window was closed once
 */
public record AppSettings(String game, Language language, WindowBounds window) {

    public static final AppSettings DEFAULT = new AppSettings(null, null, null);

    public AppSettings withGame(String newGame, Language newLanguage) {
        return new AppSettings(newGame, newLanguage, window);
    }

    public AppSettings withWindow(WindowBounds newWindow) {
        return new AppSettings(game, language, newWindow);
    }

    /** @param maximized the window was maximized; the bounds are then its last normal (not maximized) ones */
    public record WindowBounds(double x, double y, double width, double height, boolean maximized) {
    }
}
