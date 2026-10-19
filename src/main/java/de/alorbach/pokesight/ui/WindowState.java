package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.settings.AppSettings;
import de.alorbach.pokesight.settings.SettingsStore;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.List;
import java.util.Optional;

/** Remembers the main window's position, size and maximized state between runs (in {@code settings.yaml}). */
public final class WindowState {

    /** How much of a saved window must be on a current screen to restore it there (monitors may be gone). */
    static final double MIN_VISIBLE_WIDTH = 120;
    static final double MIN_VISIBLE_HEIGHT = 60;

    private WindowState() {
    }

    /** Applies the saved state; call before the stage is shown. Without a usable saved state nothing changes. */
    public static void restore(Stage stage, SettingsStore store) {
        AppSettings.WindowBounds saved = store.app().window();
        if (saved == null || saved.width() <= 0 || saved.height() <= 0) {
            return;
        }
        Rectangle2D bounds = new Rectangle2D(saved.x(), saved.y(), saved.width(), saved.height());
        List<Rectangle2D> screens = Screen.getScreens().stream().map(Screen::getVisualBounds).toList();
        usable(bounds, screens).ifPresent(usable -> {
            stage.setX(usable.getMinX());
            stage.setY(usable.getMinY());
            stage.setWidth(usable.getWidth());
            stage.setHeight(usable.getHeight());
        });
        stage.setMaximized(saved.maximized());
    }

    /** Saves the state; a maximized window keeps its last normal bounds so un-maximizing restores them. */
    public static void save(Stage stage, SettingsStore store) {
        AppSettings settings = store.app();
        AppSettings.WindowBounds previous = settings.window();
        AppSettings.WindowBounds bounds;
        if (stage.isMaximized() || stage.isIconified()) {
            bounds = previous == null ? null
                    : new AppSettings.WindowBounds(previous.x(), previous.y(), previous.width(), previous.height(), stage.isMaximized());
        } else {
            bounds = new AppSettings.WindowBounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), false);
        }
        store.saveApp(settings.withWindow(bounds));
    }

    /** The saved bounds if enough of them lies on one of the screens, so the window can be grabbed and moved. */
    static Optional<Rectangle2D> usable(Rectangle2D saved, List<Rectangle2D> screens) {
        for (Rectangle2D screen : screens) {
            double visibleWidth = Math.min(saved.getMaxX(), screen.getMaxX()) - Math.max(saved.getMinX(), screen.getMinX());
            double visibleHeight = Math.min(saved.getMaxY(), screen.getMaxY()) - Math.max(saved.getMinY(), screen.getMinY());
            if (visibleWidth >= MIN_VISIBLE_WIDTH && visibleHeight >= MIN_VISIBLE_HEIGHT) {
                return Optional.of(saved);
            }
        }
        return Optional.empty();
    }
}
