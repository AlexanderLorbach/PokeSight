package de.alorbach.pokesight.settings;

import de.alorbach.pokesight.ruleset.Language;

/**
 * App-wide settings, stored in {@code settings.yaml}.
 *
 * @param game        id of the last selected game; null if none yet
 * @param language    last selected language; null if none yet
 * @param window      main window position and size; null until the window was closed once
 * @param updateCheck ask GitHub on start whether a newer version exists; null (not set) counts as yes
 */
public record AppSettings(String game, Language language, WindowBounds window, Boolean updateCheck) {

    public static final AppSettings DEFAULT = new AppSettings(null, null, null, null);

    public AppSettings withGame(String newGame, Language newLanguage) {
        return new AppSettings(newGame, newLanguage, window, updateCheck);
    }

    public AppSettings withWindow(WindowBounds newWindow) {
        return new AppSettings(game, language, newWindow, updateCheck);
    }

    public boolean checksForUpdates() {
        return updateCheck == null || updateCheck;
    }

    /** @param maximized the window was maximized; the bounds are then its last normal (not maximized) ones */
    public record WindowBounds(double x, double y, double width, double height, boolean maximized) {
    }
}
